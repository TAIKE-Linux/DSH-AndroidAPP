#!/usr/bin/env node
/**
 * dsh-remote-gateway — standalone CLI entry.
 *
 * Authenticated LAN/WAN front for a loopback-only DeepSeek Harness (`dsh web`).
 * The actual proxy/upload/WS surface lives in src/gateway.js; this file adds
 * the standalone concerns: CLI flags, config file, token rotation, and the
 * one-command dsh supervisor (launch/restart `dsh web` when it is not running).
 *
 * Usage:
 *   node src/index.js                      # reads ./gateway.config.json
 *   node src/index.js --port 8742 --token <tok> --upstream http://127.0.0.1:3080
 *   node src/index.js --rotate-token       # generate a fresh token and exit
 *   node src/index.js --print-token        # print the active token and exit
 *   env: DSH_GATEWAY_PORT / DSH_GATEWAY_TOKEN / DSH_GATEWAY_UPSTREAM / DSH_GATEWAY_HOST
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import http from 'node:http'
import https from 'node:https'
import net from 'node:net'
import path from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { createGateway, resolveTls, resolveToken, NAME, VERSION, DEFAULT_PORT } from './gateway.js'

const __dirname = path.dirname(fileURLToPath(import.meta.url))

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
    } else if (a === '--rotate-token') {
      out.rotateToken = true
    } else if (a === '--print-token') {
      out.printToken = true
    } else if (a === '--help' || a === '-h') {
      out.help = true
    }
  }
  return out
}

function defaultConfigPath() {
  return path.join(process.cwd(), 'gateway.config.json')
}

function loadConfig() {
  const args = parseArgs(process.argv.slice(2))
  if (args.help) {
    console.log(`Usage: dsh-gateway [options]\n
  --config <path>      config JSON file (default: ./gateway.config.json)
  --port <port>        listen port (env DSH_GATEWAY_PORT, default ${DEFAULT_PORT})
  --host <host>        bind host, default 0.0.0.0 (env DSH_GATEWAY_HOST)
  --token <token>      bearer token (env DSH_GATEWAY_TOKEN)
  --upstream <url>     DSH web URL, default http://127.0.0.1:3080 (env DSH_GATEWAY_UPSTREAM)
  --dsh-command <cmd>  command used to launch DSH (default: dsh web)
  --no-launch-dsh      disable the built-in DSH supervisor (start dsh web yourself)
  --rotate-token       generate a new random token, write it to the config, exit
  --print-token        print the active token, exit`)
    process.exit(0)
  }

  const configPath = args.config ?? process.env.DSH_GATEWAY_CONFIG ?? defaultConfigPath()
  const fileConfig = {}
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
    port: Number(args.port ?? process.env.DSH_GATEWAY_PORT ?? fileConfig.port ?? DEFAULT_PORT),
    upstream: (args.upstream ?? process.env.DSH_GATEWAY_UPSTREAM ?? fileConfig.upstream ?? 'http://127.0.0.1:3080').replace(/\/+$/, ''),
    token: args.token ?? process.env.DSH_GATEWAY_TOKEN ?? fileConfig.token ?? '',
    tls: fileConfig.tls ?? {},
    uploadDir: process.env.DSH_GATEWAY_UPLOAD_DIR ?? fileConfig.uploadDir ?? '',
    uploadLimitMB: Number(process.env.DSH_GATEWAY_UPLOAD_LIMIT_MB ?? fileConfig.uploadLimitMB ?? 512),
    publicBaseUrl: process.env.DSH_GATEWAY_PUBLIC_URL ?? fileConfig.publicBaseUrl ?? '',
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
  config.configPath = configPath
  config.tokenFile = path.join(path.dirname(configPath), 'gateway.token')
  return { config, args }
}

const { config, args } = loadConfig()

const log = (message) => {
  const ts = new Date().toISOString().replace('T', ' ').slice(0, 19)
  console.log(`[${ts}] [${NAME}] ${message}`)
}

function persistConfigToken(token) {
  const written = { ...config, token, tls: config.tls }
  delete written.configPath
  delete written.tokenFile
  fs.writeFileSync(config.configPath, JSON.stringify(written, null, 2) + '\n')
}

// -- token management --------------------------------------------------------
if (args.rotateToken) {
  const fresh = crypto.randomBytes(32).toString('base64url')
  persistConfigToken(fresh)
  fs.writeFileSync(config.tokenFile, fresh + '\n')
  console.log(fresh)
  process.exit(0)
}

const tokenResolution = resolveToken({ token: config.token, tokenFile: config.tokenFile }, log)
config.token = tokenResolution.token
// Keep the standalone JSON config authoritative so `--print-token`/restart agree.
if (tokenResolution.generated) {
  persistConfigToken(config.token)
}
// Always mirror the effective token so gateway.token never drifts from config.
try {
  fs.writeFileSync(config.tokenFile, config.token + '\n')
} catch (err) {
  log(`WARN: could not write ${config.tokenFile}: ${err.message}`)
}

if (args.printToken) {
  console.log(config.token)
  process.exit(0)
}

// -- TLS ---------------------------------------------------------------------
const tls = resolveTls(
  { cert: config.tls.cert, key: config.tls.key, auto: config.tls.auto, certDir: config.tls.certDir },
  path.dirname(config.configPath),
  log,
)
config.tlsResolved = tls
if (tls) {
  config.fingerprint = tls.fingerprint
    ?? crypto.createHash('sha256').update(new crypto.X509Certificate(tls.cert).raw).digest('hex')
}

// ---------------------------------------------------------------------------
// DeepSeek Harness supervisor (standalone only; the plugin runs in-process and
// never needs this). Probe first; if a DSH is already answering, leave it
// alone. Otherwise launch `dsh web`, wait for it to answer, and restart it
// whenever it later exits.
// ---------------------------------------------------------------------------
let dshChild = null
let dshRestarts = 0
let dshStartAt = 0
let dshGivenUp = false
let shuttingDown = false

const upstreamIsLocal = (() => {
  try {
    const h = new URL(config.upstream).hostname
    return h === '127.0.0.1' || h === 'localhost' || h === '::1'
  } catch {
    return false
  }
})()

const gateway = createGateway(
  {
    host: config.host,
    port: config.port,
    upstream: config.upstream,
    token: config.token,
    tls,
    uploadDir: config.uploadDir,
    uploadLimitMB: config.uploadLimitMB,
    publicBaseUrl: config.publicBaseUrl,
    fingerprint: config.fingerprint,
  },
  { log },
)

const upstreamDescribe = () => {
  const envelope = { type: 'client-request', rpcId: crypto.randomUUID(), method: 'host.describe', payload: {} }
  const body = JSON.stringify(envelope)
  const upstreamUrl = new URL(config.upstream)
  const lib = upstreamUrl.protocol === 'https:' ? https : http
  return new Promise((resolve) => {
    const req = lib.request(
      {
        protocol: upstreamUrl.protocol,
        hostname: upstreamUrl.hostname,
        port: upstreamUrl.port || (upstreamUrl.protocol === 'https:' ? 443 : 80),
        method: 'POST',
        path: '/api/host.describe',
        headers: { 'content-type': 'application/json', 'content-length': Buffer.byteLength(body), host: upstreamUrl.host },
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
    req.setTimeout(4000, () => { req.destroy(); resolve({ error: 'timeout' }) })
    req.write(body)
    req.end()
  })
}

const upstreamProbe = async () => {
  const result = await upstreamDescribe()
  return typeof result === 'object' && result !== null && result.error === undefined
}

function portListening(host, port) {
  return new Promise((resolve) => {
    const socket = net.connect({ host, port, timeout: 1500 })
    socket.once('connect', () => { socket.destroy(); resolve(true) })
    socket.once('timeout', () => { socket.destroy(); resolve(false) })
    socket.once('error', () => resolve(false))
  })
}

function killDshTree() {
  const child = dshChild
  dshChild = null
  if (!child) return
  if (process.platform === 'win32') {
    try { spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' }) } catch { /* gone */ }
  } else {
    try { child.kill('SIGTERM') } catch { /* gone */ }
  }
}

