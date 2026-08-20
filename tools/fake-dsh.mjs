// Fake DeepSeek Harness for gateway supervisor tests: answers POST /api/* with
// a valid server-response envelope (host.describe shape). Usage:
//   node tools/fake-dsh.mjs [port] [--exit-after <seconds>]
import http from 'node:http'

const args = process.argv.slice(2)
const port = Number(args[0] ?? 9999)
const exitIdx = args.indexOf('--exit-after')
const ttl = exitIdx >= 0 ? Number(args[exitIdx + 1] ?? 0) : 0

const server = http
  .createServer((req, res) => {
    if (req.method === 'POST' && req.url.startsWith('/api/')) {
      let body = ''
      req.on('data', (c) => (body += c))
      req.on('end', () => {
        let rpcId = 'x'
        try { rpcId = JSON.parse(body).rpcId } catch { /* keep 'x' */ }
        res.writeHead(200, { 'content-type': 'application/json' })
        res.end(
          JSON.stringify({
            type: 'server-response',
            rpcId,
            result: {
              ok: true,
              value: {
                version: 'fake',
                cwd: process.cwd(),
                provider: 'fake',
                model: 'fake',
                attachedSessions: 0,
                canOpenPath: false,
              },
            },
          }),
        )
      })
    } else {
      res.writeHead(404)
      res.end()
    }
  })
  .listen(port, () => console.log(`fake-dsh listening on ${port}`))

if (ttl > 0) {
  setTimeout(() => {
    console.log(`fake-dsh exiting after ${ttl}s (simulated crash)`)
    server.close(() => process.exit(1))
  }, ttl * 1000)
}
