// Quick WS bridge test: node tools/test-ws.mjs
import wsPkg from '../gateway/node_modules/ws/index.js'
const { WebSocket } = wsPkg

const token = process.argv[2] ?? 'test-token-abc123'
const url = `ws://127.0.0.1:12787/api/events.mux?token=${encodeURIComponent(token)}`
const ws = new WebSocket(url)

let frames = 0
const timer = setTimeout(() => {
  console.log(`received ${frames} frames in 8s; closing`)
  ws.close()
  process.exit(frames > 0 ? 0 : 1)
}, 8000)

ws.on('open', () => console.log('WS OPEN'))
ws.on('message', (data) => {
  frames += 1
  const msg = JSON.parse(data.toString())
  const payload = msg.payload ?? {}
  if (frames <= 6) console.log(`frame ${frames}: method=${msg.method} rpcId=${msg.rpcId?.slice(0, 8)} type=${payload.type ?? '?'} session=${payload.sessionId ?? '-'} lastSeq=${payload.lastSeq ?? ''}`)
})
ws.on('error', (err) => {
  console.log(`WS ERROR: ${err.message}`)
  clearTimeout(timer)
  process.exit(1)
})
ws.on('close', (code) => {
  console.log(`WS CLOSED code=${code} frames=${frames}`)
  clearTimeout(timer)
  process.exit(frames > 0 ? 0 : 1)
})
