/**
 * dsh-remote-gateway — shared server core.
 *
 * This module is deliberately free of any CLI/supervisor/DSH-plugin concerns:
 * it owns ONLY the authenticated proxy surface (HTTP + WS + upload + ident) so
 * the same code can run as:
 *
 *  1. a standalone process   (`src/index.js` — node src/index.js / npm start)
 *  2. a DSH bundle plugin    (`src/plugin.js` — imported by dsh web)
 *
 * Config is fully resolved by the caller; this module never reads argv or env.
 */
import http from 'node:http'
import https from 'node:https'
import crypto from 'node:crypto'
import fs from 'node:fs'
import net from 'node:net'
import path from 'node:path'
import zlib from 'node:zlib'
import { WebSocket, WebSocketServer } from 'ws'

export const NAME = 'dsh-remote-gateway'
export const VERSION = '0.3.0'
export const DEFAULT_PORT = 8742
const MAX_BODY_BYTES = 220 * 1024 * 1024 // upstream /api default body cap is 160 MiB; keep headroom

const HOP_BY_HOP = new Set([
  'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
  'te', 'trailer', 'transfer-encoding', 'upgrade', 'host', 'authorization',
])

// Statuses that never carry a body: compressing them would corrupt semantics.
const NO_GZIP_STATUS = new Set([204, 304])

// ---------------------------------------------------------------------------
// Credential / TLS helpers
// ---------------------------------------------------------------------------
export function generateToken(bytes = 32) {
  return crypto.randomBytes(bytes).toString('base64url')
}

/**
 * Minimal ASN.1 DER encoder used to mint a self-signed X.509 certificate
 * without any openssl/mkcert dependency. Node can generate keypairs and sign
 * with them, but has no built-in certificate *builder*; the ~80 lines below
 * are the standard TLV encoding plus the X.509 SEQUENCE assembly.
 */
function derLength(len) {
  if (len < 0x80) return Buffer.from([len])
  const bytes = []
  let l = len
  while (l > 0) {
    bytes.unshift(l & 0xff)
    l >>>= 8
  }
  return Buffer.from([0x80 | bytes.length, ...bytes])
}

function derTlv(tag, value) {
  return Buffer.concat([Buffer.from([tag]), derLength(value.length), value])
}

function derInt(n) {
  let hex = n.toString(16)
  if (hex.length % 2) hex = '0' + hex
  const buf = Buffer.from(hex, 'hex')
  return derTlv(0x02, buf[0] & 0x80 ? Buffer.concat([Buffer.from([0]), buf]) : buf)
}

function derOid(oid) {
  const parts = oid.split('.').map(Number)
  const body = [parts[0] * 40 + parts[1]]
  for (let i = 2; i < parts.length; i++) {
    let v = parts[i]
    const bytes = [v & 0x7f]
    v >>>= 7
    while (v > 0) {
      bytes.unshift((v & 0x7f) | 0x80)
      v >>>= 7
    }
    body.push(...bytes)
  }
  return derTlv(0x06, Buffer.from(body))
}

const derNull = () => Buffer.from([0x05, 0x00])

function derUtcTime(date) {
  // UTCTime ::= YYMMDDHHMMSSZ (two-digit year, no separators)
  const y = String(date.getUTCFullYear() % 100).padStart(2, '0')
  const mo = String(date.getUTCMonth() + 1).padStart(2, '0')
  const d = String(date.getUTCDate()).padStart(2, '0')
  const h = String(date.getUTCHours()).padStart(2, '0')
  const mi = String(date.getUTCMinutes()).padStart(2, '0')
  const s = String(date.getUTCSeconds()).padStart(2, '0')
  return derTlv(0x17, Buffer.from(`${y}${mo}${d}${h}${mi}${s}Z`, 'utf8'))
}

function derName(attrs) {
  // Name ::= SEQUENCE OF RelativeDistinguishedName; each RDN is a SET of one
  // AttributeTypeAndValue for the common single-attribute case used here.
  const rdn = attrs.map(([oid, value]) =>
    derTlv(0x31, derTlv(0x30, Buffer.concat([
      derOid(oid),
      derTlv(0x0c, Buffer.from(value, 'utf8')),
    ]))),
  )
  return derTlv(0x30, Buffer.concat(rdn))
}

