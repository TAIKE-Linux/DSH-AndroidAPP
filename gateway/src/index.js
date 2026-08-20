#!/usr/bin/env node
/**
 * dsh-remote-gateway
 * ------------------
 * Authenticated LAN front for a loopback-only DeepSeek Harness (`dsh web`).
 *
 * Why it exists: `dsh web` deliberately refuses to bind 0.0.0.0 because its
 * `/api` surface is remote code execution with no authentication layer. This
 * gateway keeps DSH on 127.0.0.1 (untouched, upgrade-safe) and exposes ONLY the
 * `/api` JSON-RPC + downlink WebSocket surface to the LAN behind a bearer
 * token, so the Android app can drive the PC over WiFi.
 *
 * Security posture (documented, not hidden):
 *  - The bearer token gates every proxied request and WebSocket upgrade.
 *  - Everything else on the upstream (the web UI itself) is NOT proxied.
 *  - No TLS by default (home-LAN threat model); optional TLS via tls.cert/key.
 *  - The token is the only credential: treat it like a password.
 *
 * Usage:
 *   node src/index.js                  # reads ./gateway.config.json
 *   node src/index.js --port 8742 --token <tok> --upstream http://127.0.0.1:3080
 *   env: DSH_GATEWAY_PORT / DSH_GATEWAY_TOKEN / DSH_GATEWAY_UPSTREAM / DSH_GATEWAY_HOST
 */
import http from 'node:http'
import https from 'node:https'
import crypto from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import zlib from 'node:zlib'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { WebSocket, WebSocketServer } from 'ws'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const NAME = 'dsh-remote-gateway'
const VERSION = '0.1.0'
const MAX_BODY_BYTES = 220 * 1024 * 1024 // upstream /api default body cap is 160 MiB; keep headroom

const HOP_BY_HOP = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade', 'host', 'authorization',
])

// Statuses that never carry a body: compressing them would corrupt semantics.
const NO_GZIP_STATUS = new Set([204, 304])

// ---------------------------------------------------------------------------
// Config
// ---------------------------------------------------------------------------
function parseArgs(argv) {
  const out = {}
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    if (a === '--port' || a === '--token' || a === '--upstream' || a === '--host' || a === '--config' || a === '--dsh-command') {
      out[a.slice(2).replace('dsh-command', 'dshCommand')] = argv[++i]
    } else if (a === '--no-launch-dsh') {
      out.launchDsh = false
    } else if (a === '--launch-dsh') {
      out.launchDsh = true
    } else if (a === '--help' || a === '-h') {
      out.help = true
    }
  }
  return out
}

