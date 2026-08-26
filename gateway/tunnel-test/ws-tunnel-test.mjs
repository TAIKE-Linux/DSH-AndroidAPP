import { createGateway, resolveTls } from '../src/gateway.js'
import { CloudflaredTunnel } from '../src/tunnel.js'
import { WebSocket } from 'ws'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dir = path.dirname(fileURLToPath(import.meta.url))
const PORT = 18745

// Same shape as the real plugin: self-signed HTTPS gateway + real DSH upstream.
const tls = resolveTls({ auto: true, certDir: 'ws-test-certs' }, dir, () => {})
const gatewayConfig = {
  host: '127.0.0.1',
  port: PORT,
  upstream: 'http://127.0.0.1:3080', // real dsh web on this machine
  token: 'ws-e2e-token',
  tls,
  publicBaseUrl: '',
  fingerprint: tls.fingerprint,
}
const gateway = createGateway(gatewayConfig, { log: () => {} })
await gateway.listening
console.log('HTTPS gateway listening on', PORT, '(self-signed)')

const tunnel = new CloudflaredTunnel({ baseDir: dir, log: () => {} })
tunnel.onChange = (s) => { if (s.status === 'online') console.log('tunnel online:', s.url) }
await tunnel.enable(PORT, 'https')

const t0 = Date.now()
while (tunnel.getState().status !== 'online' && Date.now() - t0 < 60000) {
  await new Promise((r) => setTimeout(r, 1000))
}
const { url } = tunnel.getState()
if (!url) { console.log('FAIL: no tunnel url'); process.exit(1) }

// HTTP sanity via the public URL.
let identOk = false
for (let i = 0; i < 30; i++) {
  try {
    const res = await fetch(url + '/ident', { signal: AbortSignal.timeout(8000) })
    if (res.status === 200) { identOk = true; break }
    console.log('ident attempt', i + 1, res.status)
  } catch (e) { console.log('ident attempt', i + 1, e.cause?.code || e.message) }
  await new Promise((r) => setTimeout(r, 3000))
}
console.log('public /ident ok:', identOk)

// THE phone symptom: WS upgrade through the tunnel. Expect 101 (open event).
const wsResult = await new Promise((resolve) => {
  const ws = new WebSocket(url.replace('https://', 'wss://') + '/api/events.mux', {
    headers: { Authorization: 'Bearer ws-e2e-token' },
    rejectUnauthorized: false,
  })
  const timer = setTimeout(() => { try { ws.terminate() } catch {}; resolve('timeout') }, 20000)
  ws.on('open', () => { clearTimeout(timer); ws.close(); resolve('open(101)') })
  ws.on('unexpected-response', (_req, res) => { clearTimeout(timer); resolve(`unexpected-response ${res.statusCode}`) })
  ws.on('error', (e) => { clearTimeout(timer); resolve(`error ${e.message}`) })
})
console.log('WS via public tunnel:', wsResult)

// Auth gate on the public path.
const badWs = await new Promise((resolve) => {
  const ws = new WebSocket(url.replace('https://', 'wss://') + '/api/events.mux', { rejectUnauthorized: false })
  ws.on('open', () => { ws.close(); resolve('OPEN (should not happen)') })
  ws.on('unexpected-response', (_req, res) => resolve(`rejected ${res.statusCode}`))
  ws.on('error', (e) => resolve(`error ${e.message}`))
})
console.log('unauthorized WS via public tunnel:', badWs, '(expect rejected 401)')

const pass = identOk && wsResult === 'open(101)' && badWs === 'rejected 401'
console.log(pass ? 'WS TUNNEL TEST PASS ✓' : 'WS TUNNEL TEST FAIL')

tunnel.disable()
await gateway.close()
process.exit(pass ? 0 : 1)