const OID = {
  commonName: '2.5.4.3',
  organizationName: '2.5.4.10',
  countryName: '2.5.4.6',
  sha256WithRsa: '1.2.840.113549.1.1.11',
  rsaEncryption: '1.2.840.113549.1.1.1',
  subjectAltName: '2.5.29.17',
  basicConstraints: '2.5.29.19',
}

function derAlgorithmIdentifier(oid) {
  return derTlv(0x30, Buffer.concat([derOid(oid), derNull()]))
}

/** Build a self-signed RSA cert with a SAN covering localhost + the host IPs. */
export function generateSelfSignedCert({
  commonName = NAME,
  altNames = ['localhost', '127.0.0.1'],
  validDays = 825,
} = {}) {
  const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 })
  const pkcs1 = publicKey.export({ type: 'pkcs1', format: 'der' })

  // SubjectPublicKeyInfo ::= SEQUENCE { algId, BIT STRING (pkcs1) }
  const spki = derTlv(0x30, Buffer.concat([
    derAlgorithmIdentifier(OID.rsaEncryption),
    derTlv(0x03, Buffer.concat([Buffer.from([0]), pkcs1])),
  ]))

  const serial = crypto.randomBytes(16).readBigUInt64BE(0)
  const now = new Date()
  const notAfter = new Date(now.getTime() + validDays * 24 * 3600 * 1000)

  // subjectAltName extension value: SEQUENCE OF GeneralName.
  const generalNames = altNames.map((name) => {
    if (/^\d{1,3}(\.\d{1,3}){3}$/.test(name)) {
      const octets = Buffer.from(name.split('.').map((n) => Number(n) & 0xff))
      return derTlv(0x87, octets) // iPAddress [7]
    }
    return derTlv(0x82, Buffer.from(name, 'utf8')) // dNSName [2]
  })
  const sanValue = derTlv(0x30, Buffer.concat(generalNames))
  const basicConstraintsValue = derTlv(0x30, Buffer.concat([
    derTlv(0x01, Buffer.from([0xff])), // BOOLEAN TRUE (CA)
  ]))

  const extension = (oid, critical, value) =>
    derTlv(0x30, Buffer.concat([
      derOid(oid),
      critical ? derTlv(0x01, Buffer.from([0xff])) : Buffer.alloc(0),
      derTlv(0x04, value),
    ]))

  const extensions = derTlv(0x30, Buffer.concat([
    extension(OID.basicConstraints, true, basicConstraintsValue),
    extension(OID.subjectAltName, false, sanValue),
  ]))

  const tbs = derTlv(0x30, Buffer.concat([
    derTlv(0xa0, derInt(2)), // version v3 (explicit [0])
    derInt(serial),
    derAlgorithmIdentifier(OID.sha256WithRsa),
    derName([[OID.countryName, 'CN'], [OID.organizationName, 'dsh-remote-gateway'], [OID.commonName, commonName]]),
    derTlv(0x30, Buffer.concat([derUtcTime(now), derUtcTime(notAfter)])),
    derName([[OID.countryName, 'CN'], [OID.organizationName, 'dsh-remote-gateway'], [OID.commonName, commonName]]),
    spki,
    derTlv(0xa3, extensions), // [3] EXPLICIT
  ]))

  const signature = crypto.sign('sha256', tbs, privateKey)
  const certDer = derTlv(0x30, Buffer.concat([
    tbs,
    derAlgorithmIdentifier(OID.sha256WithRsa),
    derTlv(0x03, Buffer.concat([Buffer.from([0]), signature])),
  ]))

  const cert = '-----BEGIN CERTIFICATE-----\n' + certDer.toString('base64').replace(/(.{64})/g, '$1\n') + '\n-----END CERTIFICATE-----\n'
  const key = privateKey.export({ type: 'pkcs8', format: 'pem' }).toString()
  const fingerprint = crypto.createHash('sha256').update(certDer).digest('hex')
  return { cert, key, fingerprint }
}

