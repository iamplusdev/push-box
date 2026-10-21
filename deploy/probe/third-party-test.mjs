// 第三方入站接口测试：用真实 curl 调 POST /message，Node 模拟设备收帧验证端到端投递。
// 用法：先 bash deploy/run-dev.sh，再 node deploy/probe/third-party-test.mjs
// 可选环境变量：PB_BASE（默认 http://127.0.0.1:7404）、PB_CURL（默认 curl）
import crypto from 'node:crypto'
import fs from 'node:fs'
import os from 'node:os'
import pathModule from 'node:path'
import { execFileSync } from 'node:child_process'
import WebSocket from 'ws'

const BASE = process.env.PB_BASE ?? 'http://127.0.0.1:7404'
const CURL = process.env.PB_CURL ?? 'curl'
const MAX_LATENCY_MS = Number(process.env.PB_MAX_LATENCY_MS ?? 1000)

const FRAME_INFO = Buffer.from('pushbox-frame-v1')
const WS_AUTH_V1 = 'pushbox-ws-auth-v1'
const REST_AUTH_V1 = 'pushbox-rest-auth-v1'
const SPKI_PREFIX = { x25519: '302a300506032b656e032100', ed25519: '302a300506032b6570032100' }

const b64u = (b) => Buffer.from(b).toString('base64url')
const un = (s) => Buffer.from(s, 'base64url')
const idx = (n) => { const b = Buffer.alloc(8); b.writeBigUInt64BE(BigInt(n)); return b }
const sha256 = (b) => crypto.createHash('sha256').update(b).digest()

function pubFromRaw(raw, kind) {
  return crypto.createPublicKey({
    key: Buffer.concat([Buffer.from(SPKI_PREFIX[kind], 'hex'), Buffer.from(raw)]),
    format: 'der', type: 'spki',
  })
}
const rawPub = (key) => { const der = key.export({ type: 'spki', format: 'der' }); return der.subarray(der.length - 32) }
const genX25519 = () => { const { publicKey, privateKey } = crypto.generateKeyPairSync('x25519'); return { privateKey, raw: rawPub(publicKey) } }
const genEd25519 = () => { const { publicKey, privateKey } = crypto.generateKeyPairSync('ed25519'); return { privateKey, publicKey, raw: rawPub(publicKey) } }
const dh = (priv, peerRaw) => Buffer.from(crypto.diffieHellman({ privateKey: priv, publicKey: pubFromRaw(peerRaw, 'x25519') }))
const hkdf = (ikm, salt, info, len) => Buffer.from(crypto.hkdfSync('sha256', ikm, salt, info, len))
function sealFrame(key, n, plain) {
  const c = crypto.createCipheriv('aes-256-gcm', key, Buffer.concat([idx(n), Buffer.alloc(4)]))
  c.setAAD(idx(n))
  return Buffer.concat([c.update(plain), c.final(), c.getAuthTag()])
}
function openFrame(key, n, blob) {
  try {
    const d = crypto.createDecipheriv('aes-256-gcm', key, Buffer.concat([idx(n), Buffer.alloc(4)]))
    d.setAAD(idx(n))
    d.setAuthTag(blob.subarray(blob.length - 16))
    return Buffer.concat([d.update(blob.subarray(0, blob.length - 16)), d.final()])
  } catch { return null }
}
const signEd = (priv, msg) => crypto.sign(null, Buffer.from(msg), priv)
const verifyEd = (pubRaw, msg, sig) =>
  crypto.verify(null, Buffer.from(msg), pubFromRaw(pubRaw, 'ed25519'), Buffer.from(sig))

function unseal(resp, encPriv) {
  const key = hkdf(dh(encPriv, un(resp.eph_pub)), un(resp.salt), FRAME_INFO, 32)
  const plain = openFrame(key, resp.n, un(resp.data))
  if (plain === null) throw new Error('sealed box 解密失败')
  return JSON.parse(plain.toString('utf8'))
}

