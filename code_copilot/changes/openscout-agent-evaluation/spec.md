# 阶段 14 Agent Evaluation
> status: done
> created: 2026-06-02
> complexity: 复杂

## 1. 背景与目标

阶段 13 已提供 Agent Run 与 SSE 事件流，阶段 8-12 已完成 Agent Runtime、Tool Runtime、Project Memory / RAG、Evidence ReAct 和 Reflection Verifier。当前仍缺少一个可复现的评测入口，无法稳定回答“推荐是否相关、证据是否充分、Verifier 是否拦截幻觉、Memory 是否节省 GitHub 调用、fallback 是否可用、延迟是否退化”等问题。

阶段 14 的目标是建立第一版本地 Agent Evaluation：用固定评测集、可重复命令和结构化报告，把 Agent 执行结果转成可审查指标。完成后应能在本地运行固定命令，生成 JSON/Markdown 报告，覆盖推荐相关性、evidence 覆盖、hallucination 拦截、memory hit、fallback、延迟和 GitHub API 调用节省。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的 Runtime / Tool / Trace / Run / Evaluation。
- 调用方向：Evaluation Runner -> AgentService 或 AgentRunService -> PlanExecutor / ToolExecutor -> TraceService -> Evaluation Metrics / Report。
- 是否涉及高风险项：是。
- 高风险类型：外部 GitHub API、模型 fallback、Trace 脱敏、评测口径误导、并发执行、文件输出。

### 1.2 范围裁剪

- 本次包含：
  - 新增固定评测集，覆盖 mock、真实模式可选、memory hit、fallback、evidence gap、verifier hallucination 检查和延迟指标。
  - 新增 Java 侧 Evaluation 模型、Runner、指标计算器和报告生成器。
  - 新增可重复执行命令，优先通过 Maven 测试或 Spring profile 运行，不引入外部评测平台。
  - 报告输出 JSON 和 Markdown，记录样本级 traceId、工具事件摘要、指标、通过/失败原因和环境说明。
  - 复用 Trace/Run 事件统计 GitHub 调用、memory 命中、ReAct 补查、Verifier 结果、fallback 和延迟。
  - 增加单元测试与回归测试，保证指标计算和报告格式稳定。
- 本次不包含：
  - 前端 dashboard、在线评测服务、生产压测、CI 接入、鉴权、多用户隔离。
  - 新增 DDL、评测历史持久化表、Redis/MQ、跨实例评测调度。
  - 向量检索、FULLTEXT、embedding、动态 Tool Planning、Spring AI 动态 Tool Calling、MCP。
  - 宣称生产 SLA、真实线上准确率或大规模 benchmark。
- 后续可能拆分：
  - 阶段 15 Production Hardening 可接入 CI、演示脚本、鉴权、配额和配置治理。
  - 后续可补真实 GitHub/LLM enabled 的夜间评测或手工验收脚本，但第一版不强依赖外部 token。

## 2. Research Findings

### 2.1 相关入口与链路

