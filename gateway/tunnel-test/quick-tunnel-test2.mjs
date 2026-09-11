import http from 'node:http'
import fs from 'node:fs'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dir = path.dirname(fileURLToPath(import.meta.url))
const PORT = 18743
const logFile = path.join(dir, 'cloudflared2.log')

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain' })
  res.end(`hello-tunnel-${req.url}`)
})
await new Promise((r) => server.listen(PORT, '127.0.0.1', r))
console.log('local server on', PORT)

fs.writeFileSync(logFile, '')
const outFd = fs.openSync(logFile, 'a')
// default protocol (QUIC first, auto fallback), no forced http2
const child = spawn(path.join(dir, 'cloudflared.exe'), [
  'tunnel', '--url', `http://127.0.0.1:${PORT}`,
  '--no-autoupdate', '--loglevel', 'info',
], { cwd: dir, stdio: ['ignore', outFd, outFd] })

let url = null
let done = false
const urlRe = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/

async function testPublic() {
  for (let i = 0; i < 25; i++) {
    try {
      const res = await fetch(url + '/check', { headers: { accept: 'text/plain' }, signal: AbortSignal.timeout(8000) })
      const body = await res.text()
      console.log('attempt', i + 1, 'status:', res.status, 'body:', body.slice(0, 60))
      if (body === 'hello-tunnel-/check') { console.log('TUNNEL TEST PASS ✓'); return true }
    } catch (e) {
      console.log('attempt', i + 1, 'failed:', e.cause?.code || e.message)
    }
    await new Promise((r) => setTimeout(r, 4000))
  }
  return false
}

function tryParse() {
  if (done) return
  const output = fs.readFileSync(logFile, 'utf8')
  const m = output.match(urlRe)
  if (m && !url) {
    url = m[0]
    console.log('TUNNEL URL:', url)
    done = true
    testPublic().then((ok) => { console.log(ok ? 'RESULT: PASS' : 'RESULT: FAIL'); cleanup() })
  }
}

const poll = setInterval(tryParse, 1000)
const deadline = setTimeout(() => {
  if (!done) {
    console.log('no URL after 90s. log tail:', fs.readFileSync(logFile, 'utf8').slice(-900))
    cleanup()
  }
}, 90000)

function cleanup() {
  clearInterval(poll)
  clearTimeout(deadline)
  try { child.kill() } catch {}
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(0), 2000).unref()
}
