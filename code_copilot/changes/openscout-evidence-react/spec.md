# 阶段 11 Evidence ReAct
> status: done
> created: 2026-06-01
> complexity: 中等

## 1. 背景与目标

阶段 10 已完成 Project Memory / RAG，当前 `/api/agent/ask` 在 Agent Runtime 中按固定计划执行：解释目标、检查 memory、检索项目、补 README、规则评分、生成学习计划和最终回答。这个流程已经能复用历史项目数据，但它仍然是一次性流水线：如果评分后发现某个推荐项目缺少 README 证据、只命中陈旧或不完整的缓存、真实 GitHub API 部分失败，系统不会基于 evidence gap 做有限补查，而是直接进入学习计划和回答生成。

阶段 11 的目标是在评分之后加入受控的 Evidence ReAct 步骤：检测 Top 推荐项目的证据缺口，按最大轮数和最大 repo 数追加有限工具调用，补齐可获取的 README / 文档证据，重新评分并把 follow-up action、observation 和停止原因写入 Trace。完成后用户仍调用同一个 `/api/agent/ask`，但 Trace 能解释为什么补查、补查了什么、哪些失败被降级、最终何时停止。

可观察结果：

- Trace 展示 `evidence_gap_detected`、`evidence_follow_up_started`、`evidence_follow_up_observed`、`evidence_rescore_completed`、`evidence_react_stopped` 等事件。
- README 缺失或缓存证据不足时，real 模式可对 Top N repo 追加 `getReadme` 补查。
- GitHub 404、403/429 限流、单 repo 失败时不破坏主流程，Trace 保留失败原因。
- 补查轮数和单轮 repo 数受配置限制，默认只做一轮，避免无限循环和 API 配额失控。
- `/api/agent/ask` 响应字段保持兼容，学习计划和最终回答使用补查后的推荐结果。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的 Agent Runtime -> Tool Runtime -> Evidence ReAct -> GitHub README 补查 -> 规则评分 -> Trace。
- 调用方向：HTTP -> AgentService -> PlanExecutor -> ToolExecutor -> EvidenceReActTool -> CollectorClient.getReadme / ProjectScoreService -> TraceService。
- 是否涉及高风险项：是。
- 高风险类型：外部 GitHub API 限流、模型回答可信度、Trace 脱敏、循环控制、缓存/持久化一致性。

### 1.2 范围裁剪

- 本次包含：
  - 新增 Evidence Gap 检测模型，识别 Top 推荐项目中 README 缺失、文档 evidence 不足、memory 恢复信息不足等可补查缺口。
  - 新增 `EvidenceReActTool`，插入 `score_projects` 之后、`generate_learning_plan` 之前。
  - 新增有限 ReAct 配置：`openscout.react.enabled`、`openscout.react.max-rounds`、`openscout.react.max-follow-up-repos`。
  - real 模式下对有 action 的 gap 追加 `CollectorClient.getReadme` 补查；mock 模式只记录跳过，不调用外部 API。
  - 补查成功后更新 `AgentContext.repos`，重新运行规则评分并更新 `AgentContext.recommendations`。
  - GitHub 404、限流和普通异常按 repo 粒度记录 observation，除全局限流外继续处理后续可行动项。
  - Trace 记录 gap、follow-up、observation、rescore 和停止原因，继续复用 `TraceToolCall`，不新增 DDL。
  - 单元测试覆盖 gap 检测、planner step、ReAct 成功补查、README 缺失、限流、部分失败、最大轮数和 mock 跳过。
  - 同步 README、`项目实施进度.md` 和 `code_copilot` change 文档。
- 本次不包含：
  - 让 LLM 自主规划 Tool 或动态选择任意 Tool。
  - Spring AI `@Tool`、MCP、公开 Tool API。
  - SSE / Streaming 事件流。
  - Reflection Verifier。
  - Agent Evaluation 指标集。
  - 向量检索、embedding、FULLTEXT 索引。
  - 新增数据库表、Trace DDL 或迁移框架。
  - 生产鉴权、多用户隔离、前端展示。
- 后续可能拆分：
  - `openscout-reflection-verifier`：最终回答前拦截 LLM 编造和学习计划幻觉。
  - `openscout-agent-events-stream`：把 plan/tool/observation/ReAct 过程输出为 SSE 事件。
  - `openscout-agent-evaluation`：评测 evidence 覆盖率、hallucination 拦截率、API 调用节省和延迟。

