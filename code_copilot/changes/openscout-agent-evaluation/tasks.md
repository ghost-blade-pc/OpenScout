# 任务拆分 - 阶段 14 Agent Evaluation

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/knowledge/index.md`
- [x] 已检查工作区状态，确认当前分支为 `feature/13-agent-evaluation`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已确认本地验证命令或替代验证方式

## Task 1: Evaluation Case 与 fixture

- **目标**：建立固定评测样本、期望和阈值。
- **层级/模块**：测试 / 应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/evaluation/`：新增 case/expectation 模型
  - `openscout-agent-server/src/test/resources/evaluation/`：新增固定评测集
  - `openscout-agent-server/src/test/java/com/openscout/evaluation/`：新增 fixture 加载测试
- **依赖**：无
- **风险标记**：配置 / 外部接口
- **实现要点**：
  - case 字段包含 id、question、mode、tags、expectedKeywords、expectedEvidence、expectedTraceEvents、thresholds。
  - 默认 case 使用 mock-first，不依赖外部 GitHub 或 LLM Key。
  - 默认必过 case 可使用 seeded memory 覆盖 REAL plan 的 memory hit / GitHub 调用节省，不访问真实 GitHub。
  - 真实 GitHub/LLM case 只标为 optional，不纳入默认必过。
- **验收标准**：
  - fixture 可被稳定加载并校验必填字段。
  - 不包含 token、key、完整 README 或生产数据。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=EvaluationCaseLoaderTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`EvaluationCase.java`、`EvaluationCaseSet.java`、`EvaluationExpectations.java`、`EvaluationThresholds.java`、`EvaluationCaseLoader.java`、`src/test/resources/evaluation/cases.json`、`EvaluationCaseLoaderTest.java`
  - 验证结果：`mvn test -Dtest='Evaluation*Test,AgentEvaluation*Test'` 通过，fixture 加载和缺字段校验覆盖

## Task 2: Evaluation Runner

- **目标**：执行评测 case，收集 Agent 响应、Trace 和环境信息。
- **层级/模块**：应用服务 / 测试
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/evaluation/AgentEvaluationRunner.java`
  - `openscout-agent-server/src/main/java/com/openscout/evaluation/EvaluationSampleResult.java`
  - `openscout-agent-server/src/test/java/com/openscout/evaluation/AgentEvaluationRunnerTest.java`
- **依赖**：Task 1
- **风险标记**：并发 / 外部接口 / 安全
- **实现要点**：
  - 默认通过 `AgentService.ask()` 执行，必要时可选用 `AgentRunService` 验证 run/event 证据。
  - 执行后用 `TraceService.find(traceId)` 拉取 Trace。
  - 记录配置快照：mockAgent、collectorMode、llm/memory/react/verifier/events/persistence enabled。
  - 失败 case 保留可解释错误摘要，不输出堆栈。
- **验收标准**：
  - mock case 可稳定执行并产出 traceId。
  - 评测失败不会污染全局状态或保留长驻线程。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=AgentEvaluationRunnerTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`AgentEvaluationRunner.java`、`EvaluationSampleResult.java`、`EvaluationEnvironment.java`、`AgentEvaluationRunnerTest.java`
  - 验证结果：目标测试通过；mock case 和 seeded memory REAL plan case 生成 traceId，optional real case 默认 skipped

## Task 3: 指标计算器

- **目标**：把 response + Trace 事件转成阶段 14 验收指标。
- **层级/模块**：应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/evaluation/EvaluationMetrics.java`
  - `openscout-agent-server/src/main/java/com/openscout/evaluation/EvaluationMetricCalculator.java`
  - `openscout-agent-server/src/test/java/com/openscout/evaluation/EvaluationMetricCalculatorTest.java`
- **依赖**：Task 1、Task 2
- **风险标记**：评测口径 / Trace
- **实现要点**：
  - 推荐相关性：Top 推荐 fullName/language/topics/evidence 与 case expectedKeywords 匹配。
  - evidence 覆盖：推荐项目 evidence 非空、README/example/doc/docker 等关键证据命中。
  - hallucination/verifier：统计 `verify_completed` summary 中 issues/ok。
  - memory hit：统计 `memory_hit`、`search_repos_skipped`、`readme_cache_hit`。
  - fallback：统计 LLM disabled/fallback、tool recoverable failure、run/ask error summary。
  - latency：统计 sample latency 和总体 p50/p95 或 max。
  - GitHub API 调用节省：基于 search/readme/cache/memory Trace 事件做对照统计。
- **验收标准**：
  - 每个指标都有明确输入来源和失败原因。
  - mock fixture 的指标可重复，不因事件顺序细节产生随机失败。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=EvaluationMetricCalculatorTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`EvaluationMetrics.java`、`EvaluationMetricCalculator.java`、`EvaluationSummary.java`、`EvaluationMetricCalculatorTest.java`
  - 验证结果：目标测试通过；覆盖推荐相关性、evidence 覆盖、fallback、memory hit、verifier issue 和 GitHub 调用节省

