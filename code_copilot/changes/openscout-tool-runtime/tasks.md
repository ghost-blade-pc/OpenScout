# 任务拆分 - 阶段 9 Tool Runtime

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已检查 `code_copilot/knowledge/index.md`
- [x] 已检查工作区状态，确认不会覆盖他人修改
- [x] 已创建/切换阶段分支 `feature/08-tool-runtime`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已确认本地验证命令或替代验证方式

## Task 1: Tool Runtime 核心契约

- **目标**：定义 Tool 名称、输入、输出、执行上下文、执行结果和结构化错误模型。
- **层级/模块**：应用服务 / Runtime
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/`：新增 Tool Runtime 模型。
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/`：新增模型和契约测试。
- **依赖**：无
- **风险标记**：Trace / 过度抽象
- **实现要点**：
  - 定义 `AgentTool` 或等价接口，暴露稳定 `toolName()`。
  - 定义 Tool request/result/error/context 对象，至少包含 trace、agent context、step、输入摘要、输出摘要、错误摘要。
  - Tool 输出只暴露摘要和结构化结果，不直接要求完整对象进入 Trace。
  - 第一版不支持用户动态注册任意 Tool。
- **验收标准**：
  - Tool 契约不依赖 HTTP Controller。
  - Tool 错误能表达 recoverable/fatal。
  - 可进入 Trace 的字段都有摘要边界。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/AgentTool.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolRequest.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolResult.java`
  - 验证结果：`mvn test` 通过。

## Task 2: Tool Registry 与 Tool Executor

- **目标**：集中管理 Tool 查找、执行、耗时、错误传播和 Trace 记录。
- **层级/模块**：应用服务 / Trace
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolRegistry.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolExecutor.java`
  - `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java`
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/ToolExecutorTest.java`
- **依赖**：Task 1
- **风险标记**：Trace 脱敏 / 错误兼容
- **实现要点**：
  - Registry 从 Spring Bean 列表构建 toolName -> Tool 映射，重复 toolName 启动失败。
  - 未知 toolName 返回明确异常，便于发现 Planner/Tool 不一致。
  - Executor 记录 `agent_tool_started`、`agent_tool_finished`、`agent_tool_failed` 或等价事件。
  - fatal 错误保持原异常语义，recoverable 错误返回结果并继续。
- **验收标准**：
  - 成功、未知 Tool、重复 Tool、fatal error、recoverable error 都有单测。
  - Trace 摘要经过 `TraceService.sanitize()`。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolRegistry.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolExecutor.java`
    - `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/tool/ToolExecutorTest.java`
  - 验证结果：`mvn test` 通过。

## Task 3: 迁移现有 Runtime Step 为 Tool

- **目标**：把当前 `PlanExecutor` 私有方法拆成可注册 Tool，保留既有业务语义。
- **层级/模块**：应用服务 / 外部适配 / 领域服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/InterpretGoalTool.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/SearchReposTool.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/FetchReadmeTool.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ScoreProjectsTool.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/GenerateLearningPlanTool.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/GenerateAnswerTool.java`
  - 对应单元测试。
- **依赖**：Task 1、Task 2
- **风险标记**：外部接口 / LLM fallback / 数据库持久化
- **实现要点**：
  - Tool 名称复用 Planner 当前 toolName，避免同时修改计划语义。
  - `SearchReposTool` 保留 mock/real 分支和 `CollectorClient` 异常语义。
  - `FetchReadmeTool` 保留 Top 5、404/限流/普通异常 best-effort 跳过。
  - `ScoreProjectsTool` 仍以 `ProjectScoreService` 为最终评分来源，并保留 repo/analysis 持久化开关语义。
  - `GenerateLearningPlanTool` 保留持久化关闭或失败时返回临时计划。
  - `GenerateAnswerTool` 保留 LLM disabled fallback。
- **验收标准**：
  - 每个 Tool 都有输入摘要、输出摘要和关键失败路径测试。
  - Tool 不保存完整 README、prompt、模型响应或敏感配置到 Trace。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/InterpretGoalTool.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/SearchReposTool.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/FetchReadmeTool.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ScoreProjectsTool.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/GenerateLearningPlanTool.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/GenerateAnswerTool.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
  - 验证结果：`mvn test` 通过。

## Task 4: PlanExecutor 委托 Tool Runtime

- **目标**：让 `PlanExecutor` 只负责 plan/step 生命周期和 context 串联，具体能力由 Tool Runtime 执行。
- **层级/模块**：Agent Runtime
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentContext.java`
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
  - `openscout-agent-server/src/test/java/com/openscout/agent/AgentServiceTest.java`
