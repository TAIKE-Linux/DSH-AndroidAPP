import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const out = path.join(path.dirname(fileURLToPath(import.meta.url)), 'cloudflared.exe')
const url = 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe'
console.log('downloading', url)
const res = await fetch(url, { redirect: 'follow' })
if (!res.ok) throw new Error('HTTP ' + res.status)
const buf = Buffer.from(await res.arrayBuffer())
fs.writeFileSync(out, buf)
console.log('saved', out, buf.length, 'bytes')