function loadConfig() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help) {
    console.log(`Usage: dsh-gateway [options]\n
  --config <path>      config JSON file (default: ./gateway.config.json)
  --port <port>        listen port (env DSH_GATEWAY_PORT)
  --host <host>        bind host, default 0.0.0.0 (env DSH_GATEWAY_HOST)
  --token <token>      bearer token (env DSH_GATEWAY_TOKEN)
  --upstream <url>     DSH web URL, default http://127.0.0.1:3080 (env DSH_GATEWAY_UPSTREAM)
  --dsh-command <cmd>  command used to launch DSH (default: dsh web)
  --no-launch-dsh      disable the built-in DSH supervisor (start dsh web yourself)`)
    process.exit(0)
  }
  const fileConfig = {}
  const configPath = args.config ?? path.join(process.cwd(), 'gateway.config.json')
  if (fs.existsSync(configPath)) {
    try {
      Object.assign(fileConfig, JSON.parse(fs.readFileSync(configPath, 'utf8')))
    } catch (err) {
      console.error(`[${NAME}] failed to parse ${configPath}: ${err.message}`)
      process.exit(1)
    }
  }
  const envLaunch = process.env.DSH_GATEWAY_LAUNCH_DSH
  const config = {
    host: args.host ?? process.env.DSH_GATEWAY_HOST ?? fileConfig.host ?? '0.0.0.0',
    port: Number(args.port ?? process.env.DSH_GATEWAY_PORT ?? fileConfig.port ?? 8742),
    upstream: (args.upstream ?? process.env.DSH_GATEWAY_UPSTREAM ?? fileConfig.upstream ?? 'http://127.0.0.1:3080').replace(/\/+$/, ''),
    token: args.token ?? process.env.DSH_GATEWAY_TOKEN ?? fileConfig.token ?? '',
    tls: fileConfig.tls ?? {},
    // File uploads from the phone: destination directory and size cap.
    uploadDir: process.env.DSH_GATEWAY_UPLOAD_DIR ?? fileConfig.uploadDir ?? '',
    uploadLimitMB: Number(process.env.DSH_GATEWAY_UPLOAD_LIMIT_MB ?? fileConfig.uploadLimitMB ?? 512),
    // One-command mode: supervise (start/restart) `dsh web` when the upstream
    // is a local DSH that is not already running.
    launchDsh: args.launchDsh ?? (envLaunch !== undefined ? envLaunch !== '0' && envLaunch !== 'false' : fileConfig.launchDsh ?? true),
    dshCommand: args.dshCommand ?? process.env.DSH_GATEWAY_DSH_COMMAND ?? fileConfig.dshCommand ?? 'dsh web',
    dshCwd: process.env.DSH_GATEWAY_DSH_CWD ?? fileConfig.dshCwd ?? path.join(__dirname, '..'),
  }
  if (!Number.isInteger(config.port) || config.port < 1 || config.port > 65535) {
    console.error(`[${NAME}] invalid port: ${config.port}`)
    process.exit(1)
  }
  if (!/^https?:\/\//.test(config.upstream)) {
    console.error(`[${NAME}] upstream must be an http(s) URL, got: ${config.upstream}`)
    process.exit(1)
  }
  if (!config.token) {
    config.token = crypto.randomBytes(24).toString('base64url')
    const written = { ...config, tls: fileConfig.tls }
    fs.writeFileSync(configPath, JSON.stringify(written, null, 2) + '\n')
    console.warn(`[${NAME}] no token configured; generated one and wrote it to ${configPath}`)
  }
  config.configPath = configPath
  return config
}

const config = loadConfig()

function safeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest()
  const hb = crypto.createHash('sha256').update(String(b)).digest()
  return crypto.timingSafeEqual(ha, hb)
}

function isAuthorized(req, url) {
  const header = req.headers['authorization'] ?? ''
  if (header.startsWith('Bearer ')) return safeEqual(header.slice(7), config.token)
  const qToken = url.searchParams.get('token')
  if (qToken) return safeEqual(qToken, config.token)
  return false
}

const log = (level, message) => {
  const ts = new Date().toISOString().replace('T', ' ').slice(0, 19)
  console.log(`[${ts}] [${level}] ${message}`)
}

