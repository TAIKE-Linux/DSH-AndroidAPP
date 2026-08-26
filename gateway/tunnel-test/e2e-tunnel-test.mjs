import { createGateway } from '../src/gateway.js'
import { CloudflaredTunnel } from '../src/tunnel.js'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dir = path.dirname(fileURLToPath(import.meta.url))
const PORT = 18744
const gatewayConfig = {
  host: '127.0.0.1',
  port: PORT,
  upstream: 'http://127.0.0.1:9',
  token: 'e2e-test-token',
  tls: null,
  publicBaseUrl: '',
}

const gateway = createGateway(gatewayConfig, { log: () => {} })
await gateway.listening
console.log('gateway listening on', PORT)

const tunnel = new CloudflaredTunnel({ baseDir: dir, log: (m) => console.log('[tunnel]', m) })
tunnel.onChange = (state) => {
  if (state.status === 'online' && state.url) {
    gatewayConfig.publicBaseUrl = state.url
    console.log('publicBaseUrl ->', state.url)
  }
}
await tunnel.enable(PORT)

// wait for online
const t0 = Date.now()
while (tunnel.getState().status !== 'online' && Date.now() - t0 < 60000) {
  await new Promise((r) => setTimeout(r, 1000))
}
const state = tunnel.getState()
console.log('tunnel state:', JSON.stringify(state))
if (state.status !== 'online') { console.log('FAIL: tunnel did not come online'); process.exit(1) }

// wait for edge to answer (DNS + registration, retry up to 90s)
let ident = null
for (let i = 0; i < 30; i++) {
  try {
    const res = await fetch(state.url + '/ident', { signal: AbortSignal.timeout(8000) })
    if (res.status === 200) { ident = await res.json(); break }
    console.log('ident attempt', i + 1, 'status', res.status)
  } catch (e) {
    console.log('ident attempt', i + 1, 'failed:', e.cause?.code || e.message)
  }
  await new Promise((r) => setTimeout(r, 3000))
}

if (!ident) { console.log('FAIL: /ident unreachable via tunnel'); cleanup(1) }
console.log('ident via public URL:', JSON.stringify(ident))
const ok =
  ident?.gateway?.name === 'dsh-remote-gateway' &&
  ident?.public?.url === state.url
console.log(ok ? 'E2E TUNNEL TEST PASS ✓ (public /ident + advertised public URL)' : 'E2E TUNNEL TEST FAIL')

// also verify bearer auth still gates the public path
const noAuth = await fetch(state.url + '/api/session.list', { method: 'POST' })
console.log('unauth via public URL status:', noAuth.status, '(expect 401)')

await cleanup(ok && noAuth.status === 401 ? 0 : 1)

async function cleanup(code) {
  tunnel.disable()
  await gateway.close()
  process.exit(code)
}
