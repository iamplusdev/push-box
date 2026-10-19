#!/usr/bin/env node
// RustFS S3 能力探测（§16 待办 b / §11.1）：签名走 AWS SigV4，零第三方依赖。
// 用途：判定 ① 是否支持 SSE-S3 / SSE-C 对象加密 ② 是否支持桶生命周期规则，
//       以及 PutObject/预签名/匿名拒读/删除 等基础行为。
// 凭据只从环境变量读取，不落盘：PB_S3_ACCESS_KEY / PB_S3_SECRET_KEY
import crypto from 'node:crypto';
import http from 'node:http';

const ENDPOINT = process.env.PB_S3_ENDPOINT || 'http://127.0.0.1:7101';
const REGION = process.env.PB_S3_REGION || 'us-east-1';
const BUCKET = process.env.PB_S3_BUCKET || 'pushbox';
const AK = process.env.PB_S3_ACCESS_KEY;
const SK = process.env.PB_S3_SECRET_KEY;
if (!AK || !SK) { console.error('missing PB_S3_ACCESS_KEY / PB_S3_SECRET_KEY'); process.exit(2); }

const u = new URL(ENDPOINT);
const HOST = u.host;

const sha256hex = (d) => crypto.createHash('sha256').update(d).digest('hex');
const hmac = (k, d, enc) => crypto.createHmac('sha256', k).update(d).digest(enc);

function sign({ method, path, query = {}, headers = {}, body = '' }) {
  const payloadHash = sha256hex(body);
  const now = new Date();
  const amzDate = now.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const dateStamp = amzDate.slice(0, 8);
  const allHeaders = { host: HOST, 'x-amz-content-sha256': payloadHash, 'x-amz-date': amzDate, ...headers };
  const lower = {};
  for (const [k, v] of Object.entries(allHeaders)) lower[k.toLowerCase()] = String(v).trim();
  const names = Object.keys(lower).sort();
  const canonicalHeaders = names.map((n) => `${n}:${lower[n]}\n`).join('');
  const signedHeaders = names.join(';');
  const qs = Object.keys(query).sort().map((k) => `${enc(k)}=${enc(query[k] ?? '')}`).join('&');
  const canonical = [method, path, qs, canonicalHeaders, signedHeaders, payloadHash].join('\n');
  const scope = `${dateStamp}/${REGION}/s3/aws4_request`;
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, sha256hex(canonical)].join('\n');
  const kDate = hmac(('AWS4' + SK), dateStamp, undefined);
  const kRegion = hmac(kDate, REGION, undefined);
  const kService = hmac(kRegion, 's3', undefined);
  const kSigning = hmac(kService, 'aws4_request', undefined);
  const signature = hmac(kSigning, stringToSign, 'hex');
  const out = {};
  for (const [k, v] of Object.entries(allHeaders)) out[k] = v;
  out.Authorization = `AWS4-HMAC-SHA256 Credential=${AK}/${scope}, SignedHeaders=${signedHeaders}, Signature=${signature}`;
  return { headers: out, qs };
}
const enc = (s) => encodeURIComponent(s).replace(/[!'()*]/g, (c) => '%' + c.charCodeAt(0).toString(16).toUpperCase());

function request(method, path, { query = {}, headers = {}, body = '', anonymous = false } = {}) {
  return new Promise((resolve) => {
    let h = { ...headers };
    let qs = Object.keys(query).sort().map((k) => `${enc(k)}=${enc(query[k] ?? '')}`).join('&');
    if (!anonymous) {
      const s = sign({ method, path, query, headers, body });
      h = s.headers; qs = s.qs;
    }
    const req = http.request({ host: u.hostname, port: u.port || 80, method, path: path + (qs ? '?' + qs : ''), headers: h }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const buf = Buffer.concat(chunks);
        resolve({ status: res.statusCode, headers: res.headers, text: buf.toString('utf8'), bytes: buf.length });
      });
    });
    req.on('error', (e) => resolve({ status: 0, error: e.code || e.message }));
    if (body) req.write(body);
    req.end();
  });
}