// ---------------------------------------------------------------------------
// Upstream unary proxy (POST /api/<method> and GET /health passthrough)
// ---------------------------------------------------------------------------
function forwardRequest(req, res) {
  const upstream = new URL(config.upstream)
  const lib = upstream.protocol === 'https:' ? https : http
  // DSH never compresses its own /api responses; the phone typically sits on
  // a much slower WiFi link than the PC loopback, so the gateway gzips JSON
  // responses on the fly (session.history pages are often several MB).
  const acceptsGzip = /\bgzip\b/i.test(req.headers['accept-encoding'] ?? '')
  const headers = {}
  for (const [key, value] of Object.entries(req.headers)) {
    if (HOP_BY_HOP.has(key.toLowerCase())) continue
    headers[key] = value
  }
  headers['host'] = upstream.host
  const proxyReq = lib.request(
    {
      protocol: upstream.protocol,
      hostname: upstream.hostname,
      port: upstream.port || (upstream.protocol === 'https:' ? 443 : 80),
      method: req.method,
      path: req.url,
      headers,
    },
    (proxyRes) => {
      const status = proxyRes.statusCode ?? 502
      const isJson = String(proxyRes.headers['content-type'] ?? '').includes('application/json')
      const alreadyEncoded = proxyRes.headers['content-encoding'] !== undefined
      const compressible = acceptsGzip && isJson && !alreadyEncoded &&
        !NO_GZIP_STATUS.has(status) && req.method !== 'HEAD'
      const outHeaders = {}
      for (const [key, value] of Object.entries(proxyRes.headers)) {
        const lower = key.toLowerCase()
        if (HOP_BY_HOP.has(lower)) continue
        if (compressible && lower === 'content-length') continue // chunked instead
        outHeaders[key] = value
      }
      if (compressible) {
        outHeaders['content-encoding'] = 'gzip'
        const prevVary = outHeaders['vary']
        outHeaders['vary'] = prevVary ? `${prevVary}, Accept-Encoding` : 'Accept-Encoding'
      }
      res.writeHead(status, outHeaders)
      if (compressible) {
        const gzip = zlib.createGzip({ level: 6 })
        proxyRes.pipe(gzip).pipe(res)
        gzip.on('error', () => {
          if (!res.writableEnded) res.destroy()
        })
        res.on('close', () => {
          if (!res.writableEnded) {
            gzip.destroy()
            proxyReq.destroy()
          }
        })
      } else {
        proxyRes.pipe(res)
      }
    },
  )
  // Nagle is off by default on Node client sockets; the many small request
  // bursts (list/create/prompt) pay ~40ms each without this on WiFi RTTs.
  proxyReq.on('socket', (socket) => socket.setNoDelay(true))
  proxyReq.on('error', (err) => {
    log('error', `upstream ${req.method} ${req.url} failed: ${err.message}`)
    if (!res.headersSent) res.writeHead(502, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: false, error: `upstream unreachable: ${err.message}` }))
  })
  req.pipe(proxyReq)
}

async function upstreamDescribe() {
  const envelope = {
    type: 'client-request',
    rpcId: crypto.randomUUID(),
    method: 'host.describe',
    payload: {},
  }
  const body = JSON.stringify(envelope)
  const upstream = new URL(config.upstream)
  const lib = upstream.protocol === 'https:' ? https : http
  return new Promise((resolve) => {
    const req = lib.request(
      {
        protocol: upstream.protocol,
        hostname: upstream.hostname,
        port: upstream.port || (upstream.protocol === 'https:' ? 443 : 80),
        method: 'POST',
        path: '/api/host.describe',
        headers: {
          'content-type': 'application/json',
          'content-length': Buffer.byteLength(body),
          host: upstream.host,
        },
      },
      (res) => {
        let data = ''
        res.on('data', (c) => (data += c))
        res.on('end', () => {
          try {
            const parsed = JSON.parse(data)
            resolve(parsed?.result?.ok ? parsed.result.value : { error: parsed?.result?.error?.message ?? 'unknown' })
          } catch {
            resolve({ error: 'invalid upstream response' })
          }
        })
      },
    )
    req.on('error', (err) => resolve({ error: err.message }))
    req.setTimeout(4000, () => {
      req.destroy()
      resolve({ error: 'timeout' })
    })
    req.write(body)
    req.end()
  })
}

/**
 * Lightweight upstream reachability probe used by the unauthenticated /health
 * endpoint. The full host.describe value (cwd, provider, model, …) is
 * deliberately NOT exposed without auth — it leaks host filesystem layout
 * and deployment details to anyone on the LAN.
 */
async function upstreamProbe() {
  const result = await upstreamDescribe()
  return typeof result === 'object' && result !== null && result.error === undefined
}

// ---------------------------------------------------------------------------
// DeepSeek Harness supervisor — one command starts everything.
// Probe first: if a DSH is already answering on the upstream, leave it alone.
// Otherwise launch `dsh web` (config: launchDsh / dshCommand / dshCwd), wait
// for it to answer, and restart it whenever it later exits.
// ---------------------------------------------------------------------------
let dshChild = null
let dshRestarts = 0
let dshStartAt = 0
let dshGivenUp = false
let shuttingDown = false
let supervisorStarted = false

