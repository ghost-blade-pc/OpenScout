# 阶段 9 Tool Runtime
> status: done
> created: 2026-05-31
> complexity: 复杂

## 1. 背景与目标

阶段 8 已把 `/api/agent/ask` 的固定编排抽象为 Agent Runtime：`RuleBasedAgentPlanner` 生成固定 `AgentPlan`，`PlanExecutor` 按 step 执行目标解释、项目检索、README 证据补充、规则评分、学习计划生成和回答生成，并把 plan/step/observation 写入 Trace。

当前问题是：各 step 的实际能力仍通过 `PlanExecutor` 的 `switch (toolName)` 直接调用具体服务，Tool 的输入、输出、错误和 Trace 摘要没有统一契约。后续 Project Memory、Evidence ReAct、Verifier 和事件流都需要可复用的 Tool 抽象，否则会继续把能力分支堆进 `PlanExecutor`。

本阶段目标是建立第一版 Tool Runtime，把 Collector 调用、评分、学习计划和回答生成等能力标准化为可注册、可执行、可追踪的 Tool。完成后可观察到：

- `/api/agent/ask` 响应字段保持兼容。
- `PlanExecutor` 通过 Tool Runtime 调用能力，不再直接 `switch` 到业务服务。
- Tool 输入输出有明确对象和摘要策略，可进入 Trace。
- 限流、Collector 不可用、404、README 部分失败和 LLM fallback 仍保持现有语义。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的 Agent Runtime、Tool 调用、Trace 和推荐编排上下文。
- 调用方向：HTTP 客户端 -> Java Agent Server `/api/agent/ask` -> Agent Runtime -> Tool Runtime -> Collector/Scoring/Learning/Answer 能力 -> Trace。
- 是否涉及高风险项：是。
- 高风险类型：外部 GitHub API、限流和超时、部分失败、Trace 脱敏、LLM fallback、API 兼容。

### 1.2 范围裁剪

- 本次包含：
  - 新增 Tool Runtime 核心契约：Tool 名称、输入、输出、执行上下文、执行结果、结构化错误。
  - 新增 Tool Registry / Tool Executor，按 `PlanStep.toolName` 查找并执行 Tool，统一记录 Tool 级 Trace 事件。
  - 将现有 Runtime step 迁移为 Tool：`interpret_goal`、`search_repos`、`fetch_readme`、`score_projects`、`generate_learning_plan`、`generate_answer`。
  - 保留阶段 8 的 plan/step/observation 事件，同时新增或规范 Tool 调用事件，确保输入输出摘要可序列化、可脱敏。
  - 保留现有错误语义：search 失败中断，README 单项失败 best-effort，学习计划和 LLM 失败 fallback。
  - 单元测试覆盖 Registry、ToolExecutor、各核心 Tool、失败路径和 `/api/agent/ask` 兼容。
- 本次不包含：
  - Project Memory / RAG、向量库、关键词记忆检索。
  - Evidence-aware ReAct 追加循环。
  - Reflection Verifier。
  - SSE/Streaming、`/api/agent/runs`、前端事件展示。
  - Spring AI `@Tool` 注解或 MCP 协议。
  - 登录鉴权、多用户隔离、配额系统。
  - 新增数据库表、迁移框架或改动 `deploy/init.sql`。
- 后续可能拆分：
  - `openscout-project-memory-rag`：把 Memory 作为新的 Tool 或 Tool 前后置能力接入。
  - `openscout-evidence-react`：根据 evidence gap 动态追加有限 Tool 调用。
  - `openscout-agent-events-stream`：把 Tool Runtime 事件流化为 SSE。

## 2. Research Findings

### 2.1 相关入口与链路

