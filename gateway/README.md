# dsh-remote-gateway

给 DeepSeek Harness Android 客户端用的 PC 端认证网关。
把只监听 `127.0.0.1:3080` 的 `dsh web` 的 `/api`（JSON-RPC + 双 WebSocket 事件流）
以 **Bearer token 认证 + 默认 HTTPS** 暴露到局域网或远程。

本包有两种运行方式（二选一）：

1. **独立进程**：`npm start` / `node src/index.js` / 双击 `start.bat`；
2. **DSH bundle 插件**：`dsh plugin add <本目录>`，随 `dsh web` 一起启动，无需单独进程。

## 为什么存在

`dsh web --host 0.0.0.0` 被官方 CLI 硬性拒绝（`/api` 等于远程代码执行且无认证层）。
本网关不改动 DSH 任何配置，只是官方所说的「部署方认证层」。

## 运行（独立进程）

**一条命令搞定**：双击 `start.bat`（或 `npm start` / `node src/index.js`）。
网关启动时会自动探测 DeepSeek Harness：**没在跑就替你启动 `dsh web`，等它就绪；**
已在跑就不动它；运行中 DSH 崩溃会自动重启；Ctrl+C 关闭网关时连带关闭 DSH。

```bash
npm install          # 首次
node src/index.js    # 读取 ./gateway.config.json（首次自动生成 token + HTTPS 证书）
```

Token 管理：

```bash
npm run print-token    # 打印当前 token（也可直接读 gateway.token）
npm run rotate-token   # 重新生成随机 token（旧 token 立即失效）
```

> 需要单独控制 DSH 时：`gateway.config.json` 里设 `"launchDsh": false`（或用
> `--no-launch-dsh` 参数）即可回到"自己开 dsh web"的旧模式。
> DSH 的工作目录默认是仓库根目录（`dshCwd`），需要换目录就在配置里改。

> Windows PowerShell 5.1 不支持 `&&`：请逐行执行上述命令，或用 `;` 分隔；
> 每条命令前先 `cd` 到本目录（`D:\depkseek harness Android\gateway`）。

环境变量：`DSH_GATEWAY_PORT` / `DSH_GATEWAY_HOST` / `DSH_GATEWAY_TOKEN` / `DSH_GATEWAY_UPSTREAM`
/ `DSH_GATEWAY_LAUNCH_DSH` / `DSH_GATEWAY_DSH_COMMAND` / `DSH_GATEWAY_DSH_CWD`。

## 配置（gateway.config.json）

```jsonc
{
  "host": "0.0.0.0",                  // 局域网监听地址
  "port": 8742,                       // 手机填 https://<电脑IP>:8742
  "upstream": "http://127.0.0.1:3080",// DSH 地址（保持 loopback）
  "token": "自动生成或手填",           // 手机端凭据；按密码对待
  "launchDsh": true,                  // true=网关自动拉起/重启 dsh web（一条命令模式）
  "dshCommand": "dsh web",            // 启动 DSH 的命令（含空格路径请配合 dshCwd 用相对路径）
  "dshCwd": "",                       // DSH 工作目录；空=仓库根目录
  "uploadDir": "",                    // 上传目录；空=会话工作区 uploads/
  "uploadLimitMB": 512,               // 上传大小上限
  "publicBaseUrl": "",                // 远程访问时对外公布的完整地址（如 https://x.example.com:8742）
  "tls": {                            // HTTPS
    "auto": true,                     // true=自动生成自签名证书（无需 openssl）
    "cert": "",                       // 或指定已有证书 PEM 路径
    "key": "",                        // 或指定已有私钥 PEM 路径
    "certDir": "certs"                // 自动生成证书的存放目录
  }
}
```

## 作为 DSH 插件导入（免独立进程）

```bash
dsh plugin add <gateway 目录绝对路径>
```