const upstreamIsLocal = (() => {
  try {
    const h = new URL(config.upstream).hostname
    return h === '127.0.0.1' || h === 'localhost' || h === '::1'
  } catch {
    return false
  }
})()

function killDshTree() {
  const child = dshChild
  dshChild = null
  if (!child) return
  if (process.platform === 'win32') {
    try {
      spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    } catch { /* already gone */ }
  } else {
    try { child.kill('SIGTERM') } catch { /* already gone */ }
  }
}

function spawnDsh() {
  if (shuttingDown || dshGivenUp) return
  dshStartAt = Date.now()
  log('info', `starting DeepSeek Harness: ${config.dshCommand}  (cwd: ${config.dshCwd})`)
  const child = spawn(config.dshCommand, [], {
    shell: true,
    cwd: config.dshCwd,
    stdio: 'inherit',
    windowsHide: false,
  })
  dshChild = child
  child.on('error', (err) => {
    log('error', `failed to launch dsh: ${err.message}`)
    dshChild = null
    scheduleDshRespawn(true)
  })
  child.on('exit', (code) => {
    dshChild = null
    if (shuttingDown) return
    const aliveMs = Date.now() - dshStartAt
    const fastFail = aliveMs < 10_000
    log('warn', `DeepSeek Harness exited (code ${code}) after ${Math.round(aliveMs / 1000)}s${fastFail ? ' — looks like a startup failure' : ''}`)
    scheduleDshRespawn(fastFail)
  })
}

function scheduleDshRespawn(fastFail) {
  if (shuttingDown || dshGivenUp) return
  if (fastFail) {
    dshRestarts += 1
    if (dshRestarts >= 3) {
      dshGivenUp = true
      log('error', 'giving up on DeepSeek Harness after 3 rapid failures. Check:')
      log('error', '  1) is "dsh" installed? (npm i -g @deepseek-ai/dsh)')
      log('error', `  2) is port ${new URL(config.upstream).port} free? (Get-NetTCPConnection -LocalPort ${new URL(config.upstream).port} -State Listen)`)
      log('error', '  3) run "dsh web" manually to see its real error, or set dshCommand in gateway.config.json')
      return
    }
  } else {
    dshRestarts = 0
  }
  setTimeout(() => spawnDsh(), 5000)
}

function warnIfUnreachable() {
  upstreamProbe().then((ok) => {
    if (ok) {
      log('info', 'upstream probe: OK (DeepSeek Harness reachable)')
      return
    }
    log('warn', '')
    log('warn', `!! upstream unreachable: DeepSeek Harness does not appear to be running at ${config.upstream}`)
    log('warn', '!! start it with: dsh web   (the gateway recovers automatically once it is up)')
    log('warn', '')
  })
}

/**
 * TCP-level listen check, immune to a busy DSH (the API probe can time out
 * while the process is perfectly healthy). Used before deciding to spawn.
 */
function portListening(host, port) {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port, timeout: 1500 })
    socket.once('connect', () => {
      socket.destroy()
      resolve(true)
    })
    socket.once('timeout', () => {
      socket.destroy()
      resolve(false)
    })
    socket.once('error', () => resolve(false))
  })
}

