# 任务拆分 - 阶段 8 Agent Runtime 内核

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已检查 `code_copilot/knowledge/index.md`
- [x] 已检查工作区状态，确认不会覆盖他人修改
- [x] 已创建/切换阶段分支 `feature/07-agent-runtime`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已确认本地验证命令或替代验证方式

## Task 1: Agent Runtime 内核模型

- **目标**：定义计划、步骤、观察结果、上下文和状态枚举，为显式执行链路提供数据结构。
- **层级/模块**：应用服务 / 领域模型
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/`：新增 Runtime 模型。
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/`：新增模型或状态测试。
- **依赖**：无
- **风险标记**：Trace / API 兼容
- **实现要点**：
  - `AgentPlan` 包含 planId、mode、steps。
  - `PlanStep` 包含 stepId、toolName、purpose、inputSummary、status、continueOnFailure。
  - `StepObservation` 包含 stepId、status、outputSummary、errorSummary、latencyMs。
  - `AgentContext` 保存 userGoal、interpretation、repos、recommendations、learningPlan、answer。
  - 所有可进入 Trace 的字段只保存摘要。
- **验收标准**：
  - 模型不依赖 HTTP、MyBatis 或 Spring AI 具体实现。
  - 状态能表达 PENDING/RUNNING/SUCCESS/FAILED/SKIPPED。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentRuntimeMode.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanStepStatus.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentPlan.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanStep.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/StepObservation.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentContext.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentRuntimeResult.java`
  - 验证结果：`mvn test` 通过。

## Task 2: 规则模板 Planner

- **目标**：用规则模板生成当前 ask 链路的 AgentPlan，不依赖 LLM 动态规划。
- **层级/模块**：应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java`
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`
- **依赖**：Task 1
- **风险标记**：模型输出可信度 / 范围控制
- **实现要点**：
  - mock 模式生成：`interpret_goal`、`search_repos`、`score_projects`、`generate_learning_plan`、`generate_answer`。
  - real 模式额外包含 `fetch_readme`。
  - 第一版不生成 RAG、ReAct、Verifier、SSE、报告相关 step。
  - step 数量固定，避免无限规划。
- **验收标准**：
  - mock/real plan 顺序稳定。
  - 每个 step 有 purpose 和输入摘要。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`
  - 验证结果：`mvn test` 通过。

## Task 3: PlanExecutor 执行当前 ask 链路

- **目标**：统一执行 plan step，并将当前 AgentService 的固定流程迁移到 Runtime。
- **层级/模块**：应用服务 / 基础设施编排
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java`
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
- **依赖**：Task 1、Task 2
- **风险标记**：外部接口 / LLM fallback / 数据库持久化
- **实现要点**：
  - 复用 `GoalInterpreter`、`CollectorClient`、`ProjectScoreService`、`LearningPlanGenerator`、`AnswerGenerator`。
  - 保留 `RateLimitException`、`CollectorUnavailableException`、`GitHubApiException` 语义。
  - README enrichment 失败 best-effort 跳过，search 失败中断。
  - 修正或避免 `persistReposIfEnabled()` 中排序后下标可能不对应 repo 的风险。
  - 保持 `openscout.persistence.enabled=false` 时不依赖 MySQL。
- **验收标准**：
  - `/api/agent/ask` 在 mock 模式下结果结构兼容。
  - real 模式保留 README best-effort 和限流处理。
  - `AgentService` 只负责入口校验、Trace 生命周期和异常转换，主要计划执行由 Runtime 完成。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/AgentServiceTest.java`
  - 验证结果：`mvn test` 通过。

## Task 4: Trace 记录 plan / step / observation