导入后 `dsh web` 会同时启动网关（默认 `0.0.0.0:8742`，HTTPS 自签名自动生成）。
插件配置走 cordis 的 `config` 块，字段与上面 `gateway.config.json` 一致；
Token 存于 `~/.dsh/remote-gateway/gateway.token`，证书存于
`~/.dsh/remote-gateway/certs/`。插件模式**不会**再启动/重启 `dsh web`（它就在
DSH 进程内），`launchDsh` 仅独立进程模式生效。

### Token 重启轮换（默认开启，安全增强）

插件模式下，**每次重启 `dsh web` 都会自动生成一个全新的随机 Token**——
电脑重启后旧 Token 立即作废，手机需用面板新二维码重新扫码（公网隧道地址
本来也会随重启变化，二者天然配套）。

- 手动固定 Token：配置 `"token": "<固定值>"`（或环境变量 `DSH_GATEWAY_TOKEN`），
  显式 Token 永不轮换；
- 需要「重启后保持原 Token」时：配置 `"token": { "rotateOnRestart": false }`
  （或环境变量 `DSH_GATEWAY_TOKEN_ROTATE=0`）；
- 面板 Token 行会显示紫色提示「Token 随 dsh web 重启自动轮换」。

## HTTPS 自签名

`tls.auto: true`（推荐）时网关首次启动自动生成 2048-bit RSA 自签名证书，
监听 HTTPS；手机端添加服务器时对 `https://` 地址开启「信任自签名证书」即可。
证书 SHA-256 指纹通过未鉴权的 `GET /ident` 的 `tlsFingerprint` 字段公开，便于核对。

> 自签名证书只加密传输、不证明对端身份；**认证仍由 bearer token 承担**。
> 公网暴露请配合 Tailscale/VPN 或换成真实 CA 证书（`tls.cert`/`tls.key`）。

## UI 面板与扫码配对（插件模式）

作为 DSH 插件导入后，`dsh web` 的 **设置 → 远程网关** 会出现一个面板：

- 显示网关地址（HTTPS + 局域网 IP，`publicBaseUrl` 配置时优先显示公网地址）；
- 显示完整 Token（可复制）；
- 生成 **二维码**：用手机相机/微信扫码即可直接打开 DSH Android 并自动连接；
- 提供「复制连接串」备用（`dsh-gateway://connect?u=…&t=…`）。

手机端在「DSH 服务器」页点 **扫码**，用系统相机对准二维码即可；也可粘贴连接串手动连接。

## 远程访问（内网穿透，一键开启）

插件内置 **Cloudflare Quick Tunnel**（`cloudflared`），**无需账号、无需公网服务器、
无需端口转发**，一条命令都不用手打：

1. 打开 `dsh web` → **设置 → 远程网关** → 点「**开启内网穿透**」；
2. 首次会自动下载 `cloudflared`（约 55MB，存于 `~/.dsh/remote-gateway/`；
   网络受限的机器也可手动下载后把路径写进插件配置 `tunnel.binary`）；
3. 约 10–30 秒后出现公网地址 `https://xxx.trycloudflare.com`，面板二维码
   **自动切换为公网地址**——手机在任何网络（含蜂窝流量）扫码即可直连；
4. 点「关闭」随时停止。`/ident` 会实时公布该公网地址，局域网扫描结果也会显示。

实现与安全要点：

- 隧道通过 loopback 连接网关本地 HTTPS 端口（校验关闭，仅本机一跳）；
  公网侧是 Cloudflare 的真实 CA 证书，手机**无需**「信任自签名证书」；
- 认证不变：所有请求仍必须携带 `Authorization: Bearer <token>`；
- Quick Tunnel 无 SLA（随机域名、随隧道进程变化），长期使用建议自建
  frp/Tailscale 或 Cloudflare 命名隧道，并用 `publicBaseUrl` 固定地址；
