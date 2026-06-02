# 执行日志 - 阶段 14 Agent Evaluation

## 基本信息

- change：`openscout-agent-evaluation`
- status：done
- created：2026-06-02
- last_updated：2026-06-02

## Research 记录

- 当前工作区已从 `main` 创建并切换到 `feature/13-agent-evaluation`。
- `项目实施进度.md` 将阶段 14 定义为“建立可复现 Agent 评测集和指标报告”，验收标准覆盖推荐相关性、evidence 覆盖、hallucination 拦截、memory hit、fallback、延迟和 GitHub API 调用节省。
- `AgentController` 已有 `/api/agent/ask`、`/api/agent/traces/{traceId}`、`/api/agent/runs`、`/api/agent/runs/{runId}`、`/api/agent/runs/{runId}/events`。
- `AgentService.ask()` 可同步返回 `AgentAskResponse`；`AgentRunService.createRun()` 可异步执行并保留 run 状态、traceId、结果、错误摘要和 latency。
- `PlanExecutor` 通过固定 plan 执行 Tool Runtime，并将结果汇总为 `AgentRuntimeResult`。
- `TraceService` 记录 `TraceToolCall`，并通过 `sanitize()` 处理 token/key/password/secret 等敏感字段和摘要长度。
- `CheckMemoryTool`、`SearchReposTool`、`FetchReadmeTool`、`EvidenceReActTool`、`VerifyAnswerTool` 已记录可用于评测的 Trace 事件。
- 当前 `openscout-agent-server/src/main/java/com/openscout` 和 `src/test/java/com/openscout` 下没有 `evaluation` 包、固定评测集或报告生成器。

## 执行记录

| 时间 | 动作 | 文件 | 结果 |
|---|---|---|---|
| 2026-06-02 | 创建阶段分支 | `feature/13-agent-evaluation` | 已从 `main` 创建并切换 |
| 2026-06-02 | 创建 proposal | `spec.md`、`tasks.md`、`test-spec.md`、`log.md` | 已完成 |
| 2026-06-02 | 同步项目进度 | `项目实施进度.md` | 已标记阶段 14 proposal 进行中 |
| 2026-06-02 | apply Task 1-3 | `evaluation/*`、`evaluation/cases.json`、evaluation tests | 完成 case loader、runner、environment、sample、metric 和 summary |
| 2026-06-02 | apply Task 4-5 | `EvaluationReport*`、`AgentEvaluationCommandTest`、README | 完成 JSON/Markdown report writer、默认评测命令和 README 说明 |
| 2026-06-02 | apply Task 6 | README、项目进度和 change 文档 | 完成文档同步和验证记录 |
| 2026-06-02 | review fix | `cases.json`、`EvaluationSummary`、evaluation tests、README、change 文档、项目进度 | 默认评测补 seeded memory REAL plan；required skipped 不再计为 required passed |
| 2026-06-02 | review fix 2 | `EvaluationMetricCalculator`、`EvaluationSampleResult`、`EvaluationReportWriter`、evaluation tests、README、change 文档、项目进度 | README cache hit 不再计为真实 GitHub README 调用；样本报告增加 mode；optional skipped 不再标记 passed |
| 2026-06-02 | achieve | `code_copilot/knowledge/index.md`、`spec.md`、`log.md`、`项目实施进度.md` | 阶段 14 知识沉淀完成，change 标记 done，待合并回 main |

## 决策记录

- 第一版评测默认 mock-first，不依赖 GitHub Token、DeepSeek Key、Docker 或外网。
- 第一版报告输出到 `openscout-agent-server/target/openscout-evaluation/`，不新增 DDL，也不把评测历史持久化到 MySQL。
- 指标来源优先使用 response + Trace toolName 事件，不直接解析完整 README、prompt 或模型响应。
- 真实 GitHub/LLM enabled 评测只作为 optional，不作为默认必过项。
- 阶段 14 不做前端 dashboard、生产压测、CI、鉴权、多用户隔离或跨实例调度。
- 报告 JSON 使用 ISO-8601 `generatedAt`，避免 timestamp 数字不便审查。

## 验证记录

- proposal 阶段未执行实现测试；当前只完成规则阅读、代码研究、分支创建和 proposal 文档创建。
- `cd openscout-agent-server && mvn test -Dtest='Evaluation*Test,AgentEvaluation*Test'`：通过，7 tests；生成默认 JSON/Markdown 评测报告。
- `cd openscout-agent-server && mvn test`：通过，123 tests。
- `cd openscout-repo-collector && go test ./...`：失败，系统 PATH 中 `go` 不存在。
- `PATH=/home/lpc/project/OpenScout/.tools/go/bin:$PATH GOCACHE=/home/lpc/project/OpenScout/.tools/go-cache GOPATH=/home/lpc/project/OpenScout/.tools/go-path go test ./...`：通过。
- `docker compose -f deploy/docker-compose.yml config`：阻塞；当前 WSL 2 distro 未启用 Docker Desktop integration，Docker CLI 不可用。
- review 修复后 `cd openscout-agent-server && mvn test -Dtest='Evaluation*Test,AgentEvaluation*Test'`：通过，8 tests；默认报告 `totalCases=3`、`memoryHitCases=1`、`githubCallSavings=2`。
- review 修复后 `cd openscout-agent-server && mvn test`：通过，124 tests。
- review 修复后 `PATH=/home/lpc/project/OpenScout/.tools/go/bin:$PATH GOCACHE=/home/lpc/project/OpenScout/.tools/go-cache GOPATH=/home/lpc/project/OpenScout/.tools/go-path go test ./...`：通过。
- review 修复后 `docker compose -f deploy/docker-compose.yml config`：通过。
- review fix 2 后 `cd openscout-agent-server && mvn test -Dtest='Evaluation*Test,AgentEvaluation*Test'`：通过，8 tests；默认报告 `githubReadmeFetchCalls=0`、sample mode 包含 `MOCK`/`REAL`、optional skipped `passed=false`。
- review fix 2 后 `cd openscout-agent-server && mvn test`：通过，124 tests。
- review fix 2 后 `PATH=/home/lpc/project/OpenScout/.tools/go/bin:$PATH GOCACHE=/home/lpc/project/OpenScout/.tools/go-cache GOPATH=/home/lpc/project/OpenScout/.tools/go-path go test ./...`：通过。
- review fix 2 后 `docker compose -f deploy/docker-compose.yml config`：通过。

## 遗留问题

- 默认评测结果只代表本地 fixture，不代表生产 SLA、线上准确率或大规模 benchmark。
- 真实 GitHub / LLM enabled 评测仍为 optional，不作为默认必过项。
- raw `eventCounts` 是 Trace 事件计数，不等同于 API 调用次数；正式 API 调用统计以 `EvaluationMetrics` / `EvaluationSummary` 为准。
