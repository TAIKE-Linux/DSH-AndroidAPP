// Builds lib/client.js for the DSH client bundle by inlining the panel body
// (src/client-panel.js) into a single window.__ModuleLoader__.load(...) factory.
// The QR SVG is generated host-side (src/plugin.js uses the `qrcode` package),
// so no QR encoder needs to ship in the browser bundle.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

const panel = fs.readFileSync(path.join(root, 'src/client-panel.js'), 'utf8')

const client = `window.__ModuleLoader__.load({
  id: "@dsh-external/dsh-remote-gateway",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
${panel}
    return module.exports;
  }
});
`

const out = path.join(root, 'lib/client.js')
fs.writeFileSync(out, client)
console.log('built', out, `(${client.length} bytes)`)
