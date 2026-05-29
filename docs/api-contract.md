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

Response:

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
  "latencyMs": 42
}
```

### GET `/api/agent/traces/{traceId}`

仅用于本地演示和排障。后续如果暴露到非本地环境，需要补鉴权和审计。

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
