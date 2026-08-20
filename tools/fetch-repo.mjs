// Fetch a URL through the local HTTP proxy using Node's global fetch.
// Usage: node tools/fetch-repo.mjs [url] [outfile]
// Requires NODE_USE_ENV_PROXY=1 and HTTPS_PROXY set (default http://127.0.0.1:10808).
import fs from 'node:fs'

const url = process.argv[2] ?? 'https://dl.google.com/android/repository/repository2-3.xml'
const out = process.argv[3]

const t0 = Date.now()
const res = await fetch(url)
console.log(`status ${res.status}, ${res.headers.get('content-length') ?? '?'} bytes, ${Date.now() - t0}ms`)
if (!res.ok) {
  console.log(await res.text().catch(() => ''))
  process.exit(1)
}
const body = Buffer.from(await res.arrayBuffer())
if (out) {
  fs.writeFileSync(out, body)
  console.log(`saved ${out} (${body.length} bytes)`)
} else {
  process.stdout.write(body)
}
