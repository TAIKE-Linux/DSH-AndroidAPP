# 后续开发路线（Roadmap）

## v0.2 — 后台保活与通知（下一步优先）

- **前台服务**：保活双 WebSocket（锁屏后不断流），通知栏显示当前任务状态。
- **完成通知**：`turn/end`（completed/error）时发系统通知（POST_NOTIFICATIONS + 前台服务权限）。
- **审批通知**：`approval/requested` 以高优先级通知直达（配合「允许远程审批」开关）。

## v0.3 — 会话与工作区管理

- ~~`session.history` 分页「加载更早的消息」（beforeSeq 翻页，复用 `hasMore`）~~ ✅ v0.2.1 已落地（上下文轻载：默认只拉最近一轮，按需翻页）
- `workspace.list/create/rename/delete`：手机端管理工作区（任务分类）。
- 会话归档（`workspace.archiveSession`）、`session.fork`（从某轮重试）。
- 会话搜索（`session.search`）。
- 排队消息管理（`session/queue` 帧 + `session.updateQueue` 编辑/删除）。

## v0.4 — 成本与用量

- **按模型单价估算费用**（输入/缓存读/缓存写/输出分桶单价），会话+轮次维度报表。
- 用量历史折线（把 `tokenUsage` 投影随 seq 采样持久化到本地 Room/DataStore）。
- 上下文占用告警（`projectedTokens/contextWindow` 超阈值提示）。

## v0.5 — 安全与体验

- ~~token 用 Android Keystore 加密存储（替代 DataStore 明文）~~ ✅ v0.2.1 已落地（EncryptedSharedPreferences + allowBackup=false）
- ~~网关 TLS + App 自签名证书信任~~ ✅ v0.3.0 已落地：网关 `tls.auto` 自动生成自签名证书；App 逐服务器「信任自签名证书」开关（trust-all 客户端仅限该服务器）
- ~~网关插件化 + 远程访问~~ ✅ v0.3.0 已落地：网关可 `dsh plugin add` 作为 bundle 插件导入；`publicBaseUrl` + VPN/端口转发
- ~~自定义背景~~ ✅ v0.3.0 已落地：纯色/渐变/相册图片全局背景
- 深色模式跟随、大屏/平板布局（Navigation Rail）。
- 更好的 Markdown 渲染（`mikepenz/multiplatform-markdown-renderer`）与代码高亮、图片附件显示（`session.attachment`）。

## 后续可扩展方向

- **子代理树视图**：`subagent.list/history/prompt/interrupt` 做父子会话树导航。
- **Goal 模式**：`goal.create/edit/pause/resume/complete` 映射到手机端长任务面板。
- **快捷命令**：`/` slash 命令与 `skill.list` 的技能目录做成输入联想。
- **Slots/插件 UI**：协议层已就绪，若 DSH 后续向远程客户端开放 slot 注册，可复用本架构。
- **手表/桌面小组件**：AppWidget 显示活跃会话与 token 速率（基于前台服务状态）。
- **iOS/跨端**：协议层（data/*）与 Compose 无关，可平移至 Kotlin Multiplatform 共享层。

## 升级 DSH 的核对流程

1. 对照 `docs/PROTOCOL.md` 第 7 节映射表，逐个 diff 官方 npm 包新版的 `.d.ts`。
2. 未知事件/帧类型在 `Frames.kt`/`SessionEvents.kt` 中默认安全跳过（ignorable 语义），
   通常无需改动即可继续工作；新增投影 key 自动进入 projection cell。
3. 若 `RpcMethodMap` 新增方法，在 `ApiPayloads.kt` 补构造器即可。
