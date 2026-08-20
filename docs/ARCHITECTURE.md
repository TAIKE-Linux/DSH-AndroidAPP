# 架构与安全模型

## 总体架构

```
┌───────────────────────────────┐         HTTPS/WSS（局域网，Bearer token 认证）
│  Android 手机                  │ ──────────────────────────────────────────┐
│  Kotlin + Jetpack Compose     │                                            │
│  · DshApiClient（HTTP RPC）   │                                            ▼
│  · ConnectionManager（双 WS） │                              ┌──────────────────────────┐
└───────────────────────────────┘                              │ PC: dsh-remote-gateway     │
                                                                │ (Node.js + ws)             │
                                                                │ · 只暴露 /api              │
                                                                │ · Bearer token 校验         │
                                                                │ · HTTP 代理 + WS 桥接      │
                                                                └────────────┬─────────────┘
                                                                             │ HTTP/WS → 127.0.0.1（loopback，DSH 信任围栏放行）
                                                                             ▼
                                                              ┌──────────────────────────┐
                                                              │ PC: dsh web               │
                                                              │ (DeepSeek Harness，未改动) │
                                                              │ · 只监听 127.0.0.1:3080    │
                                                              │ · /api 四象限 RPC + 事件流  │
                                                              └──────────────────────────┘
```

## 为什么必须加网关

官方设计（来自 `dsh-web-app` 源码与 `dsh-client-connection` README）：

1. `dsh web --host 0.0.0.0` 被 CLI **硬性拒绝**：
   `"--host 0.0.0.0 is intentionally not supported yet for safety: it would expose remote code execution to the network"`。
   DSH 的 `/api` 能让代理在电脑上执行 bash/pwsh/文件读写，等于远程代码执行。
2. DSH 的 `/api` **没有认证层**——官方把浏览器的信任围栏明确界定为「可达性策略，不是认证」。
3. 官方路线是「远程访问前需要先有认证层」，即把认证交给部署方。

网关就是这层「部署方认证」：DSH 保持官方的 127.0.0.1 安全姿态，网关以**最小暴露面**
把 `/api` 以 token 认证形式提供给局域网，且**不代理网页 UI 本身**（手机用原生 App，不需要 UI）。

## 信任模型与威胁面

| 层 | 机制 | 说明 |
|---|---|---|
| 传输 | 局域网明文 HTTP + Bearer token | DSH 无 TLS；家庭 WiFi 是边界。可选 TLS（见下） |
| 认证 | 每个 HTTP 请求/WS 升级校验 `Authorization: Bearer <token>` | token 由网关首次运行随机生成（24 字节 base64url） |
| 上游围栏 | 网关以 `Host: 127.0.0.1:3080` 回连 | 恰好命中 DSH 官方 loopback 信任围栏，无需改 DSH 配置 |
| 暴露面 | 只允许 `/api/*`（POST）与两个 WS 路径 + 两个未鉴权静态端点 | 静态 UI、目录列表等一律 404 |
| 未鉴权端点 | `GET /ident`（静态识别信息）、`GET /health`（仅上游可达性布尔值） | 扫描发现专用；**不含** cwd/模型/任何主机细节（v0.2 收紧） |
| 审批安全 | 手机默认**不能**远程回答审批/提问 | `allowRemoteAnswers` 逐服务器开启；开启即等于把沙箱放行权交给手机 |

### 已知风险（如实声明）

- token 在局域网明文传输（HTTP），同一网段抓包可截获。**不建议在公共 WiFi 使用**；
  开启网关 TLS（见下）或走 VPN 可缓解。
- 拿到 token = 拿到电脑上的远程代码执行权限。token 按密码对待。
- 手机丢失且开启「允许远程审批」时，捡到者可在 token 有效期内放行沙箱操作。
  缓解：网关重启换 token（删除 `gateway.config.json` 的 token 字段重新生成）、App 侧删除服务器。

### 可选 TLS

网关支持 `tls.cert/tls.key`（`gateway.config.json`）：

```bash
# 电脑上生成自签名证书（openssl 或 mkcert）
mkcert -install
mkcert 192.168.1.10
# gateway.config.json:
#   "tls": { "cert": "./_wildcard.pem 路径", "key": "./key 路径" }
```

App 填写 `https://…` 地址即可（自签名证书需要在 App 内做信任处理，见 ROADMAP；
当前版本建议配合 mkcert 的本地 CA 或暂用 HTTP）。

## 局域网扫描发现（v0.2）

手机端 `LanScanner` 一键发现电脑网关：

1. 通过 `LinkProperties` 读取手机 IPv4 与所在网段（**无需位置权限**）；
   非 WiFi 传输（蜂窝/VPN）直接拒绝，避免向 WAN 喷洒探测。
2. 并发（≤64）探测本 /24 网段每台主机的 `GET /ident`（连接 300ms/读 500ms 超时，
   用户触发、可随时取消；重定向已禁用）。
3. 只有响应 200 且 JSON `gateway.name == "dsh-remote-gateway"` 的地址才被认定为网关，
   精确匹配避免误连；扫描流量**不携带 token**。
4. 选中结果自动预填「添加服务器」的地址栏，token 仍由用户手填（或从电脑配置读取）。

完整安全审查（含发现并修复的 `/health` 信息泄露）见 [SECURITY-REVIEW.md](SECURITY-REVIEW.md)。

## Android 端架构（MVVM + 手动 DI）

```
ui/ (Compose)
  ServerListScreen / SessionListScreen / ChatScreen
        │ collectAsStateWithLifecycle
ui/viewmodel/
  ServersViewModel / SessionsViewModel / ChatViewModel
        │
data/store/
  ServerConfigStore(EncryptedSharedPreferences，Keystore AES256-GCM)  ·  SessionStore（帧消费 + 每会话事件日志 + 投影 + 审批/提问）
        │                                        │
data/remote/
  DshApiClient（HTTP RPC）  ·  ConnectionManager（mux/host 双 WS，重连+退避）
        │
data/protocol/   ← DSH 官方 wire 契约的 Kotlin 镜像（信封、SessionEvent、MuxFrame/HostFrame、投影）
```

关键设计：

- **事件折叠**（`SessionFolder`）：与网页端同语义——`user/message` 追加气泡，
  `assistant/chunk` 流入临时流式气泡并由 `assistant/message` 定稿，
  `tool/call`+`tool/result` 配对成工具卡，`todo/write` 后写覆盖，`turn/start|end` 生成轮次标记。
- **投影收敛**：`session/projection` 帧按 higher-seq-wins 存入每会话 cell；
  `session.list`/`session.history` 的 `projections` 块作为冷启动基线。
- **gap 修复**：断线重连后，`session/subscribed.lastSeq` 大于本地已知最大 seq 时自动补拉 history 尾页。
- **回答帧**：`approval/requested`/`question/requested` 的 rpcId 原样回填到
  `POST /api/respond` 的 client-response（官方应答契约）。

## 与官方契约的对齐方式

本项目不依赖任何第三方「逆向」协议：所有类型均直接翻译自官方 npm 包
`@deepseek-ai/dsh-host-apiproxy` / `dsh-client-connection` / `dsh-session` / `dsh-token-meter`
的类型定义（见 `docs/PROTOCOL.md` 的逐项出处）。官方发布新版时，
用 `PROTOCOL.md` 的映射表核对差异即可升级。
