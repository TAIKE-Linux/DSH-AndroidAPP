window.__ModuleLoader__.load({
  id: "@dsh-external/dsh-remote-gateway",
  factory: (require) => {
    var module = { exports: {} };
    var exports = module.exports;
// Client panel for the dsh-remote-gateway plugin. Inlined into lib/client.js by
// scripts/build-client.mjs. The QR SVG is generated host-side by the plugin
// (battle-tested `qrcode` package) and served through /dsh-remote-gateway/info.
const React = require('react')
const { useEffect, useState, useCallback } = React

const styles = {
  box: { padding: '16px', display: 'flex', flexDirection: 'column', gap: '12px', fontFamily: 'system-ui, -apple-system, Segoe UI, sans-serif', color: 'var(--dsw-alias-label-primary, #1f2328)' },
  title: { fontSize: '16px', fontWeight: 600 },
  sub: { fontSize: '12px', opacity: 0.6, marginTop: '-8px' },
  label: { fontSize: '12px', opacity: 0.7, marginBottom: '-8px', textTransform: 'uppercase', letterSpacing: '0.4px' },
  code: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: '13px', fontWeight: 500, wordBreak: 'break-all' },
  smallCode: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace', fontSize: '11px', wordBreak: 'break-all', opacity: 0.8 },
  row: { display: 'flex', alignItems: 'center', gap: '8px', flexWrap: 'wrap' },
  btn: { fontSize: '12px', padding: '4px 10px', borderRadius: '6px', border: '1px solid var(--dsw-alias-border-l2, #d0d7de)', background: 'transparent', color: 'inherit', cursor: 'pointer' },
  qr: { width: '180px', height: '180px', background: '#fff', padding: '10px', borderRadius: '12px', border: '1px solid var(--dsw-alias-border-l2, #d0d7de)' },
  hint: { fontSize: '12px', color: 'var(--dsw-alias-label-secondary, #57606a)', textAlign: 'center', marginTop: '-2px' },
  qrPanel: { border: '1px solid var(--dsw-alias-border-l2, #d0d7de)', borderRadius: '12px', padding: '16px', background: 'var(--dsw-alias-bg-module-platform, rgba(0,0,0,.03))', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '10px' },
  qrHeading: { fontSize: '12px', fontWeight: 600 },
  ips: { display: 'flex', flexWrap: 'wrap', gap: '6px' },
  ip: { fontSize: '11px', opacity: 0.75, cursor: 'pointer', background: 'var(--dsw-alias-bg-module-platform, rgba(0,0,0,.04))', borderRadius: '6px', padding: '2px 8px' },
  warn: { fontSize: '12px', color: '#b45309', background: '#fef3c7', padding: '8px 10px', borderRadius: '8px' },
  okNote: { fontSize: '12px', color: '#15803d', background: '#dcfce7', padding: '8px 10px', borderRadius: '8px' },
  tunnelStatus: { fontSize: '12px', color: 'var(--dsw-alias-label-secondary, #57606a)' },
  rotateHint: { fontSize: '11px', color: '#7c3aed', background: 'rgba(124,58,237,.08)', padding: '6px 10px', borderRadius: '8px' },
}

function GatewayPanel() {
  const [info, setInfo] = useState(null)
  const [error, setError] = useState(null)
  const [copied, setCopied] = useState('')
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    try {
      const r = await fetch('/dsh-remote-gateway/info', { headers: { accept: 'application/json' } })
      if (!r.ok) throw new Error('HTTP ' + r.status)
      setInfo(await r.json())
      setError(null)
    } catch (e) {
      setError(e && e.message ? e.message : String(e))
    }
  }, [])

  // Load once and poll so the QR/baseUrl follow the tunnel as it comes online.
  useEffect(() => {
    let alive = true
    const load = async () => {
      try {
        const r = await fetch('/dsh-remote-gateway/info', { headers: { accept: 'application/json' } })
        if (!r.ok) throw new Error('HTTP ' + r.status)
        const d = await r.json()
        if (alive) setInfo(d)
      } catch (e) {
        if (alive) setError(e && e.message ? e.message : String(e))
      }
    }
    load()
    const timer = setInterval(load, 4000)
    return () => { alive = false; clearInterval(timer) }
  }, [])

  const copy = useCallback((text, label) => {
    const done = () => { setCopied(label); setTimeout(() => setCopied(''), 1500) }
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => {})
    } else {
      done()
    }
  }, [])

  const setTunnel = useCallback(async (enable) => {
    setBusy(true)
    try {
      await fetch('/dsh-remote-gateway/tunnel', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ enable }),
      })
    } catch (e) {
      setError(e && e.message ? e.message : String(e))
    } finally {
      setBusy(false)
      refresh()
    }
  }, [refresh])

  if (error && !info) {
    return React.createElement('div', { style: styles.box },
      React.createElement('div', { style: styles.warn }, '无法读取网关信息：' + error),
    )
  }
  if (!info) {
    return React.createElement('div', { style: styles.box }, '读取中…')
  }

  const base = info.baseUrl || info.publicBaseUrl
  const uri = info.deepLink
  const tunnel = info.tunnel || { status: 'off', url: null, error: null }
  const children = [
    React.createElement('div', { key: 'title', style: styles.title }, '远程网关'),
    React.createElement('div', { key: 'sub', style: styles.sub }, `${info.name} v${info.version} · ${info.scheme.toUpperCase()}`),
  ]

  if (base) {
    children.push(React.createElement('div', { key: 'alabel', style: styles.label }, '网关地址'))
    children.push(React.createElement('div', { key: 'arow', style: styles.row },
      React.createElement('code', { style: styles.code }, base),
      React.createElement('button', { style: styles.btn, onClick: () => copy(base, 'addr') }, copied === 'addr' ? '已复制' : '复制'),
    ))
    const extras = (info.lanIps || []).filter((ip) => !base.includes(`://${ip}:`))
    if (extras.length > 0) {
      const shown = extras.slice(0, 4)
      const rest = extras.length - shown.length
      children.push(React.createElement('div', { key: 'ips', style: styles.ips },
        ...shown.map((ip) => React.createElement('span', {
          key: ip,
          style: styles.ip,
          onClick: () => copy(`${info.scheme}://${ip}:${info.port}`, 'addr'),
        }, ip)),
        rest > 0 ? React.createElement('span', { key: 'more', style: styles.ip }, `+${rest}`) : null,
      ))
    }
  }

  children.push(React.createElement('div', { key: 'tlabel', style: styles.label }, 'Token'))
  children.push(React.createElement('div', { key: 'trow', style: styles.row },
    React.createElement('code', { style: styles.smallCode }, info.token),
    React.createElement('button', { style: styles.btn, onClick: () => copy(info.token, 'tok') }, copied === 'tok' ? '已复制' : '复制'),
  ))
  if (info.tokenRotates) {
    children.push(React.createElement('div', { key: 'rotate-hint', style: styles.rotateHint },
      '🔁 Token 随 dsh web 重启自动轮换：重启电脑后请用新二维码重新扫码连接',
    ))
  }

  // -- Remote access (NAT traversal) section ---------------------------------
  const tunnelRow = []
  tunnelRow.push(React.createElement('div', { key: 'tunnel-label', style: styles.label }, '远程访问（不在同一网络也能连）'))
  if (tunnel.status === 'online' && tunnel.url) {
    tunnelRow.push(React.createElement('div', { key: 'tunnel-ok', style: styles.okNote },
      '内网穿透已开启，任何网络（含手机流量）均可访问',
    ))
    tunnelRow.push(React.createElement('div', { key: 'tunnel-row', style: styles.row },
      React.createElement('code', { style: styles.smallCode }, tunnel.url),
      React.createElement('button', { style: styles.btn, onClick: () => copy(tunnel.url, 'tun') }, copied === 'tun' ? '已复制' : '复制'),
      React.createElement('button', { style: styles.btn, disabled: busy, onClick: () => setTunnel(false) }, busy ? '处理中…' : '关闭'),
    ))
  } else if (tunnel.status === 'downloading' || tunnel.status === 'starting') {
    tunnelRow.push(React.createElement('div', { key: 'tunnel-status', style: styles.tunnelStatus },
      tunnel.status === 'downloading' ? '正在下载 cloudflared…（约 55MB，仅首次）' : '正在建立隧道…（首次约 10–30 秒，URL 就绪后二维码自动切换为公网地址）',
    ))
  } else if (tunnel.status === 'error') {
    tunnelRow.push(React.createElement('div', { key: 'tunnel-err', style: styles.warn }, `内网穿透失败：${tunnel.error || '未知错误'}`))
    tunnelRow.push(React.createElement('button', { key: 'tunnel-retry', style: styles.btn, disabled: busy, onClick: () => setTunnel(true) }, busy ? '处理中…' : '重试开启'))
  } else {
    tunnelRow.push(React.createElement('div', { key: 'tunnel-off', style: styles.tunnelStatus }, '未开启：手机需与电脑在同一局域网（或使用 VPN）。'))
    tunnelRow.push(React.createElement('button', { key: 'tunnel-on', style: styles.btn, disabled: busy, onClick: () => setTunnel(true) }, busy ? '处理中…' : '开启内网穿透（免费，无需账号）'))
  }
  children.push(React.createElement('div', { key: 'tunnel-block', style: styles.qrPanel }, ...tunnelRow))

  // -- QR section --------------------------------------------------------------
  if (base && info.qrSvg) {
    const qrSvg = info.qrSvg
      .replace(/width="\d+"/, 'width="180"')
      .replace(/height="\d+"/, 'height="180"')
    const qrLabel = tunnel.status === 'online' && tunnel.url ? '扫码连接（公网地址）' : '扫码连接（局域网地址）'
    children.push(React.createElement('div', { key: 'qrpanel', style: styles.qrPanel },
      React.createElement('div', { key: 'qlabel', style: styles.qrHeading }, qrLabel),
      React.createElement('div', {
        key: 'qr',
        style: styles.qr,
        dangerouslySetInnerHTML: { __html: qrSvg },
      }),
      React.createElement('div', { key: 'hint', style: styles.hint }, '用手机相机 / DSH Android「扫码」扫描'),
      React.createElement('button', {
        key: 'uri',
        style: styles.btn,
        onClick: () => copy(uri, 'uri'),
      }, copied === 'uri' ? '已复制' : '复制连接串'),
    ))
  } else {
    children.push(React.createElement('div', { key: 'warn', style: styles.warn },
      '未检测到可用局域网 IP：请开启上方「内网穿透」，或配置 publicBaseUrl。',
    ))
  }

  return React.createElement('div', { style: styles.box }, ...children)
}

exports.inject = ['slots']
exports.apply = function apply(ctx) {
  ctx.effect(() => ctx.slots.inject('settings.section', () => ctx.slots.register({
    name: 'settings.section',
    id: 'dsh-remote-gateway',
    order: 90,
    label: () => '远程网关',
  }, GatewayPanel)), 'dsh-remote-gateway: settings section')
}

    return module.exports;
  }
});
