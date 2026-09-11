import http from 'node:http'
import fs from 'node:fs'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dir = path.dirname(fileURLToPath(import.meta.url))
const PORT = 18742
const logFile = path.join(dir, 'cloudflared.log')

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain' })
  res.end(`hello-tunnel-${req.url}`)
})
await new Promise((r) => server.listen(PORT, '127.0.0.1', r))
console.log('local server on', PORT)

fs.writeFileSync(logFile, '')
const outFd = fs.openSync(logFile, 'a')
const child = spawn(path.join(dir, 'cloudflared.exe'), [
  'tunnel', '--url', `http://127.0.0.1:${PORT}`,
  '--no-autoupdate', '--protocol', 'http2', '--loglevel', 'info',
], { cwd: dir, stdio: ['ignore', outFd, outFd] })

let url = null
let done = false
const urlRe = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/

async function testPublic() {
  // the edge needs a few seconds before it answers; retry with backoff
  for (let i = 0; i < 12; i++) {
    try {
      const res = await fetch(url + '/check', { headers: { accept: 'text/plain' }, signal: AbortSignal.timeout(8000) })
      const body = await res.text()
      console.log('attempt', i + 1, 'status:', res.status, 'body:', body)
      console.log(body === 'hello-tunnel-/check' ? 'TUNNEL TEST PASS ✓' : 'TUNNEL TEST FAIL (body mismatch)')
      return
    } catch (e) {
      console.log('attempt', i + 1, 'failed:', e.cause?.code || e.message)
      await new Promise((r) => setTimeout(r, 3000))
    }
  }
  console.log('TUNNEL TEST FAIL (no successful public fetch)')
}

function tryParse() {
  if (done) return
  const output = fs.readFileSync(logFile, 'utf8')
  const m = output.match(urlRe)
  if (m && !url) {
    url = m[0]
    console.log('TUNNEL URL:', url)
    done = true
    testPublic().finally(cleanup)
  }
}

const poll = setInterval(tryParse, 1000)
const deadline = setTimeout(() => {
  if (!done) {
    console.log('no URL after 60s. log tail:', fs.readFileSync(logFile, 'utf8').slice(-800))
    cleanup()
  }
}, 60000)

function cleanup() {
  clearInterval(poll)
  clearTimeout(deadline)
  try { child.kill() } catch {}
  server.close(() => process.exit(0))
  setTimeout(() => process.exit(0), 2000).unref()
}