- 可配置 `"tunnel": { "enabled": true }` 让插件启动即开启（或环境变量
  `DSH_GATEWAY_TUNNEL=1`）；`POST /dsh-remote-gateway/tunnel` `{enable:true|false}`
  是面板使用的运行时开关。

## 一条命令模式（DSH 托管）

- 启动顺序自动化：探测 `upstream` 端口 → 未监听才拉起 `dshCommand` → 每 2s 探测直到 DSH 应答；
- **崩溃自愈**：DSH 运行中退出 → 5 秒后自动重启；连续 3 次秒退（如命令不存在/端口被占）则放弃并打印排查指引；
- **关闭联动**：网关 Ctrl+C 退出时结束 DSH 进程树（Windows 用 `taskkill /T /F`）；
- 端口已被占用但 API 探测不通时只警告不拉起，避免双实例冲突。

## 接口

| 路径 | 方法 | 鉴权 | 说明 |
|---|---|---|---|
| `/ident` | GET | 否 | **扫描识别端点**：`{gateway:{name,version},scheme,tlsFingerprint?,public?}`，供手机扫描发现（零敏感信息） |
| `/health` | GET | 否 | 网关信息 + 上游可达性布尔值（不含 cwd/模型等主机细节，v0.2 安全收紧） |
| `/api/<method>` | POST | 是 | 代理 DSH 一元 RPC（`client-request` 信封原样转发） |
| `/api/respond` | POST | 是 | 代理应答帧（审批/提问） |
| `/api/events.mux` | WS | 是 | mux 下行事件流桥接（header 或 `?token=`） |
| `/api/events.host` | WS | 是 | host 下行事件流桥接 |
| `/upload` | PUT/POST | 是 | **手机 → 电脑文件上传**：`?name=文件名[&cwd=会话工作目录]`，原始字节流式写盘 |
| 其他 | — | — | 一律 404（不代理网页 UI/静态资源） |

鉴权头：`Authorization: Bearer <token>`。token 比较用 SHA-256 + timingSafeEqual。

## 手机上传文件

App 聊天页 📎 按钮选文件后走 `PUT /upload`：

- 文件保存到 **`<会话cwd>/uploads/`**（App 自动带上会话工作目录；缺省回退到
  `<DSH启动目录>/uploads` 或网关目录下 `uploads/`，可用 `uploadDir` 配置固定位置），
  代理的 fs 工具直接可见，对话里引用 `uploads/文件名` 即可。
- 文件名清洗（去路径分隔符/控制字符，防目录穿越）、重名自动 `-1` 后缀、**永不覆盖**；
- 大小上限 `uploadLimitMB`（默认 512MB），超限 413；全程流式写盘，不占内存；
- 与 `/api` 同等的 Bearer 鉴权；响应 `{ok,name,path,relPath,size}`。

## 性能优化（v0.2.1）

- **HTTP 响应 gzip**：DSH 自身不压缩 `/api` 响应，网关对 JSON 响应做流式 gzip
  （客户端带 `Accept-Encoding: gzip` 时，实测 `session.history` 页 1.27MB → 87KB，
  **-93%**）。App 的 OkHttp 自动解压，无需任何配置。
- **WS 压缩协商**：网关两端都声明 `perMessageDeflate`（DSH 上游目前不开启，自动回退明文，
  零风险；未来官方开启即自动受益）。
- **连接复用与低延迟**：上游 socket 关闭 Nagle（`setNoDelay`）、keep-alive 空闲窗口
  5s → 30s，App 端连接池复用更充分。

## 安全说明

- 局域网明文 HTTP + token（DSH 本身无 TLS）；不建议公共 WiFi 使用，可启用 `tls` 或 VPN。
- 拿到 token 即等于电脑上的远程代码执行；请勿泄露，删除配置中的 token 字段即可重新生成。
- 网关对上游以 `Host: 127.0.0.1` 回连，恰好通过 DSH 官方的 loopback 信任围栏，
  DSH 无需任何额外配置、可随时官方升级。
