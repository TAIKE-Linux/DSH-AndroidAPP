// Measure gateway gzip end-to-end with raw (undecoded) byte counts.
// Usage: node tools/measure-gzip.mjs
const base = 'http://127.0.0.1:12787/api/session.history'
const token = 'test-token-abc123'
const body = JSON.stringify({
  type: 'client-request', rpcId: 't-gz2', method: 'session.history',
  payload: { sessionId: 'session-b5083776-aec5-4667-94b6-01194717d63a', maxMessages: 10 },
})

async function fetchRaw(gzip) {
  const res = await fetch(base, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${token}`,
      ...(gzip ? { 'accept-encoding': 'gzip' } : {}),
    },
    body,
  })
  const buf = Buffer.from(await res.arrayBuffer())
  return { status: res.status, encoding: res.headers.get('content-encoding'), bytes: buf.length }
}

const plain = await fetchRaw(false)
const gz = await fetchRaw(true)
console.log(`plain:  ${plain.bytes} bytes (status ${plain.status})`)
console.log(`gzip:   ${gz.bytes} bytes (status ${gz.status}, encoding=${gz.encoding})`)
console.log(`ratio:  ${((1 - gz.bytes / plain.bytes) * 100).toFixed(1)}% smaller`)