async function api(path, { method = 'GET', query = '', body, device } = {}) {
  const bodyBuf = body === undefined ? null : Buffer.from(JSON.stringify(body))
  const headers = { 'content-type': 'application/json' }
  if (device) {
    const nonce = crypto.randomBytes(16)
    const ts = Date.now()
    const target = `${REST_AUTH_V1}\n${device.deviceId}\n${method.toUpperCase()}\n${path}\n${query}` +
      `\n${sha256(bodyBuf ?? Buffer.alloc(0)).toString('hex')}\n${ts}\n${b64u(nonce)}`
    headers.authorization = `Bearer ${device.token}`
    headers['X-PB-Device'] = device.deviceId
    headers['X-PB-Ts'] = String(ts)
    headers['X-PB-Nonce'] = b64u(nonce)
    headers['X-PB-Sig'] = b64u(signEd(device.keys.sign.privateKey, target))
  }
  const res = await fetch(`${BASE}${path}${query ? '?' + query : ''}`,
    { method, headers, body: bodyBuf ? bodyBuf.toString('utf8') : undefined })
  return { status: res.status, json: await res.json().catch(() => ({})) }
}

class Device {
  constructor(name) {
    this.name = name
    this.keys = { enc: genX25519(), sign: genEd25519() }
    this.frames = []
  }
  async enroll() {
    const res = await api('/api/v1/devices/register', { method: 'POST', body: {
      enc_pubkey: b64u(this.keys.enc.raw), sign_pubkey: b64u(this.keys.sign.raw),
      name: this.name, platform: 'node' } })
    if (res.status !== 200) throw new Error(`设备注册失败 ${res.status}: ${JSON.stringify(res.json)}`)
    const info = unseal(res.json, this.keys.enc.privateKey)
    this.deviceId = info.device_id
    this.token = info.token
    return info
  }
  connect() {
    const wsUrl = BASE.replace(/^http/, 'ws') + '/ws'
    this.ws = new WebSocket(`${wsUrl}?since_seq=0`)
    this.ws.on('message', (raw) => {
      let msg
      try { msg = JSON.parse(raw.toString()) } catch { return }
      if (msg.t === 'hello') {
        this._nonce = un(msg.nonce)
        this._fp = msg.server_pub_fp
        const ts = Date.now()
        this.ws.send(JSON.stringify({ t: 'auth', device_id: this.deviceId, token: this.token,
          ts, sig: b64u(signEd(this.keys.sign.privateKey,
            `${WS_AUTH_V1}\n${this.deviceId}\n${b64u(this._nonce)}\n${ts}`)) }))
        return
      }
      if (msg.t === 'auth_fail') { console.error('握手被拒:', msg); return }
      if (msg.t === 'kx') {
        const serverPub = un(msg.server_pub)
        if (!verifyEd(serverPub, Buffer.concat([un(msg.eph_pub), this._nonce]), un(msg.sig))) {
          console.error('kx 验签失败'); this.ws.close(); return
        }
        this.sessionKey = hkdf(dh(this.keys.enc.privateKey, un(msg.eph_pub)), this._nonce, FRAME_INFO, 32)
        return
      }
      if (msg.t === 'enc') {
        const plain = openFrame(this.sessionKey, msg.n, un(msg.data))
        if (plain === null) { console.error(`帧 ${msg.n} 解密失败`); return }
        const frame = JSON.parse(plain.toString('utf8'))
        this.frames.push({ frame, at: Date.now() })
        this.ws.send(JSON.stringify({ t: 'pong' }))
      }
    })
  }
  async waitReady(timeoutMs = 20000) {
    const ok = await until(() => this.frames.some((f) =>
      f.frame.t === 'resume_ok' || f.frame.t === 'resume_gap'), timeoutMs)
    if (!ok) throw new Error(`${timeoutMs}ms 内没有收到 resume 帧，WS 握手未完成`)
  }
  close() { this.ws?.close() }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
async function until(pred, timeoutMs) {
  const deadline = Date.now() + timeoutMs
  for (;;) {
    const v = pred()
    if (v) return v
    if (Date.now() > deadline) return null
    await sleep(20)
  }
}

// 第三方视角：完全通过命令行 curl 调接口，与 Postman/Apicifer/告警平台无差别。
// 请求体写临时文件再 @file 传入：Windows 下命令行参数会把中文重编码成 GBK，导致 JSON 损坏。
function thirdPartyPost(bodyObj) {
  const file = pathModule.join(os.tmpdir(), `pb-body-${crypto.randomBytes(8).toString('hex')}.json`)
  try {
    fs.writeFileSync(file, JSON.stringify(bodyObj), 'utf8')
    const out = execFileSync(CURL, ['-s', '-w', '\n%{http_code}', '-X', 'POST',
      `${BASE}/message`, '-H', 'content-type: application/json',
      '--data-binary', `@${file}`], { encoding: 'utf8' })
    const nl = out.lastIndexOf('\n')
    const status = Number(out.slice(nl + 1))
    const jsonBody = (() => { try { return JSON.parse(out.slice(0, nl)) } catch { return {} } })()
    return { status, json: jsonBody }
  } finally {
    fs.rmSync(file, { force: true })
  }
}

const results = []
function check(no, name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${no} ${name}${detail ? `  [${detail}]` : ''}`)
}

async function main() {
  console.log(`目标服务端: ${BASE}\n`)

  // ---- 准备：模拟设备上线 + 建频道（这两步是 App 侧职责，不是第三方接口面）
  const dev = new Device('third-party-test')
  await dev.enroll()
  const created = await api('/api/v1/channels', { method: 'POST',
    body: { name: '第三方测试频道' }, device: dev })
  if (created.status !== 200) throw new Error(`建频道失败 ${created.status}: ${JSON.stringify(created.json)}`)
  const chan = unseal(created.json, dev.keys.enc.privateKey)
  dev.connect()
  await dev.waitReady()
  console.log(`设备 ${dev.deviceId} 已在线；频道 ${chan.channel_no}（secret 仅本次回显）\n`)

  const cred = { channel: chan.channel_no, secret: chan.secret }
  const waitFrame = (pred, ms = 15000) =>
    until(() => dev.frames.find((f) => pred(f.frame)), ms)

  // ---- T1 纯文字告警：curl POST /message → 设备 1s 内收到解密帧
  const t1Body = { ...cred, type: 'text', title: 'Grafana 告警',
    content: 'CPU 使用率超过 90%', level: 'warning', sender_id: 'grafana',
    sender_name: 'Grafana', msg_key: `tp-t1-${Date.now()}` }
  const t0 = Date.now()
  const r1 = thirdPartyPost(t1Body)
  console.log('T1 curl >', JSON.stringify({ ...t1Body, secret: '***' }))
  const f1 = await waitFrame((f) => f.t === 'message' && f.id === r1.json?.id, MAX_LATENCY_MS + 3000)
  const latency1 = f1 ? f1.at - t0 : -1
  check('T1', 'curl 发纯文字 → 200 受理且在线设备收到内容一致的下行帧',
    r1.status === 200 && r1.json.ok === true && f1?.frame.content === 'CPU 使用率超过 90%'
      && f1?.frame.notify === true && latency1 < MAX_LATENCY_MS,
    `HTTP ${r1.status}, 延迟 ${latency1}ms, seq=${r1.json.seq}`)

  // ---- T2 幂等：同 msg_key 重发 → 同 id/seq，设备不重复收
  const key2 = `tp-t2-${Date.now()}`
  const r2a = thirdPartyPost({ ...cred, type: 'text', content: '只应送达一次', msg_key: key2 })
  await waitFrame((f) => f.t === 'message' && f.id === r2a.json?.id, 5000)
  await sleep(200)
  const r2b = thirdPartyPost({ ...cred, type: 'text', content: '只应送达一次', msg_key: key2 })
  const dupCount = dev.frames.filter((f) => f.frame.t === 'message' && f.frame.id === r2a.json.id).length
  check('T2', '同 msg_key 重发返回同一 id/seq 且设备只收到一帧',
    r2b.json.id === r2a.json.id && r2b.json.seq === r2a.json.seq && dupCount === 1,
    `id=${r2a.json.id} 重复帧=${dupCount}`)

  // ---- T3 凭据错误：secret 错 / 频道不存在 → 同一个 401
  const r3a = thirdPartyPost({ ...cred, secret: 'wrong-secret', type: 'text', content: 'x' })
  const r3b = thirdPartyPost({ channel: '99999999', secret: 'whatever', type: 'text', content: 'x' })
  check('T3', 'secret 错与频道不存在都返回 401 invalid_credential',
    r3a.status === 401 && r3b.status === 401 && r3a.json.error === r3b.json.error,
    `${r3a.status}/${r3a.json.error} vs ${r3b.status}/${r3b.json.error}`)

  // ---- T4 带图消息：当前构建为 P0 口径（外链只登记为 pending，抓取器属 P1）。
  //     抓取器落地后此断言要升级为 D24 终态口径：message 帧到达时 media 已是 ready/failed。
  const t4Body = { ...cred, type: 'text', content: '带截图告警',
    images: ['https://www.gstatic.com/webp/gallery/1.jpg'], msg_key: `tp-t4-${Date.now()}` }
  const r4 = thirdPartyPost(t4Body)
  console.log('T4 curl >', JSON.stringify({ ...t4Body, secret: '***' }))
  const f4 = await waitFrame((f) => f.t === 'message' && f.id === r4.json?.id, 15000)
  const mediaSlots = f4?.frame.media ?? []
  check('T4', 'images[] 外链被受理：message 帧带 1 个 pending 槽位（待抓取器实现后进终态）',
    r4.status === 200 && r4.json.media_status === 'pending' && r4.json.media_count === 1
      && mediaSlots.length === 1 && mediaSlots[0].status === 'pending',
    `受理=${r4.json.media_status}/${r4.json.media_count}，帧内 media=${JSON.stringify(mediaSlots.map((m) => m.status))}`)

  // ---- T5 参数校验：data: 内联图拒绝、超长/空内容拒绝
  const r5a = thirdPartyPost({ ...cred, type: 'text', content: '内联图',
    images: ['data:image/png;base64,AAAA'] })
  const r5b = thirdPartyPost({ ...cred, type: 'text', content: '' })
  check('T5', 'data: 内联图与空内容均被 400 拒绝',
    r5a.status === 400 && r5b.status === 400,
    `${r5a.status}/${r5a.json.error} vs ${r5b.status}/${r5b.json.error}`)

  // ---- T6 离线补拉：设备断线期间发的消息，重连后 notify=false 补齐
  dev.close()
  await until(() => dev.ws.readyState === WebSocket.CLOSED, 2000)
  const r6 = thirdPartyPost({ ...cred, type: 'text', content: '离线期间发的消息',
    msg_key: `tp-t6-${Date.now()}` })
  const dev2 = dev
  dev2.frames = []
  dev2.connect()
  await dev2.waitReady()
  const f6 = await waitFrame((f) => f.t === 'message' && f.id === r6.json?.id, 10000)
  check('T6', '断线期间消息在重连后按 since_seq 补齐且不再弹通知',
    f6 && f6.frame.notify === false, `notify=${f6?.frame.notify}`)

  dev.close()
  await sleep(200)

  const template = `{"channel":"<CHANNEL>","secret":"<SECRET>","type":"text","content":"hello",` +
    `"title":"标题","level":"info","sender_id":"my-service","msg_key":"k-1"}`
  console.log(`\n可直接复用的第三方 curl 模板（把 <CHANNEL>/<SECRET> 换成频道凭据；` +
    `含中文时请求体必须走 UTF-8 文件或工具的编码设置，不要直接拼进 Windows 命令行）：\n` +
    `curl -X POST ${BASE}/message -H 'content-type: application/json' --data-binary @body.json\n` +
    `# body.json（UTF-8）= ${template}\n`)
  const failed = results.filter((r) => !r.ok)
  console.log(`${results.length - failed.length}/${results.length} 通过`)
  if (failed.length) console.log('未通过：' + failed.map((f) => f.name).join(' | '))
  process.exitCode = failed.length ? 1 : 0
}

main().catch((e) => { console.error('测试异常终止:', e); process.exitCode = 2 })
