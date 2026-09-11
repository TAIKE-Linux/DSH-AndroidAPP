// Thin lib/ shim so the DSH loader sees a conventional lib/index.js entry,
// while the actual plugin logic stays in src/plugin.js (shared with the
// standalone CLI's src/index.js via src/gateway.js).
export { name, inject, apply } from '../src/plugin.js'
