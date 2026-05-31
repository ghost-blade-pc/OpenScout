# 阶段 8 Agent Runtime 内核
> status: done
> created: 2026-05-31
> complexity: 复杂

## 1. 背景与目标

阶段 7 已完成推荐、LLM 回答、学习计划生成与持久化闭环。当前 `/api/agent/ask` 可运行，但核心编排仍集中在 `AgentService` 的固定方法顺序中：目标解释、Collector 调用、README enrichment、规则评分、学习计划、回答生成和 Trace 完成都由一个服务直接串联。

本阶段目标是把固定编排抽象为显式 Agent Runtime 内核，为后续 Tool Runtime、Project Memory、Evidence-aware ReAct、Reflection Verifier 打基础。第一版 Runtime 只做工程内核重构：显式计划、步骤、观察结果、执行上下文和统一执行器；不改变业务推荐结果，不引入 RAG、SSE、前端或生产鉴权。

完成后可观察到：

- `/api/agent/ask` 响应字段保持兼容。
- mock 与真实 GitHub 模式走同一套 plan executor。
- Trace 中可以看到 plan、step、observation 的摘要。
- step 失败有明确状态和 fallback 策略，仍保持当前演示链路可运行。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的 Agent 编排与 Trace 上下文。
- 调用方向：HTTP 客户端 -> Java Agent Server `/api/agent/ask` -> Agent Runtime -> Goal/Collector/Scoring/Learning/Answer 能力 -> Trace。
- 是否涉及高风险项：是。
- 高风险类型：外部 GitHub API 调用、LLM fallback、Agent Trace 数据结构、API 兼容、数据库持久化摘要、异常处理。

### 1.2 范围裁剪

- 本次包含：
  - 新增 Agent Runtime 内核模型：`AgentPlan`、`PlanStep`、`StepObservation`、`AgentContext`、step 状态枚举。
  - 新增规则模板 Planner，第一版固定生成当前 ask 链路所需步骤，不依赖 LLM 动态规划。
  - 新增 `PlanExecutor`，按 plan 执行当前能力，并在每步后写入 observation。
  - 调整 `AgentService`，让 ask mock/real 入口委托 Runtime 执行，保留对 Controller 的响应契约。
  - Trace 增强：记录 plan 创建、step 开始/结束、observation 摘要；保持脱敏和长度截断。
  - 单元测试覆盖 Planner、Executor、失败 step、mock/real 模式分支和响应兼容。
- 本次不包含：
  - 项目对比报告、报告导出、简历/面试表达增强。
  - 前端、SSE、Streaming、`/api/agent/runs`。
  - RAG、Project Memory、向量库、Redis adapter。
  - Evidence-aware ReAct 追加查询循环。
  - Reflection Verifier。
  - Spring AI Tool Calling、MCP、多 Agent。
  - 登录鉴权、多用户隔离、生产权限体系。
  - 新增数据库迁移框架或变更 `deploy/init.sql` 表结构。
- 后续可能拆分：
  - `openscout-tool-runtime`：把 Collector、Scoring、Learning、Answer 标准化为 Tool。
  - `openscout-project-memory-rag`：引入项目知识复用和 freshness。
  - `openscout-evidence-react`：基于 evidence gap 追加有限工具调用。
  - `openscout-reflection-verifier`：最终回答和学习计划自检。

## 2. Research Findings

### 2.1 相关入口与链路

