# Demo Cases

## Case 1: 推荐 Spring AI Agent 学习项目

```bash
curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想一周内学习 Spring AI Agent，帮我找几个适合学习的开源项目"}'
```

预期：

- 返回 `spring-projects/spring-ai` 等候选项目。
- 每个项目包含规则评分和 evidence。
- 返回 `traceId`。

## Case 2: Go Collector mock 项目列表

```bash
curl http://localhost:8081/api/repos/mock
```

预期：

- 返回固定项目列表。
- 不依赖 GitHub Token。

## Case 3: 批量项目画像，允许部分失败

```bash
curl -X POST http://localhost:8081/api/repos/batch-profile \
  -H 'Content-Type: application/json' \
  -d '{"repos":["spring-projects/spring-ai","langchain4j/langchain4j","bad"]}'
```

预期：

- 合法项目进入 `items`。
- 非法项目进入 `errors`，不影响整体响应。