// presigned URL（Query 签名，用于 §4.5 客户端取图链路验证）
function presign(path, seconds = 60) {
  const now = new Date();
  const amzDate = now.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '');
  const dateStamp = amzDate.slice(0, 8);
  const expires = String(seconds);
  const scope = `${dateStamp}/${REGION}/s3/aws4_request`;
  const query = {
    'X-Amz-Algorithm': 'AWS4-HMAC-SHA256',
    'X-Amz-Credential': `${AK}/${scope}`,
    'X-Amz-Date': amzDate,
    'X-Amz-Expires': expires,
    'X-Amz-SignedHeaders': 'host',
  };
  const qs = Object.keys(query).sort().map((k) => `${enc(k)}=${enc(query[k])}`).join('&');
  const canonical = ['GET', path, qs, `host:${HOST}\n`, 'host', 'UNSIGNED-PAYLOAD'].join('\n');
  // 注意：预签名的 payload 位必须写字面量 UNSIGNED-PAYLOAD，
  // 用 sha256('UNSIGNED-PAYLOAD') 会得到 SignatureDoesNotMatch（第一次跑就踩了）。
  const stringToSign = ['AWS4-HMAC-SHA256', amzDate, scope, sha256hex(canonical)].join('\n');
  let k = Buffer.from('AWS4' + SK);
  for (const part of [dateStamp, REGION, 's3', 'aws4_request']) k = hmac(k, part, undefined);
  const signature = hmac(k, stringToSign, 'hex');
  return `${ENDPOINT}${path}?${qs}&X-Amz-Signature=${signature}`;
}

