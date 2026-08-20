# DeepSeek Harness Android

在手机上遥控电脑上的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（DSH）：
发布任务到电脑运行、实时查看任务过程（消息流 / 工具调用 / 审批 / 提问 / 后台任务）、
实时查看 token 消耗（输入 / 输出 / 缓存 / 上下文占用），并保留完整的后续开发接口。

> 官方现状：`dsh web` 出于安全考虑**只监听 127.0.0.1 且无认证**（`--host 0.0.0.0` 被 CLI 硬性拒绝），
> 因此本项目增加了一个 **PC 端认证网关**，让手机通过局域网安全地访问 DSH 的 `/api`。
> Android 端直接实现 DSH 官方 wire 协议（四象限 RPC + 双 WebSocket 下行流），与网页端使用完全相同的契约。

## 仓库结构

```
├── android/                  # Android 客户端（Kotlin + Jetpack Compose + Material 3）
│   └── app/src/main/java/com/dsh/android/
│       ├── data/protocol/    # DSH 官方 wire 契约的 Kotlin 模型（信封/事件/帧/投影）
│       ├── data/remote/      # DshApiClient（HTTP RPC）+ ConnectionManager（双 WS 重连）
│       ├── data/store/       # ServerConfigStore + SessionStore（事件折叠、token 投影）
│       └── ui/               # Compose 界面（服务器/会话/聊天/工具卡/审批/提问）
├── gateway/                  # PC 端远程网关（Node.js + ws，Bearer 认证代理）
├── tools/                    # 开发辅助脚本（Gradle 下载、WS 测试）
├── docs/
│   ├── ARCHITECTURE.md       # 总体架构与安全模型
│   ├── PROTOCOL.md           # DSH /api wire 协议参考（基于官方契约整理）
│   └── ROADMAP.md            # 后续开发路线
└── reference/                # （可选）官方仓库源码参考
```

## 快速开始

### 1. 电脑端 —— 一条命令启动全部

```bash
# 首次：安装网关依赖
cd gateway
npm install

# 之后每次：双击 gateway\start.bat（或 npm start）
# 网关会自动探测并启动 DeepSeek Harness（dsh web），崩溃自动重启；
# Ctrl+C 关闭网关时连带关闭 DSH。首次运行会生成随机 token 并写入 gateway.config.json。
```

> 想自己管理 DSH：`gateway.config.json` 设 `"launchDsh": false`，或网关启动加 `--no-launch-dsh`，
> 然后像以前一样先 `dsh web` 再 `node src/index.js`。
> **Windows PowerShell 5.1 注意**：不支持 `&&`，请分行执行或用 `;` 连接。

网关默认监听 `0.0.0.0:8742`，只代理 `/api`（HTTP RPC + 两个 WebSocket 下行流），
所有请求都需要 `Authorization: Bearer <token>`。DSH 本体保持 127.0.0.1 不动，随时可官方升级。

防火墙放行 8742 端口；手机与电脑处于同一局域网。

### 2. 手机端

用 **Android Studio**（Ladybug 或更新版本）打开 `android/` 目录同步并运行；
或命令行构建（wrapper 已入库，Windows 可直接 `gradlew.bat`；本机未装 Gradle 时可用
`tools/gradle-dist/gradle-8.11.1/bin/gradle.bat` 代替 `gradlew`）：

```bash
cd android
gradlew.bat :app:assembleDebug        # PowerShell 下为 .\gradlew.bat
# 产物：android/app/build/outputs/apk/debug/app-debug.apk
```

> 依赖下载慢？`android/gradle.properties` 里已启用本机代理（默认 v2rayN/clash 端口 10808，
> 按实际修改；机器级配置建议写入 `GRADLE_USER_HOME/gradle.properties`，避免提交进仓库）。
> 本机 Gradle 缓存（`GRADLE_USER_HOME`）默认使用仓库内 `.gradle-home/`，已预置 AGP/Kotlin 插件，
> 其余依赖经代理补齐。

打开 App → 点右上角 🔍 **自动扫描局域网**发现电脑网关（自动填入 IP）→ 填 Token。
手动输入网关地址时**自动补全 `http://` 前缀**（直接输 `192.168.1.10:8742` 即可）：