async function superviseDsh() {
  if (supervisorStarted || shuttingDown) return
  supervisorStarted = true
  if (!config.launchDsh) {
    log('info', 'dsh supervisor disabled (launchDsh=false): start "dsh web" yourself')
    warnIfUnreachable()
    return
  }
  if (!upstreamIsLocal) {
    log('info', `upstream ${config.upstream} is not local — dsh supervisor skipped`)
    warnIfUnreachable()
    return
  }
  const u = new URL(config.upstream)
  const host = u.hostname
  const port = Number(u.port || (u.protocol === 'https:' ? 443 : 80))
  const listening = await portListening(host, port)
  if (listening) {
    // A process holds the upstream port. Don't risk a second instance: even a
    // busy DSH keeps the socket open, so this is the reliable "already up"
    // signal. The API probe below only refines the log message.
    log('info', `port ${host}:${port} is listening — assuming DeepSeek Harness is already up, not starting a second instance`)
    upstreamProbe().then((ok) => {
      if (!ok) {
        log('warn', `note: ${host}:${port} is occupied but did not answer the DSH API probe — check what is running there if the app misbehaves`)
      } else {
        log('info', 'upstream probe: OK (DeepSeek Harness reachable)')
      }
    })
    return
  }
  log('info', 'DeepSeek Harness not detected — one-command mode: launching it now')
  spawnDsh()
  let waited = 0
  const poll = setInterval(async () => {
    waited += 2
    if (shuttingDown || dshGivenUp) {
      clearInterval(poll)
      return
    }
    if (await upstreamProbe()) {
      clearInterval(poll)
      log('info', `DeepSeek Harness is up after ~${waited}s — gateway fully operational`)
    } else if (waited >= 180) {
      clearInterval(poll)
      log('warn', 'DeepSeek Harness still not reachable after 3 minutes; the gateway keeps retrying and recovers once it is up')
    }
  }, 2000)
}

// ---------------------------------------------------------------------------
// Phone -> PC file upload (PUT /upload, bearer-authenticated)
// ---------------------------------------------------------------------------
let uploadDir = null
let uploadBase = null // DSH cwd when known (for agent-friendly relative paths)

/**
 * Resolve the upload destination lazily on first use: the configured
 * uploadDir if set, otherwise <DSH cwd>/uploads so the agent's filesystem
 * tools see the file without any extra path gymnastics. Falls back to the
 * gateway's own uploads/ folder when the upstream cannot be reached.
 */
async function resolveUploadDir() {
  if (uploadDir) return
  let cwd = null
  try {
    const d = await upstreamDescribe()
    if (d && typeof d.cwd === 'string' && d.cwd) cwd = d.cwd
  } catch { /* upstream down: use the gateway-local fallback */ }
  uploadDir = config.uploadDir
    ? path.resolve(config.uploadDir)
    : path.join(cwd ?? path.join(__dirname, '..'), 'uploads')
  uploadBase = cwd
  fs.mkdirSync(uploadDir, { recursive: true })
  log('info', `upload dir: ${uploadDir}`)
}

/** Strip path separators/control chars; the name never leaves uploadDir. */
function sanitizeUploadName(raw) {
  const cleaned = String(raw ?? '')
    .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
    .replace(/^\.+$/, '_') // a bare ".." or "..." becomes an innocuous name
    .trim()
    .slice(0, 120)
  return cleaned || `file-${Date.now()}`
}

/** Never overwrite: insert -1, -2, … before the extension on conflicts. */
function uniqueUploadPath(dir, name) {
  const ext = path.extname(name)
  const base = path.basename(name, ext)
  let candidate = path.join(dir, name)
  let i = 1
  while (fs.existsSync(candidate)) {
    candidate = path.join(dir, `${base}-${i}${ext}`)
    i += 1
  }
  return candidate
}

