# 测试计划 - 阶段 9 Tool Runtime

## 1. 测试目标

验证阶段 9 将现有 Agent Runtime step 标准化为 Tool Runtime 后，`/api/agent/ask` 的响应、Trace、错误处理、LLM fallback、学习计划和 Collector 部分失败语义保持兼容。

## 2. 测试范围

- P0：
  - Tool Registry / Tool Executor 契约、重复注册、未知 Tool、成功和失败路径。
  - `interpret_goal`、`search_repos`、`fetch_readme`、`score_projects`、`generate_learning_plan`、`generate_answer` Tool 的关键行为。
  - `PlanExecutor` 委托 Tool Runtime 后的 mock 成功、Collector 不可用、real README 部分失败。
  - Trace 中 plan/step/observation 与 Tool Runtime 事件均可见，且摘要脱敏。
- P1：
  - 持久化开启时 repo/analysis/learning plan 写入失败不阻塞 ask。
  - RateLimit 保留 `Retry-After` 语义。
  - LLM disabled 和 LLM 调用异常 fallback。
- 不覆盖：
  - Project Memory / RAG。
  - Evidence-aware ReAct。
  - Reflection Verifier。
  - SSE/Streaming。
  - Spring AI `@Tool` 注解或 MCP。
  - 生产鉴权、多用户隔离和配额。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| Registry 注册唯一 Tool | 单元 | 多个不同 toolName Bean | 可按 toolName 查找 | P0 |
| Registry 检测重复 Tool | 单元 | 两个相同 toolName Bean | 启动或构造失败，错误清晰 | P0 |
| 未知 Tool | 单元 | Planner step toolName 不存在 | Executor 抛明确异常并记录失败 | P0 |
| Tool Executor 成功 | 单元 | 可成功返回的测试 Tool | 记录 started/finished，返回 ToolResult | P0 |
| Tool Executor fatal 失败 | 单元 | 抛 `CollectorUnavailableException` 的 Tool | 记录 failed，并保留原异常语义 | P0 |
| Tool Executor recoverable 失败 | 单元 | 返回 recoverable ToolError | 记录 failed/finished，Plan 继续执行 | P0 |
| mock 搜索 Tool | 单元 | `mockAgent=true`，keyword | 调用 `fetchMockRepos`，输出 items 摘要 | P0 |
| real 搜索 Tool 限流 | 单元 | `searchRepos` 抛 `RateLimitException` | 异常向上保留，Controller 可设置 Retry-After | P0 |
| README Tool 部分失败 | 单元 | Top repo 中 README 404/429/异常 | 失败 repo skipped，整体继续 | P0 |
| 评分 Tool | 单元 | repo list + userGoal | 生成排序后的 recommendations，分数来自规则评分 | P0 |
| 学习计划 Tool 持久化关闭 | 单元 | `persistence.enabled=false` | 返回 7 天临时计划，`persisted=false` | P0 |
| 学习计划 Tool 持久化异常 | 单元 | persistence service 抛异常 | 返回临时计划并记录错误摘要 | P1 |
| 回答 Tool LLM disabled | 单元 | `llm.enabled=false` | 返回模板 fallback，不改评分 | P0 |
| PlanExecutor mock 回归 | 集成式单元 | mock repo list | ask 结果含 recommendations、learningPlan、answer | P0 |
| PlanExecutor search 失败 | 集成式单元 | Collector 不可用 | Trace 记录 Tool/step failed，异常向上 | P0 |
| Trace 脱敏 | 单元 | input/output 含 `apiKey=secret` | Trace 保存 `<redacted>` 且截断超长摘要 | P0 |

## 4. 验证命令

```bash
cd openscout-agent-server && mvn test
cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
docker compose -f deploy/docker-compose.yml config
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-05-31 | `cd openscout-agent-server && mvn test` | 通过 | 34 tests |
| 2026-05-31 | `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...` | 通过 | 使用项目本地 Go 1.26.3 |
| 2026-05-31 | `docker compose -f deploy/docker-compose.yml config` | 通过 | Compose 配置可解析 |
