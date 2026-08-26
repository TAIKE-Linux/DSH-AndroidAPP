/**
 * CloudflaredTunnel — zero-account NAT traversal for the gateway.
 *
 * Spawns a Cloudflare Quick Tunnel (cloudflared) that publishes the local
 * gateway port as a public https://<random>.trycloudflare.com URL, so the
 * phone can reach DSH from any network (cellular included) without port
 * forwarding, VPN, or an account.
 *
 * Robustness model (all lessons from real runs on a flaky China-Telecom link):
 *  - registration can take 10-100s while the edge answers 530/1033 — the
 *    manager keeps the old URL until a NEW one is assigned;
 *  - child exits are restarted INDEFINITELY with backoff (a restart assigns a
 *    fresh URL — callers see it via onChange and update the QR/panel);
 *  - a health probe hits /ident through the public URL every 20s and force-
 *    restarts the tunnel after 3 consecutive failures (a wedged edge
 *    connection recovers on its own otherwise).
 *
 * Binary: config.binary, else <baseDir>/cloudflared-<platform>; auto-downloaded
 * from GitHub releases on first enable. Log capture goes through a FILE (not
 * piped stdio) so it works even in restricted process environments.
 */
import { spawn, spawnSync } from 'node:child_process'
import fs from 'node:fs'
import https from 'node:https'
import path from 'node:path'

const BINARIES = {
  win32: 'cloudflared-windows-amd64.exe',
  linux: 'cloudflared-linux-amd64',
}
const RELEASE_BASE = 'https://github.com/cloudflare/cloudflared/releases/latest/download/'
const URL_RE = /https:\/\/[a-z0-9-]+\.trycloudflare\.com/

export class CloudflaredTunnel {
  constructor({ binary = '', baseDir, log = () => {} }) {
    this.binary = binary
    this.baseDir = baseDir
    this.log = log
    this.child = null
    this.port = 0
    this.scheme = 'http'
    this.enabled = false
    this.restarts = 0
    this.restartTimer = null
    this.poll = null
    this.healthTimer = null
    this.healthFailures = 0
    this.state = { status: 'off', url: null, error: null } // off|downloading|starting|online|error
    this.onChange = null
  }

  getState() {
    return { ...this.state }
  }

  setState(patch) {
    this.state = { ...this.state, ...patch }
    try { this.onChange?.(this.getState()) } catch { /* listener error ignored */ }
  }

  /** Start (or resume) the tunnel for the given local port and scheme. */
  async enable(localPort, localScheme = 'http') {
    if (this.enabled) return this.getState()
    this.enabled = true
    this.port = localPort
    this.scheme = localScheme
    this.restarts = 0
    this.healthFailures = 0
    try {
      const bin = await this.ensureBinary()
      this.setState({ status: 'starting', error: null })
      this.spawn(bin)
      this.startPolling()
      this.startHealthProbe()
    } catch (err) {
      this.enabled = false
      this.setState({ status: 'error', url: null, error: err.message })
    }
    return this.getState()
  }

  disable() {
    this.enabled = false
    this.stopPolling()
    this.stopHealthProbe()
    if (this.restartTimer) { clearTimeout(this.restartTimer); this.restartTimer = null }
    this.killChild()
    this.setState({ status: 'off', url: null, error: null })
  }

