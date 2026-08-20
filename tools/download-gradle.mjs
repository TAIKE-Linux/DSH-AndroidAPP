// Robust Gradle distribution downloader (retry + resume), run with: node tools/download-gradle.mjs
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const dest = path.join(__dirname, 'gradle-8.11.1-bin.zip')
const url = 'https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function fetchWithRedirects(u, redirectsLeft = 5) {
  const res = await fetch(u, { redirect: 'manual', headers: { 'user-agent': 'dsh-android-tooling/0.1' } })
  if ([301, 302, 303, 307, 308].includes(res.status)) {
    if (redirectsLeft <= 0) throw new Error('too many redirects')
    const loc = res.headers.get('location')
    if (!loc) throw new Error('redirect without location')
    return fetchWithRedirects(new URL(loc, u).toString(), redirectsLeft - 1)
  }
  return res
}

async function main() {
  let attempt = 0
  for (;;) {
    attempt += 1
    const have = fs.existsSync(dest) ? fs.statSync(dest).size : 0
    const headers = { 'user-agent': 'dsh-android-tooling/0.1' }
    if (have > 0) headers.range = `bytes=${have}-`
    try {
      console.log(`[attempt ${attempt}] requesting${have > 0 ? ` (resume at ${have})` : ''} ...`)
      const res = await fetchWithRedirects(url)
      if (res.status === 200) {
        if (have > 0) fs.truncateSync(dest, 0)
        const file = fs.createWriteStream(dest, have > 0 ? { flags: 'w' } : {})
        const reader = res.body.getReader()
        let total = have
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          await new Promise((resolve, reject) => file.write(value, (e) => (e ? reject(e) : resolve())))
          total += value.length
        }
        await new Promise((resolve) => file.end(resolve))
        console.log(`downloaded ${total} bytes -> ${dest}`)
        return
      }
      if (res.status === 206) {
        const file = fs.createWriteStream(dest, { flags: 'a' })
        const reader = res.body.getReader()
        let total = have
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          await new Promise((resolve, reject) => file.write(value, (e) => (e ? reject(e) : resolve())))
          total += value.length
        }
        await new Promise((resolve) => file.end(resolve))
        console.log(`downloaded (resumed) ${total} bytes -> ${dest}`)
        return
      }
      console.log(`unexpected status ${res.status}, retrying`)
    } catch (err) {
      console.log(`attempt ${attempt} failed: ${err.message}`)
    }
    if (attempt >= 8) {
      console.error('giving up')
      process.exit(1)
    }
    await sleep(2000 * attempt)
  }
}

main()