## 2. Research Findings

### 2.1 相关入口与链路

- HTTP/API：`openscout-agent-server/src/main/java/com/openscout/agent/AgentController.java` 暴露 `POST /api/agent/ask` 和 Trace 查询接口，当前响应兼容性必须保持。
- Agent 编排：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java` 调用 `RuleBasedAgentPlanner` 生成固定计划，并逐步通过 `ToolExecutor` 执行。
- Planner：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java` 当前 mock 计划为 `interpret_goal -> check_memory -> search_repos -> score_projects -> generate_learning_plan -> generate_answer`，real 计划额外包含 `fetch_readme`。
- Runtime 上下文：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentContext.java` 保存 `repos`、`recommendations`、`learningPlan`、`answer`、memory 状态，是 Evidence ReAct 更新 repo 和推荐结果的承载点。
- Tool Runtime：`openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolExecutor.java` 统一记录 `agent_tool_started`、`agent_tool_finished`、`agent_tool_failed`。
- README 补充：`openscout-agent-server/src/main/java/com/openscout/agent/tool/FetchReadmeTool.java` real 模式对 Top 5 repo 调用 `CollectorClient.getReadme`，并对 404、限流、普通异常做 best-effort 跳过。
- 评分：`openscout-agent-server/src/main/java/com/openscout/agent/tool/ScoreProjectsTool.java` 使用 `ProjectScoreService` 生成推荐并按分数排序，持久化开启时写 `repo_info` / `repo_analysis` 并记录 `memory_writeback`。
- 评分 evidence：`openscout-agent-server/src/main/java/com/openscout/scoring/ProjectScoreService.java` 生成 `docs:*`、`learning:*`、`match:*`、`activity:*`、`resume:*` evidence；README 长度和 examples 是文档证据的主要来源。
- Trace：`openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` 已提供通用 `recordToolCall()`，并统一做敏感信息脱敏和摘要截断。
- 配置：`openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java` 和 `application.yml` 已有 `persistence`、`memory`、`trace` 等配置分组，可继续增加 `react` 分组。
- 测试基线：`RuleBasedAgentPlannerTest`、`PlanExecutorTest`、`CheckMemoryToolTest`、`ProjectScoreServiceTest`、`TraceServiceTest` 已覆盖当前 Runtime / Tool / Memory / Trace 行为。

### 2.2 现有实现摘要

- 当前计划是静态一次性流程，评分完成后直接生成学习计划和最终回答，没有依据评分 evidence 再触发补查。
- `FetchReadmeTool` 已经具备单轮 Top 5 README 补充能力，但只在评分前执行；如果某些 repo 仍缺 README 或 memory 恢复证据不足，后续没有二次补证据机会。
- `ProjectScoreService` 的 evidence 是字符串列表，不存在结构化 gap 类型；阶段 11 需要新增轻量模型把 gap 类型、repo、原因、action 和 observation 显式化。
- `TraceService.recordToolCall()` 已能承载新事件，本阶段不需要改 `agent_trace` DDL。
- `PlanExecutor` 已能执行新增固定 Tool，只要 Planner 插入 step 且 Spring 注入新的 `AgentTool` 即可被 `ToolRegistry` 注册。

### 2.3 发现的问题

- 评分后缺少 evidence gap 判断，回答可能基于低文档证据项目继续生成，Trace 不能说明证据不足。
- README 获取失败只在 `readme_fetch_github` 摘要里聚合 `skipped`，缺少每个 repo 的 follow-up observation。
- 当前 memory 命中路径可能从 evidence 推断 README 长度，仍可能出现信息不足但未再次补查的情况。
- 学习计划和回答生成会消费当前 `recommendations`，如果补查发生在它们之后，无法影响输出，因此 ReAct 必须位于 `score_projects` 之后、`generate_learning_plan` 之前。
- 无循环上限会带来 GitHub API 配额风险，因此第一版必须默认一轮、可配置上限，并在 Trace 中记录停止原因。

### 2.4 风险初判

- GitHub API 限流：追加 README 补查会增加外部调用，必须限制 repo 数和轮数；遇到 `RateLimitException` 后停止本轮后续调用并记录 `retryAfterSeconds`。
- 评分一致性：补查成功后必须重新评分，保证学习计划和回答使用更新后的 evidence；补查失败不能清空已有推荐。
- Trace 脱敏和体积：observation 只能记录 repo、gap 类型、状态、错误摘要和长度，不保存完整 README 或 token。
- 计划兼容：新增 `evidence_react` step 会改变 Trace step 数量，但不改变 `/api/agent/ask` 响应字段。
- 持久化一致性：补查后重评分的结果是否写回 memory 需要复用或提取现有持久化逻辑，避免只更新内存却不回写缓存。

## 3. 功能点

- [x] 功能 1：新增 Evidence Gap 模型和检测器，识别可补查的 README / 文档 evidence 缺口。
- [x] 功能 2：新增 `EvidenceReActTool`，按配置执行有限补查、记录 observation、更新 context。
- [x] 功能 3：修改 `RuleBasedAgentPlanner`，在 `score_projects` 和 `generate_learning_plan` 之间插入 `evidence_react`。
- [x] 功能 4：新增 `openscout.react.*` 配置和默认值。
- [x] 功能 5：抽取或复用 README enrichment 逻辑，避免 `FetchReadmeTool` 与 `EvidenceReActTool` 分叉。
- [x] 功能 6：补查成功后重新评分并保持推荐排序；持久化开启时回写最新分析。
- [x] 功能 7：新增 Trace 事件约定和 README 文档说明。
- [x] 功能 8：补齐 Planner、Tool、gap detector、PlanExecutor 回归测试和异常场景测试。
- [x] 功能 9：同步 `项目实施进度.md`、change 文档和知识索引。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| Java Tool | `EvidenceReActTool` | 新增固定 Tool，执行受限补查和重评分 | 内部新增，不改 API | 从 Planner 移除 step 或关闭配置 |
| Java Model | `EvidenceGap` / `FollowUpObservation` | 新增内部模型，描述 gap/action/observation | 内部新增 | 删除模型和 Tool |
| Java Config | `openscout.react.enabled` | 默认 `true`，控制 ReAct 是否启用 | 新增配置 | 设为 `false` 回退旧流程 |
| Java Config | `openscout.react.max-rounds` | 默认 `1`，限制循环轮数 | 新增配置 | 设为 `0` 或关闭 enabled |
| Java Config | `openscout.react.max-follow-up-repos` | 默认 `3`，限制单轮补查 repo 数 | 新增配置 | 设为 `0` 或关闭 enabled |
| Trace | `TraceToolCall` 事件 | 新增 evidence gap / follow-up / observation / stopped 事件 | 不新增 DDL | 关闭 ReAct 或删除事件记录 |
| DB | 无 | 不新增表，不改 `deploy/init.sql` | 无迁移风险 | 无 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | 复用 `AgentAskRequest` | 保留既有字段；学习计划和回答基于补查后的 recommendations | 不新增鉴权 | 必须兼容 |
| `/api/agent/traces/{traceId}` | GET | path `traceId` | `toolCalls` 追加 evidence ReAct 相关事件 | 当前仍为本地调试接口 | 兼容追加事件 |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector | `GET /api/repos/{owner}/{repo}/readme?mode=github` | owner/repo | README 文本和长度 | 复用 CollectorClient 超时；不新增重试 | 404/普通错误记录 observation 后继续；限流记录 retryAfter 并停止后续补查 |
| MySQL | 既有 repo/analysis persistence | repo 和评分结果 | upsert / insert | 复用阶段 4 逻辑 | 写入失败只 `warn`，不影响 ask |

## 6. 风险与关注点

- **外部 API 配额**：默认一轮、最多 3 个 repo；real 模式才调用 GitHub README；mock 模式记录 skipped。
- **循环停止条件**：`disabled`、`mode_not_real`、`no_recommendations`、`no_actionable_gap`、`max_rounds_reached`、`rate_limited` 都必须进入 Trace。
- **部分失败语义**：单 repo 404 或普通异常不影响其他 repo；限流代表全局风险，停止后续调用。
- **重评分后的持久化**：如果补查后只更新内存，阶段 10 memory 下次仍可能命中旧 evidence；建议把 `ScoreProjectsTool` 的评分+持久化能力抽出为可复用服务，供 `score_projects` 和 `evidence_react` 共用。
- **LLM 不得编造**：AnswerGenerator 仍只能基于规则评分和 evidence 生成回答；ReAct 只补证据，不允许 LLM 直接改分。
- **Trace 体积**：不记录完整 README、完整 prompt 或模型输出；只记录长度、gap 类型、状态和错误摘要。

## 7. 测试策略

- 单元测试：
  - `EvidenceGapDetectorTest`：README 缺失、docs evidence 不足、无 gap、Top N 限制。
  - `RuleBasedAgentPlannerTest`：mock / real 计划包含 `evidence_react`，位置在 `score_projects` 后。
  - `EvidenceReActToolTest`：disabled、mock 跳过、无推荐跳过、无 gap 停止、成功补 README 后重评分、404 部分失败、RateLimit 停止、max-rounds 生效。
  - `TraceServiceTest` 或 Tool 测试：ReAct 事件摘要脱敏，不保存完整 README。
  - `PlanExecutorTest`：mock 成功链路兼容；real 模式 README 部分失败后仍生成回答和学习计划。
- 回归验证：
  - `cd openscout-agent-server && mvn test`
  - `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...`
  - `docker compose -f deploy/docker-compose.yml config`
- 可选本地集成：
  - 启动 Go + Java real 模式，构造 README 缺失/限流场景，观察 Trace 中 gap、follow-up、observation 和 stop reason。

## 8. 待澄清

- [x] `/apply` 前确认：第一版默认 `max-rounds=1`、`max-follow-up-repos=3`。
- [x] `/apply` 前确认：ReAct 补查第一版只做 README evidence，不做 release、目录结构、issues 等额外 GitHub 调用。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| ReAct 入口 | 新增固定 `evidence_react` Tool | 在 `PlanExecutor` 写特殊循环 | 符合阶段 9 Tool Runtime 约定，Trace 和测试边界清晰 | 计划 step 数增加 |
| 执行位置 | `score_projects` 后、`generate_learning_plan` 前 | `fetch_readme` 前或回答生成前 | 需要先有评分 evidence 才能判断 gap，并让学习计划/回答消费补查结果 | 需要补查后重评分 |
| 循环上限 | 配置化，默认 1 轮 | 不设上限或硬编码 | 控制 GitHub API 成本和执行时间 | 可能无法一次补齐所有 gap |
| 第一版 action | 只补 README | 同时补 release/issues/tree | README 是当前评分证据中最直接的缺口，风险和调用成本最低 | 其他 evidence 后续扩展 |
| Trace 存储 | 复用 `TraceToolCall` | 新增 trace 表或 JSON schema | 阶段 11 不做 DDL，保持阶段 4/8/9 兼容 | 事件仍是摘要型 |
| 限流处理 | 记录 observation 并停止后续补查 | 抛错中断 ask | ReAct 是增强能力，不应破坏主流程 | 可能保留部分 gap |

## 10. 确认记录

- 确认时间：2026-06-01。
- 确认人：用户。
- 确认范围：默认 `max-rounds=1`、`max-follow-up-repos=3`；第一版只补 README evidence，不做 release、目录结构、issues 等额外 GitHub 调用；开始 `/apply`。

## 11. 收尾与 Deferred

- Apply 结果：已完成 EvidenceGapDetector、ReadmeEvidenceEnricher、EvidenceReActTool、RecommendationScoringService、Planner step、`openscout.react.*` 配置、Trace 事件、README 和进度文档同步。
- 验证结果：`cd openscout-agent-server && mvn test` 通过（70 tests）；`cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...` 通过；`docker compose -f deploy/docker-compose.yml config` 通过。
- Review 结论：主流程与 Spec 对齐，review 发现的 3 个 deferred 均已关闭。
- Review fix 1：`FetchReadmeTool` 把同一次 ask 内 README 失败状态写入 `AgentContext`，`EvidenceReActTool` 对已失败 repo 记录 `skipped_previous_<status>` observation，不再重复调用 README。
- Review fix 2：`ReadmeEvidenceEnricher` 仅在 `readmeLength > 0` 时返回 fetched；空 README 返回 `empty_readme` observation。
- Review fix 3：`FetchReadmeTool` 遇到 `rate_limited` 后停止后续 Top 5 README 调用，并把 `status/retryAfterSeconds` 写入 `AgentContext`；`EvidenceReActTool` 复用该 observation，输出 `rate_limited` 和 `retryAfterSeconds` 后停止补查。
- 归档状态：阶段 11 已沉淀为 `done`；当前无阶段 11 阻塞 deferred。
