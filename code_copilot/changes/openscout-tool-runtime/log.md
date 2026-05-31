# 执行日志 - 阶段 9 Tool Runtime

## 基本信息

- change：`openscout-tool-runtime`
- status：`done`
- created：2026-05-31
- last_updated：2026-05-31

## Research 记录

- 已读取 `code_copilot/README.md`、`code_copilot/rules/project-context.md`、`code_copilot/rules/coding-style.md`、`code_copilot/rules/domain-rules.md`、`code_copilot/rules/security.md`。
- 已读取 `code_copilot/agents/copilot-prompt.md` 和 `code_copilot/knowledge/index.md`。
- 已读取 `项目实施进度.md`，确认阶段 8 已合并到 `main`，下一阶段为阶段 9 `feature/08-tool-runtime`。
- 已检查当前分支和工作区，创建并切换到 `feature/08-tool-runtime`。
- 已检查阶段 8 `openscout-agent-runtime` 的 spec/tasks，确认阶段 9 应基于现有 Agent Runtime 做 Tool Runtime，不进入 Memory/RAG、ReAct、Verifier 或 SSE。
- 已检查当前代码事实：
  - `AgentService` 只负责请求校验、Trace 生命周期和异常转换，委托 `PlanExecutor`。
  - `RuleBasedAgentPlanner` 生成固定 toolName step。
  - `PlanExecutor` 仍通过 `switch (step.getToolName())` 调用私有方法。
  - `TraceService` 已记录 plan/step/observation 事件并做脱敏截断。
  - `CollectorClient` 已封装结构化异常和真实/Mock Collector 调用。

## 执行记录

| 时间 | 动作 | 文件 | 结果 |
|---|---|---|---|
| 2026-05-31 | 创建阶段分支 | Git `feature/08-tool-runtime` | 已创建并切换 |
| 2026-05-31 | 创建 proposal spec | `code_copilot/changes/openscout-tool-runtime/spec.md` | 已完成 |
| 2026-05-31 | 创建任务拆分 | `code_copilot/changes/openscout-tool-runtime/tasks.md` | 已完成 |
| 2026-05-31 | 创建测试计划 | `code_copilot/changes/openscout-tool-runtime/test-spec.md` | 已完成 |
| 2026-05-31 | 创建执行日志 | `code_copilot/changes/openscout-tool-runtime/log.md` | 已完成 |
| 2026-05-31 | 用户确认 apply 边界 | `spec.md` / `tasks.md` | 确认只做 Java Agent Server 内部抽象，复用 `TraceToolCall`，不新增 Trace DDL |
| 2026-05-31 | 实现 Tool Runtime 核心契约 | `openscout-agent-server/src/main/java/com/openscout/agent/tool/` | 已完成 |
| 2026-05-31 | 迁移 Runtime step 为 Tool | `InterpretGoalTool`、`SearchReposTool`、`FetchReadmeTool`、`ScoreProjectsTool`、`GenerateLearningPlanTool`、`GenerateAnswerTool` | 已完成 |
| 2026-05-31 | 改造 PlanExecutor | `openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java` | 已委托 Tool Runtime |
| 2026-05-31 | 增强 Trace Tool 事件 | `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` | 新增 `agent_tool_started` / `agent_tool_finished` / `agent_tool_failed` 事件 |
| 2026-05-31 | 补充测试 | `ToolExecutorTest`、`PlanExecutorTest` | 已完成 |
| 2026-05-31 | 同步文档 | `README.md`、`docs/api-contract.md`、`项目实施进度.md`、change 文档 | 已完成 |
| 2026-05-31 | review | `openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java` | 无阻塞问题；记录 1 个低风险 Trace latency 残留 |
| 2026-05-31 | achieve 知识沉淀 | `code_copilot/knowledge/index.md`、change 文档、`项目实施进度.md` | 已完成 |

## 决策记录

- 阶段 9 只做内部 Tool Runtime，不新增公开 Tool API。
- Tool Runtime 第一版只执行 Planner 固定 toolName，不开放用户动态指定工具。
- Tool 事件继续复用 `TraceToolCall`，不新增 Trace DDL。
- `PlanExecutor` 迁移后仍保留阶段 8 的 plan/step/observation 事件。
- review 后保留低风险点：fatal exception 分支中 `agent_step_finished` / `agent_observation_created` 的 latency 为 0；`agent_tool_failed` 仍记录真实耗时。后续若做事件流或 Trace 精度增强，可统一 step/tool latency。
- Memory/RAG、ReAct、Verifier、SSE、MCP 和生产鉴权全部留到后续阶段。

## 验证记录

- `cd openscout-agent-server && mvn test`：通过，34 tests。
- `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...`：通过。
- `docker compose -f deploy/docker-compose.yml config`：通过。
- 文档检查命令：
  ```bash
  rg -n "Tool Runtime|agent_tool_|openscout-tool-runtime|阶段 9" README.md docs/api-contract.md 项目实施进度.md code_copilot/changes/openscout-tool-runtime
  ```

## 遗留问题

- 本次未做 Project Memory/RAG、ReAct、Verifier、SSE、Spring AI `@Tool`、MCP、生产鉴权。
- Tool 事件复用 `TraceToolCall`，后续若做 SSE/run API 可在阶段 13 重新设计独立事件模型。
- fatal failure 的 step observation latency 仍为 0，低风险记录到知识索引；当前不影响错误语义、响应兼容或验证通过。