## Task 4: 报告生成

- **目标**：输出 JSON 和 Markdown 评测报告。
- **层级/模块**：应用服务 / 文档
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/evaluation/EvaluationReport.java`
  - `openscout-agent-server/src/main/java/com/openscout/evaluation/EvaluationReportWriter.java`
  - `openscout-agent-server/src/test/java/com/openscout/evaluation/EvaluationReportWriterTest.java`
  - `openscout-agent-server/target/openscout-evaluation/`：运行时生成，不纳入 Git
- **依赖**：Task 2、Task 3
- **风险标记**：安全 / 文件输出
- **实现要点**：
  - JSON 保留结构化字段：环境、case 结果、指标、失败原因、traceId、关键事件计数。
  - Markdown 面向人工审查：总体结论、样本表、指标表、未通过原因、不可夸大说明。
  - 统一脱敏：不输出 token、key、完整 README、完整 prompt、完整模型响应。
- **验收标准**：
  - 报告路径固定在 `target/openscout-evaluation/`。
  - 报告字段和排序有测试保护。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=EvaluationReportWriterTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`EvaluationReport.java`、`EvaluationReportWriter.java`、`EvaluationRecommendationSnapshot.java`、`EvaluationReportWriterTest.java`
  - 验证结果：目标测试通过；JSON/Markdown 报告写入 `target/openscout-evaluation/` 并覆盖敏感字段脱敏

## Task 5: 固定评测命令

- **目标**：提供可重复运行的阶段 14 评测命令。
- **层级/模块**：测试 / 配置
- **涉及文件**：
  - `openscout-agent-server/src/test/java/com/openscout/evaluation/AgentEvaluationCommandTest.java`
  - `README.md`
  - `code_copilot/changes/openscout-agent-evaluation/test-spec.md`
- **依赖**：Task 1-4
- **风险标记**：配置 / 外部接口
- **实现要点**：
  - 默认命令不需要 GitHub Token、DeepSeek Key、Docker 或外网。
  - 可选命令允许在真实 GitHub / LLM enabled 环境下运行，并在报告中标记 optional。
  - 命令失败时输出明确原因和报告路径。
- **验收标准**：
  - 固定命令能生成 JSON/Markdown 报告。
  - 默认命令可在 CI-like 本地环境稳定运行。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=AgentEvaluationCommandTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`AgentEvaluationCommandTest.java`、`README.md`、`test-spec.md`
  - 验证结果：`mvn test -Dtest=AgentEvaluationCommandTest` 在目标测试集中通过，并生成 JSON/Markdown 报告

## Task 6: 回归测试与文档同步

- **目标**：补齐阶段回归、README 和 change 日志。
- **层级/模块**：测试 / 文档
- **涉及文件**：
  - `README.md`
  - `项目实施进度.md`
  - `code_copilot/changes/openscout-agent-evaluation/test-spec.md`
  - `code_copilot/changes/openscout-agent-evaluation/log.md`
- **依赖**：Task 1-5
- **风险标记**：无
- **实现要点**：
  - 文档写明默认评测、可选真实评测、报告路径和不能夸大的口径。
  - 记录 Java、Go、Docker compose config 回归结果。
  - Docker 不可用时如实记录环境阻塞，不伪造通过。
- **验收标准**：
  - `cd openscout-agent-server && mvn test` 通过。
  - `cd openscout-repo-collector && go test ./...` 通过。
  - `docker compose -f deploy/docker-compose.yml config` 通过或记录环境阻塞。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  cd openscout-repo-collector && go test ./...
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`README.md`、`项目实施进度.md`、`spec.md`、`tasks.md`、`test-spec.md`、`log.md`
  - 验证结果：evaluation 目标测试 8 tests 通过；Java 全量 124 tests 通过；Go tests 通过；Docker Compose config 通过；review fix 2 后默认报告 `githubReadmeFetchCalls=0`、sample mode 可见、optional skipped 不再标记 passed

## 变更摘要

- **总文件数**：27
- **新增文件**：25
- **修改文件**：2
- **删除文件**：0
- **Spec-Plan 偏差记录**：无业务范围偏差；第一版按 spec 默认 mock-first，并用 seeded memory REAL plan 覆盖 memory hit / GitHub 调用节省；真实 GitHub / LLM enabled case 为 optional。
- **未完成项**：真实 GitHub / LLM enabled 评测仍需手工或后续 CI-like 环境单独执行。
- **遗留风险**：默认评测结果只代表本地 fixture，不代表生产 SLA、线上准确率或大规模 benchmark；真实 GitHub / LLM enabled 评测仍需手工或后续 CI-like 环境单独执行。
- **Achieve 状态**：已完成知识沉淀，change 标记为 `done`，待合并回 `main`。
