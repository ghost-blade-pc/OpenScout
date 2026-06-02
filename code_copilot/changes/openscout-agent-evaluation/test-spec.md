# 测试计划 - 阶段 14 Agent Evaluation

## 1. 测试目标

验证阶段 14 新增的 Agent Evaluation 能用固定命令生成可复现报告，并覆盖推荐相关性、evidence 覆盖、hallucination/verifier、memory hit、fallback、latency 和 GitHub API 调用节省指标。

## 2. 测试范围

- Evaluation case / fixture 加载与字段校验。
- AgentEvaluationRunner 执行 mock-first case 并收集 response + Trace。
- EvaluationMetricCalculator 指标计算。
- EvaluationReportWriter JSON/Markdown 输出。
- 默认固定评测命令。
- 现有 Agent Runtime、Tool Runtime、Trace、Run、Memory、ReAct、Verifier 回归。

## 3. 不测试范围

- 不做生产压测或线上 SLA 验证。
- 不把真实 GitHub/LLM enabled 作为默认必过项。
- 不测试前端 dashboard、CI、鉴权、多用户隔离、跨实例调度。
- 不验证数据库迁移或评测历史持久化，因为阶段 14 不新增 DDL。

## 4. 测试用例矩阵

| 用例 | 类型 | 输入 | 期望 | 优先级 |
|---|---|---|---|---|
| fixture 加载 | 单元 | 固定 evaluation cases | 必填字段、mode、threshold、tags 校验通过 | P0 |
| fixture 缺字段 | 单元 | 缺 question/expectation 的 case | 抛出可解释校验错误 | P0 |
| mock 推荐相关性 | 集成 | Spring AI Agent 学习目标 | Top 推荐命中预期关键词，指标达阈值 | P0 |
| evidence 覆盖 | 集成 | mock case + recommendations | evidence 非空且覆盖关键维度 | P0 |
| verifier 统计 | 单元/集成 | 构造分数篡改或无证据声明 | 报告记录 verifier issue | P0 |
| memory hit | 集成/单元 | seeded memory 或构造 Trace | 统计 `memory_hit`、`search_repos_skipped` 和节省调用 | P0 |
| fallback 统计 | 单元/集成 | LLM disabled 或 recoverable failure | 报告记录 fallback，不误判为失败 | P0 |
| latency 指标 | 单元/集成 | 多个 sample latency | 输出 sample latency、总体 p50/p95 或 max | P1 |
| GitHub 调用节省 | 单元 | memory miss/hit 对照 Trace | 输出 search/readme/cache 事件差异 | P1 |
| report JSON | 单元 | EvaluationReport | JSON 字段稳定且不含敏感值 | P0 |
| report Markdown | 单元 | EvaluationReport | 包含总体结论、样本表、指标表和边界说明 | P0 |
| 默认评测命令 | 集成 | `mvn test -Dtest=AgentEvaluationCommandTest` | 生成 JSON/Markdown 报告 | P0 |
| Java 全量回归 | 回归 | `mvn test` | 通过 | P0 |
| Go 全量回归 | 回归 | `go test ./...` | 通过 | P0 |
| Docker config | 回归 | `docker compose -f deploy/docker-compose.yml config` | 通过；不可用时记录环境阻塞 | P1 |

## 5. 验证命令

```bash
cd openscout-agent-server && mvn test -Dtest='Evaluation*Test,AgentEvaluation*Test'
cd openscout-agent-server && mvn test
cd openscout-repo-collector && go test ./...
docker compose -f deploy/docker-compose.yml config
```

如系统 PATH 中无 Go，使用项目本地工具链：

```bash
source scripts/use-local-tools.sh
cd openscout-repo-collector && go test ./...
```

## 6. 通过标准

- 默认评测命令生成 JSON 和 Markdown 报告。
- P0 evaluation tests 通过。
- 报告不包含 token、key、完整 README、完整 prompt、完整模型响应或异常堆栈。
- Java 全量测试通过。
- Go 全量测试通过。
- Docker config 通过；若环境不可用，必须在 `log.md` 和进度文档中记录具体阻塞。

## 7. 失败处理

- 指标阈值失败：保留 case id、traceId、失败指标和关键 Trace 事件。
- 外部 API 限流：真实模式 case 标记 optional 或 skipped，并记录 retryAfter/限流摘要。
- LLM 缺 Key：默认进入 fallback 指标，不作为默认评测失败。
- 报告写入失败：测试失败，并输出目标路径与错误摘要。

## 8. 已执行验证

- `cd openscout-agent-server && mvn test -Dtest='Evaluation*Test,AgentEvaluation*Test'`：通过，8 tests；覆盖 fixture loader、指标计算、报告写入、runner、默认评测命令、seeded memory REAL plan、required skipped 判定。
- `cd openscout-agent-server && mvn test`：通过，124 tests。
- `cd openscout-repo-collector && go test ./...`：失败，系统 PATH 中 `go` 不存在；已使用项目本地 `.tools/go`、`.tools/go-cache`、`.tools/go-path` 后通过。
- `docker compose -f deploy/docker-compose.yml config`：通过。
- 默认评测报告已生成到 `openscout-agent-server/target/openscout-evaluation/agent-evaluation-report.json` 和 `.md`。
- review 修复后默认评测报告摘要：`totalCases=3`、`passedCases=2`、`skippedCases=1`、`memoryHitCases=1`、`githubCallSavings=2`。
- review fix 2 后默认评测报告摘要：`githubReadmeFetchCalls=0`、sample table/JSON 包含 `mode`、optional skipped 样本 `passed=false`。
