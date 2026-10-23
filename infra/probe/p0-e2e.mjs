// P0 纯文字闭环验收探针（§12）。它就是一个"软件版 Android 设备"：
// 自己生成 X25519/Ed25519 密钥、按 §4.3/§4.4 逐字节复刻签名与帧加密，因此这条链路一旦跑通，
// Android 侧只剩 UI 与存储，不再会有协议层意外。
//
// 用法：先 bash infra/run-dev.sh，再 node infra/probe/p0-e2e.mjs
// PB_BASE 默认打本机标准端口 7404（与 base application.yml、prod 的 ${PB_SERVER_PORT:7404} 同一个默认值）。
// 这会真建空间与频道，所以那个端口上跑着别人手上的实例时，先另起一个自己用完就关的：
//   bash infra/run-dev.sh --server.port=8081  &&  PB_BASE=http://127.0.0.1:8081 node infra/probe/p0-e2e.mjs
import crypto from 'node:crypto'
import http from 'node:http'
import WebSocket from 'ws'

const BASE = process.env.PB_BASE ?? 'http://127.0.0.1:7404'
const DEBUG = Boolean(process.env.PB_DEBUG)
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws'
// 验收线固定是 1s（§12 ①）；数据库在远端时单条 SQL 就是几百毫秒，
// 所以等待窗口和阈值分开配：窗口只决定"还等多久才算丢"，阈值才是判分线。
const MAX_LATENCY_MS = Number(process.env.PB_MAX_LATENCY_MS ?? 1000)
const READY_TIMEOUT_MS = Number(process.env.PB_READY_TIMEOUT_MS ?? 20000)
const FRAME_WAIT_MS = Math.max(READY_TIMEOUT_MS, MAX_LATENCY_MS * 5)

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
  } catch {
    return null
  }
}
const signEd = (priv, msg) => crypto.sign(null, Buffer.from(msg), priv)
const verifyEd = (pubRaw, msg, sig) =>
  crypto.verify(null, Buffer.from(msg), pubFromRaw(pubRaw, 'ed25519'), Buffer.from(sig))

/** §4.4：REST 响应是一次性密封盒，用设备自己的静态 enc 私钥还原。 */
function unseal(resp, encPriv) {
  if (!resp || !resp.eph_pub) throw new Error(`响应不是密封盒：${JSON.stringify(resp).slice(0, 160)}`)
  const key = hkdf(dh(encPriv, un(resp.eph_pub)), un(resp.salt), FRAME_INFO, 32)
  const plain = openFrame(key, resp.n, un(resp.data))
  if (plain === null) throw new Error('sealed box 解密失败')
  return JSON.parse(plain.toString('utf8'))
}

/** §4.4 的 REST 签名头：path/query/body 都进摘要，所以 query 必须和实际发出的完全一致。 */
function signedHeaders(device, method, path, query, bodyBuf) {
  const nonce = crypto.randomBytes(16)
  const ts = Date.now()
  const target = `${REST_AUTH_V1}\n${device.deviceId}\n${method.toUpperCase()}\n${path}\n${query}` +
    `\n${sha256(bodyBuf ?? Buffer.alloc(0)).toString('hex')}\n${ts}\n${b64u(nonce)}`
  return {
    'content-type': 'application/json',
    authorization: `Bearer ${device.token}`,
    'X-PB-Device': device.deviceId,
    'X-PB-Ts': String(ts),
    'X-PB-Nonce': b64u(nonce),
    'X-PB-Sig': b64u(signEd(device.keys.sign.privateKey, target)),
  }
}

async function api(path, { method = 'GET', query = '', body, device } = {}) {
  const bodyBuf = body === undefined ? null : Buffer.from(JSON.stringify(body))
  const headers = device ? signedHeaders(device, method, path, query, bodyBuf)
    : { 'content-type': 'application/json' }
  const res = await fetch(`${BASE}${path}${query ? '?' + query : ''}`,
      { method, headers, body: bodyBuf ? bodyBuf.toString('utf8') : undefined })
  return { status: res.status, json: await res.json().catch(() => ({})) }
}

class Device {
  constructor(name) {
    this.name = name
    this.keys = { enc: genX25519(), sign: genEd25519() }
  }

  async enroll(viaJoin, groupSecret) {
    const body = {
      enc_pubkey: b64u(this.keys.enc.raw),
      sign_pubkey: b64u(this.keys.sign.raw),
      name: this.name,
      platform: 'node',
    }
    if (groupSecret) body.group_secret = groupSecret
    const res = await api(`/api/v1/devices/${viaJoin ? 'join' : 'register'}`, { method: 'POST', body })
    if (res.status !== 200) throw new Error(`${this.name} 注册失败 ${res.status}: ${JSON.stringify(res.json)}`)
    const info = unseal(res.json, this.keys.enc.privateKey)
    this.deviceId = info.device_id
    this.groupId = info.group_id
    this.token = info.token
    this.recoveryCode = info.recovery_code
    this.serverPubFp = info.server_pub_fp
    return info
  }

  /** 该设备的帧流：连接期间收到的每一帧都记下来，供断言轮询。 */
  connect(sinceSeq = 0) {
    const sink = { frames: [], kx: [], resets: [], lastN: null, closed: false, error: null,
      lastSeq: sinceSeq }
    const ws = new WebSocket(`${WS_URL}?since_seq=${sinceSeq}`)
    ws.on('message', (raw) => {
      let msg
      try { msg = JSON.parse(raw.toString()) } catch { return }
      if (DEBUG) console.log('  <=', raw.toString().slice(0, 240))
      if (msg.t === 'hello') {
        const nonce = un(msg.nonce)
        const ts = Date.now()
        const sig = signEd(this.keys.sign.privateKey,
          `${WS_AUTH_V1}\n${this.deviceId}\n${b64u(nonce)}\n${ts}`)
        const authFrame = JSON.stringify({ t: 'auth', device_id: this.deviceId, token: this.token,
          ts, sig: b64u(sig) })
        if (DEBUG) console.log('  =>', authFrame.slice(0, 240))
        ws.send(authFrame)
        this._nonce = nonce
        this._fp = msg.server_pub_fp
        return
      }
      if (msg.t === 'auth_fail') { sink.error = msg.error; ws.close(); return }
      if (msg.t === 'kx') {
        const serverPub = un(msg.server_pub)
        const fp = sha256(serverPub).toString('hex').slice(0, 16)
        if (fp !== this._fp) { sink.error = 'server_pub_fp 与身份公钥不符'; ws.close(); return }
        if (!verifyEd(serverPub, Buffer.concat([un(msg.eph_pub), this._nonce]), un(msg.sig))) {
          sink.error = 'kx 验签失败'; ws.close(); return
        }
        this.sessionKey = hkdf(dh(this.keys.enc.privateKey, un(msg.eph_pub)), this._nonce,
          FRAME_INFO, 32)
        sink.kx.push(msg.epoch ?? 0)
        return
      }
      if (msg.t === 'enc') {
        // 序号回落只可能来自跨界轮换（同一条连接的帧号本应单调递增）
        if (sink.lastN !== null && msg.n <= sink.lastN) sink.resets.push(msg.n)
        sink.lastN = msg.n
        const plain = openFrame(this.sessionKey, msg.n, un(msg.data))
        if (plain === null) { sink.error = `帧 ${msg.n} 解密失败`; ws.close(); return }
        const frame = JSON.parse(plain.toString('utf8'))
        sink.frames.push(frame)
        if (frame.t === 'message') sink.lastSeq = Math.max(sink.lastSeq, frame.seq)
        ws.send(JSON.stringify({ t: 'pong' }))
      }
    })
    ws.on('close', (code, reason) => {
      sink.closed = true
      if (DEBUG) console.log('  xx close', code, reason.toString())
    })
    ws.on('error', (e) => { sink.error = String(e) })
    this.sink = sink
    this.ws = ws
    return sink
  }