- **目标**：在不修改 DDL 的前提下，让 Trace 可复盘一次 ask 的计划和步骤执行结果。
- **层级/模块**：Trace / 持久化兼容
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java`
  - `openscout-agent-server/src/main/java/com/openscout/trace/AgentTrace.java`（仅在确有必要时兼容追加内存字段）
  - `openscout-agent-server/src/test/java/com/openscout/trace/TraceServiceTest.java`
- **依赖**：Task 1、Task 3
- **风险标记**：Trace 脱敏 / 持久化兼容 / 安全
- **实现要点**：
  - 推荐第一版复用 `TraceToolCall`，新增 toolName 约定：`agent_plan_created`、`agent_step_started`、`agent_step_finished`、`agent_observation_created`。
  - 统一调用 `sanitize()`，不保存完整 README、prompt、模型响应、token 或 key。
  - `complete()` / `fail()` 仍能把 toolCalls 写入既有 `tool_calls_json`。
- **验收标准**：
  - Trace 查询可看到 planId、stepId、状态和 observation 摘要。
  - 脱敏和截断测试通过。
  - 不要求新增数据库字段。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java`
    - `openscout-agent-server/src/test/java/com/openscout/trace/TraceServiceTest.java`
  - 验证结果：`mvn test` 通过。

## Task 5: 回归测试与手工验证口径

- **目标**：证明 Runtime 重构不破坏现有 ask、Trace、学习计划、LLM fallback 和 Collector 错误处理。
- **层级/模块**：测试
- **涉及文件**：
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/`
  - `openscout-agent-server/src/test/java/com/openscout/agent/`
  - `openscout-agent-server/src/test/java/com/openscout/trace/`
- **依赖**：Task 1-4
- **风险标记**：回归验证
- **实现要点**：
  - Planner 单测。
  - Executor 成功与失败路径单测。
  - Trace 事件和脱敏测试。
  - AgentService 响应兼容测试。
  - 执行 Java、Go、Docker config 回归。
- **验收标准**：
  - Java 全量测试通过。
  - Go 测试通过。
  - Docker Compose config 通过。
  - 可选 curl 验证 Trace 中有 plan/step/observation。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  cd openscout-repo-collector && go test ./...
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/AgentServiceTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/trace/TraceServiceTest.java`
  - 验证结果：`mvn test` 通过（29 tests）；`go test ./...` 通过；`docker compose -f deploy/docker-compose.yml config` 通过。

## Task 6: 文档与 code_copilot 同步

- **目标**：同步 README、API 契约、实施进度和 change 文档。
- **层级/模块**：文档 / SpecAI
- **涉及文件**：
  - `README.md`
  - `docs/api-contract.md`
  - `项目实施进度.md`
  - `code_copilot/changes/openscout-agent-runtime/spec.md`
  - `code_copilot/changes/openscout-agent-runtime/tasks.md`
  - `code_copilot/changes/openscout-agent-runtime/test-spec.md`
  - `code_copilot/changes/openscout-agent-runtime/log.md`
- **依赖**：Task 1-5
- **风险标记**：文档与实现一致性
- **实现要点**：
  - 文档记录 Runtime 只显式化现有链路。
  - 明确不包含项目对比报告、前端、RAG、SSE、生产鉴权。
  - `/apply` 完成后回填任务完成记录和真实验证结果。
- **验收标准**：
  - 文档中的 Runtime 能力和实际代码一致。
  - `log.md` 有真实执行记录。
- **验证命令**：
  ```bash
  rg -n "Agent Runtime|agent_plan_created|openscout-agent-runtime|阶段 8" README.md docs/api-contract.md 项目实施进度.md code_copilot/changes/openscout-agent-runtime
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `README.md`
    - `docs/api-contract.md`
    - `项目实施进度.md`
    - `code_copilot/changes/openscout-agent-runtime/spec.md`
    - `code_copilot/changes/openscout-agent-runtime/tasks.md`
    - `code_copilot/changes/openscout-agent-runtime/test-spec.md`
    - `code_copilot/changes/openscout-agent-runtime/log.md`
  - 验证结果：文档已记录 Agent Runtime、Trace toolName 事件和明确不做范围。

## 变更摘要

> `/apply` 完成后填写。

- **总文件数**：22
- **新增文件**：16
- **修改文件**：6
- **删除文件**：0
- **Spec-Plan 偏差记录**：按用户确认使用 `TraceToolCall.toolName` 事件记录 plan/step/observation，未新增 Trace DDL；`AgentService` 已收敛为 Trace 生命周期和异常转换，主要执行迁移到 `PlanExecutor`。
- **未完成项**：未做项目对比报告、前端、RAG、SSE、生产鉴权，均属于明确不做范围。
- **遗留风险**：Trace Runtime 事件复用 `toolCalls`，表达力有限；后续若做可视化或 run API，可在阶段 13 重新设计事件流模型。