- HTTP/API：`openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java` 提供 `POST /api/agent/ask`、`GET /api/agent/traces/{traceId}`、`POST /api/agent/runs`、`GET /api/agent/runs/{runId}`、`GET /api/agent/runs/{runId}/events`。
- Application Service：`openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java` 同步执行 ask；`openscout-agent-server/src/main/java/com/openscout/agent/run/AgentRunService.java` 异步执行 run，并在结果中保留 answer、recommendations、learningPlan、errorSummary、latencyMs 和 eventsUrl。
- Runtime：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java` 按 `RuleBasedAgentPlanner` 生成的固定 step 执行，并返回 `AgentRuntimeResult`。
- Tool Runtime：`openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolExecutor.java` 统一记录 `agent_tool_started`、`agent_tool_finished`、`agent_tool_failed`。
- Trace：`openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` 记录 `TraceToolCall`，并通过 `sanitize()` 脱敏和截断摘要；`find(traceId)` 可从内存或持久化读取 Trace。
- Memory：`openscout-agent-server/src/main/java/com/openscout/agent/tool/CheckMemoryTool.java` 记录 `memory_hit`、`memory_miss`、`memory_check`；`SearchReposTool` memory 命中时记录 `search_repos_skipped`。
- GitHub 调用：`SearchReposTool` 在 mock 模式记录 `repo_search_mock`，真实模式记录 `repo_search_github`；`FetchReadmeTool` 记录 `readme_fetch_github` 和 `readme_cache_hit`。
- Evidence / Verifier：`EvidenceReActTool` 记录 `evidence_gap_detected`、`evidence_follow_up_*`、`evidence_rescore_completed`、`evidence_react_stopped`；`VerifyAnswerTool` 记录 `verify_completed`，输出 summary。
- Config：`openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java` 已有 `mockAgent`、`collectorMode`、`llm`、`learning`、`persistence`、`memory`、`react`、`verifier`、`events`、`trace` 配置分组；`application.yml` 已有对应环境变量。
- Test：`openscout-agent-server/src/test/java/com/openscout` 已覆盖 AgentService、AgentRunService、PlanExecutor、TraceService、Memory、ReAct、Verifier、Controller 等，但尚无 evaluation 包、固定评测集或报告测试。

### 2.2 现有实现摘要

- 当前 mock 计划：`interpret_goal -> check_memory -> search_repos -> score_projects -> evidence_react -> generate_learning_plan -> generate_answer -> verify_answer`。
- 当前 real 计划：`interpret_goal -> check_memory -> search_repos -> fetch_readme -> score_projects -> evidence_react -> generate_learning_plan -> generate_answer -> verify_answer`。
- `/api/agent/ask` 和 run 响应均保留 `traceId`、recommendations、learningPlan、latencyMs，可作为评测样本结果来源。
- Trace 中已有足够多的 toolName 事件用于统计 memory hit、GitHub search/readme 调用、fallback、verifier、ReAct 和失败原因。
- 当前没有评测 fixture、golden expectations、指标阈值、报告目录或固定评测命令。

### 2.3 发现的问题

- 没有统一评测入口，当前只能通过单测数量或手工 curl 判断阶段能力，难以生成可复现指标。
- 没有固定样本集，无法对推荐相关性、evidence 覆盖和 hallucination 拦截做稳定回归。
- Memory 节省 GitHub API 调用目前只能从 Trace 事件人工判断，没有自动指标。
- Verifier 规则检查已有单测，但缺少端到端评测报告中的拦截率和误报说明。
- fallback 行为散落在 LLM、学习计划、GitHub 限流、Collector 不可用等路径中，缺少统一报告口径。

### 2.4 风险初判

- 指标误导风险：mock fixture 只能证明本地可重复行为，不能包装成生产准确率或线上 SLA。
- 外部 API 风险：真实 GitHub 模式受 token、限流、网络和缓存影响；第一版真实模式应标为可选，不作为默认必过。
- 模型风险：DeepSeek Key 缺失或 LLM disabled 时必须能运行评测；LLM enabled 指标应单独标注环境。
- 并发风险：如果复用 `AgentRunService`，要避免异步 run retention 清理影响评测结果读取。
- 安全风险：报告不能输出 GitHub Token、模型 Key、完整 README、完整 prompt、完整模型响应或异常堆栈。
- 文件输出风险：报告路径必须固定在仓库内可控目录，避免覆盖源码或用户文件。

## 3. 功能点

- [ ] 功能 1：新增 Evaluation Case 模型和固定评测集，描述 question、mode、期望推荐关键词、期望 evidence、期望 trace event、阈值和标签。
- [ ] 功能 2：新增 Evaluation Runner，支持按固定 case 执行 Agent，收集 response、trace、run/event 可选证据和环境信息。
- [ ] 功能 3：新增指标计算器，至少输出推荐相关性、evidence 覆盖率、hallucination/verifier 命中、memory hit、fallback、latency、GitHub API 调用节省。
- [ ] 功能 4：新增报告生成器，输出 JSON 和 Markdown，并保留样本级结果、失败原因、traceId、关键 tool event 计数和总体指标。
- [ ] 功能 5：新增固定命令入口，优先使用 Maven 测试或 Spring profile 运行，例如 `mvn test -Dtest=AgentEvaluationRunnerTest` 或等价命令。
- [ ] 功能 6：新增单元测试和回归测试，覆盖指标计算、报告格式、脱敏、mock fixture、memory hit、verifier issue 和 fallback 统计。
- [ ] 功能 7：同步 README、`test-spec.md`、`log.md` 和 `项目实施进度.md`，明确第一版评测报告的可写结论和不能夸大的口径。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| Java Package | `com.openscout.evaluation` | 新增评测模型、runner、metric、report 组件 | 新增内部模块，不影响现有 API | 删除包和测试即可回退 |
| Test Resource | `src/test/resources/evaluation/` 或等价路径 | 固定评测 case 与 golden expectations | 仅测试/评测使用 | 回退 fixture 文件 |
| Report Output | `openscout-agent-server/target/openscout-evaluation/` | 生成 JSON/Markdown 报告 | Maven target 输出，不进入 Git | 清理 target |
| Java Config | 可选 `openscout.evaluation.*` | 控制报告目录、case 过滤、超时或真实模式开关 | 默认不影响运行时 | 关闭或回退默认值 |
| Database | 无 | 不新增 DDL，不新增评测历史表 | 无迁移风险 | 不适用 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | `AgentAskRequest` | `AgentAskResponse` | 既有 MVP 无鉴权，不应公网暴露 | 保持不变 |
| `/api/agent/runs` | POST | `AgentAskRequest` | `AgentRunCreateResponse` | 既有 MVP 无鉴权，不应公网暴露 | 保持不变，可选用于评测 |
| Evaluation Runner | Maven/Test 命令 | 固定 fixture | JSON/Markdown report | 本地命令，不提供公网入口 | 新增，不影响现有 HTTP API |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector | 既有 mock/search/readme/batch-profile | 不变 | 不变 | 沿用 `CollectorClient` 和 Go 侧配置 | mock 默认必测；真实模式可选，限流单独记录 |
| DeepSeek/OpenAI 兼容接口 | 既有 ChatClient 调用 | 不变 | 不变 | 沿用 `openscout.llm.*` | 缺 Key 或 disabled 时计入 fallback，不阻断默认评测 |
| 文件系统 | `target/openscout-evaluation/` | report DTO | JSON/Markdown | 无重试 | 写入失败时测试失败并记录错误摘要 |

## 6. 风险与关注点

- 评测报告必须标明环境：mock/real、LLM enabled/disabled、persistence/memory/react/verifier/events 配置、是否使用 GitHub Token。
- 默认评测不得依赖外网、真实 GitHub Token 或模型 Key；真实模式只能作为可选 case 或手工验证。
- 指标阈值必须来自 fixture 预期和 Trace 事件，不得虚构线上准确率。
- 报告中的 answer、prompt、README、错误信息必须复用 Trace 脱敏/截断规则或只输出摘要。
- Memory hit 指标需要区分“命中缓存导致跳过 search/readme”和“缓存存在但过期/未命中”。
- GitHub API 调用节省只能在同类 case 对照下计算，例如 memory miss vs hit 的 `repo_search_github`、`readme_fetch_github`、`readme_cache_hit` 事件差异。
- Verifier 指标要区分“发现 issue”与“阻断回答”；当前 verifier 第一版只记录 Trace，不阻断主流程。

## 7. 测试策略

- 单元测试：
  - EvaluationMetricCalculator：推荐相关性、evidence 覆盖、verifier issue、memory hit、fallback、latency 和 API 调用节省计算。
  - EvaluationReportWriter：JSON/Markdown 输出字段、路径、排序和脱敏。
  - EvaluationCaseLoader：fixture 解析和缺失字段校验。
- 集成/回归测试：
  - mock fixture 跑通 AgentService，生成报告且所有 P0 case 达到阈值。
  - memory seeded case 能产生 `memory_hit` 和 `search_repos_skipped`，并统计调用节省。
  - hallucination fixture 或构造结果能触发 verifier issue，并在报告中体现。
  - fallback case 覆盖 LLM disabled 或 Collector failure 的可解释降级。
- 全量回归：
  - `cd openscout-agent-server && mvn test`
  - `cd openscout-repo-collector && go test ./...`
  - `docker compose -f deploy/docker-compose.yml config`，若 Docker 环境不可用则如实记录阻塞。

## 8. 待澄清

- 无阻塞待澄清项。默认第一版做本地、可复现、mock-first 的评测命令和报告，不接前端、不接 CI、不做线上 SLA，不强依赖真实 GitHub 或 LLM Key。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| 默认评测模式 | mock-first fixture | 默认真实 GitHub/LLM | 避免外部网络、token、限流和模型波动影响可复现性 | 真实模式指标只能标为可选 |
| 命令入口 | Maven 测试或 Spring test runner | 新增 CLI 框架 | 当前项目已用 Maven/JUnit，少引入依赖 | 报告可放在 `target` 下 |
| 指标来源 | response + Trace toolName 事件 | 只看最终回答文本 | Trace 已覆盖 memory/ReAct/verifier/fallback/API 调用摘要 | 需要保证事件名称稳定 |
| 报告格式 | JSON + Markdown | 只输出控制台文本 | JSON 便于机器读取，Markdown 便于人工审查 | 需要测试报告字段稳定 |
| 存储 | 不新增 DDL，报告写 `target` | MySQL 评测历史表 | 阶段 14 聚焦可复现本地报告，不做生产评测平台 | 历史趋势后续再做 |
| GitHub 调用节省 | 基于 Trace event 对照统计 | 直接读 GitHub rate limit header | 当前 Java Trace 已记录 search/readme/cache/memory 事件，且 mock 可测 | 不是生产 API 配额账单 |

## 10. 确认记录

- 确认时间：2026-06-02
- 确认人：用户
- 确认范围：按 proposal 执行阶段 14 Agent Evaluation 第一版；默认 mock-first 可复现评测，不接前端/CI/DDL/生产压测，真实 GitHub/LLM enabled 仅作为 optional。

## 11. Apply 结果

- 已新增 `com.openscout.evaluation` 模块：评测 case/expectation/threshold、fixture loader、environment snapshot、metric calculator、runner、report、report writer。
- 已新增默认 fixture：`src/test/resources/evaluation/cases.json`，包含 1 个必测 mock case、1 个必测 seeded memory REAL plan case 和 1 个 optional real GitHub/memory case。
- 已新增默认评测命令：`cd openscout-agent-server && mvn test -Dtest=AgentEvaluationCommandTest`。
- 已新增报告输出：`target/openscout-evaluation/agent-evaluation-report.json` 和 `target/openscout-evaluation/agent-evaluation-report.md`。
- 已新增测试：`EvaluationCaseLoaderTest`、`EvaluationMetricCalculatorTest`、`EvaluationReportWriterTest`、`AgentEvaluationRunnerTest`、`AgentEvaluationCommandTest` 和测试辅助 `EvaluationTestSupport`。
- 第一版指标来源为 `AgentAskResponse` + `AgentTrace.TraceToolCall.toolName` 事件；不新增 DDL、不新增 HTTP API、不依赖外网或模型 Key。
- 验证结果：evaluation 目标测试 8 tests 通过；Java 全量 124 tests 通过；Go tests 通过；Docker Compose config 通过；review fix 2 后默认报告 `githubReadmeFetchCalls=0`、sample mode 可见、optional skipped 不再标记 passed。

## 12. Achieve 总结

- 阶段 14 已完成 achieve：阶段知识已沉淀到 `code_copilot/knowledge/index.md`。
- 可写结论：OpenScout 当前具备本地可复现的 Agent Evaluation 第一版，可用固定 Maven 命令生成 JSON/Markdown 报告，报告覆盖推荐相关性、evidence 覆盖、Verifier issue、Memory hit、fallback、延迟和 GitHub 调用节省指标。
- 不能夸大的结论：默认报告只证明本地 fixture 与 seeded memory 场景，不代表生产 SLA、线上准确率、大规模 benchmark 或真实 GitHub/LLM enabled 评测。
- 保留风险：raw `eventCounts` 是 Trace 事件计数，不等同于 API 调用次数；真实 GitHub / LLM enabled 仍需 optional 手工或后续 CI-like 环境单独执行。
