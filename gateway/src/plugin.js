/**
 * dsh-remote-gateway — DSH bundle plugin entry.
 *
 * Imports into `dsh web` so one DSH process serves both the loopback web UI and
 * the authenticated remote gateway (0.0.0.0:<port>). The gateway surface itself
 * (token auth, /api proxy, WS bridge, /upload, /ident) is shared with the
 * standalone CLI in src/gateway.js — no logic is duplicated.
 *
 * As a plugin we never launch/restart `dsh web` (we ARE dsh web), so the
 * supervisor stays in the standalone CLI.
 */
import crypto from 'node:crypto'
import fs from 'node:fs'
import { createGateway, resolveTls, resolveToken, generateToken, NAME, VERSION, DEFAULT_PORT } from './gateway.js'
import { CloudflaredTunnel } from './tunnel.js'
import { homedir, networkInterfaces } from 'node:os'
import path from 'node:path'
import http from 'node:http'
import QRCode from 'qrcode'

export const name = '@dsh-external/dsh-remote-gateway'
export const inject = ['webServer']

const DSH_HOME = process.env.DSH_HOME || path.join(homedir(), '.dsh')
const BASE_DIR = path.join(DSH_HOME, 'remote-gateway')

function num(value, fallback) {
  const n = Number(value ?? fallback)
  return Number.isFinite(n) ? n : fallback
}

function resolveUpstream(configured) {
  return (configured ?? process.env.DSH_GATEWAY_UPSTREAM ?? 'http://127.0.0.1:3080').replace(/\/+$/, '')
}

/**
 * All non-internal IPv4 addresses, private (RFC1918) first so the QR primary
 * address is the phone-reachable LAN IP rather than a VPN/virtual adapter.
 */
function lanIps() {
  const privateIps = []
  const others = []
  for (const ifaces of Object.values(networkInterfaces())) {
    for (const iface of ifaces ?? []) {
      if (iface.family !== 'IPv4' || iface.internal) continue
      const ip = iface.address
      const isPrivate = /^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)/.test(ip)
      ;(isPrivate ? privateIps : others).push(ip)
    }
  }
  // Prefer common home-LAN ranges first for the primary pick.
  const rank = (ip) => {
    if (ip.startsWith('192.168.')) return 0
    if (ip.startsWith('10.')) return 1
    if (/^172\.(1[6-9]|2[0-9]|3[01])\./.test(ip)) return 2
    return 3
  }
  privateIps.sort((a, b) => rank(a) - rank(b) || a.localeCompare(b, undefined, { numeric: true }))
  return [...new Set([...privateIps, ...others])]
}

/** Read a small JSON request body (used by the tunnel control endpoint). */
function readJsonBody(req, limit = 64 * 1024) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', (c) => {
      data += c
      if (data.length > limit) reject(new Error('body too large'))
    })
    req.on('end', () => {
      try {
        resolve(data ? JSON.parse(data) : {})
      } catch (err) {
        reject(new Error('invalid json'))
      }
    })
    req.on('error', reject)
  })
}

/** Fire-and-forget reachability hint: the standard `dsh web` port is 3080. */
function logUpstreamHint(upstream, log) {
  const url = new URL(upstream)
  const probe = () => {
    const req = http.request(
      { hostname: url.hostname, port: url.port || 80, path: '/api/host.describe', method: 'POST',
        headers: { 'content-type': 'application/json', 'content-length': 55, host: url.host } },
      (res) => { res.resume(); },
    )
    req.on('error', () => {
      log(`!! upstream unreachable at ${upstream} — if dsh web is on another port, set plugin config "upstream" (or DSH_GATEWAY_UPSTREAM)`)
    })
    req.on('socket', (s) => s.setTimeout(1500, () => req.destroy()))
    req.write(JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(), method: 'host.describe', payload: {} }))
    req.end()
  }
  setTimeout(probe, 1000)
}

