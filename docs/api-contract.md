# API Contract

## Java Agent Server

### POST `/api/agent/ask`

Request:

```json
{
  "question": "我想一周内学习 Spring AI Agent",
  "goal": null,
  "mode": "mock"
}
```

Response (200):

```json
{
  "traceId": "uuid",
  "answer": "已基于 mock Agent 完成项目推荐...",
  "recommendations": [
    {
      "fullName": "spring-projects/spring-ai",
      "description": "...",
      "language": "Java",
      "stars": 12000,
      "updatedAt": "2026-05-20T00:00:00Z",
      "score": {
        "totalScore": 86,
        "activityScore": 20,
        "docScore": 16,
        "matchScore": 30,
        "learningScore": 10,
        "resumeValueScore": 10,
        "evidence": []
      },
      "reason": "..."
    }
  ],
  "learningPlan": {
    "goalId": null,
    "goal": "我想一周内学习 Spring AI Agent",
    "targetStack": "Java",
    "durationDays": 7,
    "persisted": false,
    "tasks": [
      {
        "id": null,
        "dayNo": 1,
        "title": "明确目标与项目范围",
        "detail": "围绕用户目标阅读推荐项目 README、快速开始和目录结构...",
        "expectedOutput": "写出项目定位、核心模块猜测和 3 个待验证问题。",
        "status": "TODO"
      }
    ]
  },
  "latencyMs": 42
}
```

Error (502 Bad Gateway):

```json
{
  "traceId": "uuid",
  "message": "Agent 执行失败... (error details)"
}
```

限流时 Response 头会包含 `Retry-After: <seconds>`。

### GET `/api/agent/traces/{traceId}`

仅用于本地演示和排障。后续如果暴露到非本地环境，需要补鉴权和审计。

### GET `/api/learning/goals/{goalId}`

查询已持久化的学习目标和 7 天任务。仅当 `OPSCOUT_PERSISTENCE_ENABLED=true` 且 `/api/agent/ask` 返回 `learningPlan.persisted=true` 时有可查询数据。

Response (200):

```json
{
  "goalId": 1,
  "goal": "我想一周内学习 Spring AI Agent",
  "targetStack": "Java",
  "durationDays": 7,
  "persisted": true,
  "tasks": [
    {
      "id": 10,
      "dayNo": 1,
      "title": "明确目标与项目范围",
      "detail": "围绕用户目标阅读推荐项目 README、快速开始和目录结构...",
      "expectedOutput": "写出项目定位、核心模块猜测和 3 个待验证问题。",
      "status": "TODO"
    }
  ]
}
```

不存在时返回 404。

### PATCH `/api/learning/tasks/{taskId}/status`

更新单个学习任务状态。状态只允许 `TODO`、`DOING`、`DONE`。

Request:

```json
{
  "status": "DONE"
}
```

Response (200):

```json
{
  "id": 10,
  "dayNo": 1,
  "title": "明确目标与项目范围",
  "detail": "围绕用户目标阅读推荐项目 README、快速开始和目录结构...",
  "expectedOutput": "写出项目定位、核心模块猜测和 3 个待验证问题。",
  "status": "DONE"
}
```

非法状态返回 400；任务不存在返回 404。`/api/learning/*` 当前仅用于本地 Demo，未做登录鉴权，不应公网暴露。

## Go Repo Collector

### GET `/api/repos/mock`

返回固定 mock 项目列表。

### GET `/api/repos/search?keyword=&limit=&mode=`

`mode=mock` 使用本地数据；`mode=github` 调用 GitHub API。

### GET `/api/repos/{owner}/{repo}/profile?mode=`

返回项目画像。

### GET `/api/repos/{owner}/{repo}/readme?mode=`

返回 README 内容摘要，真实模式会限制返回长度。

### POST `/api/repos/batch-profile?mode=`

Request:

```json
{
  "repos": [
    "spring-projects/spring-ai",
    "langchain4j/langchain4j"
  ]
}
```

Response:

```json
{
  "items": [],
  "errors": []
}
```

### 错误响应（阶段 5+）

所有端点非 2xx 时返回结构化 `ErrorResponse`：

```json
{
  "error": "github rate limit exceeded",
  "code": "RATE_LIMITED",
  "retryAfter": 60
}
```

`code` 可选值：

| code | HTTP 状态码 | 含义 |
|---|---|---|
| `RATE_LIMITED` | 429 | GitHub 限流，`retryAfter` 有效 |
| `FORBIDDEN` | 403 | GitHub 鉴权失败或访问被拒 |
| `NOT_FOUND` | 404 | 仓库/资源不存在 |
| `API_ERROR` | 其他 4xx/5xx | 通用 GitHub API 错误 |
| `INTERNAL` | N/A | Go Collector 内部错误（非 GitHub 侧） |

兼容性说明：Java 侧优先解析 `code` 字段，解析失败时 fallback 到旧版 `{"message":"..."}` 格式。