async function handleUpload(req, res, url) {
  const ip = req.socket.remoteAddress ?? '?'
  if (!isAuthorized(req, url)) {
    res.writeHead(401, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: false, error: 'unauthorized' }))
    log('warn', `${ip} ${req.method} /upload -> 401`)
    return
  }
  try {
    // The app passes the session's own cwd (from session.list) so the file
    // lands inside the workspace the agent actually reads; fall back to the
    // host-level default when absent.
    const requestedCwd = url.searchParams.get('cwd') ?? ''
    let dir
    let relBase
    if (requestedCwd && path.isAbsolute(requestedCwd)) {
      relBase = path.resolve(requestedCwd)
      dir = path.join(relBase, 'uploads')
    } else {
      await resolveUploadDir()
      dir = uploadDir
      relBase = uploadBase
    }
    fs.mkdirSync(dir, { recursive: true })
    const name = sanitizeUploadName(url.searchParams.get('name'))
    const target = uniqueUploadPath(dir, name)
    const limit = (Number.isFinite(config.uploadLimitMB) ? config.uploadLimitMB : 512) * 1024 * 1024
    const tmp = `${target}.part-${process.pid}-${Date.now()}`
    const out = fs.createWriteStream(tmp)
    let received = 0
    let settled = false

    const fail = (code, message) => {
      if (settled) return
      settled = true
      out.destroy()
      fs.unlink(tmp, () => {})
      if (!res.headersSent) res.writeHead(code, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: false, error: message }))
      log('warn', `${ip} upload failed: ${message}`)
    }

    out.on('error', () => fail(500, 'disk write failed'))
    req.on('error', () => fail(400, 'request aborted'))
    req.on('data', (chunk) => {
      if (settled) return
      received += chunk.length
      if (received > limit) {
        fail(413, `upload exceeds the ${config.uploadLimitMB} MB limit`)
        return
      }
      if (!out.write(chunk)) req.pause()
    })
    out.on('drain', () => req.resume())
    req.on('end', () => {
      if (!settled) out.end()
    })
    out.on('finish', () => {
      if (settled) return
      settled = true
      fs.renameSync(tmp, target)
      let rel = relBase ? path.relative(relBase, target) : path.basename(target)
      if (!rel || rel.startsWith('..')) rel = target
      rel = rel.split(path.sep).join('/')
      log('info', `${ip} upload -> ${target} (${received} bytes)`)
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: true, name: path.basename(target), path: target, relPath: rel, size: received }))
    })
  } catch (err) {
    log('error', `upload handling failed: ${err.message}`)
    if (!res.headersSent) res.writeHead(500, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: false, error: err.message }))
  }
}

// ---------------------------------------------------------------------------
// WebSocket bridge (downlink /api/events.mux and /api/events.host)
// ---------------------------------------------------------------------------
function bridgeSocket(clientWs, upstreamUrl) {
  // permessage-deflate is offered on both legs. The DSH upstream declines it
  // (its WebSocketServer has it disabled), so the offer is a harmless no-op
  // there today; the phone leg compresses the moment a client negotiates it.
  const upstreamWs = new WebSocket(upstreamUrl, { perMessageDeflate: true })
  upstreamWs.on('open', () => {
    clientWs.on('message', (data, isBinary) => upstreamWs.send(data, { binary: isBinary }))
    upstreamWs.on('message', (data, isBinary) => clientWs.send(data, { binary: isBinary }))
  })
  upstreamWs.on('close', (code) => clientWs.close(1011, 'upstream closed'))
  upstreamWs.on('error', (err) => {
    log('warn', `upstream socket error: ${err.message}`)
    // Tell the phone WHY the bridge died (e.g. DSH not running) instead of a
    // silent close: the app maps ECONNREFUSED to an actionable hint.
    const reason = `upstream ${err.code ?? 'error'} ${err.message ?? ''}`.trim().slice(0, 120)
    clientWs.close(1011, reason)
  })
  clientWs.on('close', () => {
    try { upstreamWs.close() } catch { /* already closed */ }
  })
}

// ---------------------------------------------------------------------------
// Server
// ---------------------------------------------------------------------------
const server = (() => {
  const tls = config.tls
  if (tls && tls.cert && tls.key) {
    return https.createServer({
      cert: fs.readFileSync(tls.cert),
      key: fs.readFileSync(tls.key),
    })
  }
  return http.createServer()
})()

const wss = new WebSocketServer({ noServer: true, perMessageDeflate: true })

// Keep idle keep-alive connections around longer than Node's 5s default so
// the app's pooled HTTP connections survive between refreshes (no handshake).
server.keepAliveTimeout = 30_000
server.headersTimeout = 31_000

