# MindIsle AI SSE 接口说明（DeepSeek v3.2 / 阿里云）

本文档描述新增的 AI 对话接口，供客户端接入使用。接口基于现有 JWT 鉴权体系，支持：

- 按用户隔离会话
- 服务端持久化全量历史消息
- SSE 流式输出
- 基于 `Last-Event-ID` 的断线重连回放

## 1. 基础约定

- API 前缀：`/api/v1/ai`
- 鉴权：所有接口都需要 `Authorization: Bearer <accessToken>`
- 响应包装（非 SSE）：

```json
{
  "code": 0,
  "message": "OK",
  "data": {}
}
```

## 2. 数据结构

### 2.1 创建会话请求

```json
{
  "title": "可选，<=100字符"
}
```

### 2.2 创建会话响应 `CreateConversationResponse`

```json
{
  "conversationId": 1,
  "title": "慢病咨询",
  "createdAt": "2026-02-21T06:10:00Z"
}
```

### 2.3 发起流式对话请求 `StreamChatRequest`

```json
{
  "userMessage": "最近睡眠不太好怎么办？",
  "clientMessageId": "2c47f6f4-9a58-4a9e-8e53-3fe8a9f42ed6",
  "temperature": 0.7,
  "maxTokens": 2048
}
```

- `clientMessageId` 为客户端幂等键，建议 UUID。
- 重试同一条用户消息时必须复用同一个 `clientMessageId`。

## 3. HTTP 接口

### 3.1 创建会话

- `POST /api/v1/ai/conversations`
- Headers：
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`
- Body：`CreateConversationRequest`
- 成功：`201`
- 响应：`ApiResponse<CreateConversationResponse>`

### 3.2 会话列表

- `GET /api/v1/ai/conversations?limit=20&cursor=<optional>`
- Headers：
  - `Authorization: Bearer <accessToken>`
- Query：
  - `limit`：可选，范围 1~50，默认 20
  - `cursor`：可选，上一页返回的 `nextCursor`
- 成功：`200`
- 响应：

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "items": [
      {
        "conversationId": 12,
        "title": "慢病咨询",
        "summary": "历史摘要...",
        "lastMessageAt": "2026-02-21T06:30:00Z",
        "createdAt": "2026-02-20T10:00:00Z"
      }
    ],
    "nextCursor": "11"
  }
}
```

### 3.3 历史消息

- `GET /api/v1/ai/conversations/{conversationId}/messages?limit=50&before=<optional>`
- Headers：
  - `Authorization: Bearer <accessToken>`
- Query：
  - `limit`：可选，范围 1~100，默认 50
  - `before`：可选，分页游标（消息 ID）
- 成功：`200`
- 响应：`ApiResponse<ListMessagesResponse>`

### 3.4 发起/重试流式生成（SSE）

- `POST /api/v1/ai/conversations/{conversationId}/stream`
- Headers：
  - `Authorization: Bearer <accessToken>`
  - `Content-Type: application/json`
  - `Accept: text/event-stream`
  - `Last-Event-ID`：可选（同一 generation 断线恢复时使用）
- Body：`StreamChatRequest`
- 成功：`200` + SSE 流

### 3.5 按 generation 重连（SSE）

- `GET /api/v1/ai/generations/{generationId}/stream`
- Headers：
  - `Authorization: Bearer <accessToken>`
  - `Accept: text/event-stream`
  - `Last-Event-ID`：可选，格式 `generationId:seq`
- 成功：`200` + SSE 流

## 4. SSE 协议

### 4.1 事件通用格式

服务端按如下格式推送：

```text
id: <generationId>:<seq>
event: <eventName>
data: <json>

```

其中：

- `id` 用于断线重连
- `event` 为事件名
- `data` 为 JSON 文本

### 4.2 事件类型

1. `meta`

```json
{
  "generationId": "abc123",
  "conversationId": 12,
  "model": "deepseek-v3.2",
  "createdAt": "2026-02-21T06:30:00Z"
}
```

2. `delta`

```json
{ "text": "本次新增文本片段" }
```

3. `usage`（可选）

```json
{
  "promptTokens": 120,
  "completionTokens": 300,
  "totalTokens": 420
}
```

4. `done`

```json
{
  "assistantMessageId": 987,
  "finishReason": "stop"
}
```

5. `error`

```json
{
  "code": 50201,
  "message": "Upstream request failed"
}
```

说明：

- `reasoning_content` 不向客户端透出。
- 当收到 `done` 或 `error` 后，本次流结束。

## 5. 断线重连流程

1. 首次调用 `POST /conversations/{conversationId}/stream`。
2. 从 `meta` 事件中拿到 `generationId`。
3. 客户端持续记录最后一个 SSE `id`（如 `abc123:17`）。
4. 断线后调用 `GET /generations/{generationId}/stream`，并携带 `Last-Event-ID: abc123:17`。
5. 服务端先回放 `seq > 17` 的事件，再继续实时推送（若任务仍在运行）。
6. 回放窗口默认 10 分钟，超时返回 `40911`，客户端需重发问题创建新 generation。

## 6. 幂等与历史策略

- 服务端保存每个用户的完整会话消息历史（`USER/ASSISTANT`）。
- 调用模型时使用：
  - 固定 System 提示词（仅服务端代码硬编码）
  - 会话摘要
  - 最近 N 条消息（默认 12 条）
- 同一会话下：
  - 相同 `clientMessageId` + 相同 `userMessage`：视为幂等重试，复用已有 generation。
  - 相同 `clientMessageId` + 不同 `userMessage`：返回 `40910`。

## 7. 新增错误码

| code | HTTP | 含义 |
|---|---|---|
| 40010 | 400 | `AI_INVALID_ARGUMENT`，参数非法（空消息、超长、非法游标、非法 Last-Event-ID 等） |
| 40310 | 403 | `AI_CONVERSATION_FORBIDDEN`，会话/生成任务不属于当前用户 |
| 40410 | 404 | `AI_CONVERSATION_NOT_FOUND` |
| 40411 | 404 | `AI_GENERATION_NOT_FOUND` |
| 40910 | 409 | `AI_IDEMPOTENCY_CONFLICT`，同 `clientMessageId` 内容冲突 |
| 40911 | 409 | `AI_REPLAY_WINDOW_EXPIRED`，重连回放窗口超时或回放缺口 |
| 42910 | 429 | `AI_RATE_LIMITED`，上游限流 |
| 50020 | 500 | `AI_STREAM_INTERNAL_ERROR`，服务端流处理异常 |
| 50201 | 502 | `AI_UPSTREAM_ERROR`，阿里云/模型上游异常 |

同时仍可能返回已有通用错误码：

- `40100`：未登录/Token 无效
- `50000`：未捕获内部错误

## 8. 客户端建议

1. 每条用户消息都生成唯一 `clientMessageId`（UUID）。
2. 接收 SSE 时，实时缓存最后 `id`。
3. 断线后优先走 `GET /generations/{generationId}/stream + Last-Event-ID` 重连。
4. 收到 `40911` 后，提示用户网络中断过久并允许“重新发送”。