- HTTP/API：`openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java` 提供 `POST /api/agent/ask` 和 `GET /api/agent/traces/{traceId}`，异常时返回 `AgentErrorResponse`，RateLimit 会设置 `Retry-After`。
- Agent 编排：`openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java` 的 `ask()` 先创建 `AgentTrace`，再按 `openscout.mock-agent` 分派到 `askMock()` 或 `askReal()`。
- mock 链路：`AgentService.askMock()` 当前顺序为 `GoalInterpreter.interpret()` -> `CollectorClient.fetchMockRepos()` -> `scoreAndRank()` -> `persistReposIfEnabled()` -> `buildLearningPlan()` -> `AnswerGenerator.generate()` -> `TraceService.complete()`。
- real 链路：`AgentService.askReal()` 当前顺序为 `GoalInterpreter.interpret()` -> `CollectorClient.searchRepos()` -> `enrichWithReadme()` -> `scoreAndRank()` -> `persistReposIfEnabled()` -> `buildLearningPlan()` -> `AnswerGenerator.generate()` -> `TraceService.complete()`。
- 外部 Client：`openscout-agent-server/src/main/java/com/openscout/client/CollectorClient.java` 已封装 mock、search、profile、readme、batch-profile 调用，并将连接失败、限流和 GitHub API 错误翻译为结构化异常。
- LLM 目标解释：`openscout-agent-server/src/main/java/com/openscout/agent/GoalInterpreter.java` 已支持 LLM JSON 输出和 fallback，并通过 `TraceService.recordToolCall()` 记录 `llm_goal_interpret`。
- LLM 回答生成：`openscout-agent-server/src/main/java/com/openscout/agent/AnswerGenerator.java` 已约束 LLM 不得修改规则评分，并通过 `TraceService.recordToolCall()` 记录 `llm_answer_generate`。
- 学习计划：`openscout-agent-server/src/main/java/com/openscout/learning/LearningPlanGenerator.java` 基于 Top 推荐和 evidence 生成 7 天任务，LLM 仅做文案增强，失败 fallback。
- 规则评分：`openscout-agent-server/src/main/java/com/openscout/scoring/ProjectScoreService.java` 生成五维评分和 evidence，是最终分数来源。
- Trace：`openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` 目前提供 `start()`、`recordToolCall()`、`complete()`、`fail()`、`find()`，并统一脱敏和截断摘要。
- Trace 模型：`openscout-agent-server/src/main/java/com/openscout/trace/AgentTrace.java` 当前保存 `toolCalls`、`scoreSummary`、`finalAnswer`、`latencyMs`、`status`、`errorMessage`。
- Trace 持久化：`code_copilot/knowledge/index.md` 记录阶段 4 的 `tool_calls_json` 持久化模式；`recordToolCall()` 只写内存，`complete()`/`fail()` 时一次性落库。
- 配置：`openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java` 包含 `mockAgent`、`collectorMode`、`llm.enabled`、`learning.enabled`、`persistence.enabled`、`trace.maxSummaryLength`。
- 测试：现有测试覆盖 `AnswerGeneratorTest`、`GoalInterpreterTest`、`LearningPlanGeneratorTest`、`ProjectScoreServiceTest`、`TraceServiceTest`、`LearningControllerTest`、`LearningPlanPersistenceServiceTest`。

### 2.2 现有实现摘要

- 当前 Agent 是可运行的固定流程，不具备显式 plan 和 step 状态。
- Trace 当前只保存工具调用摘要，无法区分“计划创建”“步骤开始/结束”“观察结果”和“步骤失败是否可继续”。
- mock 与 real 两条路径重复一部分编排逻辑：目标解释、评分、持久化、学习计划、回答生成相同，差异集中在 repo 获取和 README enrichment。
- 当前 Collector 错误处理、LLM fallback 和学习计划 fallback 已可复用；Runtime 第一版应保留这些行为，不重新发明外部调用和评分规则。

### 2.3 发现的问题

- `AgentService` 承载过多职责：流程控制、外部调用、评分、持久化、学习计划和回答生成混在一个类中，后续 Tool Runtime / Memory / ReAct 难以插入。
- Trace 缺少计划级结构，Demo 和排障只能看到散点 tool call，不能复盘一次 ask 的完整计划和步骤状态。
- 真实模式 README enrichment 的耗时记录当前使用聚合 tool call 且 latency 为 `0`，无法准确表达每个步骤的观察和失败摘要。
- step 失败语义不显式：哪些失败可继续、哪些失败应中断，当前靠方法内 catch 或外层 RuntimeException 隐含表达。
- 若直接做 RAG、SSE 或项目报告，会继续堆业务输出，无法先解决 Agent 决策闭环的可观测性和可扩展点。

### 2.4 风险初判

