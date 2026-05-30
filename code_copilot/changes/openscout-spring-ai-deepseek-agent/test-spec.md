# 测试计划 - OpenScout Spring AI + DeepSeek Agent 编排

## P0 — 自动化测试（必须通过）

### Java 单测

```bash
cd openscout-agent-server && mvn test
```

- `GoalInterpreterTest` — JSON 解析（markdown 代码块/裸 JSON/null fallback），LLM 禁用时的 fallback 行为。
- `AnswerGeneratorTest` — 模板回答生成、空推荐列表处理、prompt 构建含评分详情。
- 现有测试全部通过（重命名 `MockAgentService` → `AgentService` 无破坏）。

### Go 单测

```bash
cd openscout-repo-collector && go test ./... && go vet ./...
```

- Go Collector 侧无变更，测试通过。

### Docker Compose 配置校验

```bash
docker compose -f deploy/docker-compose.yml config
```

- MySQL/Redis 配置不变。

## P1 — 集成测试（手动验证）

### LLM 禁用模式

```bash
# 不设 DEEPSEEK_API_KEY 或设 OPSCOUT_LLM_ENABLED=false
cd openscout-agent-server && mvn spring-boot:run
curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想学习 Spring AI Agent"}'
```

期望：返回 200 + traceId + 模板回答（含 "LLM 不可用" 或 "规则评分"）。

### LLM 启用模式

```bash
export DEEPSEEK_API_KEY=<secret>
cd openscout-agent-server && mvn spring-boot:run
curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想学习 Spring AI Agent"}'
```

期望：返回 200 + traceId + LLM 自然语言回答（非模板文本）。

### LLM Trace 记录

```bash
curl http://localhost:8080/api/agent/traces/<traceId> | jq .toolCalls
```

期望：`toolCalls` 数组含 `llm_goal_interpret` 和 `llm_answer_generate` 记录。

### Fallback 验证

```bash
# 设错误的 DEEPSEEK_BASE_URL
export DEEPSEEK_BASE_URL=https://invalid.example.com
# 调用后应 fallback 到模板回答，不返回 500
```

期望：返回 200 + 模板回答（非 500 错误）。

## P2 — 质量对比（人工评估）

- 同一 question 分别测试 LLM 模式和模板模式
- 对比回答自然度、推荐理由个性化程度、评分引用准确性

## 测试环境

- JDK 17 + Maven
- Go 1.26.3（通过 `scripts/use-local-tools.sh` 启用）
- DeepSeek API Key（P1/P2 测试需配置）