export function apply(ctx, config = {}) {
  const log = (message) => ctx.logger?.info?.(`[${NAME}] ${message}`)

  // Token policy (plugin mode):
  //  - explicit token (config.token string / DSH_GATEWAY_TOKEN env) always wins;
  //  - otherwise, by default a FRESH random token is minted on EVERY dsh web
  //    restart — a rebooted PC never keeps a long-lived phone credential;
  //  - disable rotation with config "token": { "rotateOnRestart": false }
  //    (or env DSH_GATEWAY_TOKEN_ROTATE=0) to keep the persisted token.
  const explicitToken = config.token ?? process.env.DSH_GATEWAY_TOKEN
  const tokenFile = config.tokenFile || path.join(BASE_DIR, 'gateway.token')
  const rotateToken = !explicitToken
    && config.token?.rotateOnRestart !== false
    && process.env.DSH_GATEWAY_TOKEN_ROTATE !== '0'
  const token = explicitToken
    ?? (rotateToken ? (() => {
      const fresh = generateToken()
      try {
        fs.mkdirSync(path.dirname(tokenFile), { recursive: true })
        fs.writeFileSync(tokenFile, fresh + '\n', 'utf8')
        log(`token rotated: fresh random token generated for this dsh web session -> ${tokenFile}`)
      } catch (err) {
        log(`WARN: could not persist rotated token: ${err.message}`)
      }
      return fresh
    })() : resolveToken({ token: '', tokenFile }, log).token)

  const tls = resolveTls(
    {
      cert: config.tls?.cert,
      key: config.tls?.key,
      auto: config.tls?.auto ?? true, // plugin defaults to HTTPS
      certDir: config.tls?.certDir || 'certs',
    },
    BASE_DIR,
    log,
  )

  const upstream = resolveUpstream(config.upstream)
  const gatewayConfig = {
    host: config.host ?? process.env.DSH_GATEWAY_HOST ?? '0.0.0.0',
    port: num(config.port ?? process.env.DSH_GATEWAY_PORT, DEFAULT_PORT),
    upstream,
    token,
    tls,
    uploadDir: config.uploadDir ?? process.env.DSH_GATEWAY_UPLOAD_DIR ?? '',
    uploadLimitMB: num(config.uploadLimitMB ?? process.env.DSH_GATEWAY_UPLOAD_LIMIT_MB, 512),
    publicBaseUrl: config.publicBaseUrl ?? process.env.DSH_GATEWAY_PUBLIC_URL ?? '',
    fingerprint: tls?.fingerprint ?? null,
  }

  // NAT traversal (zero-account Cloudflare quick tunnel). The tunnel fronts the
  // gateway over the local HTTPS listener with cert verification off (it is a
  // loopback hop); the public URL is then advertised everywhere (QR, /ident).
  const tunnel = new CloudflaredTunnel({
    binary: config.tunnel?.binary ?? '',
    baseDir: BASE_DIR,
    log,
  })
  tunnel.onChange = (state) => {
    if (state.status === 'online' && state.url) {
      gatewayConfig.publicBaseUrl = state.url
      log(`remote access ready: ${state.url}`)
    } else if (state.status === 'off') {
      // restore the configured (non-tunnel) public URL, if any
      gatewayConfig.publicBaseUrl = config.publicBaseUrl ?? process.env.DSH_GATEWAY_PUBLIC_URL ?? ''
    }
    if (state.status === 'error') log(`remote access error: ${state.error}`)
  }

  const tunnelEnabledByDefault = config.tunnel?.enabled === true || process.env.DSH_GATEWAY_TUNNEL === '1'

  // All resources are registered through ctx.effect so a plugin reload/unload
  // tears the listener down cleanly (cordis disposal contract).
  ctx.effect(() => {
    const gateway = createGateway(gatewayConfig, { log })
    gateway.listening.then(() => {
      const addr = gateway.server.address()
      log(`listening on ${addr.address}:${addr.port} (${tls ? 'https' : 'http'}) -> upstream ${upstream}`)
      if (gatewayConfig.publicBaseUrl) log(`public base URL: ${gatewayConfig.publicBaseUrl}`)
      logUpstreamHint(upstream, log)
      if (tunnelEnabledByDefault) {
        tunnel.enable(gatewayConfig.port, tls ? 'https' : 'http')
          .catch((err) => log(`remote access start failed: ${err.message}`))
      }
    }).catch((err) => {
      log(`failed to listen: ${err.message}`)
    })
    return () => {
      tunnel.disable()
      gateway.close().catch(() => {})
    }
  }, `${name}: gateway`)

  // Loopback-only info endpoint consumed by the client settings panel. This
  // route lives on DSH's own web server (127.0.0.1), so exposing the token here
  // is the same trust domain as the local browser the user is already using.
  ctx.effect(() => {
    const dispose = ctx.webServer.register({
      kind: 'exact',
      path: '/dsh-remote-gateway/info',
      handler: async (req, res) => {
        if (req.method !== 'GET' && req.method !== 'HEAD') {
          res.writeHead(405, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: 'method not allowed' }))
          return
        }
        const ips = lanIps()
        const baseUrl = gatewayConfig.publicBaseUrl
          || (ips[0] ? `${tls ? 'https' : 'http'}://${ips[0]}:${gatewayConfig.port}` : '')
        const deepLink = baseUrl
          ? `dsh-gateway://connect?u=${encodeURIComponent(baseUrl)}&t=${encodeURIComponent(gatewayConfig.token)}`
          : ''
        // QR generated by the battle-tested `qrcode` package (independently
        // verified with jsQR) — the panel renders this SVG instead of encoding
        // client-side, so the phone scanner always gets a spec-correct code.
        let qrSvg = ''
        if (deepLink) {
          try {
            qrSvg = await QRCode.toString(deepLink, {
              type: 'svg',
              errorCorrectionLevel: 'M',
              margin: 4,
              // Vector size; the panel constrains the rendered size further.
              width: 240,
              color: { dark: '#0f172a', light: '#ffffff' },
            })
          } catch (err) {
            log(`qr generation failed: ${err.message}`)
          }
        }
        res.writeHead(200, {
          'content-type': 'application/json; charset=utf-8',
          'cache-control': 'no-store',
        })
        res.end(JSON.stringify({
          ok: true,
          name: NAME,
          version: VERSION,
          scheme: tls ? 'https' : 'http',
          port: gatewayConfig.port,
          lanIps: ips,
          baseUrl,
          deepLink,
          qrSvg,
          publicBaseUrl: gatewayConfig.publicBaseUrl || null,
          tunnel: tunnel.getState(),
          tlsFingerprint: gatewayConfig.fingerprint ?? null,
          token: gatewayConfig.token,
          tokenRotates: rotateToken,
          tokenPreview: gatewayConfig.token.slice(0, 4) + '…' + gatewayConfig.token.slice(-4),
        }))
      },
    })
    return () => dispose()
  }, `${name}: info api`)

  // Remote-access control endpoint (loopback only, like /info).
  ctx.effect(() => {
    const dispose = ctx.webServer.register({
      kind: 'exact',
      path: '/dsh-remote-gateway/tunnel',
      handler: async (req, res) => {
        if (req.method !== 'POST') {
          res.writeHead(405, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: 'method not allowed' }))
          return
        }
        try {
          const body = await readJsonBody(req)
          if (body.enable === true) {
            await tunnel.enable(gatewayConfig.port, tls ? 'https' : 'http')
          } else if (body.enable === false) {
            tunnel.disable()
          }
          res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' })
          res.end(JSON.stringify({ ok: true, ...tunnel.getState() }))
        } catch (err) {
          res.writeHead(400, { 'content-type': 'application/json' })
          res.end(JSON.stringify({ ok: false, error: err.message }))
        }
      },
    })
    return () => dispose()
  }, `${name}: tunnel api`)

  log(`plugin v${VERSION} loaded`)
}