  /** Kill the process (and its tree on Windows) so disable() is reliable. */
  killChild() {
    const child = this.child
    this.child = null
    if (!child) return
    // Direct signal first (works everywhere, including restricted sandboxes);
    // cloudflared is a single process, so this is usually sufficient.
    try { child.kill() } catch { /* already gone */ }
    if (process.platform === 'win32') {
      // Belt-and-suspenders: if the direct kill did not take, sweep the tree.
      const pid = child.pid
      const sweep = setTimeout(() => {
        try {
          spawnSync('taskkill', ['/pid', String(pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true })
        } catch { /* already gone */ }
      }, 2000)
      if (typeof sweep.unref === 'function') sweep.unref()
    }
  }

  spawn(bin) {
    const logFile = path.join(this.baseDir, 'cloudflared.log')
    fs.mkdirSync(this.baseDir, { recursive: true })
    fs.writeFileSync(logFile, '')
    const fd = fs.openSync(logFile, 'a')
    const isHttps = this.scheme === 'https'
    const args = [
      'tunnel',
      '--url', `${this.scheme}://127.0.0.1:${this.port}`,
      '--no-autoupdate',
      '--loglevel', 'info',
    ]
    // The plugin gateway serves HTTPS with a self-signed cert; this is a
    // loopback hop inside one machine, so cert verification is pointless —
    // without this flag cloudflared would reject the origin and the edge
    // would answer 502 Bad Gateway for every request (the phone symptom).
    if (isHttps) args.push('--no-tls-verify')
    this.child = spawn(bin, args, {
      cwd: this.baseDir,
      windowsHide: true,
      stdio: ['ignore', fd, fd],
    })
    this.child.on('error', (err) => {
      this.setState({ status: 'error', error: err.message })
    })
    this.child.on('exit', () => {
      this.child = null
      if (!this.enabled) return
      // Indefinite restart with backoff. Every restart mints a FRESH public
      // URL; onChange lets callers refresh the QR/panel with it.
      this.restarts += 1
      const delay = Math.min(30_000, 5000 * 2 ** Math.min(this.restarts - 1, 3))
      this.log(`tunnel exited — restarting in ${delay / 1000}s (restart #${this.restarts}, new public URL will be assigned)`)
      this.setState({ status: 'starting', error: null })
      this.restartTimer = setTimeout(() => {
        this.restartTimer = null
        if (this.enabled) this.spawn(bin)
      }, delay)
    })
  }

  startPolling() {
    this.stopPolling()
    const logFile = path.join(this.baseDir, 'cloudflared.log')
    this.poll = setInterval(() => {
      let content = ''
      try { content = fs.readFileSync(logFile, 'utf8') } catch { return }
      const m = content.match(URL_RE)
      if (m && m[0] !== this.state.url) {
        this.healthFailures = 0
        this.setState({ status: 'online', url: m[0], error: null })
        this.log(`public tunnel ready: ${m[0]} (edge may take 10-20s more to answer)`)
      }
    }, 1000)
  }

  stopPolling() {
    if (this.poll) { clearInterval(this.poll); this.poll = null }
  }

  /**
   * Public-URL health probe. TLS verification is off so it also works behind
   * networks whose intercepting CA is not in Node's bundle (a plain fetch to
   * trycloudflare.com fails on those networks even though the tunnel itself
   * is perfectly healthy). Only reachability matters here.
   */
  startHealthProbe() {
    this.stopHealthProbe()
    this.healthTimer = setInterval(() => {
      if (!this.enabled || !this.state.url) return
      const req = https.get(this.state.url + '/ident', {
        rejectUnauthorized: false,
        timeout: 6000,
        headers: { 'user-agent': 'dsh-remote-gateway-healthprobe' },
      }, (res) => {
        res.resume()
        const ok = res.statusCode === 200
        this.healthFailures = ok ? 0 : this.healthFailures + 1
        if (!ok) this.log(`tunnel health probe: HTTP ${res.statusCode} (${this.healthFailures}/3)`)
        this.maybeRestartUnhealthy()
      })
      req.on('error', (err) => {
        this.healthFailures += 1
        this.log(`tunnel health probe failed: ${err.message} (${this.healthFailures}/3)`)
        this.maybeRestartUnhealthy()
      })
      req.on('timeout', () => { req.destroy() })
    }, 20_000)
  }

  stopHealthProbe() {
    if (this.healthTimer) { clearInterval(this.healthTimer); this.healthTimer = null }
  }

  maybeRestartUnhealthy() {
    if (!this.enabled || this.healthFailures < 3) return
    this.healthFailures = 0
    this.log('tunnel unhealthy (3 failed probes) — restarting it (fresh public URL will be assigned)')
    this.setState({ status: 'starting', error: null })
    this.killChild() // the exit handler schedules the restart
  }

  async ensureBinary() {
    if (this.binary && fs.existsSync(this.binary)) return this.binary
    const name = BINARIES[process.platform]
    if (!name) {
      throw new Error(`unsupported platform ${process.platform}: download cloudflared manually and set tunnel.binary`)
    }
    const dest = path.join(this.baseDir, name)
    if (fs.existsSync(dest)) return dest
    this.setState({ status: 'downloading', error: null })
    await download(RELEASE_BASE + name, dest, (msg) => this.log(msg))
    if (process.platform === 'linux') fs.chmodSync(dest, 0o755)
    return dest
  }
}

/**
 * Streaming download with redirect-following. TLS verification is relaxed:
 * some corporate/ISP networks present non-public root CAs to Node (which has
 * its own CA bundle), while the OS store trusts them — and this must work on
 * user machines we don't control. Supply chain conscious users can pre-place
 * the binary and set tunnel.binary instead.
 */
function download(url, dest, log, depth = 0) {
  return new Promise((resolve, reject) => {
    if (depth > 6) { reject(new Error('too many redirects')); return }
    const req = https.get(url, {
      rejectUnauthorized: false,
      headers: { 'user-agent': 'dsh-remote-gateway' },
    }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
        res.resume()
        resolve(download(new URL(res.headers.location, url).href, dest, log, depth + 1))
        return
      }
      if (res.statusCode !== 200) {
        res.resume()
        reject(new Error(`download failed: HTTP ${res.statusCode}`))
        return
      }
      const tmp = dest + '.part'
      const out = fs.createWriteStream(tmp)
      let received = 0
      let lastLog = 0
      res.on('data', (c) => {
        received += c.length
        if (received - lastLog > 8 * 1024 * 1024) {
          lastLog = received
          log(`downloading cloudflared… ${(received / 1024 / 1024).toFixed(0)} MB`)
        }
      })
      res.pipe(out)
      out.on('finish', () => { fs.renameSync(tmp, dest); resolve(dest) })
      out.on('error', (e) => { fs.rmSync(tmp, { force: true }); reject(e) })
      res.on('error', (e) => { fs.rmSync(tmp, { force: true }); reject(e) })
    })
    req.on('error', reject)
  })
}