- API 兼容：`AgentAskResponse` 的现有字段不能改名、删除或改变语义。
- 外部 API：真实 GitHub 调用仍需保留现有超时、限流、403/429/404 和部分失败处理。
- LLM 输出可信度：Runtime 不允许让 LLM 动态决定评分或学习计划事实；Planner 第一版不使用 LLM。
- Trace 安全：plan、step、observation 写入 Trace 时只能保存摘要，不保存完整 README、完整 prompt、密钥或超大响应。
- 持久化兼容：阶段 8 不改 DDL；若需要保存 plan/observation，应复用 `tool_calls_json` 或兼容扩展内存模型，不能破坏已有 Trace 查询。
- 回归风险：重构 Agent 编排后必须证明 mock ask、real mode 错误处理、学习计划 fallback 和 LLM fallback 不退化。

## 3. 功能点

- [x] 功能 1：新增 Agent Runtime 内核模型，表达计划、步骤、观察结果、上下文和状态。
- [x] 功能 2：新增规则模板 Planner，按当前 ask 链路生成固定 `AgentPlan`。
- [x] 功能 3：新增 PlanExecutor，统一执行 mock/real 模式下的计划步骤，并产出 observation。
- [x] 功能 4：调整 AgentService，将 `ask()` 主流程委托 Runtime，保留响应字段和异常语义。
- [x] 功能 5：增强 Trace 记录 plan/step/observation 摘要，复用脱敏和截断规则。
- [x] 功能 6：补充 Planner、Executor、Trace 和 AgentService 回归测试。
- [x] 功能 7：同步 README、API 契约、项目实施进度和 change 文档。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| Java Model | `com.openscout.agent.runtime.*` | 新增 AgentPlan、PlanStep、StepObservation、AgentContext、状态枚举等内存模型 | 新增类型，不影响旧接口 | 删除 Runtime 包并回退 AgentService 编排 |
| Trace | `AgentTrace.toolCalls` 或兼容扩展字段 | 记录 plan/step/observation 摘要，仍做脱敏和长度截断 | 不改 DDL，保持现有 Trace API 可读 | 回退新增 Trace 记录即可 |
| Config | 暂无新增必需配置 | 第一版 Planner 固定模板，不新增开关 | 不影响旧配置 | 无 |
| DB | 无 | 本阶段不修改 `deploy/init.sql` | 无迁移风险 | 无 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | 复用 `AgentAskRequest` | 保留 `traceId`、`answer`、`recommendations`、`learningPlan`、`latencyMs` 字段 | 不新增鉴权；继续标注本地 Demo 边界 | 必须向后兼容 |
| `/api/agent/traces/{traceId}` | GET | path `traceId` | `AgentTrace` 中可看到 plan/step/observation 摘要 | 不新增鉴权；不得公网暴露 | 字段可兼容追加，旧 toolCalls 语义保留 |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector mock | `GET /api/repos/mock` | keyword | repo list | 复用现有 RestClient 行为 | 失败由 Runtime 标记 step failed，并沿用 AgentCallException |
| Go Collector search | `GET /api/repos/search` | keyword、limit、mode | repo list | 复用 `CollectorClient` 错误翻译 | 限流保留 RateLimitException 和 Retry-After |
| Go Collector README | `GET /api/repos/{owner}/{repo}/readme` | owner/repo/mode | README 摘要 | 复用 `CollectorClient` 错误翻译 | 单 repo 失败 best-effort 跳过，不阻塞整体 |
| DeepSeek/OpenAI 兼容 ChatClient | Spring AI ChatClient | goal 或推荐摘要 | JSON / answer 文本 | 复用现有 LLM fallback | 失败 fallback，不中断评分和推荐 |
| MySQL | MyBatis-Plus Mapper | repo analysis、learning plan、trace | insert/update/select | 无重试 | 写入失败记录 warn，不阻塞 ask 主流程 |

## 6. 风险与关注点