- **依赖**：Task 2、Task 3
- **风险标记**：API 兼容 / 行为回归
- **实现要点**：
  - 移除或收敛 `PlanExecutor` 中按 toolName 的业务 `switch`。
  - 保留 `agent_plan_created`、`agent_step_started`、`agent_step_finished`、`agent_observation_created` 事件。
  - Tool 执行结果写回 `AgentContext`，最终构造 `AgentRuntimeResult`。
  - search fatal 失败仍抛出原异常；README 和学习计划持久化失败按既有语义继续。
- **验收标准**：
  - `/api/agent/ask` 响应字段和错误语义兼容。
  - mock 成功、real README 部分失败、Collector 不可用、RateLimit 语义均通过测试。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
  - 验证结果：`mvn test` 通过。

## Task 5: 回归验证与测试补齐

- **目标**：证明 Tool Runtime 没有破坏 Agent Runtime、Trace、LLM fallback 和 Collector 错误处理。
- **层级/模块**：测试
- **涉及文件**：
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/`
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/`
  - `openscout-agent-server/src/test/java/com/openscout/trace/`
- **依赖**：Task 1-4
- **风险标记**：回归验证
- **实现要点**：
  - Registry/Executor 单测。
  - 各 Tool 成功和失败路径单测。
  - PlanExecutor 集成式单测。
  - Trace Tool 事件脱敏和截断测试。
  - Java、Go、Docker config 回归。
- **验收标准**：
  - Java 全量测试通过。
  - Go 测试通过。
  - Docker Compose config 通过。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/test/java/com/openscout/agent/tool/ToolExecutorTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
  - 验证结果：Java `mvn test` 通过（34 tests）；Go `go test ./...` 通过；Docker Compose config 通过。

## Task 6: 文档与 code_copilot 同步

- **目标**：同步 README、API 契约、实施进度和 change 文档。
- **层级/模块**：文档 / SpecAI
- **涉及文件**：
  - `README.md`
  - `docs/api-contract.md`
  - `项目实施进度.md`
  - `code_copilot/changes/openscout-tool-runtime/spec.md`
  - `code_copilot/changes/openscout-tool-runtime/tasks.md`
  - `code_copilot/changes/openscout-tool-runtime/test-spec.md`
  - `code_copilot/changes/openscout-tool-runtime/log.md`
- **依赖**：Task 1-5
- **风险标记**：文档与实现一致性
- **实现要点**：
  - 文档说明 Tool Runtime 是内部抽象，不是公开 Tool API。
  - 明确不包含 Memory/RAG、ReAct、Verifier、SSE、MCP 和数据库迁移。
  - `/apply` 完成后回填任务完成记录和真实验证结果。
- **验收标准**：
  - 文档中的 Tool Runtime 能力和实际代码一致。
  - `log.md` 有真实执行记录。
- **验证命令**：
  ```bash
  rg -n "Tool Runtime|agent_tool_|openscout-tool-runtime|阶段 9" README.md docs/api-contract.md 项目实施进度.md code_copilot/changes/openscout-tool-runtime
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `README.md`
    - `docs/api-contract.md`
    - `项目实施进度.md`
    - `code_copilot/changes/openscout-tool-runtime/spec.md`
    - `code_copilot/changes/openscout-tool-runtime/tasks.md`
    - `code_copilot/changes/openscout-tool-runtime/test-spec.md`
    - `code_copilot/changes/openscout-tool-runtime/log.md`
  - 验证结果：文档已记录 Tool Runtime 内部抽象、TraceToolCall 复用和不新增 DDL 边界。

## 变更摘要

> `/apply` 完成后填写。

- **总文件数**：22
- **新增文件**：16
- **修改文件**：6
- **删除文件**：0
- **Spec-Plan 偏差记录**：按用户确认，Tool Runtime 仅作为 Java Agent Server 内部抽象；继续复用 `TraceToolCall` 记录 `agent_tool_*` 事件；未新增 Trace DDL 或公开 Tool API。
- **未完成项**：未做 Project Memory/RAG、ReAct、Verifier、SSE、Spring AI `@Tool`、MCP、生产鉴权，均属于明确不做范围。
- **遗留风险**：Tool 事件继续复用 `TraceToolCall`，表达力有限；后续阶段 13 若做事件流，可再设计独立 run/event 模型。
- **Review 记录**：无阻塞问题；低风险残留为 fatal exception 分支的 step observation latency 为 0，但 `agent_tool_failed` 保留真实耗时。