- HTTP/API：`openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java` 提供 `POST /api/agent/ask` 和 `GET /api/agent/traces/{traceId}`。
- Agent 入口：`openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java` 当前负责请求校验、Trace 生命周期和异常转换，具体执行委托 `PlanExecutor.execute()`。
- Planner：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java` 生成固定 step，toolName 为 `interpret_goal`、`search_repos`、`fetch_readme`、`score_projects`、`generate_learning_plan`、`generate_answer`。
- Runtime 执行：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java` 当前通过 `switch (step.getToolName())` 调用私有方法。
- Runtime 上下文：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentContext.java` 保存 userGoal、mode、interpretation、repos、recommendations、learningPlan、answer。
- Collector 调用：`openscout-agent-server/src/main/java/com/openscout/client/CollectorClient.java` 封装 mock/search/profile/readme/batch-profile，并把连接失败、限流和 GitHub API 错误翻译为 `CollectorException` 子类。
- 评分：`openscout-agent-server/src/main/java/com/openscout/scoring/ProjectScoreService.java` 生成最终规则评分，LLM 不得覆盖。
- 学习计划：`openscout-agent-server/src/main/java/com/openscout/learning/LearningPlanGenerator.java` 生成 7 天计划，LLM 只做文案增强，失败 fallback。
- 回答生成：`openscout-agent-server/src/main/java/com/openscout/agent/AnswerGenerator.java` 生成最终回答，LLM 不可用时 fallback 到模板。
- Trace：`openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` 已支持 `agent_plan_created`、`agent_step_started`、`agent_step_finished`、`agent_observation_created`，并统一脱敏和截断。
- 测试：`openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java` 已覆盖 mock 成功、search 失败、real README 失败继续执行；`TraceServiceTest` 覆盖 Runtime 事件和脱敏。

### 2.2 现有实现摘要

- Agent Runtime 已能表达 plan、step、observation，但 Tool 仍是隐式私有方法。
- `PlanExecutor` 同时承担 step 调度、能力执行、输入输出摘要、部分 Trace 记录和持久化分支，职责偏重。
- `CollectorClient` 已有结构化异常层级，Tool Runtime 应复用，不重新封装外部 HTTP 细节。
- Trace 目前可保存 Runtime 事件和底层工具调用，但没有统一的 Tool 输入/输出/错误对象。

### 2.3 发现的问题

- `PlanExecutor` 的 `switch` 让新增 Tool 必须改核心执行器，不利于后续 Memory、ReAct、Verifier 扩展。
- Tool 输入输出没有契约，后续无法稳定序列化到 Trace、事件流或评测样本。
- README 部分失败、限流、404、Collector 不可用等语义分散在私有方法里，缺少 Tool 级结构化结果。
- `TraceToolCall.toolName` 已被同时用于底层调用和 Runtime 事件，阶段 9 需要建立更清楚的命名约定，避免排障时混淆。

### 2.4 风险初判

- API 兼容：不得删除或重命名 `/api/agent/ask` 的 `traceId`、`answer`、`recommendations`、`learningPlan`、`latencyMs`。
- 行为兼容：mock/real 模式的推荐结果、学习计划 fallback、LLM fallback、RateLimit `Retry-After` 语义不能退化。
- 错误兼容：search 失败仍应让 `AgentService` 包装为 `AgentCallException`；README 单项失败仍应跳过而非整体失败。
- Trace 安全：Tool 输入输出只保存摘要，不保存完整 README、完整 prompt、完整模型响应、token 或 key。
- 过度抽象：第一版 Tool Runtime 只服务当前固定 plan，不引入动态规划、任意工具调用或循环。

## 3. 功能点

- [x] 功能 1：新增 Tool Runtime 核心契约和基础模型。
- [x] 功能 2：新增 Tool Registry / Tool Executor，按 `PlanStep.toolName` 执行 Tool。
- [x] 功能 3：将现有 Runtime step 迁移为 Tool 实现。
- [x] 功能 4：调整 `PlanExecutor`，通过 Tool Runtime 执行计划并维护 `AgentContext`。
- [x] 功能 5：规范 Trace 中 Tool 事件、输入输出摘要和结构化错误记录。
- [x] 功能 6：补充 Registry、Executor、Tool、AgentService 回归测试。
- [x] 功能 7：同步 README、API 契约、项目实施进度和 change 文档。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| Java Model | `com.openscout.agent.tool.*` | 新增 Tool 契约、ToolRequest、ToolResult、ToolError、ToolContext 等模型 | 新增内部类型，不影响 API | 回退到 `PlanExecutor` 私有方法执行 |
| Java Service | Tool Registry / Tool Executor | 统一 Tool 查找、执行、耗时、错误和 Trace 记录 | 内部实现替换，API 兼容 | 保留 Runtime plan，回退执行器 |
| Trace | `TraceToolCall` 事件约定 | 新增或规范 `agent_tool_started`、`agent_tool_finished`、`agent_tool_failed` 等 Tool 事件 | 不改 DDL，继续写入 `tool_calls_json` | 删除新增事件记录即可 |
| Config | 暂无新增必需配置 | 复用现有 `openscout.*` 配置 | 无配置迁移风险 | 无 |
| DB | 无 | 本阶段不改 `deploy/init.sql` | 无迁移风险 | 无 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | 复用 `AgentAskRequest` | 保留既有字段；内部 Tool Runtime 不新增响应字段 | 不新增鉴权；继续标注本地 Demo 边界 | 必须向后兼容 |
| `/api/agent/traces/{traceId}` | GET | path `traceId` | `toolCalls` 可看到 Tool Runtime 事件和摘要 | 不新增鉴权；不得公网暴露 | 兼容追加事件，不改旧字段 |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector mock | `GET /api/repos/mock` | keyword | repo list | 复用 `CollectorClient` | 失败中断当前 ask，Trace 记录 Tool failed |
| Go Collector search | `GET /api/repos/search` | keyword、limit、mode | repo list | 复用 `CollectorClient` | 限流保留 `RateLimitException` 和 `Retry-After` |
| Go Collector README | `GET /api/repos/{owner}/{repo}/readme` | owner/repo/mode | README 摘要 | 复用 `CollectorClient` | 单 repo 失败 best-effort 跳过，记录 skipped |
| DeepSeek/OpenAI ChatClient | Spring AI ChatClient | goal 或推荐摘要 | JSON / answer 文本 | 复用现有 LLM fallback | 失败 fallback，不中断推荐 |
| MySQL | MyBatis-Plus Mapper | repo、analysis、learning、trace | insert/update/select | 无重试 | 写入失败 warn，不阻塞 ask 主流程 |

## 6. 风险与关注点

- Tool Runtime 第一版只能执行 Planner 生成的固定 Tool，不开放用户任意指定 toolName。
- Tool 输入输出必须有摘要方法，不能把完整 README、prompt、模型响应或密钥写入 Trace。
- `PlanExecutor` 迁移后仍要保留阶段 8 的 plan/step/observation 事件，不能只剩 Tool 事件。
- Tool 错误需要区分 fatal 与 recoverable：search/score/answer 通常 fatal，README 和学习计划持久化可 recoverable。
- LLM 只做目标解释、回答和学习计划文案增强，不得修改规则评分或编造项目能力。
- 不做 Memory/RAG、ReAct、Verifier、SSE；这些能力不得夹带进入阶段 9。

## 7. 测试策略

- 单元测试：
  - Tool Registry 能注册并按 toolName 找到唯一 Tool，未知 toolName 返回明确异常。
  - Tool Executor 成功执行时记录 started/finished 事件和耗时。
  - Tool Executor 遇到 fatal error 时记录 failed 事件并抛出原异常语义。
  - README Tool 遇到 404/限流/普通异常时按 best-effort 跳过单项。
  - Scoring Tool 仍使用 `ProjectScoreService`，分数由规则评分产生。
  - Learning Tool 在持久化关闭或失败时返回非持久化计划，不中断 ask。
  - Answer Tool 在 LLM disabled 时返回模板 fallback。
- 回归测试：
  - `AgentServiceTest` 验证 `/api/agent/ask` 响应兼容。
  - `PlanExecutorTest` 验证 mock 成功、search 失败、real README 部分失败。
  - `TraceServiceTest` 验证 Tool Runtime 事件脱敏和截断。
- 验证命令：
  - `cd openscout-agent-server && mvn test`
  - `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...`
  - `docker compose -f deploy/docker-compose.yml config`

## 8. 待澄清

- [x] `/apply` 前确认：第一版 Tool Runtime 只作为 Java Agent Server 内部抽象，不新增公开 Tool API。
- [x] `/apply` 前确认：Tool Runtime 事件继续复用 `TraceToolCall`，不新增 Trace DDL。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| Tool 调用范围 | 仅执行 Planner 固定 toolName | 用户请求可动态指定 Tool | 降低安全和失控风险 | 自主性有限，但可测试 |
| Tool 事件存储 | 复用 `TraceToolCall` | 新增 Trace 表或 JSON 字段 | 避免 DDL 和迁移风险 | 事件结构表达力有限 |
| Tool 输入输出 | 内部对象 + 摘要字符串 | 直接序列化完整对象 | 保护 README、prompt 和敏感字段 | 需要每个 Tool 明确摘要策略 |
| 错误模型 | ToolResult/ToolError 标记 recoverable/fatal，并保留原异常 | 全部吞掉返回失败对象 | 保持现有 Controller 错误语义 | Executor 需要明确传播策略 |
| 实现顺序 | 先内部 Tool Runtime，再接 Memory/ReAct | 同时做 RAG/ReAct | 阶段 9 验收标准聚焦 Tool 标准化 | 后续阶段继续拆分 |

## 10. 确认记录

- 确认时间：2026-05-31。
- 确认人：用户。
- 确认范围：第一版 Tool Runtime 只作为 Java Agent Server 内部抽象；继续复用 `TraceToolCall`；不新增 Trace DDL、公开 Tool API、Memory/RAG、ReAct、Verifier、SSE 或 MCP。
- `/apply` 记录：2026-05-31，已开始实现 Tool Runtime 内部抽象。
- `/archive` 完成时间：2026-05-31。阶段 9 知识已沉淀到 `code_copilot/knowledge/index.md`。