function spawnDsh() {
  if (shuttingDown || dshGivenUp) return
  dshStartAt = Date.now()
  log(`starting DeepSeek Harness: ${config.dshCommand}  (cwd: ${config.dshCwd})`)
  const child = spawn(config.dshCommand, [], { shell: true, cwd: config.dshCwd, stdio: 'inherit', windowsHide: false })
  dshChild = child
  child.on('error', (err) => {
    log(`failed to launch dsh: ${err.message}`)
    dshChild = null
    scheduleDshRespawn(true)
  })
  child.on('exit', (code) => {
    dshChild = null
    if (shuttingDown) return
    const aliveMs = Date.now() - dshStartAt
    const fastFail = aliveMs < 10_000
    log(`DeepSeek Harness exited (code ${code}) after ${Math.round(aliveMs / 1000)}s${fastFail ? ' — looks like a startup failure' : ''}`)
    scheduleDshRespawn(fastFail)
  })
}

function scheduleDshRespawn(fastFail) {
  if (shuttingDown || dshGivenUp) return
  if (fastFail) {
    dshRestarts += 1
    if (dshRestarts >= 3) {
      dshGivenUp = true
      log('giving up on DeepSeek Harness after 3 rapid failures. Check:')
      log('  1) is "dsh" installed? (npm i -g @deepseek-ai/dsh)')
      log(`  2) is port ${new URL(config.upstream).port} free?`)
      log('  3) run "dsh web" manually to see its real error, or set dshCommand in gateway.config.json')
      return
    }
  } else {
    dshRestarts = 0
  }
  setTimeout(() => spawnDsh(), 5000)
}

