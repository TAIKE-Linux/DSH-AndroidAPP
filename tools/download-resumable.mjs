// Resumable HTTPS download through the local CONNECT proxy (mirrors
// test-proxy.mjs tunnel mechanics, which is the reliable path on this host).
// Usage: node tools/download-resumable.mjs <url> <outfile> [proxyPort]
// Writes progressively; resumes from the current file size with Range.
import net from 'node:net'
import tls from 'node:tls'
import fs from 'node:fs'

const url = process.argv[2]
const out = process.argv[3]
const proxyPort = Number(process.argv[4] ?? 10808)
if (!url || !out) {
  console.error('usage: download-resumable.mjs <url> <outfile> [proxyPort]')
  process.exit(2)
}
const u = new URL(url)
const totalHeader = `GET ${u.pathname}${u.search} HTTP/1.1\r\nHost: ${u.host}\r\nUser-Agent: dsh-tools/1\r\nConnection: close\r\n`

function openTunnel(proxyPort, host, targetPort) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(proxyPort, '127.0.0.1', () => {
      socket.write(`CONNECT ${host}:${targetPort} HTTP/1.1\r\nHost: ${host}:${targetPort}\r\n\r\n`)
      let buf = ''
      socket.on('data', (d) => {
        buf += d.toString('latin1')
        if (buf.includes('\r\n\r\n')) {
          const status = buf.split('\r\n')[0]
          if (!status.includes('200')) {
            socket.destroy()
            reject(new Error(`proxy refused: ${status}`))
            return
          }
          socket.removeAllListeners('data')
          resolve({ socket, head: Buffer.from(buf.split('\r\n\r\n')[1] ?? '', 'latin1') })
        }
      })
    })
    socket.on('error', reject)
    setTimeout(() => reject(new Error('tunnel timeout')), 15000)
  })
}

async function downloadOnce(offset) {
  const { socket, head } = await openTunnel(proxyPort, u.host, u.port || 443)
  const req = new Promise((resolve, reject) => {
    let headerDone = false
    let headerBuf = ''
    let status = 0
    let length = null
    let got = 0
    const tlsSock = tls.connect({ socket, servername: u.host })
    tlsSock.on('error', (e) => reject(new Error(`tls: ${e.message}`)))
    tlsSock.on('secureConnect', () => {
      const range = offset > 0 ? `Range: bytes=${offset}-\r\n` : ''
      tlsSock.write(totalHeader + range + '\r\n')
    })
    tlsSock.on('data', (d) => {
      if (!headerDone) {
        headerBuf += d.toString('latin1')
        const idx = headerBuf.indexOf('\r\n\r\n')
        if (idx < 0) return
        const headText = headerBuf.slice(0, idx)
        status = Number(headText.split('\r\n')[0].split(' ')[1])
        const lm = headText.match(/content-length:\s*(\d+)/i)
        if (lm) length = Number(lm[1])
        const body = Buffer.from(headerBuf.slice(idx + 4), 'latin1')
        headerDone = true
        const fd = fs.openSync(out, offset > 0 ? 'a' : 'w')
        try {
          if (body.length) { fs.writeSync(fd, body); got += body.length }
          tlsSock.on('data', (chunk) => {
            fs.writeSync(fd, chunk)
            got += chunk.length
            if (got % (8 * 1024 * 1024) < 65536) console.log(`  got ${offset + got} bytes`)
          })
          tlsSock.on('end', () => resolve({ status, got, fd }))
          tlsSock.on('close', () => resolve({ status, got, fd }))
        } catch (e) {
          reject(e)
        }
      }
    })
    setTimeout(() => reject(new Error('download stall timeout')), 120000)
  })
  return req
}

const start = fs.existsSync(out) ? fs.statSync(out).size : 0
console.log(`downloading ${url} -> ${out} (resume from ${start})`)
try {
  const { status, got } = await downloadOnce(start)
  console.log(`done: status ${status}, +${got} bytes, total ${fs.statSync(out).size}`)
  if (status !== 200 && status !== 206) process.exit(1)
} catch (e) {
  console.error(`FAILED: ${e.message} (partial ${fs.existsSync(out) ? fs.statSync(out).size : 0} bytes; re-run to resume)`)
  process.exit(1)
}
