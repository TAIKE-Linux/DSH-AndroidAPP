# DSH `/api` Wire 协议参考

> 依据官方 npm 包类型定义整理（v0.1.0-rc.6）：
> `@deepseek-ai/dsh-host-apiproxy`（api/ 契约）、`@deepseek-ai/dsh-client-connection`（载体）、
> `@deepseek-ai/dsh-session`（事件）、`@deepseek-ai/dsh-token-meter`（投影）。
> 本文件是 `deepseek-harness-android` 的实现依据，也是升级新版 DSH 时的核对清单。

## 1. 载体

- 一元调用：`POST /api/<method>`，请求体 `ClientRequest`，响应体 `ServerResponse`。
- 应答帧：`POST /api/respond`，请求体 `ClientResponse`，响应体 `RpcReceipt`。
- 下行事件：两个 WebSocket：`ws://host:port/api/events.mux` 与 `ws://host:port/api/events.host`，
  每个文本帧 = `ServerRequest` 信封（`method` = 帧类型，`payload` = 帧本体）。
- 就绪 = 两个 WS 都打开 + `host.describe` 成功（官方 ConnectionController 语义）。

### 四象限信封（rpc.d.ts）

```jsonc
// ClientRequest（客户端发起，POST /api/<method> 请求体）
{ "type": "client-request", "rpcId": "uuid", "method": "session.list", "payload": {} }

// ServerResponse（HTTP 响应体；rpcId 原样回显）
{ "type": "server-response", "rpcId": "uuid",
  "result": { "ok": true, "value": {} } | { "ok": false, "error": { "code", "message", "details" } } }

// ServerRequest（WS 下行帧信封；answerable 帧 rpcId 稳定可回填）
{ "type": "server-request", "rpcId": "uuid", "method": "session/subscribed", "payload": { /* 帧 */ } }

// ClientResponse（应答 answerable 帧，POST /api/respond 请求体）
{ "type": "client-response", "rpcId": "<帧的 rpcId>", "result": { "ok": true, "value": {} } }

// RpcReceipt（/api/respond 的 HTTP 响应体）
{ "accepted": true } | { "accepted": false, "reason": "not-pending" | "bad-response" }
```

错误码（节选）：`bad-request`、`cancelled`、`session-not-found`、`agent-busy`、
`session-conflict`、`agent-preset-*`、`settings-rejected`、`internal` …（`RpcErrorDetailsMap` 全表见 rpc.d.ts）。

## 2. 方法表（RpcMethodMap）

本 App 使用的方法加粗：

| 方法 | 说明 |
|---|---|
| **`session.list`** | 全部会话摘要（updatedAt 降序），含 `projections` 基线（title/tokenUsage…） |
| `session.search` | 跨会话搜索 |
| **`session.create`** | 创建会话 + 空闲 agent（可预分配 sessionId、cwd、agentPreset） |
| **`session.history`** | 历史事件页 + 尾页 `projections` 块 |
| **`session.models` / `session.selectModel`** | 会话模型目录 / 切换模型 |
| **`session.rename`** | 重命名（`session/title` 事件固定标题） |
| **`session.fork`** | 从某轮 fork 新会话 |
| **`session.prompt`** | 发消息（`mode: queue \| steer`；`/` 开头走 slash 命令） |
| `session.attachment` / `session.updateQueue` | 附件读取 / 排队消息编辑删除 |
| **`session.cancel`** | 停止当前轮 |
| `subagent.list/history/prompt/interrupt` | 子代理 |
| **`host.describe`** | 握手快照（version/cwd/provider/model/attachedSessions） |
| `host.pickDirectory/listDirectory/createDirectory/openPath` | 桌面能力 |
| `workspace.*` | 工作区管理 |
| `skill.list`、`agentPreset.*`、`goal.*`、`settings.*`、`credentials.*`、`llm.*` | 其余平面 |

## 3. 事件流帧

### mux 帧（MuxFrame）

```jsonc
{ "type": "session/event", "sessionId", "event": SessionEvent, "view?": ToolEventView }
{ "type": "session/subscribed", "sessionId", "lastSeq": 34845 }        // 订阅基线（gap 检测锚点）
{ "type": "approval/requested", "sessionId", "approvalId", "toolName", "callId?", "reason?" }  // 可应答
{ "type": "approval/resolved", "sessionId", "approvalId", "outcome" }
{ "type": "question/requested", "sessionId", "questions": AskUserQuestionItem[] }             // 可应答
{ "type": "question/resolved", "sessionId", "questionRpcId", "outcome" }
{ "type": "session/queue", "sessionId", "items": QueuedInboxItem[] }
{ "type": "session/jobs", "sessionId", "jobs": JobView[] }              // 后台任务快照
{ "type": "session/projection", "sessionId", "key", "value", "seq" }    // 投影推送（higher-seq-wins）
{ "type": "stream/error", "error": RpcError }
```