async function superviseDsh() {
  if (!config.launchDsh) {
    log('dsh supervisor disabled (launchDsh=false): start "dsh web" yourself')
    const ok = await upstreamProbe()
    log(ok ? 'upstream probe: OK' : `!! upstream unreachable at ${config.upstream} — the gateway recovers once it is up`)
    return
  }
  if (!upstreamIsLocal) {
    log(`upstream ${config.upstream} is not local — dsh supervisor skipped`)
    return
  }
  const u = new URL(config.upstream)
  const host = u.hostname
  const port = Number(u.port || (u.protocol === 'https:' ? 443 : 80))
  const listening = await portListening(host, port)
  if (listening) {
    log(`port ${host}:${port} is listening — assuming DeepSeek Harness is already up`)
    const ok = await upstreamProbe()
    log(ok ? 'upstream probe: OK (DeepSeek Harness reachable)' : `note: ${host}:${port} occupied but not answering the DSH API`)
    return
  }
  log('DeepSeek Harness not detected — one-command mode: launching it now')
  spawnDsh()
  let waited = 0
  const poll = setInterval(async () => {
    waited += 2
    if (shuttingDown || dshGivenUp) { clearInterval(poll); return }
    if (await upstreamProbe()) {
      clearInterval(poll)
      log(`DeepSeek Harness is up after ~${waited}s — gateway fully operational`)
    } else if (waited >= 180) {
      clearInterval(poll)
      log('DeepSeek Harness still not reachable after 3 minutes; the gateway keeps retrying and recovers once it is up')
    }
  }, 2000)
}

gateway.listening.then(() => {
  const addr = gateway.server.address()
  log(`listening on ${addr.address}:${addr.port} (${tls ? 'https' : 'http'})`)
  log(`upstream: ${config.upstream}`)
  log(`token: ******** (run "node src/index.js --print-token" or read gateway.token)`)
  if (config.publicBaseUrl) log(`public base URL: ${config.publicBaseUrl}`)
  log(`config: ${config.configPath}`)
  superviseDsh()
}).catch((err) => {
  log(`failed to listen: ${err.message}`)
  process.exit(1)
})

process.on('SIGINT', () => {
  log('shutting down')
  shuttingDown = true
  killDshTree()
  gateway.close().then(() => process.exit(0))
  setTimeout(() => process.exit(0), 3000).unref()
})
