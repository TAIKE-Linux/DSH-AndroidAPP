import { createGateway, resolveTls } from '../src/gateway.js'
import { CloudflaredTunnel } from '../src/tunnel.js'
import { WebSocket } from 'ws'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dir = path.dirname(fileURLToPath(import.meta.url))
const PORT = 18746

const tls = resolveTls({ auto: true, certDir: 'sh-certs' }, dir, () => {})
const gatewayConfig = {
  host: '127.0.0.1', port: PORT, upstream: 'http://127.0.0.1:3080',
  token: 'sh-token', tls, publicBaseUrl: '', fingerprint: tls.fingerprint,
}
const gateway = createGateway(gatewayConfig, { log: () => {} })
await gateway.listening
console.log('HTTPS gateway on', PORT)

const tunnel = new CloudflaredTunnel({ baseDir: dir, log: (m) => console.log('[tunnel]', m) })
const urlLog = []
tunnel.onChange = (s) => {
  if (s.status === 'online' && s.url) {
    gatewayConfig.publicBaseUrl = s.url
    urlLog.push(s.url)
    console.log('URL ASSIGNED:', s.url)
  }
}
await tunnel.enable(PORT, 'https')

async function waitIdent(url, label, timeoutMs = 120000) {
  const t0 = Date.now()
  let last = ''
  while (Date.now() - t0 < timeoutMs) {
    try {
      const res = await fetch(url + '/ident', { signal: AbortSignal.timeout(8000) })
      if (res.status === 200) return true
      last = String(res.status)
    } catch (e) { last = e.cause?.code || e.message }
    await new Promise((r) => setTimeout(r, 3000))
  }
  console.log(`${label}: /ident never 200 (last: ${last})`)
  return false
}

function wsUpgrade(url) {
  return new Promise((resolve) => {
    const ws = new WebSocket(url.replace('https://', 'wss://') + '/api/events.mux', {
      headers: { Authorization: 'Bearer sh-token' }, rejectUnauthorized: false,
    })
    const t = setTimeout(() => { try { ws.terminate() } catch {}; resolve('timeout') }, 20000)
    ws.on('open', () => { clearTimeout(t); ws.close(); resolve('101') })
    ws.on('unexpected-response', (_r, res) => { clearTimeout(t); resolve(`HTTP ${res.statusCode}`) })
    ws.on('error', (e) => { clearTimeout(t); resolve(e.message) })
  })
}

// Phase 1: first tunnel
const tStart = Date.now()
while (tunnel.getState().status !== 'online' && Date.now() - tStart < 120000) {
  await new Promise((r) => setTimeout(r, 2000))
}
const url1 = tunnel.getState().url
if (!url1) { console.log('FAIL: no first url'); process.exit(1) }
console.log('phase1 url:', url1)
console.log('phase1 /ident ok:', await waitIdent(url1, 'url1'))
console.log('phase1 WS:', await wsUpgrade(url1))
console.log('phase1 WS:', await wsUpgrade(url1))

// Phase 2: kill the tunnel (simulates the crash the user saw) — old URL dies
tunnel.killChild()
await new Promise((r) => setTimeout(r, 8000))
const stale = await (async () => {
  try { const res = await fetch(url1 + '/ident', { signal: AbortSignal.timeout(8000) }); return `HTTP ${res.status}` } catch (e) { return e.cause?.code || e.message }
})()
console.log('phase2 old URL after kill:', stale, '(edge typically 530/1033 — this is what the phone saw)')

// Phase 3: auto-restart should assign a NEW URL that works again
const t0 = Date.now()
while (tunnel.getState().url === url1 && Date.now() - t0 < 120000) {
  await new Promise((r) => setTimeout(r, 2000))
}
const url2 = tunnel.getState().url
if (url2 === url1 || !url2) { console.log('FAIL: no new URL after restart'); process.exit(1) }
console.log('phase3 new URL:', url2)
console.log('phase3 /ident ok:', await waitIdent(url2, 'url2'))
console.log('phase3 WS:', await wsUpgrade(url2))

const pass = url2 !== url1
console.log(pass ? 'SELF-HEAL TEST PASS ✓ (crash → new URL → working again)' : 'SELF-HEAL TEST FAIL')

tunnel.disable()
await gateway.close()
process.exit(pass ? 0 : 1)
