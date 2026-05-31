# 测试计划 - 阶段 8 Agent Runtime 内核

## 1. 测试目标

验证 Agent Runtime 重构只显式化当前 ask 编排，不破坏 `/api/agent/ask` 响应兼容、Trace 查询、LLM fallback、学习计划生成、Collector 错误处理和默认无 MySQL 可运行能力。

## 2. 测试范围

- P0：
  - Planner 固定生成 mock/real 两类 plan。
  - PlanExecutor 成功执行 mock 链路并产出推荐、学习计划、回答和 Trace。
  - real 链路 README enrichment 失败 best-effort，search 失败中断。
  - Trace 记录 plan/step/observation 摘要，且脱敏、截断生效。
  - `/api/agent/ask` 响应字段兼容。
- P1：
  - 持久化启用时 Trace tool calls 仍可落库。
  - LLM disabled / unavailable fallback 不影响 Runtime。
  - `openscout.learning.enabled=false` 时 Runtime 能跳过学习计划步骤或记录 skipped。
- 不覆盖：
  - 项目对比报告。
  - 前端、SSE、Streaming、`/api/agent/runs`。
  - RAG、Project Memory、向量检索。
  - Evidence-aware ReAct 循环。
  - Reflection Verifier。
  - 生产鉴权、多用户隔离。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| mock planner | 单元 | `mockAgent=true` | plan 包含 interpret/search/score/learning/answer，不包含 fetch_readme | P0 |
| real planner | 单元 | `mockAgent=false` | plan 包含 fetch_readme，step 顺序稳定 | P0 |
| mock executor 成功 | 单元/集成式单测 | mock Collector 返回 repo list | 返回 recommendations、learningPlan、answer，Trace SUCCESS | P0 |
| search 失败 | 单元 | Collector 抛 `CollectorUnavailableException` | search step FAILED，Trace FAILED，`AgentCallException` 语义保留 | P0 |
| README 单项失败 | 单元 | `getReadme()` 抛 404 或 RateLimit | observation 记录跳过，整体仍可评分和回答 | P0 |
| LLM fallback | 单元 | `openscout.llm.enabled=false` | goal/answer/learning 走模板或 fallback，不中断 | P0 |
| Trace 脱敏 | 单元 | observation 包含 `api_key=abc` | Trace 中保存 `<redacted>`，长文本截断 | P0 |
| ask 响应兼容 | Controller/Service 测试 | `AgentAskRequest` | 保留 `traceId`、`answer`、`recommendations`、`learningPlan`、`latencyMs` | P0 |
| 持久化关闭 | 单元/回归 | `openscout.persistence.enabled=false` | 不需要 MySQL，测试通过 | P0 |
| Compose 配置 | 命令验证 | `deploy/docker-compose.yml` | config 通过 | P1 |

## 4. 验证命令

```bash
cd openscout-agent-server && mvn test
cd openscout-repo-collector && go test ./...
docker compose -f deploy/docker-compose.yml config
```

可选手工验证：

```bash
# 启动 Go Collector 和 Java Agent 后
curl -s http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"I want to learn Spring AI agent projects"}'

curl -s http://localhost:8080/api/agent/traces/<traceId>
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-05-31 | `cd openscout-agent-server && mvn test` | 通过 | 29 tests，覆盖 Runtime/Trace/AgentService 回归 |
| 2026-05-31 | `cd openscout-repo-collector && go test ./...` | 通过 | 使用项目本地 `.tools/go`、本地 GOCACHE/GOPATH |
| 2026-05-31 | `docker compose -f deploy/docker-compose.yml config` | 通过 | Compose 配置有效 |
