// Streaming resumable download through the local HTTP proxy using Node's
// global fetch (NODE_USE_ENV_PROXY=1 + HTTPS_PROXY must be set by the caller).
// Unlike fetch-repo.mjs this streams to disk, so a stalled proxy connection
// leaves a resumable partial file instead of losing the whole body.
// Usage: node tools/fetch-stream.mjs <url> <outfile> [maxRetries]
import fs from 'node:fs'

const url = process.argv[2]
const out = process.argv[3]
const maxRetries = Number(process.argv[4] ?? 8)
if (!url || !out) {
  console.error('usage: fetch-stream.mjs <url> <outfile> [maxRetries]')
  process.exit(2)
}

const total = fs.existsSync(out) ? fs.statSync(out).size : 0
const expect = (size) => total + size

for (let attempt = 1; attempt <= maxRetries; attempt++) {
  const offset = fs.existsSync(out) ? fs.statSync(out).size : 0
  const t0 = Date.now()
  try {
    const res = await fetch(url, { headers: offset > 0 ? { range: `bytes=${offset}-` } : {} })
    console.log(`attempt ${attempt}: status ${res.status} len=${res.headers.get('content-length') ?? '?'} (offset ${offset})`)
    if (res.status === 416) {
      // Range past EOF: server thinks we already have everything.
      if (res.headers.get('content-range')?.startsWith(`bytes */${offset}`)) break
      throw new Error('unexpected 416')
    }
    if (res.status !== 200 && res.status !== 206) {
      console.log(await res.text().catch(() => ''))
      process.exit(1)
    }
    const fd = fs.openSync(out, offset > 0 ? 'a' : 'w')
    let got = 0
    try {
      for await (const chunk of res.body) {
        const buf = Buffer.from(chunk)
        fs.writeSync(fd, buf)
        got += buf.length
        if (got % (16 * 1024 * 1024) < 65536) console.log(`  got ${offset + got} bytes (${Math.round(got / Math.max(1, (Date.now() - t0) / 1000) / 1024)} KB/s)`)
      }
      console.log(`done: ${offset + got} bytes in ${Date.now() - t0}ms`)
      fs.closeSync(fd)
      break
    } catch (e) {
      fs.closeSync(fd)
      throw e
    }
  } catch (e) {
    const partial = fs.existsSync(out) ? fs.statSync(out).size : 0
    console.log(`attempt ${attempt} failed: ${e.message} (partial ${partial} bytes)`)
    if (attempt === maxRetries) {
      console.error(`giving up; re-run to resume from ${partial}`)
      process.exit(1)
    }
    await new Promise((r) => setTimeout(r, 2000))
  }
}
console.log(`final size: ${fs.statSync(out).size}`)
