// Verify lightweight-context loading + beforeSeq paging against the real DSH
// through the test gateway. Mirrors exactly what the app now does.
const base = 'http://127.0.0.1:12787/api/session.history'
const token = 'test-token-abc123'
const sid = 'session-b5083776-aec5-4667-94b6-01194717d63a' // big real session

async function history(payload, gzip = true) {
  const res = await fetch(base, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${token}`,
      'accept-encoding': gzip ? 'gzip' : 'identity',
    },
    body: JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(), method: 'session.history', payload }),
  })
  // undici decodes gzip transparently; measure with identity for raw JSON size,
  // and separately report the gzip wire size via a second call with gzip.
  return { status: res.status, json: await res.json() }
}

// 1) Tail page, last-turn window (what openSession now transfers)
const tail = await history({ sessionId: sid, maxMessages: 4 })
const page = tail.json.result.value
const events = page.events
const minSeq = Math.min(...events.map((e) => e.event.seq))
const maxSeq = Math.max(...events.map((e) => e.event.seq))
const types = {}
for (const e of events) types[e.event.type] = (types[e.event.type] ?? 0) + 1
console.log(`tail(maxMessages=4): ${events.length} events, seq ${minSeq}..${maxSeq}, hasMore=${page.hasMore}`)
console.log(`  event types: ${JSON.stringify(types)}`)
console.log(`  last user question: "${events.filter(e => e.event.type === 'user/message').at(-1)?.event?.data?.content?.[0]?.text?.slice(0, 40) ?? '(none)'}..."`)

// 2) Older page via beforeSeq (what "load older" transfers)
const older = await history({ sessionId: sid, beforeSeq: minSeq, maxMessages: 20 })
const op = older.json.result.value
const oMin = Math.min(...op.events.map((e) => e.event.seq))
const oMax = Math.max(...op.events.map((e) => e.event.seq))
console.log(`older(beforeSeq=${minSeq}, 20): ${op.events.length} events, seq ${oMin}..${oMax}, hasMore=${op.hasMore}`)
console.log(`  no overlap: max older seq ${oMax} < anchor ${minSeq} -> ${oMax < minSeq}`)

// 3) Wire sizes: old behavior (50 messages) vs new (4 messages), gzipped
async function wireBytes(payload) {
  const res = await fetch(base, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${token}`,
      'accept-encoding': 'gzip',
    },
    body: JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(), method: 'session.history', payload }),
  })
  return Buffer.from(await res.arrayBuffer()).length // decompressed; use raw length below instead
}
async function rawWireBytes(payload) {
  // undici auto-decodes; use content-length of a plain (non-gzip) call to gauge
  // relative JSON sizes instead of absolute wire bytes.
  const res = await fetch(base, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: `Bearer ${token}`,
      'accept-encoding': 'identity',
    },
    body: JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(), method: 'session.history', payload }),
  })
  const b = Buffer.from(await res.arrayBuffer())
  return b.length
}
const old50 = await rawWireBytes({ sessionId: sid, maxMessages: 50 })
const new4 = await rawWireBytes({ sessionId: sid, maxMessages: 4 })
console.log(`JSON size old(50 msgs): ${(old50 / 1024).toFixed(0)} KB vs new(4 msgs): ${(new4 / 1024).toFixed(0)} KB (-${(100 - (new4 / old50) * 100).toFixed(0)}%)`)