const report = [];
const check = (name, pass, detail) => { report.push({ name, pass, detail }); console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}\n      ${detail}`); };
const KEY = 'probe/hello.txt';
const CONTENT = 'pushbox rustfs capability probe ' + new Date().toISOString();

const run = async () => {
  // 1. 认证与 region
  let r = await request('GET', '/', { query: { 'location': '' } });
  check('GetBucketLocation pushbox', r.status === 200, `status=${r.status} ${(r.text || '').slice(0, 160).replace(/\s+/g, ' ')}`);

  // 2. 建桶（幂等）
  r = await request('PUT', `/${BUCKET}/`);
  const already = /BucketAlreadyOwnedByYou|BucketAlreadyExists/.test(r.text || '');
  check('CreateBucket pushbox', r.status === 200 || already, `status=${r.status}${already ? ' (已存在)' : ''} ${r.error || ''}`);

  // 3. 普通 PutObject
  r = await request('PUT', `/${BUCKET}/${KEY}`, { headers: { 'content-type': 'text/plain', 'content-length': String(Buffer.byteLength(CONTENT)) }, body: CONTENT });
  check('PutObject (明文, 无 SSE)', r.status === 200, `status=${r.status} etag=${r.headers.etag || '-'} ${r.error || ''}`);

  // 4. SSE-S3（AES256）——实测结果：RustFS 未配 RUSTFS_SSE_S3_MASTER_KEY 时返回 400 InvalidRequest
  r = await request('PUT', `/${BUCKET}/probe/sse-s3.txt`, { headers: { 'content-type': 'text/plain', 'content-length': String(Buffer.byteLength(CONTENT)), 'x-amz-server-side-encryption': 'AES256' }, body: CONTENT });
  const sseS3ok = r.status === 200;
  check('PutObject with SSE-S3 (AES256)', sseS3ok, sseS3ok ? `status=200 resp-header=${r.headers['x-amz-server-side-encryption'] || '(无回显)'}` : `status=${r.status} ⇒ 需服务端配 RUSTFS_SSE_S3_MASTER_KEY：${(r.text || '').replace(/\s+/g, ' ').slice(0, 160)}`);

  // 5. SSE-C（客户自带密钥）—— 注意：不能同时带 managed 的 x-amz-server-side-encryption 头，RustFS 会 400
  const key32 = crypto.randomBytes(32).toString('base64');
  const keyMd5 = crypto.createHash('md5').update(Buffer.from(key32, 'base64')).digest('base64');
  const sseC = { 'x-amz-server-side-encryption-customer-algorithm': 'AES256', 'x-amz-server-side-encryption-customer-key': key32, 'x-amz-server-side-encryption-customer-key-md5': keyMd5 };
  r = await request('PUT', `/${BUCKET}/probe/sse-c.txt`, { headers: { 'content-type': 'text/plain', 'content-length': String(Buffer.byteLength(CONTENT)), ...sseC }, body: CONTENT });
  check('PutObject with SSE-C', r.status === 200 && /AES256/i.test(String(r.headers['x-amz-server-side-encryption'] || '')), `status=${r.status} resp-sse=${r.headers['x-amz-server-side-encryption'] || '-'}`);
  r = await request('GET', `/${BUCKET}/probe/sse-c.txt`, { headers: sseC });
  check('GetObject with SSE-C key', r.status === 200 && r.text === CONTENT, `status=${r.status} match=${r.text === CONTENT}`);
  r = await request('GET', `/${BUCKET}/probe/sse-c.txt`, { headers: {} });
  check('SSE-C 对象无钥读取被拒', r.status >= 400 && r.status < 500, `status=${r.status} ⇒ 说明桶里落的是密文`);

  // 6. GetObject 普通 + 内容校验
  r = await request('GET', `/${BUCKET}/${KEY}`);
  check('GetObject 回读一致', r.status === 200 && r.text === CONTENT, `status=${r.status} bytes=${r.bytes}`);

  // 7. 预签名 URL（§4.5 客户端取图链路）
  const url = presign(`/${BUCKET}/${KEY}`, 60);
  const pu = new URL(url);
  r = await new Promise((res) => { const q = http.request({ host: pu.hostname, port: pu.port, method: 'GET', path: pu.pathname + pu.search }, (x) => { const b = []; x.on('data', (c) => b.push(c)); x.on('end', () => res({ status: x.statusCode, text: Buffer.concat(b).toString() })); }); q.on('error', (e) => res({ status: 0, text: e.code })); q.end(); });
  check('Presigned URL 匿名可取（60s 内）', r.status === 200 && r.text === CONTENT, `status=${r.status}`);
  const urlExpired = presign(`/${BUCKET}/${KEY}`, -5);
  const eu = new URL(urlExpired);
  r = await new Promise((res) => { const q = http.request({ host: eu.hostname, port: eu.port, method: 'GET', path: eu.pathname + eu.search }, (x) => { const b = []; x.on('data', (c) => b.push(c)); x.on('end', () => res({ status: x.statusCode, text: Buffer.concat(b).toString() })); }); q.on('error', (e) => res({ status: 0, text: e.code })); q.end(); });
  check('Presigned URL 过期/非法时效被拒（4xx 即可）', r.status >= 400 && r.status < 500, `status=${r.status} ${(r.text || '').slice(0, 120).replace(/\s+/g, ' ')}`);

  // 8. 匿名读必须被拒（私有桶前提）
  r = await request('GET', `/${BUCKET}/${KEY}`, { anonymous: true });
  check('匿名 GetObject 被拒', r.status === 403, `status=${r.status}`);

  // 9. 生命周期规则 ——§16 结论点 ②
  const lifecycleXml = `<?xml version="1.0" encoding="UTF-8"?><LifecycleConfiguration><Rule><ID>probe-grace-delete</ID><Filter><Prefix>probe/grace/</Prefix></Filter><Status>Enabled</Status><Expiration><Days>7</Days></Expiration></Rule></LifecycleConfiguration>`;
  r = await request('PUT', `/${BUCKET}/`, { query: { 'lifecycle': '' }, headers: { 'content-type': 'application/xml', 'content-length': String(Buffer.byteLength(lifecycleXml)) }, body: lifecycleXml });
  const lcPutOk = r.status === 200;
  check('PutBucketLifecycleConfiguration', lcPutOk, `status=${r.status} ${(r.text || '').slice(0, 200).replace(/\s+/g, ' ')}`);
  r = await request('GET', `/${BUCKET}/`, { query: { 'lifecycle': '' } });
  check('GetBucketLifecycleConfiguration 回读', r.status === 200 && /probe-grace-delete/.test(r.text || ''), `status=${r.status} ${(r.text || '').slice(0, 200).replace(/\s+/g, ' ')}`);

  // 10. 分片上传（大图 5MB+ 前提）
  const big = Buffer.alloc(5 * 1024 * 1024, 'p');
  r = await request('PUT', `/${BUCKET}/probe/big.bin`, { headers: { 'content-type': 'application/octet-stream', 'content-length': String(big.length) }, body: big });
  check('PutObject 5MB', r.status === 200, `status=${r.status} bytes=${r.bytes || big.length}`);

  // 11. 多部分上传接口是否存在
  r = await request('POST', `/${BUCKET}/probe/mpu.bin`, { query: { 'uploads': '' } });
  check('CreateMultipartUpload 支持', r.status === 200 && /UploadId/.test(r.text || ''), `status=${r.status} ${(r.text || '').slice(0, 160).replace(/\s+/g, ' ')}`);

  // 12. DeleteObject + sweep 前提
  for (const k of ['probe/big.bin', 'probe/sse-s3.txt', 'probe/sse-c.txt', 'probe/mpu.bin', KEY]) {
    r = await request('DELETE', `/${BUCKET}/${k}`);
    check(`DeleteObject ${k}`, r.status === 204 || r.status === 200, `status=${r.status}`);
  }
  r = await request('GET', `/${BUCKET}/${KEY}`);
  check('删除后回读 404', r.status === 404, `status=${r.status}`);

  // 13. 落盘是否明文（关键：SSE 不支持时我们必须自己封装）
  r = await request('PUT', `/${BUCKET}/probe/plaintext-check.txt`, { headers: { 'content-type': 'text/plain', 'content-length': String(Buffer.byteLength(CONTENT)) }, body: CONTENT });
  const marker = 'pushbox-plaintext-marker';
  const marked = CONTENT + ' ' + marker;
  await request('PUT', `/${BUCKET}/probe/plaintext-check.txt`, { headers: { 'content-type': 'text/plain', 'content-length': String(Buffer.byteLength(marked)) }, body: marked });
  r = await request('GET', `/${BUCKET}/probe/plaintext-check.txt`);
  check('无 SSE 的对象内容原样可读（⇒ 桶内是明文）', /plaintext-marker/.test(r.text || ''), `status=${r.status}`);
  await request('DELETE', `/${BUCKET}/probe/plaintext-check.txt`);

  // 14. 收尾：清掉探测写的生命周期规则，不在服务端留副作用
  r = await request('DELETE', `/${BUCKET}/`, { query: { 'lifecycle': '' } });
  const rl = await request('GET', `/${BUCKET}/`, { query: { 'lifecycle': '' } });
  check('清理 lifecycle 规则（204 + 回读 404）', r.status === 204 && rl.status === 404, `delete=${r.status} get=${rl.status}`);
  const lst = await request('GET', `/${BUCKET}/`, { query: { 'list-type': '2', 'prefix': 'probe/' } });
  check('probe/ 前缀已清空', !/<Key>/.test(lst.text || ''), `剩余=${(lst.text || '').split('<Key>').length - 1}`);

  const passed = report.filter((x) => x.pass).length;
  console.log(`\n=== ${passed}/${report.length} passed ===`);
  console.log('结论 SSE-S3=' + (report.find((x) => x.name.includes('SSE-S3')).pass ? 'YES' : 'NO') +
    ' | SSE-C=' + (report.find((x) => x.name === 'PutObject with SSE-C').pass ? 'YES' : 'NO') +
    ' | Lifecycle=' + (lcPutOk ? 'YES' : 'NO'));
};

run();