- Runtime 第一版是“显式化当前流程”，不是引入自主 Agent；不能让 LLM 决定任意工具调用。
- PlanStep 必须有最大数量和固定模板，避免为后续 ReAct 留下无限循环入口。
- StepObservation 必须是摘要，不能保存完整 README、完整 prompt、完整模型响应或敏感配置。
- mock 和 real 模式差异只应体现在数据采集步骤，评分、学习计划、回答生成、Trace 规则应复用。
- 当前 `AgentService.persistReposIfEnabled()` 按 `repos` 下标取 `recommendations` 存在“排序后下标不一定对应 repo”的潜在风险；本阶段若移动代码应修正映射方式，或在 apply 前作为明确子任务记录。
- `TracePersistenceService` 当前按 `tool_calls_json` 序列化已有 `TraceToolCall`；若新增 Trace 字段会涉及 DB JSON 兼容，本阶段优先通过新的 toolName 事件记录计划和步骤，避免 DDL 变更。
- 不做项目对比报告、前端、RAG、SSE、生产鉴权；这些能力不得夹带进入本阶段。

## 7. 测试策略

- 单元测试：
  - Planner 在 mock 模式生成 `interpret_goal -> search_repos -> score_projects -> generate_learning_plan -> generate_answer`。
  - Planner 在 real 模式生成包含 `fetch_readme` 的计划。
  - PlanExecutor 成功路径能产出 recommendations、learningPlan、answer 和 Trace observation。
  - 可继续失败：单个 README 获取失败只记录 observation，不中断整体。
  - 不可继续失败：Collector search 失败会标记 step failed、Trace failed，并保持 `AgentCallException` 语义。
  - TraceService 对 plan/step/observation 摘要执行脱敏和截断。
- 回归测试：
  - `AgentController` / `AgentService` 响应字段保持兼容。
  - LLM disabled 时仍使用模板 fallback。
  - `openscout.persistence.enabled=false` 时不依赖 MySQL。
- 验证命令：
  - `cd openscout-agent-server && mvn test`
  - `cd openscout-repo-collector && go test ./...`
  - `docker compose -f deploy/docker-compose.yml config`
- 可选手工验证：
  - mock 模式 curl `/api/agent/ask` 后查询 `/api/agent/traces/{traceId}`，确认 Trace 包含 plan/step/observation 摘要。
  - real 模式在 Go Collector 可用时验证 search/readme best-effort 行为。

## 8. 待澄清

- [x] 用户已明确：本次只 propose `openscout-agent-runtime`，不要直接做项目对比报告、前端、RAG、SSE 或生产鉴权。
- [x] `/apply` 前确认：Trace 中 plan/step/observation 第一版全部编码为 `TraceToolCall.toolName` 事件，不新增 Trace DDL。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| Planner 方式 | 规则模板 Planner | LLM 动态规划 | 第一版必须可测试、可限制、可 fallback | Agent 自主性有限，但内核稳定 |
| Runtime 范围 | 显式化当前 ask 链路 | 同时做 Tool Runtime / RAG / ReAct | 降低重构风险，先建立扩展点 | 后续阶段继续拆分 |
| Trace 存储 | 优先复用 `TraceToolCall` 记录 plan/step/observation 事件 | 新增 DB 字段或新表 | 避免 DDL 和迁移风险 | Trace 结构表达力受 toolCalls 限制 |
| API 契约 | `/api/agent/ask` 向后兼容 | 新增 `/api/agent/runs` | 用户明确不要 SSE；当前客户端依赖 ask | Runtime 先隐藏在服务内部 |
| 模式复用 | mock/real 共用 PlanExecutor，数据采集 step 分支 | 保留两个完整方法 | 为 Tool Runtime 做铺垫 | 需要更细粒度测试 |
| 失败策略 | step 显式区分可继续/不可继续 | 继续依赖异常流 | Trace 更可读，后续 ReAct 可复用 | 需要定义状态枚举 |

## 10. 确认记录

- 确认时间：2026-05-31。
- 确认人：用户。
- 确认范围：只执行 `/propose openscout-agent-runtime`；不进入 `/apply`；不做项目对比报告、前端、RAG、SSE 或生产鉴权。
- `/apply` 确认：2026-05-31，用户确认使用 toolName 事件，并开始 apply。
- `/archive` 完成时间：2026-05-31。阶段 8 知识已沉淀到 `code_copilot/knowledge/index.md`。
