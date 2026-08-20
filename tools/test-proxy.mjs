// Proxy connectivity test: node tools/test-proxy.mjs [port]
import net from 'node:net'
import tls from 'node:tls'

const port = process.argv[2] ?? '10808'

function openTunnel(proxyPort, host, targetPort) {
  return new Promise((resolve, reject) => {
    const socket = net.connect(Number(proxyPort), '127.0.0.1', () => {
      socket.write(`CONNECT ${host}:${targetPort} HTTP/1.1\r\nHost: ${host}:${targetPort}\r\n\r\n`)
      let buf = ''
      socket.on('data', (d) => {
        buf += d.toString()
        if (buf.includes('\r\n\r\n')) {
          const status = buf.split('\r\n')[0]
          if (!status.includes('200')) {
            socket.destroy()
            reject(new Error(`proxy refused: ${status}`))
            return
          }
          socket.removeAllListeners('data')
          resolve(socket)
        }
      })
    })
    socket.on('error', reject)
    setTimeout(() => reject(new Error('connect timeout')), 5000)
  })
}

const test = async () => {
  const t0 = Date.now()
  try {
    const raw = await openTunnel(port, 'dl.google.com', 443)
    let bytes = 0
    let finished = false
    const tlsSocket = tls.connect({ socket: raw, servername: 'dl.google.com' }, () => {
      tlsSocket.write(
        'GET /android/repository/repository2-3.xml HTTP/1.1\r\n' +
          'Host: dl.google.com\r\nRange: bytes=0-1023\r\nConnection: close\r\n\r\n',
      )
    })
    tlsSocket.on('data', (d) => {
      bytes += d.length
    })
    tlsSocket.on('end', () => {
      finished = true
      console.log(`proxy 127.0.0.1:${port} -> OK, ${bytes} bytes in ${Date.now() - t0}ms`)
      process.exit(0)
    })
    tlsSocket.on('close', () => {
      if (!finished) {
        console.log(`proxy 127.0.0.1:${port} -> TLS closed early (${bytes} bytes)`)
        process.exit(1)
      }
    })
    tlsSocket.on('error', (e) => {
      console.log(`proxy 127.0.0.1:${port} -> TLS error: ${e.message}`)
      process.exit(1)
    })
    setTimeout(() => {
      console.log(`proxy 127.0.0.1:${port} -> timeout`)
      process.exit(1)
    }, 12000)
  } catch (e) {
    console.log(`proxy 127.0.0.1:${port} -> FAILED: ${e.message}`)
    process.exit(1)
  }
}

test()