/** Persist a generated token unless one is already configured. */
export function resolveToken({ token = '', tokenFile = '' }, log) {
  if (token) return { token, generated: false }
  if (tokenFile) {
    try {
      const fromFile = fs.readFileSync(tokenFile, 'utf8').trim()
      if (fromFile) return { token: fromFile, generated: false }
    } catch { /* not present yet */ }
  }
  const generated = generateToken()
  if (tokenFile) {
    try {
      fs.mkdirSync(path.dirname(tokenFile), { recursive: true })
      fs.writeFileSync(tokenFile, generated + '\n', 'utf8')
      log(`generated random token -> ${tokenFile}`)
    } catch (err) {
      log(`WARN: could not persist token: ${err.message}`)
    }
  }
  return { token: generated, generated: true }
}

/**
 * Resolve TLS material for the gateway listener.
 *  - explicit cert/key paths win (existing behavior)
 *  - auto=true mints a self-signed certificate and persists it under certDir
 * Returns null for plain HTTP.
 */
export function resolveTls({ cert = '', key = '', auto = false, certDir = '' } = {}, baseDir, log) {
  if (cert && key) {
    return {
      cert: fs.readFileSync(path.resolve(baseDir, cert), 'utf8'),
      key: fs.readFileSync(path.resolve(baseDir, key), 'utf8'),
      fingerprint: null,
    }
  }
  if (!auto) return null

  const dir = path.resolve(baseDir, certDir || 'certs')
  const certPath = path.join(dir, 'gateway-cert.pem')
  const keyPath = path.join(dir, 'gateway-key.pem')
  fs.mkdirSync(dir, { recursive: true })
  let material
  try {
    material = {
      cert: fs.readFileSync(certPath, 'utf8'),
      key: fs.readFileSync(keyPath, 'utf8'),
    }
    try {
      material.fingerprint = crypto.createHash('sha256')
        .update(new crypto.X509Certificate(material.cert).raw)
        .digest('hex')
    } catch {
      material.fingerprint = null
    }
    log(`using existing self-signed certificate (${certPath})`)
  } catch {
    material = generateSelfSignedCert({ commonName: NAME })
    fs.writeFileSync(certPath, material.cert, 'utf8')
    fs.writeFileSync(keyPath, material.key, { mode: 0o600 })
    log(`generated self-signed certificate -> ${certPath}`)
  }
  material.certPath = certPath
  material.keyPath = keyPath
  return material
}