### host 帧（HostFrame）

`host/session-added`（含 blank/parentSessionId/cwd/agentPreset）、`host/session-removed`、
`host/session-status{running}`、`host/agent-error{message}`、`host/workspace-*`、`host/stream-error`。

### 应答载荷

```jsonc
// 审批（result.value）
{ "sessionId", "approvalId", "outcome": "allowed-once" | "rejected" }
// 提问（result.value）
{ "sessionId", "answer": { "answers": [ { "id", "selected": ["…"], "custom"? } ] } }
```

## 4. SessionEvent（会话日志事件，dsh-session/types）

统一外壳：`{ "type", "seq", "time", "data", "ignorable"?, "surfaceOp"?, "sourceEventSeqs"? }`

| type | data 要点 | 用途 |
|---|---|---|
| `turn/start` | `{turn}` | 轮次开始（App 记录 token 投影快照） |
| `turn/end` | `{turn, reason:{kind}}`；kind ∈ completed/aborted/blocked/error/max-tokens/interrupted | 轮次状态 + 计算本轮 token 增量 |
| `step/start` / `step/end` | `{turn, step}` | 一步 = 一次模型调用 + 工具执行 |
| `user/message` | `Message{id, role:"user", content:ContentBlock[], source}` | source.kind=user → 气泡；plugin → 上下文提示 |
| `assistant/chunk` | `{turn, step, chunk: StreamChunk}` | 流式增量（text/reasoning/tool-call delta） |
| `assistant/message` | `{turn, step, message, usage?: TokenUsage}` | 定稿 + **单步 token 用量** |
| `tool/call` | `{turn, step, callId, name, arguments}` | 工具调用卡 |
| `tool/result` | `{turn, step, message: ToolResultMessage, error?, meta?}` | 工具结果 |
| `todo/write` | `{todos:[{content,status}]}` | 任务清单（整表快照，后写覆盖） |
| `request/header` / `request/context` | 请求头/路由容量 | 模型切换线索 |
| `session/end-seed` | 空 | 种子历史边界 |

`ContentBlock`：`text | reasoning | image | tool-call{id,name,arguments} | tool-result{toolCallId,content,isError?}`。
`StreamChunk`：`block-start | text-delta | reasoning-delta | tool-call-delta | block-end | usage | finish`。
`TokenUsage`：`{inputTokens, outputTokens, cacheReadTokens?, cacheWriteTokens?, reasoningTokens?}`
（口径：input 为未缓存输入；计费输入 = input + cacheRead + cacheWrite，各桶互斥）。

## 5. Token 相关投影（dsh-token-meter）

| projection key | 值 | 说明 |
|---|---|---|
| `tokenUsage` | `{uncachedInputTokens, outputTokens, cacheReadTokens?, cacheWriteTokens?}` | 会话累计（持久日志折叠，权威统计） |
| `contextPressure` | `{pressureTokens?, projectedTokens?, contextWindow?}` | 上下文压测/预测/窗口；占用 = projected/contextWindow |
| `contextBreakdown` | `{systemTokens?, toolsTokens?, messageTokens?}` | 上下文构成（**4 字符/token 启发式估算**，勿当计费口径） |
| `title` | `string \| null` | 会话标题 |
| `sessionListMetadata` | `{blank, lastPromptAt}` | 列表冷启动提示 |

来源：`session/projection` 帧（live）、`session.history` 尾页 `projections` 块、
`session.list` 行内 `projections`（冷启动基线），三者统一按 higher-seq-wins 收敛。

## 6. 信任围栏（部署必须知道）

- 每个 `/api` 请求/升级都校验 `Host` 头：loopback 或 `trustedHosts` 精确匹配（WHATWG 规范化）。
- `dsh web --host 0.0.0.0` 被 CLI 拒绝；非 loopback 组合必须显式 `--trusted-host`。
- 因此网关回连 `127.0.0.1:3080` 天然通过围栏，无需改动 DSH 任何配置。

## 7. 官方类型 → 本项目代码映射

| 官方类型（npm 包） | 本仓库 |
|---|---|
| `RpcMessage`（rpc.d.ts） | `Wire.kt`（envelope + clientRequest/clientResponse 构造器） |
| `RpcMethodMap` | `ApiPayloads.kt`（payload 构造器） |
| `MuxFrame` / `HostFrame`（events.d.ts） | `Frames.kt`（容错解析，未知帧 → Unknown） |
| `SessionEventMap`（types.d.ts） | `SessionEvents.kt`（RawSessionEvent + 按需解码） |
| `TokenUsageProjection` / `ContextPressure` / `ContextBreakdown` | `Frames.kt` |
| `AbstractApiClient` / `ConnectionController` | `DshApiClient.kt` / `ConnectionManager.kt` |
| 官方客户端 surface fold | `SessionFolder.kt` |