- 网关地址：`http://电脑IP:8742`（扫描发现会自动填好；手动输入可省略 `http://` 前缀）
- Token：网关生成并打印在 `gateway.config.json` 中的值
- 允许远程审批/回答：默认关闭（安全开关，见 ARCHITECTURE.md）

连接成功后即可：点右上角 🔍 **自动扫描局域网**发现电脑网关（自动填入 IP）→ 查看电脑上的全部会话 → 新建会话 → **输入任务发布到电脑运行** →
实时看到回复流式输出、工具调用卡片、任务清单、每轮 token 增量，顶部可展开 Token 详细统计。

## 功能清单（v0.2.5-beta）

| 能力 | 说明 | 协议 |
|---|---|---|
| **局域网扫描发现** | 一键扫描 /24 网段 × 端口 8742/8743/8744，经 `/ident` 精确识别；扫到的 IP:端口直接展示，**已保存过 token 的网关点「连接」一步直连**，未保存的点「使用」自动预填地址 | `GET /ident` |
| 发布任务 | 新建会话 + `session.prompt(mode=queue)` | `session.create` / `session.prompt` |
| 实时过程 | 流式 assistant 文本/思考过程、工具调用+结果卡、todo 清单、每轮状态（完成/出错/停止）、后台任务条 | WS `/api/events.mux` / `events.host` |
| Token 消耗 | 会话累计输入/输出/缓存读写（`tokenUsage` 投影）、每轮增量、上下文占用条（`contextPressure`）、构成估算（`contextBreakdown`） | `session/projection` 帧 + `assistant/message.usage` |
| 任务控制 | 停止当前轮（`session.cancel`）、模型切换（`session.models`/`selectModel`）、重命名（聊天页 ✏️） | 对应 RPC |
| 远程审批 | 沙箱操作审批（允许一次/拒绝）、代理提问回答（默认关闭，逐服务器开关） | `approval/requested` → `POST /api/respond` |
| 断线恢复 | 双流自动重连 + 指数退避 + 订阅基线 gap 补拉 | 官方 ConnectionController 同款策略 |
| **传输超时与自愈** | 一元 RPC 60s 硬超时、应答 30s、上传 10min；超时弹出提示并**自动强制重建双流**；运行中 120s 无新消息显示温和提示（长工具调用不误杀） | OkHttp callTimeout |
| 交互优化 | 阅读历史智能停滚 + 回到底部、IME 回车发送、圆角一体化输入框（📎/发送内置）、排队/错误/停滞提示 | — |
| Token 静态加密 | 服务器库（含网关 token）经 Android Keystore（AES256-GCM）加密存储；云备份已关闭 | security-crypto |
| 连接速度优化 | 网关 gzip 压缩 JSON 响应（历史页实测 -93%）、WS 压缩协商、Nagle 关闭、keep-alive 30s；App 端 4s 快速失败 + 双流死链即断即重连 | — |
| 上下文轻载 | 默认只传输「最近一次提问 + 完整回答」，更早内容按页加载（beforeSeq 翻页，位置不跳动） | `session.history` 分页 |
| **手机传文件到电脑** | 聊天页 📎 选任意文件 → 流式上传（进度条）到**会话工作区 `uploads/`**，自动填入引用文案 | 网关 `PUT /upload` |
| 聊天流畅度 | 流式阶段纯文本渲染（定稿后一次性渲染 Markdown）、80ms 采样折叠、列表 contentType 复用、发送加载态 | — |

## 文档

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — 架构、安全模型、为什么需要网关
- [docs/PROTOCOL.md](docs/PROTOCOL.md) — DSH `/api` 协议完整参考（本项目实现的依据）
- [docs/ROADMAP.md](docs/ROADMAP.md) — 后续开发计划（后台保活通知、workspace 管理、成本统计、TLS 等）
- [gateway/README.md](gateway/README.md) — 网关配置与部署

## 参考来源

- 官方仓库：[deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness)
- 官方文档：仓库内 `docs/`、各包 `README.md`（`dsh-host-webserver`、`dsh-client-connection`、`dsh-token-meter`、`dsh-host-apiproxy` 等）
- 社区：[awesome-deepseek-harness](https://github.com/0xsline/awesome-deepseek-harness)、
  [deepseek-harness-desktop（Electron 壳）](https://github.com/RZX00/deepseek-harness-desktop)
  —— 目前社区主要是桌面壳，本项目是第一个面向手机远程控制的实现。

## License

MIT