  async waitReady() {
    const ready = await until(() => this.sink.frames.some((f) => f.t === 'resume_ok'
      || f.t === 'resume_gap') || this.sink.error, READY_TIMEOUT_MS)
    if (this.sink.error) throw new Error(`握手失败: ${this.sink.error}`)
    if (!ready) throw new Error(`握手后 ${READY_TIMEOUT_MS}ms 内没有任何下行帧（服务端可能吞了异常）`)
    return this.sink
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

const results = []
function check(no, name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${no} ${name}${detail ? `  [${detail}]` : ''}`)
}

async function main() {
  console.log(`目标服务端: ${BASE}\n`)

  // ---- 设备 A 首启（拿到恢复码）
  const a = new Device('probe-A')
  const aInfo = await a.enroll(false)
  console.log(`📱 设备 A 注册成功:`)
  console.log(`   device_id: ${a.deviceId}`)
  console.log(`   recovery_code: ${a.recoveryCode}`)
  console.log(`   channel_no (待创建): 稍后显示\n`)
  check('0a', '注册返回密封的 token/恢复码，且服务端身份指纹可读',
    Boolean(a.deviceId && a.token && a.recoveryCode && a.serverPubFp),
    `device_id=${a.deviceId}`)

  // ---- 频道：8 位纯数字 + 一次性回显 secret
  const created = await api('/api/v1/channels', { method: 'POST', body: { name: '联调频道' }, device: a })
  const ch = created.json
  const chan = unseal(ch, a.keys.enc.privateKey)
  console.log(`📺 频道创建成功:`)
  console.log(`   channel_no: ${chan.channel_no}`)
  console.log(`   secret: ${chan.secret}\n`)
  check('0b', '建频道得到 8 位数字频道号与 secret',
    created.status === 200 && /^\d{8}$/.test(String(chan.channel_no)) && Boolean(chan.secret),
    `channel_no=${chan.channel_no}`)

  const aSink = a.connect(0)
  await a.waitReady()
  check('0c', 'WS 握手 hello→auth→kx 完成，resume_ok 已收到', !aSink.error)

  const ingest = (payload) => api('/message', { method: 'POST', body: payload })
  const thirdParty = { channel: chan.channel_no, secret: chan.secret }

  // ---- ① 发消息 → 1s 内出通知内容
  const t0 = Date.now()
  const post = await ingest({ ...thirdParty, type: 'text', content: '联调测试：一条纯文字消息',
    title: 'Grafana 告警', level: 'warning', sender_id: 'grafana', sender_name: 'Grafana',
    msg_key: 'p0-first' })
  const gotFrame = await until(() => aSink.frames.find((f) => f.t === 'message' && f.id === post.json.id),
    FRAME_WAIT_MS)
  const postMs = Date.now() - t0
  const latency = Date.now() - t0
  check('①', `curl 发消息 → 在线设备 ${MAX_LATENCY_MS}ms 内收到解密后的内容`,
    post.status === 200 && gotFrame && latency < MAX_LATENCY_MS && gotFrame.content === '联调测试：一条纯文字消息'
      && gotFrame.notify === true,
    `延迟 ${latency}ms, seq=${post.json.seq}, summary=${post.json.summary}`)

  // 延迟分段归因：① 只测一次，落在连接池冷态上；连发几轮才能分清"链路 RTT × 串行 SQL 往返"
  // 与"代码里多出来的往返"。POST 时间 = 服务端受理（含全部 SQL）；下发时间 = POST 之后到帧到达。
  const samples = Number(process.env.PB_LATENCY_SAMPLE ?? 0)
  const posts = [], tails = []
  for (let i = 0; i < samples; i++) {
    const s0 = Date.now()
    const p = await ingest({ ...thirdParty, type: 'text', content: `延迟取样 ${i}`,
      level: 'info', sender_id: 'grafana' })
    const s1 = Date.now()
    await until(() => aSink.frames.some((f) => f.t === 'message' && f.id === p.json?.id),
      FRAME_WAIT_MS)
    posts.push(s1 - s0)
    tails.push(Date.now() - s1)
  }
  if (samples > 0) {
    const fmt = (a) => a.slice().sort((x, y) => x - y).map((v) => `${v}ms`).join(' ')
    console.log(`     取样 ${samples} 轮  POST（服务端受理）= ${fmt(posts)}  ` +
      `受理后到帧到达= ${fmt(tails)}  ① 首轮 POST=${postMs}ms`)
  }

  // ---- ② 幂等：同 msg_key 重发不产生第二条消息、也不再下发
  const again = await ingest({ ...thirdParty, type: 'text', content: '联调测试：一条纯文字消息',
    title: 'Grafana 告警', level: 'warning', sender_id: 'grafana', msg_key: 'p0-first' })
  const dupes = aSink.frames.filter((f) => f.t === 'message' && f.id === post.json.id).length
  check('②', '同 msg_key 重发返回同一 id/seq 且不重复下发',
    again.status === 200 && again.json.id === post.json.id && again.json.seq === post.json.seq
      && dupes === 1,
    `重复帧数=${dupes}`)

  // ---- ③ 凭据错误统一 401，不泄露频道是否存在
  const badSecret = await ingest({ channel: chan.channel_no, secret: 'wrong-secret', type: 'text', content: 'x' })
  const badChannel = await ingest({ channel: '99999999', secret: 'whatever', type: 'text', content: 'x' })
  check('③', 'secret 错与频道不存在都回同一个 401 invalid_credential',
    badSecret.status === 401 && badChannel.status === 401
      && badSecret.json.error === badChannel.json.error,
    `${badSecret.status}/${badSecret.json.error} vs ${badChannel.status}/${badChannel.json.error}`)

  // ---- ④ 断线期间积压，重连补齐且不重复弹通知
  a.close()
  await until(() => aSink.closed, 2000)
  const offlineIds = []
  for (let i = 0; i < 2; i++) {
    const r = await ingest({ ...thirdParty, type: 'text', content: `离线补拉 ${i}`, msg_key: `offline-${i}` })
    offlineIds.push(r.json.id)
  }
  const a2 = a // 同一设备身份重连，等价于 App 进程被杀后重启
  const replaySink = a2.connect(post.json.seq)
  await a2.waitReady()
  const replayed = replaySink.frames.filter((f) => f.t === 'message')
  const missing = offlineIds.filter((id) => replayed.some((f) => f.id === id))
  const gotOld = replayed.some((f) => f.id === post.json.id)
  check('④', '断线重连按 since_seq 补齐缺的 2 条且不重复已有消息',
    missing.length === 2 && !gotOld && replayed.every((f) => f.notify === false),
    `补齐 ${replayed.length} 帧，缺=${missing.length}，重复=${gotOld}`)
  a2.close()
  await until(() => replaySink.closed, 2000)

  // ---- ⑤ 恢复码换新设备 → 历史文字 100% 恢复（REST 密封盒）
  const b = new Device('probe-B')
  await b.enroll(true, a.recoveryCode)
  const hist = await api('/api/v1/messages', { query: 'after_seq=0&limit=200', device: b })
  const page = unseal(hist.json, b.keys.enc.privateKey)
  const texts = page.messages.filter((m) => m.deleted_at == null).map((m) => m.content)
  check('⑤', '仅凭恢复码的新设备能密封拉回全部历史文字',
    hist.status === 200 && texts.includes('联调测试：一条纯文字消息')
      && texts.includes('离线补拉 0') && texts.includes('离线补拉 1'),
    `${texts.length} 条 / latest_seq=${page.latest_seq}`)

  // ---- ⑥ 未注册设备拿不到任何内容
  const stranger = new Device('probe-stranger')
  await stranger.enroll(false)
  const stolen = await api('/api/v1/messages', { query: 'after_seq=0',
    device: { deviceId: a.deviceId, token: 'not-a-real-token', keys: stranger.keys } })
  const own = await api('/api/v1/messages', { query: 'after_seq=0', device: stranger })
  const ownPage = unseal(own.json, stranger.keys.enc.privateKey)
  check('⑥', '伪造 token 被 401 拒绝；别的分组自拉历史为空',
    stolen.status === 401 && own.status === 200 && ownPage.messages.length === 0,
    `伪造=${stolen.status}， stranger 历史=${ownPage.messages.length}`)

  // ---- ⑦ 图片只登记不抓取（P0 边界，D24 的抓取器属 P1）
  const withImages = await ingest({ ...thirdParty, type: 'text', content: '带图告警',
    images: ['https://example.com/a.png'], msg_key: 'img-1' })
  const badImage = await ingest({ ...thirdParty, type: 'text', content: '内联图',
    images: ['data:image/png;base64,AAAA'], msg_key: 'img-2' })
  check('⑦', 'images[] 接受外链并标记 pending，data: 内联图被拒',
    withImages.status === 200 && withImages.json.media_status === 'pending'
      && withImages.json.media_count === 1 && badImage.status === 400,
    `${withImages.json.media_status}/${withImages.json.media_count} vs ${badImage.status} ${badImage.json.error}`)

  // ---- ⑧ 同组设备上限 5（D26）
  const extra = []
  let limitHit = null
  for (let i = 0; i < 4; i++) {
    const d = new Device(`probe-x${i}`)
    const r = await api('/api/v1/devices/join', { method: 'POST', body: {
      group_secret: a.recoveryCode, enc_pubkey: b64u(d.keys.enc.raw),
      sign_pubkey: b64u(d.keys.sign.raw), name: d.name, platform: 'node' } })
    if (r.status === 409) { limitHit = r.json; break }
    if (r.status === 200) {
      // join 的响应同样是密封盒，token/device_id 要从明文里取
      const info = unseal(r.json, d.keys.enc.privateKey)
      d.deviceId = info.device_id
      d.token = info.token
      extra.push(d)
    }
  }
  const active = unseal((await api('/api/v1/devices', { device: a })).json, a.keys.enc.privateKey)
  check('⑧', '同一恢复码活动设备上限 5，超出回 409',
    active.filter((d) => !d.revoked_at).length === 5 && Boolean(limitHit),
    `在册=${active.filter((d) => !d.revoked_at).length}，超限响应=${JSON.stringify(limitHit)}`)

  // ---- ⑨ 吊销即时生效：token 立刻不能拉历史，在线连接被踢
  const victim = extra[extra.length - 1]
  const revoked = await api(`/api/v1/devices/${victim.deviceId}`, { method: 'DELETE', device: a })
  const afterRevoke = await api('/api/v1/messages', { query: 'after_seq=0', device: victim })
  check('⑨', '吊销设备后其 token 立即失效',
    revoked.status === 200 && afterRevoke.status === 401,
    `revoke=${revoked.status}, 复用 token=${afterRevoke.status}`)

  // ---- ⑩ level 被频道 max_level 截断（D25：词表只有 info/warning/error，未知值降级为 info）
  await api('/api/v1/channels/' + chan.channel_no, { method: 'PUT',
    body: { max_level: 'warning' }, device: a })
  const noisy = await ingest({ ...thirdParty, type: 'text', content: '过高级别', level: 'error',
    msg_key: 'lvl-1' })
  const quiet = await ingest({ ...thirdParty, type: 'text', content: '未知级别', level: 'critical',
    msg_key: 'lvl-2' })
  check('⑩', '消息 level 被频道 max_level 截断，未知词表值降级为 info',
    noisy.status === 200 && noisy.json.level === 'warning'
      && quiet.json.level === 'info',
    `error → ${noisy.json.level}，critical → ${quiet.json.level}`)

  // ---- ⑪ 频道软归档（§5.2 DELETE /api/v1/channels/{no}）：即刻拒写、列表隐去、历史保留
  const c2 = unseal((await api('/api/v1/channels', { method: 'POST',
    body: { name: '待归档频道' }, device: a })).json, a.keys.enc.privateKey)
  const toC2 = await ingest({ channel: c2.channel_no, secret: c2.secret, type: 'text',
    content: '归档前的最后一条', msg_key: 'arch-1' })
  const archived = await api(`/api/v1/channels/${c2.channel_no}`, { method: 'DELETE', device: a })
  const afterArchive = await ingest({ channel: c2.channel_no, secret: c2.secret, type: 'text',
    content: '归档后仍尝试写入', msg_key: 'arch-2' })
  const listed = unseal((await api('/api/v1/channels', { device: a })).json, a.keys.enc.privateKey)
    .channels
  // 列表项走 ChannelService.view()，键名是 snake_case（与两端客户端读的同一个键）
  const keptHistory = unseal((await api('/api/v1/messages',
    { query: 'after_seq=0&limit=500', device: a })).json, a.keys.enc.privateKey)
    .messages.some((m) => m.id === toC2.json.id)
  const reArchive = await api(`/api/v1/channels/${c2.channel_no}`, { method: 'DELETE', device: a })
  const crossGroup = await api(`/api/v1/channels/${chan.channel_no}`, { method: 'DELETE',
    device: stranger })
  check('⑪', '归档频道即刻拒写 403、列表隐去、历史保留，重复归档与他组频道均 404',
    archived.status === 200 && unseal(archived.json, a.keys.enc.privateKey).ok === true
      && afterArchive.status === 403 && afterArchive.json.error === 'channel_locked'
      && !listed.some((c) => c.channel_no === c2.channel_no)
      && listed.some((c) => c.channel_no === chan.channel_no) && keptHistory
      && reArchive.status === 404 && crossGroup.status === 404,
    `archive=${archived.status}, 归档后写入=${afterArchive.status}/${afterArchive.json.error},`
      + ` 重复=${reArchive.status}, 跨组=${crossGroup.status},`
      // 隐去/保留这三条是纯读侧判定，不写进诊断行的话，失败了只看到一串全对的状态码，查不出是哪半
      + ` 列表=${listed.length}条(归档号在否=${listed.some((c) => c.channel_no === c2.channel_no)},`
      + ` 常用号在否=${listed.some((c) => c.channel_no === chan.channel_no)}), 历史保留=${keptHistory}`)

  // ---- ⑫ 超 32KB 帧截断下发，按 id 取回全文；补拉路径同规则截断（§5.3 补发保证）
  const capFrom = unseal((await api('/api/v1/messages',
    { query: 'after_seq=0&limit=1', device: a })).json, a.keys.enc.privateKey).latest_seq
  const capSink = a.connect(capFrom)
  await a.waitReady()
  const longBody = 'a'.repeat(32700)
  const big = await ingest({ ...thirdParty, type: 'text', content: longBody, msg_key: 'big-1',
    sender_id: 'grafana' })
  const bigFrame = await until(() => capSink.frames.find((f) => f.t === 'message'
    && f.id === big.json.id), FRAME_WAIT_MS)
  const full = unseal((await api(`/api/v1/messages/${big.json.id}`, { device: a })).json,
    a.keys.enc.privateKey)
  const bigSeq = bigFrame?.seq ?? 0
  a.close()
  await until(() => capSink.closed, 2000)
  const gapSink = a.connect(Math.max(0, bigSeq - 1))
  await a.waitReady()
  const replayedBig = gapSink.frames.find((f) => f.t === 'message' && f.id === big.json.id)
  a.close()
  await until(() => gapSink.closed, 2000)
  await api(`/api/v1/messages/${big.json.id}`, { method: 'DELETE', device: a })
  const gone = await api(`/api/v1/messages/${big.json.id}`, { device: a })
  const foreign = await api(`/api/v1/messages/${post.json.id}`, { device: stranger })
  const frameBytes = (f) => (f ? Buffer.byteLength(JSON.stringify(f), 'utf8') : Infinity)
  check('⑫', '超长正文在线帧截断到 32KB 内、REST 可按 id 取回全文、补拉同样截断、删除与他组读取 404',
    big.status === 200 && Boolean(bigFrame) && bigFrame.truncated === true
      && bigFrame.content.length < longBody.length && frameBytes(bigFrame) <= 32768
      && full.content === longBody && full.id === big.json.id && full.truncated === undefined
      && Boolean(replayedBig) && replayedBig.truncated === true
      && frameBytes(replayedBig) <= 32768
      && gone.status === 404 && foreign.status === 404,
    `在线帧 ${frameBytes(bigFrame)}B 正文 ${bigFrame?.content?.length}/${longBody.length}，`
      + ` 补拉帧 ${replayedBig ? frameBytes(replayedBig) : '—'}B，`
      + ` 删除后=${gone.status}，跨组=${foreign.status}`)

  // ---- ⑬ 会话密钥跨界（§4.3 ratchet）。需要服务端配合缩短阈值：
  //       bash infra/run-dev.sh --pb.ws.rotate-after-frames=3
  //       PB_ROTATE_AFTER_FRAMES=3 node infra/probe/p0-e2e.mjs
  const rotateAt = Number(process.env.PB_ROTATE_AFTER_FRAMES ?? 0)
  if (rotateAt > 0) {
    const fromSeq = unseal((await api('/api/v1/messages',
      { query: 'after_seq=0&limit=1', device: a })).json, a.keys.enc.privateKey).latest_seq
    const rSink = a.connect(fromSeq)
    await a.waitReady()
    const rotIds = []
    for (let i = 0; i < rotateAt + 2; i++) {
      const r = await ingest({ ...thirdParty, type: 'text', content: `跨界取样 ${i}`,
        sender_id: 'grafana', msg_key: `rot-${i}` })
      rotIds.push(r.json.id)
    }
    const allArrived = await until(() => rSink.frames.filter((f) => f.t === 'message'
      && rotIds.includes(f.id)).length === rotIds.length, FRAME_WAIT_MS)
    a.close()
    await until(() => rSink.closed, 2000)
    check(`⑬(阈值 ${rotateAt})`, '到帧数阈值即发二次 kx（epoch 递增），新密钥续帧全解且序号从 0 重启',
      Boolean(allArrived) && rSink.kx.length >= 2 && rSink.error === null
        && rSink.kx.every((e, i) => e === i) && rSink.resets.includes(0),
      `kx epochs=${JSON.stringify(rSink.kx)}，序号回落 ${JSON.stringify(rSink.resets)}，`
        + ` 错误=${rSink.error ?? '无'}`)
  }

  // ---- ⑭ D24 图文同步（§4.5 合并窗口）：一条 message 帧带齐 media[]，窗口外的图才补 media_ready
  //       想强制走"窗口到期先发正文"这条分支：bash infra/run-dev.sh --pb.notify.media-wait=200ms
  const mediaFrom = unseal((await api('/api/v1/messages',
    { query: 'after_seq=0&limit=1', device: a })).json, a.keys.enc.privateKey).latest_seq
  const mSink = a.connect(mediaFrom)
  await a.waitReady()
  // httpbin.org 的响应时间在 1.5s～10s+ 之间漂，会把抓取超时（10s）当偶发失败引进来，
  // 所以默认图床用 picsum（0.8s、一跳重定向）；要复现特定图源用 PB_TEST_IMAGE 覆盖。
  const d24Image = process.env.PB_TEST_IMAGE ?? 'https://picsum.photos/200/150'
  const d24BadImage = process.env.PB_BAD_IMAGE ?? 'https://example.com/not-an-image.png'
  const good = await ingest({ ...thirdParty, type: 'text', content: 'D24：图文应当同帧到达',
    images: [d24Image], msg_key: 'd24-good' })
  const bad = await ingest({ ...thirdParty, type: 'text', content: 'D24：抓不到图也是终态',
    images: [d24BadImage], msg_key: 'd24-bad' })
  const merge = (id) => {
    const msgs = mSink.frames.filter((f) => f.t === 'message' && f.id === id)
    const ready = mSink.frames.filter((f) => f.t === 'media_ready' && f.id === id)
    const slots = new Map((msgs[0]?.media ?? []).map((s) => [s.idx, s]))
    for (const f of ready) for (const s of f.media ?? []) slots.set(s.idx, s)
    return { msg: msgs[0], msgs: msgs.length, ready, slots: [...slots.values()] }
  }
  const settled = (id) => {
    const m = merge(id)
    return m.msg && m.slots.length > 0 && m.slots.every((s) => s.status !== 'pending') ? m : null
  }
  const goodState = await until(() => settled(good.json.id), 30000)
  const badState = await until(() => settled(bad.json.id), 30000)
  const keyOf = `media/${good.json.id}/0`
  const goodSlot = goodState?.slots.find((s) => s.idx === 0)
  const badSlot = badState?.slots.find((s) => s.idx === 0)
  const announcedInline = goodState?.msg?.media?.[0]?.status === 'ready'
  const extras = [...goodState?.ready ?? [], ...badState?.ready ?? []]
  a.close()
  await until(() => mSink.closed, 2000)
  check('⑭', 'D24：每条带图消息只有一条 message 帧；窗口内到齐就不补发，窗口外才用 media_ready 且不再通知',
    good.status === 200 && Boolean(goodState) && goodState.msgs === 1
      && goodState.msg.notify === true && goodState.msg.seq === good.json.seq
      && goodSlot?.status === 'ready' && goodSlot?.key === keyOf
      && String(goodSlot?.mime).startsWith('image/') && Number(goodSlot?.size) > 0
      && (announcedInline ? goodState.ready.length === 0 : goodState.ready.length >= 1)
      && Boolean(badState) && badState.msgs === 1 && badSlot?.status === 'failed'
      && extras.every((f) => f.notify === undefined
        && f.seq === (f.id === good.json.id ? good.json.seq : bad.json.seq)),
    `到齐方式=${announcedInline ? '窗口内同帧' : 'media_ready 补发 ' + goodState?.ready.length}，`
      + ` 失败图终态=${badSlot?.status}，good=${goodState?.msgs} 帧/${goodState?.slots.length} 槽`)

  // ---- ⑮ 媒体读接口（§5.2 GET /api/v1/media/{msg_id}/{idx}?size=thumb|full）
  //       服务端校验完归属后自己带客户钥把对象取回来，直接以 200 回字节；非 ready 槽位回 404 + 状态。
  const mediaCall = async (msgId, idx, query, device) => {
    const path = `/api/v1/media/${msgId}/${idx}`
    const res = await fetch(`${BASE}${path}?${query}`, {
      headers: signedHeaders(device, 'GET', path, query), redirect: 'manual' })
    const out = { status: res.status, noStore: res.headers.get('cache-control'),
      mime: res.headers.get('content-type'), bytes: null, json: null }
    if (res.status === 200) {
      out.bytes = Buffer.from(await res.arrayBuffer())
    } else {
      out.json = await res.json().catch(() => ({}))
      out.mime = null
    }
    return out
  }
  const full200 = await mediaCall(good.json.id, 0, 'size=full', a)
  const thumb200 = await mediaCall(good.json.id, 0, 'size=thumb', a)
  const failed200 = await mediaCall(bad.json.id, 0, 'size=full', a)
  const foreignMedia = await mediaCall(good.json.id, 0, 'size=full', stranger)
  const badSize = await mediaCall(good.json.id, 0, 'size=medium', a)
  // 字节只有签名这条路径：不带任何凭据直接打同一个 URL 必须 401，
  // 否则等于桶虽私有、却又开了一条匿名读图的后门。
  const noAuth = await fetch(`${BASE}/api/v1/media/${good.json.id}/0?size=full`)
  check('⑮', '媒体读接口 200 直接回与槽位一致的字节（服务端代理，不再 302 预签名），'
    + '无缩略图时降级发原图，未签名 401，失败槽位 404+状态，跨组 404，非法 size 400',
    full200.status === 200 && full200.noStore === 'no-store'
      && full200.bytes !== null && Number(goodSlot?.size) === full200.bytes.length
      && full200.mime === goodSlot?.mime
      && thumb200.status === 200 && thumb200.bytes?.length > 0
      && String(thumb200.mime).startsWith('image/')
      && noAuth.status === 401
      && failed200.status === 404 && failed200.json.error === 'not_found'
      && failed200.json.hint === 'failed'
      && foreignMedia.status === 404 && badSize.status === 400
      && badSize.json.error === 'invalid_param',
    `full=${full200.status}/${full200.bytes?.length ?? 0}B mime=${full200.mime}，`
      + ` thumb=${thumb200.status}/${thumb200.bytes?.length ?? 0}B，未签名=${noAuth.status}，`
      + ` failed=${failed200.status}/${failed200.json.hint}，`
      + ` 跨组=${foreignMedia.status}，size=medium=${badSize.status}`)

  // ---- ⑯ tombstone → 宽限期后 media sweep 真删对象（§4.5）。需要服务端配合缩短节奏：
  //       bash infra/run-dev.sh --pb.s3.delete-grace=2s --pb.s3.purge-initial-delay=1s \
  //         --pb.s3.purge-interval=3s
  //       PB_PURGE_WAIT_MS=30000 node infra/probe/p0-e2e.mjs
  const purgeWait = Number(process.env.PB_PURGE_WAIT_MS ?? 0)
  if (purgeWait > 0) {
    const tombstoned = await api(`/api/v1/messages/${good.json.id}`, { method: 'DELETE', device: a })
    const afterTombstone = await mediaCall(good.json.id, 0, 'size=full', a)
    // 读接口已经是服务端代理，探针手上没有桶凭据，"桶里字节没了"只能靠状态列证明：
    // sweep 只在 DeleteObject 成功返回之后才写 purged（MediaPurgeService），
    // 所以 deleted→purged 这个变化本身就等价于"对象真删了"；
    // 桶级证据（purge 后 exists() 为 false、无钥 HEAD 404）由 MediaPurgeServiceTest ② 和
    // MediaObjectsTest ⑦ 守着，这里不重复引入 SigV4 凭据。
    let readHint = afterTombstone.json.hint
    const purgeDeadline = Date.now() + purgeWait
    while (readHint !== 'purged' && Date.now() < purgeDeadline) {
      await sleep(500)
      readHint = (await mediaCall(good.json.id, 0, 'size=full', a)).json.hint
    }
    // 真删之后这条路径不能复活：仍是 404，而不是"状态 purged 但读得出字节"
    const afterPurge = await mediaCall(good.json.id, 0, 'size=full', a)
    check(`⑯(宽限期 ${process.env.PB_PURGE_GRACE ?? '2s'})`,
      '删除消息后媒体读接口立即 404+deleted，宽限期一到 sweep 真删桶里的对象并把槽位标成 purged',
      tombstoned.status === 200 && afterTombstone.status === 404
        && afterTombstone.json.hint === 'deleted'
        && readHint === 'purged'
        && afterPurge.status === 404 && afterPurge.json.hint === 'purged'
        && afterPurge.bytes === null,
      `delete=${tombstoned.status}，读图=${afterTombstone.status}/${afterTombstone.json.hint}`
        + `→${readHint}，真删后再读=${afterPurge.status}/${afterPurge.json.hint}`)
  }

  // ---- ⑰ 服务端全文搜索（§5.2 D19）：密封响应、group 边界、过滤收窄、LIKE 兜底、tombstone 不出现
  const srchNeedle = 'PBSEARCH7c41'
  const srchA = await ingest({ ...thirdParty, type: 'text', sender_id: 'grafana',
    content: `搜索联调：${srchNeedle} 只在 A 这条里`, msg_key: 'srch-a' })
  const srchB = await ingest({ ...thirdParty, type: 'text', sender_id: 'ci-bot',
    content: `搜索联调：${srchNeedle} 也在 B 这条里`, msg_key: 'srch-b' })
  const srchC = await ingest({ ...thirdParty, type: 'text', sender_id: 'ci-bot',
    content: '磁盘余量告警，需要扩容', msg_key: 'srch-c' })
  const srch = async (query, device) => {
    const r = await api('/api/v1/search', { query, device })
    return { status: r.status, page: r.status === 200 ? unseal(r.json, device.keys.enc.privateKey) : r.json }
  }
  const hitA = await srch(`q=${srchNeedle}`, a)
  const hitFiltered = await srch(`q=${srchNeedle}&sender_id=ci-bot`, a)
  // 单字查询：ngram_token_size=2 让 MATCH 恒零命中，这一条走的就是 LIKE 兜底
  const hitOneChar = await srch('q=%E7%A3%81', a)
  const hitNone = await srch('q=%E6%97%A0%E6%AD%A4%E8%AF%8DABCD', a)
  const hitPaged = await srch('q=%E5%91%8A%E8%AD%A6&limit=2', a)
  const hitBlank = await srch('q=%20', a)
  const hitForeign = await srch(`q=${srchNeedle}`, stranger)
  await api(`/api/v1/messages/${srchA.json.id}`, { method: 'DELETE', device: a })
  const afterDelete = await srch(`q=${srchNeedle}`, a)
  const idsOf = (p) => (p?.results ?? []).map((r) => r.id)
  check('⑰', '服务端搜索命中正文、按 sender_id 收窄、单字走 LIKE 兜底、零命中回空页、'
    + 'tombstone 不出现在结果里、跨组搜不到、limit 生效且 has_more 正确',
    hitA.status === 200 && idsOf(hitA.page).includes(srchA.json.id)
      && idsOf(hitA.page).includes(srchB.json.id)
      && (hitA.page?.results ?? []).some((r) => String(r.snippet).includes(srchNeedle))
      && (hitA.page?.results ?? []).every((r) => r.created_at > 0 && r.channel_no === chan.channel_no)
      && hitFiltered.status === 200 && idsOf(hitFiltered.page).join() === srchB.json.id
      && hitOneChar.status === 200 && idsOf(hitOneChar.page).join() === srchC.json.id
      && hitNone.status === 200 && idsOf(hitNone.page).length === 0
      && hitNone.page?.has_more === false
      && idsOf(hitPaged.page).length === 2 && hitPaged.page?.has_more === true
      && hitBlank.status === 400 && hitBlank.page.error === 'invalid_param'
      && hitForeign.status === 200 && idsOf(hitForeign.page).length === 0
      && afterDelete.status === 200 && !idsOf(afterDelete.page).includes(srchA.json.id)
      && idsOf(afterDelete.page).includes(srchB.json.id),
    `命中=${idsOf(hitA.page).length}，ci-bot=${idsOf(hitFiltered.page).length}，`
      + ` 单字=${idsOf(hitOneChar.page).length}，limit=2 后 has_more=${hitPaged.page?.has_more}，`
      + ` 空词=${hitNone.status}/q 空=${hitBlank.status}，跨组=${idsOf(hitForeign.page).length}，`
      + ` 删除后剩=${idsOf(afterDelete.page).length}`)

  // ---- ⑱ 清空整会话（§5.2 DELETE /api/v1/messages?channel_no=&sender_id=，D13）
  //       一次 UPDATE 标完 + 只广播一条带 scope 的 tombstone 帧，而不是 N 条逐帧删除。
  const bulkSender = 'bulk-bot'
  const bulkIds = []
  for (let i = 0; i < 3; i++) {
    const r = await ingest({ ...thirdParty, type: 'text', sender_id: bulkSender,
      content: `批量删除联调 ${i} PBBULK9x`, msg_key: `bulk-${i}` })
    bulkIds.push(r.json.id)
  }
  const bulkQuery = `channel_no=${chan.channel_no}&sender_id=${bulkSender}`
  // ⑭ 结尾已经把 a 的连接关了，所以这里重新起一条：清空会话从 latest_seq 之后不会有回放，
  // 帧流里出现的 tombstone 只可能来自本次删除广播。
  const bulkFrom = unseal((await api('/api/v1/messages',
    { query: 'after_seq=0&limit=1', device: a })).json, a.keys.enc.privateKey).latest_seq
  const bSink = a.connect(bulkFrom)
  await a.waitReady()
  // 会话级：带 sender_id 只清这一条会话
  const bulkResp = await api('/api/v1/messages', { method: 'DELETE', query: bulkQuery, device: a })
  const bulk = unseal(bulkResp.json, a.keys.enc.privateKey)
  const bulkAgain = unseal((await api('/api/v1/messages', { method: 'DELETE', query: bulkQuery,
    device: a })).json, a.keys.enc.privateKey)
  const scopeFrame = await until(() => bSink.frames.find((f) => f.t === 'tombstone' && f.scope
    && f.scope.channel_no === chan.channel_no && f.scope.sender_id === bulkSender), FRAME_WAIT_MS)
  const bulkSearched = await srch('q=PBBULK9x', a)
  const foreignBulk = await srch('q=PBBULK9x', stranger)
  const histAfterBulk = unseal((await api('/api/v1/messages',
    { query: 'after_seq=0&limit=500', device: a })).json, a.keys.enc.privateKey)
  const bulkTombstones = histAfterBulk.tombstones.filter((t) => bulkIds.includes(t.id))
  // 频道级：省略 sender_id 就是清这个频道的全部会话，scope 帧的 sender_id 为空
  const wholeResp = await api('/api/v1/messages', { method: 'DELETE',
    query: `channel_no=${chan.channel_no}`, device: a })
  const whole = unseal(wholeResp.json, a.keys.enc.privateKey)
  const wholeFrame = await until(() => bSink.frames.find((f) => f.t === 'tombstone' && f.scope
    && f.scope.channel_no === chan.channel_no && (f.scope.sender_id ?? '') === ''), FRAME_WAIT_MS)
  const histAfterWhole = unseal((await api('/api/v1/messages',
    { query: 'after_seq=0&limit=500', device: a })).json, a.keys.enc.privateKey)
  const noChannelMissing = await api('/api/v1/messages', { method: 'DELETE', query: 'sender_id=x',
    device: a })
  check('⑱', '清空整会话只标该会话、只发一条带 sender_id 的 scope tombstone 帧，重复调用幂等回 0；'
    + '省略 sender_id 清整个频道并发一条 sender_id 为空的 scope 帧，缺 channel_no 400，'
    + '搜索召不回、跨组搜不到、别的频道不受影响',
    bulkResp.status === 200 && bulk.ok === true && bulk.deleted === 3
      && bulkAgain.deleted === 0
      && Boolean(scopeFrame) && scopeFrame.id === undefined
      && bSink.frames.filter((f) => f.t === 'tombstone' && f.scope
        && f.scope.sender_id === bulkSender).length === 1
      && bulkTombstones.length === 3
      // ngram 的 MATCH 会因 bigram 重合召回别的消息（"PB" 之类），这属于全文检索的正常召回，
      // 所以这里判的是"被清空的那三条一定不再出现"，而不是"结果必须为空"
      && idsOf(bulkSearched.page).every((id) => !bulkIds.includes(id))
      && idsOf(foreignBulk.page).length === 0
      && histAfterBulk.messages.some((m) => m.sender_id === 'ci-bot')
      && wholeResp.status === 200 && whole.deleted >= 1 && String(whole.sender_id) === ''
      && Boolean(wholeFrame)
      && histAfterWhole.messages.every((m) => m.channel_no !== chan.channel_no)
      && noChannelMissing.status === 400 && noChannelMissing.json.error === 'invalid_param',
    `会话级=${bulk.deleted} → 重复=${bulkAgain.deleted}，频道级=${whole.deleted}，`
      + ` scope 帧=${scopeFrame ? 1 : 0}+${wholeFrame ? 1 : 0} 条，`
      + ` 历史 tombstone=${bulkTombstones.length}，整频道后活消息=${histAfterWhole.messages.length}，`
      + ` 缺 channel_no=${noChannelMissing.status}，搜索残留=`
      + `${idsOf(bulkSearched.page).filter((id) => bulkIds.includes(id)).length}，`
      + ` sink 错误=${bSink.error ?? '无'}`)

  // ---- ⑲ 抓取失败重试（§4.5 B2b）：远端 5xx 只代表这一次没抓到，sweep 会在 retry-max 内再抓，
  //       抓到就原地补一条 media_ready（同 id 同 seq，不重复通知）。
  //       需要服务端配合放开本机图床白名单并加快巡检节奏：
  //         bash infra/run-dev.sh --pb.media.allow-hosts=127.0.0.1:7799 \
  //           --pb.media.retry-initial-delay=1s --pb.media.retry-interval=5s
  //         PB_RETRY_WAIT_MS=40000 node infra/probe/p0-e2e.mjs
  const retryWait = Number(process.env.PB_RETRY_WAIT_MS ?? 0)
  if (retryWait > 0) {
    const retryPort = Number(process.env.PB_RETRY_PORT ?? 7799)
    // 一张 1x1 的 GIF89a：抓取器认魔数，不认扩展名
    const gif = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64')
    let serveOk = false
    const imageSrv = http.createServer((req, res) => {
      if (!serveOk) { res.writeHead(503); res.end('later'); return }
      res.writeHead(200, { 'content-type': 'image/gif', 'content-length': String(gif.length) })
      res.end(gif)
    })
    await new Promise((resolve, reject) => {
      imageSrv.once('error', reject)
      imageSrv.listen(retryPort, '127.0.0.1', resolve)
    })
    const retryFrom = unseal((await api('/api/v1/messages',
      { query: 'after_seq=0&limit=1', device: a })).json, a.keys.enc.privateKey).latest_seq
    const rSink = a.connect(retryFrom)
    await a.waitReady()
    const retry = await ingest({ ...thirdParty, type: 'text', sender_id: 'retry-bot',
      content: '重试联调：第一次 5xx，第二次才给图',
      images: [`http://127.0.0.1:${retryPort}/r.gif`], msg_key: 'retry-503' })
    // 首轮必须以 failed 收场，否则这条测的是运气而不是重试
    const firstAttempt = await until(() => rSink.frames.find((f) => f.t === 'message'
      && f.id === retry.json.id && f.media?.[0]?.status === 'failed'), retryWait)
    serveOk = true
    const retried = await until(() => rSink.frames.find((f) => f.t === 'media_ready'
      && f.id === retry.json.id && f.media?.[0]?.status === 'ready'), retryWait)
    const readAfterRetry = retried ? await mediaCall(retry.json.id, 0, 'size=full', a) : null
    imageSrv.close()
    check(`⑲(重试窗口 ${Math.round(retryWait / 1000)}s)`,
      '远端 5xx 先记 failed，定时重试抓到后原地补一条 media_ready（同 seq、不重复通知），'
        + '媒体读接口随之 200 且字节与图床一致',
      retry.status === 200 && Boolean(firstAttempt) && firstAttempt.notify === true
        && Boolean(retried) && retried.seq === retry.json.seq && retried.notify === undefined
        && retried.status === 'ready' && retried.media[0].key === `media/${retry.json.id}/0`
        && readAfterRetry?.status === 200 && readAfterRetry.bytes?.equals(gif)
        && String(readAfterRetry.mime).startsWith('image/')
        // 一次变化只补一帧：重试不能把客户端刷成补帧风暴
        && rSink.frames.filter((f) => f.t === 'media_ready' && f.id === retry.json.id).length === 1,
      `首轮=${firstAttempt?.media?.[0]?.status ?? '没等到 failed 帧'}，`
        + ` 重试后=${retried?.media?.[0]?.status ?? '没补发'}，读图=${readAfterRetry?.status}/`
        + `${readAfterRetry?.bytes?.length ?? 0}B，聚合=${retried?.status ?? '-'}，`
        + ` 帧数=${rSink.frames.length}，sink 错误=${rSink.error ?? '无'}`)
  }

  // ---- ⑳ sender_avatar 转存（§4.5 B3）：第三方自报的头像只有转存成功后才允许下发，
  //       而且下发的一定是服务端自己的读路径 /api/v1/avatars/{sha256(sender_id)}。
  const gif1x1 = Buffer.from('R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7', 'base64')
  const urlSender = 'avatar-url-bot'
  const inlineSender = 'avatar-inline-bot'
  const junkSender = 'avatar-junk-bot'
  const avatarImage = process.env.PB_TEST_AVATAR ?? 'https://picsum.photos/seed/pushbox-av/96/96'
  // 客户端本地也能算 token：它只认这个路径，永远拿不到第三方头像 URL
  const avPath = (id) => `/api/v1/avatars/${sha256(Buffer.from(id, 'utf8')).toString('hex')}`
  const avatarWaitMs = Number(process.env.PB_AVATAR_WAIT_MS ?? 30000)
  const avDeadline = Date.now() + avatarWaitMs
  const pollAvatar = async (fn) => {
    for (;;) {
      const value = await fn()
      if (value) return value
      if (Date.now() > avDeadline) return null
      await sleep(400)
    }
  }
  const detail = async (id) => unseal((await api(`/api/v1/messages/${id}`, { device: a })).json,
    a.keys.enc.privateKey)
  const avatarRead = async (path, device) => {
    const res = await fetch(`${BASE}${path}`, {
      headers: signedHeaders(device, 'GET', path, ''), redirect: 'manual' })
    const out = { status: res.status, noStore: res.headers.get('cache-control'),
      mime: res.headers.get('content-type'), bytes: null }
    if (res.status === 200) {
      // 服务端代理：签名过的这一次请求就直接拿到字节，设备侧不需要再跳去桶地址
      out.bytes = Buffer.from(await res.arrayBuffer())
    }
    return out
  }
  const avFrom = unseal((await api('/api/v1/messages',
    { query: 'after_seq=0&limit=1', device: a })).json, a.keys.enc.privateKey).latest_seq
  const avSink = a.connect(avFrom)
  await a.waitReady()
  const avUrl = await ingest({ ...thirdParty, type: 'text', content: '头像联调：URL 头像要转存',
    sender_id: urlSender, sender_name: '头像URL', sender_avatar: avatarImage, msg_key: 'av-url' })
  const avInline = await ingest({ ...thirdParty, type: 'text', content: '头像联调：内联头像不出网',
    sender_id: inlineSender, sender_name: '头像内联', msg_key: 'av-inline',
    sender_avatar: 'data:image/gif;base64,' + gif1x1.toString('base64') })
  // 坏头像只是没有头像，不能让整条消息进不了库
  const avJunk = await ingest({ ...thirdParty, type: 'text', content: '头像联调：坏头像不拦消息',
    sender_id: junkSender, sender_name: '头像乱填', msg_key: 'av-junk',
    sender_avatar: 'javascript:alert(1)' })
  const storedUrl = await pollAvatar(async () => {
    const d = await detail(avUrl.json.id)
    return d.sender_avatar === avPath(urlSender) ? d : null
  })
  const storedInline = await pollAvatar(async () => {
    const d = await detail(avInline.json.id)
    return d.sender_avatar === avPath(inlineSender) ? d : null
  })
  const junkDetail = await detail(avJunk.json.id)
  const urlRead = await avatarRead(avPath(urlSender), a)
  const inlineRead = await avatarRead(avPath(inlineSender), a)
  const foreignAvatar = await avatarRead(avPath(urlSender), stranger)
  const junkAvatar = await avatarRead(avPath(junkSender), a)
  const badToken = await avatarRead('/api/v1/avatars/not-a-sha256', a)
  const avFrames = avSink.frames.filter((f) => f.t === 'message'
    && [urlSender, inlineSender, junkSender].includes(f.sender_id))
  const leaked = avFrames.filter((f) => f.sender_avatar != null
    && !String(f.sender_avatar).startsWith('/api/v1/avatars/'))
  // 头像同样只有签名这一条路径：不带凭据直接打同一个 token 必须 401
  const noAuthAvatar = await fetch(`${BASE}${avPath(urlSender)}`)
  check('⑳', 'sender_avatar 只有转存成功后才下发，且只暴露 /api/v1/avatars/{sha256(sender_id)}：'
    + 'URL 与内联头像都能 200 取回字节，未签名 401，坏写法只是没头像，跨组/未存成/畸形 token 都 404',
    avUrl.status === 200 && avInline.status === 200 && avJunk.status === 200
      && avFrames.length === 3 && leaked.length === 0
      && Boolean(storedUrl) && Boolean(storedInline) && junkDetail.sender_avatar == null
      && urlRead.status === 200 && urlRead.noStore === 'no-store'
      && urlRead.bytes.length > 0 && String(urlRead.mime).startsWith('image/')
      && inlineRead.status === 200 && inlineRead.bytes.equals(gif1x1)
      && inlineRead.mime === 'image/gif'
      && noAuthAvatar.status === 401
      && foreignAvatar.status === 404 && junkAvatar.status === 404 && badToken.status === 404,
    `URL 头像=${urlRead.status}/${urlRead.bytes.length}B mime=${urlRead.mime}，`
      + ` 内联=${inlineRead.status}/${inlineRead.bytes.length}B mime=${inlineRead.mime}，`
      + ` 未签名=${noAuthAvatar.status}，`
      + ` 帧里的头像=${JSON.stringify(avFrames.map((f) => f.sender_avatar ?? 'null'))}，`
      + ` 跨组/未存成/坏 token=${foreignAvatar.status}/${junkAvatar.status}/${badToken.status}，`
      + ` 等待窗口=${Math.round(avatarWaitMs / 1000)}s`)

  // ---- ㉑ 补拉循环：落后条数超过"一批"时必须逐批发到取空，再回 resume_ok
  // 服务端原先只发一批就 resume_ok，那是一个静默丢消息的 bug：落后条数落在"一批"与
  // pb.ws.gap-threshold(2000) 之间的设备既收不满、也收不到 resume_gap，本机把"这批发完"当成
  // "已经追平"，剩下的要等下次断线重连才有机会补上。跑这条要把服务端起成
  // `--pb.ws.replay-batch=5`（与 ⑬ 的 rotate-after-frames 同一个套路）；
  // 默认 500 时 12 条一批就发完，这条只证明"补齐 + resume_ok"，证不到循环本身。
  a.close()
  await until(() => aSink.closed, 2000)
  const backlog = []
  for (let i = 0; i < 12; i++) {
    const r = await ingest({ ...thirdParty, type: 'text', content: `补拉批次 ${i}`,
      msg_key: `replay-batch-${i}`, sender_id: 'grafana' })
    backlog.push({ id: r.json.id, seq: r.json.seq })
  }
  const batchSink = a.connect(backlog[0].seq - 1)
  await until(() => batchSink.frames.some((f) => f.t === 'resume_ok' || f.t === 'resume_gap')
    || batchSink.error, READY_TIMEOUT_MS)
  const batchGot = batchSink.frames.filter((f) => f.t === 'message')
  const batchIds = new Set(batchGot.map((f) => f.id))
  const batchMissing = backlog.filter((m) => !batchIds.has(m.id))
  check('㉑', `补拉按批循环到取空（服务端 replay-batch=${process.env.PB_REPLAY_BATCH ?? '默认 500'}）：`
    + '落后 12 条全部补齐、只回一次 resume_ok、不误报 resume_gap、重放帧一律 notify=false',
    !batchSink.error && batchMissing.length === 0
      && batchSink.frames.filter((f) => f.t === 'resume_ok').length === 1
      && !batchSink.frames.some((f) => f.t === 'resume_gap')
      && batchGot.every((f) => f.notify === false),
    `补齐 ${batchGot.length}/12，缺=${batchMissing.length}，`
      + `resume_ok=${batchSink.frames.filter((f) => f.t === 'resume_ok').length}`)
  a.close()
  await until(() => batchSink.closed, 2000)

  // ---- ㉒ 加入频道（§5.2）：同 Space 内凭「频道号 + 密钥」接入
  // 这条用 B 建频道而不是 A：建频道的那台设备会被服务端默认接入它自己的新频道（否则一开过滤
  // 就把自己刚建的频道静默掐断），拿 A 自己的频道证不到「同 Space 看得见、但没接入」这一档。
  const c3 = unseal((await api('/api/v1/channels', { method: 'POST',
    body: { name: 'B 的频道' }, device: b })).json, b.keys.enc.privateKey)
  const channelsOf = async (device) => unseal((await api('/api/v1/channels', { device })).json,
    device.keys.enc.privateKey)
  const flagOf = (page, no) => page.channels.find((c) => c.channel_no === no)?.subscribed
  const joinAs = (device, channelNo, secret) => api('/api/v1/channels/join',
    { method: 'POST', device, body: { channel_no: channelNo, secret } })
  // leave 的判据同时要看 HTTP 码和 removed，所以这里把两者一起带回来：
  // 只 unseal 出 body 的话，`leave1.status` 是 undefined，整条 case 会假失败。
  const leaveAs = async (device, channelNo) => {
    const res = await api('/api/v1/channels/leave',
      { method: 'POST', device, body: { channel_no: channelNo } })
    return { status: res.status, body: unseal(res.json, device.keys.enc.privateKey) }
  }
  const page0 = await channelsOf(a)
  const wrongSecret = await joinAs(a, c3.channel_no, '0'.repeat(32))
  const blankNo = await joinAs(a, '    ', c3.secret)
  const crossGroupJoin = await joinAs(stranger, chan.channel_no, chan.secret)
  const archivedJoin = await joinAs(a, c2.channel_no, c2.secret)
  const joinOk = await joinAs(a, c3.channel_no, c3.secret)
  const joinBody = unseal(joinOk.json, a.keys.enc.privateKey)
  const page1 = await channelsOf(a)
  const joinAgain = await joinAs(a, c3.channel_no, c3.secret)
  const leave1 = await leaveAs(a, c3.channel_no)
  const leave2 = await leaveAs(a, c3.channel_no)
  const leaveUnknown = await leaveAs(a, '00000000')
  const modeBad = await api('/api/v1/channels/subscription-mode', { method: 'POST', device: a,
    body: { subscribed_only: 2 } })
  const page2 = await channelsOf(a)
  check('㉒', '加入频道：本 Space 内频道号+密钥接入并在列表里标记（建频道方默认已接入），'
    + '错密钥 401、空频道号 400、跨组与已归档频道 404，接入回显里永不含密钥，'
    + '取消接入幂等，过滤模式只认 0/1',
    page0.channels.length > 0 && page0.subscribed_only === 0
      && flagOf(page0, chan.channel_no) === true && flagOf(page0, c3.channel_no) === false
      && wrongSecret.status === 401 && blankNo.status === 400
      && crossGroupJoin.status === 404 && archivedJoin.status === 404
      && joinOk.status === 200 && joinBody.ok === true
      && joinBody.channel_no === c3.channel_no && joinBody.secret == null
      && joinBody.subscribed_only === 0
      && flagOf(page1, c3.channel_no) === true && joinAgain.status === 200
      && leave1.status === 200 && leave1.body.ok === true && leave1.body.removed === true
      && leave2.status === 200 && leave2.body.removed === false
      && leaveUnknown.status === 200 && leaveUnknown.body.removed === false
      && modeBad.status === 400 && page2.subscribed_only === 0
      && flagOf(page2, c3.channel_no) === false,
    `接入前=${page0.channels.length}条 chan=${flagOf(page0, chan.channel_no)}`
      + `/c3=${flagOf(page0, c3.channel_no)}/mode=${page0.subscribed_only}，`
      + ` 错密钥/空号/跨组/归档=${wrongSecret.status}/${blankNo.status}/`
      + `${crossGroupJoin.status}/${archivedJoin.status}，`
      + ` 接入=${joinOk.status}/${joinBody.ok}/${joinBody.channel_no === c3.channel_no}`
      + `/${joinBody.subscribed_only} 后 c3=${flagOf(page1, c3.channel_no)}，`
      + ` 再接入=${joinAgain.status}，`
      + ` 取消=${leave1.status}/${leave1.body.ok}/${leave1.body.removed}/${leave2.body.removed}`
      + `/${leaveUnknown.status}/${leaveUnknown.body.removed}，`
      + ` 模式 2=${modeBad.status}，接入后=${flagOf(page2, c3.channel_no)}`
      + `/mode=${page2.subscribed_only}，`
      // 回显键名整串打出来：这条判的是"接入响应里没有密钥"，只写个 false 查不出是哪一栏漏进来的
      + ` 接入回显键=${JSON.stringify(Object.keys(joinBody))}`)

  // ---- ㉓ 过滤开关真的参与投递（两端「APP 和 PC 收到同样的消息」判的就是这一条）
  // 三条路径都要盯：实时帧（deviceIdsAllowedForChannel）、REST 历史与 WS 回放（readScope）。
  // 判 m1「没送到」必须先确认同一条连接上后发的 m2 到了、且频道主 B 也到了，
  // 否则证到的只是没来得及；接入之后的历史会**回溯**放开——接入前那条也读得到，
  // 因为过滤按频道而不是按接入时刻。
  const filterWait = Number(process.env.PB_FILTER_WAIT_MS ?? 2000)
  const fltFrom = unseal((await api('/api/v1/messages',
    { query: 'after_seq=0&limit=1', device: a })).json, a.keys.enc.privateKey).latest_seq
  const modeOn = unseal((await api('/api/v1/channels/subscription-mode', { method: 'POST',
    device: a, body: { subscribed_only: 1 } })).json, a.keys.enc.privateKey)
  const fA = a.connect(fltFrom)
  await a.waitReady()
  const fB = b.connect(fltFrom)
  await b.waitReady()
  const m1 = await ingest({ channel: c3.channel_no, secret: c3.secret, type: 'text',
    content: '接入过滤：这条 A 现在不该看到', sender_id: 'join-bot', msg_key: 'flt-1' })
  const m2 = await ingest({ ...thirdParty, type: 'text', content: '接入过滤：这条 A 该看到',
    sender_id: 'join-bot', msg_key: 'flt-2' })
  const gotM2 = await until(() => fA.frames.find((f) => f.t === 'message'
    && f.id === m2.json.id), FRAME_WAIT_MS)
  const gotM1B = await until(() => fB.frames.find((f) => f.t === 'message'
    && f.id === m1.json.id), FRAME_WAIT_MS)
  await sleep(filterWait)
  const gotM1A = fA.frames.find((f) => f.t === 'message' && f.id === m1.json.id)
  const historyOf = async (device) => unseal((await api('/api/v1/messages',
    { query: `after_seq=${fltFrom}&limit=200`, device })).json, device.keys.enc.privateKey).messages
  const inHist = (page, id) => page.some((m) => m.id === id)
  const histA1 = await historyOf(a)
  const histB1 = await historyOf(b)
  const joinC3 = await joinAs(a, c3.channel_no, c3.secret)
  const m3 = await ingest({ channel: c3.channel_no, secret: c3.secret, type: 'text',
    content: '接入过滤：接入后同一条频道就来了', sender_id: 'join-bot', msg_key: 'flt-3' })
  const gotM3 = await until(() => fA.frames.find((f) => f.t === 'message'
    && f.id === m3.json.id), FRAME_WAIT_MS)
  const histA2 = await historyOf(a)
  const modeOff = unseal((await api('/api/v1/channels/subscription-mode', { method: 'POST',
    device: a, body: { subscribed_only: 0 } })).json, a.keys.enc.privateKey)
  await leaveAs(a, c3.channel_no)
  const m4 = await ingest({ channel: c3.channel_no, secret: c3.secret, type: 'text',
    content: '接入过滤：关回全收后没接入也照收', sender_id: 'join-bot', msg_key: 'flt-4' })
  const gotM4 = await until(() => fA.frames.find((f) => f.t === 'message'
    && f.id === m4.json.id), FRAME_WAIT_MS)
  const histA3 = await historyOf(a)
  check('㉓', `开了「只接收已接入频道」后，未接入频道既不实时下发也不进历史；`
    + `接入（频道号+密钥）后立刻放开并回溯到接入前那条；关回全收后即使没接入也照收`
    + `（缺席判定窗口 ${filterWait}ms）`,
    modeOn.ok === true && modeOn.subscribed_only === 1 && !fA.error && !fB.error
      && m1.status === 200 && m2.status === 200
      && Boolean(gotM2) && Boolean(gotM1B) && !gotM1A
      && inHist(histA1, m2.json.id) && !inHist(histA1, m1.json.id)
      && inHist(histB1, m1.json.id)
      && joinC3.status === 200 && Boolean(gotM3)
      && inHist(histA2, m3.json.id) && inHist(histA2, m1.json.id)
      && modeOff.subscribed_only === 0 && Boolean(gotM4) && inHist(histA3, m4.json.id),
    `实时 A：m2=${Boolean(gotM2)} m1=${Boolean(gotM1A)} m3=${Boolean(gotM3)} m4=${Boolean(gotM4)}，`
      + ` B 收到 m1=${Boolean(gotM1B)}，历史 A：接入前=${inHist(histA1, m2.json.id)}/`
      + `${inHist(histA1, m1.json.id)} 接入后=${inHist(histA2, m3.json.id)}/`
      + `${inHist(histA2, m1.json.id)} 全收=${inHist(histA3, m4.json.id)}，`
      + ` A 帧数=${fA.frames.length} 错误=${fA.error ?? '无'}`)

  for (const s of [a, b, stranger]) s.close()
  await sleep(200)

  const failed = results.filter((r) => !r.ok)
  console.log(`\n${results.length - failed.length}/${results.length} 通过`)
  if (failed.length) console.log('未通过：' + failed.map((f) => f.name).join(' | '))
  process.exitCode = failed.length ? 1 : 0
}

main().catch((e) => { console.error('探针异常终止:', e); process.exitCode = 2 })
