// Parallel ranged download through the local HTTP proxy (NODE_USE_ENV_PROXY=1
// + HTTPS_PROXY must be set by the caller). The proxy throttles each
// connection, so throughput scales with the number of parallel ranges.
// Usage: node tools/fetch-parallel.mjs <url> <outfile> [parts]
import fs from 'node:fs'

const url = process.argv[2]
const out = process.argv[3]
const parts = Number(process.argv[4] ?? 16)
if (!url || !out) {
  console.error('usage: fetch-parallel.mjs <url> <outfile> [parts]')
  process.exit(2)
}

const t0 = Date.now()
const head = await fetch(url, { method: 'HEAD' })
const total = Number(head.headers.get('content-length') ?? 0)
if (!head.ok || !total) {
  console.log(`HEAD failed: ${head.status}`)
  process.exit(1)
}
console.log(`total ${total} bytes, ${parts} parts`)

const chunk = Math.ceil(total / parts)
const jobs = []
for (let i = 0; i < parts; i++) {
  const start = i * chunk
  if (start >= total) break
  const end = Math.min(total - 1, start + chunk - 1)
  jobs.push((async () => {
    for (let attempt = 1; attempt <= 5; attempt++) {
      try {
        const res = await fetch(url, { headers: { range: `bytes=${start}-${end}` } })
        if (res.status !== 206 && res.status !== 200) throw new Error(`status ${res.status}`)
        const fd = fs.openSync(`${out}.part${i}`, 'w')
        let got = 0
        try {
          for await (const piece of res.body) {
            const buf = Buffer.from(piece)
            fs.writeSync(fd, buf)
            got += buf.length
          }
        } finally {
          fs.closeSync(fd)
        }
        console.log(`part ${i}: ${got} bytes`)
        return got
      } catch (e) {
        console.log(`part ${i} attempt ${attempt} failed: ${e.message}`)
        if (attempt === 5) throw e
        await new Promise((r) => setTimeout(r, 1500))
      }
    }
  })())
}

const sizes = await Promise.all(jobs)
const fd = fs.openSync(out, 'w')
for (let i = 0; i < sizes.length; i++) {
  const buf = fs.readFileSync(`${out}.part${i}`)
  fs.writeSync(fd, buf)
  fs.unlinkSync(`${out}.part${i}`)
}
fs.closeSync(fd)
console.log(`done: ${fs.statSync(out).size} bytes in ${Date.now() - t0}ms`)
if (fs.statSync(out).size !== total) {
  console.error('SIZE MISMATCH — re-run to retry')
  process.exit(1)
}