// ---------------------------------------------------------------------------
// Gateway server
// ---------------------------------------------------------------------------
export function createGateway(config, { log = (m) => console.log(m) } = {}) {
  const {
    host = '0.0.0.0',
    port = DEFAULT_PORT,
    upstream = 'http://127.0.0.1:3080',
    token,
    tls = null,
    uploadDir = '',
    uploadLimitMB = 512,
    fingerprint = null,
  } = config

  const safeEqual = (a, b) => {
    const ha = crypto.createHash('sha256').update(String(a)).digest()
    const hb = crypto.createHash('sha256').update(String(b)).digest()
    return crypto.timingSafeEqual(ha, hb)
  }

  const isAuthorized = (req, url) => {
    const header = req.headers['authorization'] ?? ''
    if (header.startsWith('Bearer ')) return safeEqual(header.slice(7), token)
    const qToken = url.searchParams.get('token')
    if (qToken) return safeEqual(qToken, token)
    return false
  }

  // -- upstream unary proxy ------------------------------------------------
  const forwardRequest = (req, res) => {
    const upstreamUrl = new URL(upstream)
    const lib = upstreamUrl.protocol === 'https:' ? https : http
    const acceptsGzip = /\bgzip\b/i.test(req.headers['accept-encoding'] ?? '')
    const headers = {}
    for (const [key, value] of Object.entries(req.headers)) {
      if (HOP_BY_HOP.has(key.toLowerCase())) continue
      headers[key] = value
    }
    headers['host'] = upstreamUrl.host
    const proxyReq = lib.request(
      {
        protocol: upstreamUrl.protocol,
        hostname: upstreamUrl.hostname,
        port: upstreamUrl.port || (upstreamUrl.protocol === 'https:' ? 443 : 80),
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
          if (compressible && lower === 'content-length') continue
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
    proxyReq.on('socket', (socket) => socket.setNoDelay(true))
    proxyReq.on('error', (err) => {
      log(`upstream ${req.method} ${req.url} failed: ${err.message}`)
      if (!res.headersSent) res.writeHead(502, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: false, error: `upstream unreachable: ${err.message}` }))
    })
    req.pipe(proxyReq)
  }

  const upstreamDescribe = () => {
    const envelope = {
      type: 'client-request',
      rpcId: crypto.randomUUID(),
      method: 'host.describe',
      payload: {},
    }
    const body = JSON.stringify(envelope)
    const upstreamUrl = new URL(upstream)
    const lib = upstreamUrl.protocol === 'https:' ? https : http
    return new Promise((resolve) => {
      const req = lib.request(
        {
          protocol: upstreamUrl.protocol,
          hostname: upstreamUrl.hostname,
          port: upstreamUrl.port || (upstreamUrl.protocol === 'https:' ? 443 : 80),
          method: 'POST',
          path: '/api/host.describe',
          headers: {
            'content-type': 'application/json',
            'content-length': Buffer.byteLength(body),
            host: upstreamUrl.host,
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

  const upstreamProbe = async () => {
    const result = await upstreamDescribe()
    return typeof result === 'object' && result !== null && result.error === undefined
  }

  // -- phone -> PC upload ---------------------------------------------------
  let uploadResolved = null
  let uploadBase = null

  const resolveUploadDir = async () => {
    if (uploadResolved) return uploadResolved
    let cwd = null
    try {
      const d = await upstreamDescribe()
      if (d && typeof d.cwd === 'string' && d.cwd) cwd = d.cwd
    } catch { /* upstream down: fall back */ }
    const resolved = uploadDir
      ? path.resolve(uploadDir)
      : path.join(cwd ?? process.cwd(), 'uploads')
    uploadBase = cwd
    fs.mkdirSync(resolved, { recursive: true })
    log(`upload dir: ${resolved}`)
    uploadResolved = resolved
    return resolved
  }

  const sanitizeUploadName = (raw) => {
    const cleaned = String(raw ?? '')
      .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_')
      .replace(/^\.+$/, '_')
      .trim()
      .slice(0, 120)
    return cleaned || `file-${Date.now()}`
  }

  const uniqueUploadPath = (dir, name) => {
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

  const handleUpload = async (req, res, url) => {
    const ip = req.socket.remoteAddress ?? '?'
    if (!isAuthorized(req, url)) {
      res.writeHead(401, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: false, error: 'unauthorized' }))
      log(`${ip} ${req.method} /upload -> 401`)
      return
    }
    try {
      const requestedCwd = url.searchParams.get('cwd') ?? ''
      let dir
      let relBase
      if (requestedCwd && path.isAbsolute(requestedCwd)) {
        relBase = path.resolve(requestedCwd)
        dir = path.join(relBase, 'uploads')
      } else {
        await resolveUploadDir()
        dir = uploadResolved
        relBase = uploadBase
      }
      fs.mkdirSync(dir, { recursive: true })
      const name = sanitizeUploadName(url.searchParams.get('name'))
      const target = uniqueUploadPath(dir, name)
      const limit = (Number.isFinite(uploadLimitMB) ? uploadLimitMB : 512) * 1024 * 1024
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
        log(`${ip} upload failed: ${message}`)
      }

      out.on('error', () => fail(500, 'disk write failed'))
      req.on('error', () => fail(400, 'request aborted'))
      req.on('data', (chunk) => {
        if (settled) return
        received += chunk.length
        if (received > limit) {
          fail(413, `upload exceeds the ${uploadLimitMB} MB limit`)
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
        log(`${ip} upload -> ${target} (${received} bytes)`)
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(JSON.stringify({ ok: true, name: path.basename(target), path: target, relPath: rel, size: received }))
      })
    } catch (err) {
      log(`upload handling failed: ${err.message}`)
      if (!res.headersSent) res.writeHead(500, { 'content-type': 'application/json' })
      res.end(JSON.stringify({ ok: false, error: err.message }))
    }
  }

  // -- WS bridge -------------------------------------------------------------
  const bridgeSocket = (clientWs, upstreamUrl) => {
    const upstreamWs = new WebSocket(upstreamUrl, { perMessageDeflate: true })
    upstreamWs.on('open', () => {
      clientWs.on('message', (data, isBinary) => upstreamWs.send(data, { binary: isBinary }))
      upstreamWs.on('message', (data, isBinary) => clientWs.send(data, { binary: isBinary }))
    })
    upstreamWs.on('close', (code) => clientWs.close(1011, 'upstream closed'))
    upstreamWs.on('error', (err) => {
      log(`upstream socket error: ${err.message}`)
      const reason = `upstream ${err.code ?? 'error'} ${err.message ?? ''}`.trim().slice(0, 120)
      clientWs.close(1011, reason)
    })
    clientWs.on('close', () => {
      try { upstreamWs.close() } catch { /* already closed */ }
    })
  }

  // -- server ----------------------------------------------------------------
  const server = tls
    ? https.createServer({ cert: tls.cert, key: tls.key })
    : http.createServer()

  const wss = new WebSocketServer({ noServer: true, perMessageDeflate: true })

  server.keepAliveTimeout = 30_000
  server.headersTimeout = 31_000

  server.on('request', (req, res) => {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
    const ip = req.socket.remoteAddress ?? '?'
    const finish = (code, message) => {
      log(`${ip} ${req.method} ${url.pathname} -> ${code} ${message ?? ''}`)
    }
    try {
      if (req.method === 'GET' && (url.pathname === '/ident' || url.pathname === '/health')) {
        const base = {
          ok: true,
          gateway: { name: NAME, version: VERSION },
          scheme: tls ? 'https' : 'http',
        }
        if (fingerprint) base.tlsFingerprint = fingerprint
        // Live read: the plugin's tunnel manager mutates config.publicBaseUrl
        // when the NAT-traversal URL comes up, and /ident must reflect it.
        if (config.publicBaseUrl) base.public = { url: config.publicBaseUrl }
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
      log(`request handling failed: ${err.message}`)
      if (!res.headersSent) res.writeHead(500)
      res.end()
    }
  })

  server.on('upgrade', (req, socket, head) => {
    const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
    const ip = socket.remoteAddress ?? '?'
    const allowed = url.pathname === '/api/events.mux' || url.pathname === '/api/events.host'
    // Well-formed HTTP rejections (status + Content-Length + body) so reverse
    // proxies and NAT-traversal edges can forward them as-is; a bare socket
    // destroy or a header-only 401 gets translated into a confusing 502.
    const reject = (code, payload) => {
      const body = JSON.stringify(payload)
      socket.write(
        `HTTP/1.1 ${code}\r\n` +
        'Content-Type: application/json; charset=utf-8\r\n' +
        `Content-Length: ${Buffer.byteLength(body)}\r\n` +
        'Connection: close\r\n\r\n' +
        body,
      )
      socket.end()
    }
    if (!allowed) {
      reject(404, { ok: false, error: 'not found' })
      log(`${ip} upgrade ${url.pathname} -> refused (path)`)
      return
    }
    if (!isAuthorized(req, url)) {
      reject(401, { ok: false, error: 'unauthorized' })
      log(`${ip} upgrade ${url.pathname} -> 401 unauthorized`)
      return
    }
    wss.handleUpgrade(req, socket, head, (clientWs) => {
      const upstreamUrl = `${upstream.replace(/^http/, 'ws')}${url.pathname}${url.search}`
      log(`${ip} upgrade ${url.pathname} -> bridged`)
      bridgeSocket(clientWs, upstreamUrl)
    })
  })

  server.on('error', (err) => {
    log(`server error: ${err.message}`)
  })

  const listening = new Promise((resolve, reject) => {
    server.once('listening', resolve)
    server.once('error', reject)
    server.listen(port, host)
  })

  const close = () => new Promise((resolve) => {
    for (const client of wss.clients) {
      try { client.close() } catch { /* already closed */ }
    }
    server.close(() => resolve())
    // Fallback for open keep-alive sockets that keep server.close() pending.
    setTimeout(resolve, 3000).unref()
  })

  return { server, wss, listening, close }
}