server.on('request', (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
  const ip = req.socket.remoteAddress ?? '?'
  const finish = (code, message) => {
    log('info', `${ip} ${req.method} ${url.pathname} -> ${code} ${message ?? ''}`)
  }
  try {
    // Unauthenticated, minimal-information endpoints used by the Android
    // LAN-scanner (/ident) and by human debugging (/health). Both are static
    // JSON with no upstream details, no token, and no state change.
    if (req.method === 'GET' && (url.pathname === '/ident' || url.pathname === '/health')) {
      const base = {
        ok: true,
        gateway: { name: NAME, version: VERSION },
        scheme: config.tls?.cert && config.tls?.key ? 'https' : 'http',
      }
      if (url.pathname === '/health') {
        upstreamProbe().then((reachable) => {
          res.writeHead(200, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ ...base, upstream: { reachable } }))
          finish(200, 'health')
        })
        return
      }
      res.writeHead(200, { 'content-type': 'application/json' })
      res.end(JSON.stringify(base))
      finish(200, 'ident')
      return
    }
    // Phone -> PC file upload. Authenticated like everything else; the file
    // lands in the DSH working directory's uploads/ so the agent can use it.
    if ((req.method === 'PUT' || req.method === 'POST') && url.pathname === '/upload') {
      handleUpload(req, res, url)
      return
    }
    if (!url.pathname.startsWith('/api/')) {
      res.writeHead(404, { 'content-type': 'text/plain' })
      res.end('not found')
      finish(404)
      return
    }
    if (!isAuthorized(req, url)) {
      res.writeHead(401, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: false, error: 'unauthorized' }))
      finish(401, 'unauthorized')
      return
    }
    if (req.method !== 'POST') {
      res.writeHead(405, { 'content-type': 'text/plain' })
      res.end('method not allowed')
      finish(405)
      return
    }
    let received = 0
    let aborted = false
    req.on('data', (chunk) => {
      if (aborted) return
      received += chunk.length
      if (received > MAX_BODY_BYTES) {
        // Answer first, then sever the socket: the client gets a well-formed
        // 413 instead of a bare connection reset.
        aborted = true
        if (!res.headersSent) {
          res.writeHead(413, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: 'request too large' }))
        }
        req.destroy()
        finish(413)
      }
    })
    res.on('finish', () => finish(res.statusCode, url.pathname))
    forwardRequest(req, res)
  } catch (err) {
    log('error', `request handling failed: ${err.message}`)
    if (!res.headersSent) res.writeHead(500)
    res.end()
  }
})

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
  const ip = socket.remoteAddress ?? '?'
  const allowed = url.pathname === '/api/events.mux' || url.pathname === '/api/events.host'
  if (!allowed) {
    socket.destroy()
    log('warn', `${ip} upgrade ${url.pathname} -> refused (path)`)
    return
  }
  if (!isAuthorized(req, url)) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
    socket.destroy()
    log('warn', `${ip} upgrade ${url.pathname} -> 401 unauthorized`)
    return
  }
  wss.handleUpgrade(req, socket, head, (clientWs) => {
    const upstreamUrl = `${config.upstream.replace(/^http/, 'ws')}${url.pathname}${url.search}`
    log('info', `${ip} upgrade ${url.pathname} -> bridged`)
    bridgeSocket(clientWs, upstreamUrl)
  })
})

server.on('listening', () => {
  const addr = server.address()
  log('info', `listening on ${addr.address}:${addr.port} (${config.tls?.cert && config.tls?.key ? 'https' : 'http'})`)
  log('info', `upstream: ${config.upstream}`)
  log('info', 'token: ******** (see config file)')
  log('info', `config: ${config.configPath}`)
  // One-command mode: probes the upstream and launches `dsh web` if needed.
  superviseDsh()
})

server.on('error', (err) => {
  log('error', `server error: ${err.message}`)
  process.exit(1)
})

process.on('SIGINT', () => {
  log('info', 'shutting down')
  shuttingDown = true
  for (const client of wss.clients) client.close()
  killDshTree()
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(0), 3000).unref()
})

server.listen(config.port, config.host)
