# Agent 推理架构优化方案

## 背景判断

当前 OpenScout Agent 已经具备可运行的工程闭环：用户输入学习目标后，Java Agent Server 调用 Go Repo Collector 获取 GitHub 项目数据，再进行规则评分、LLM 解释、学习计划生成和 Agent Trace 记录。

当前链路本质上仍是固定流程编排：

```text
用户目标 -> 关键词提取 -> GitHub/Mock 数据采集 -> 规则评分 -> LLM 解释 -> 学习计划 -> Trace
```

它的优点是稳定、可测试、可解释、容易 fallback；不足是 Agent 推理能力偏弱，缺少动态规划、标准工具运行时、基于观察结果的追加工具调用、长期记忆、证据充分性判断、最终输出自检和系统化评测。

后续升级目标不是“多接几个 API”，而是把 OpenScout 从“项目推荐流程”升级为一个可观测、可控、有记忆、有自检的 Agent Runtime。

## 总体目标

目标形态：

```text
User Goal
  -> Session / User Context
  -> Planner 生成 AgentPlan
  -> Memory Retriever 检索历史项目知识
  -> PlanExecutor 执行工具
  -> Evidence-aware ReAct 补查证据
  -> Scoring Engine 规则评分
  -> Reflection Verifier 自检
  -> Answer / Learning Plan / Report
  -> Trace + Memory 写回
```

核心能力：

- 会规划：知道为了回答用户目标要做哪些步骤。
- 会用工具：GitHub search/profile/readme/issues/releases/examples 都是标准 Tool。
- 会观察：每个工具调用产生 Observation。
- 会判断证据够不够：不够就补查。
- 会记忆：历史项目知识、用户目标、Trace 能复用。
- 会自检：回答和学习计划必须被 evidence 支撑。
- 可追踪：完整展示 plan、tool、observation、memory hit、verification。

## 架构原则

1. 规则评分仍然是最终分数来源，LLM 不得覆盖规则评分。
2. Planner 只能生成可执行、可追踪、可限制次数的计划步骤。
3. Tool 必须有明确输入输出、超时、错误类型、限流策略和 Trace 摘要。
4. Project Memory 负责复用历史知识，但不得绕过过期判断和来源记录。
5. RAG 检索结果只能作为证据候选，最终推荐仍要回到规则评分和可追溯 evidence。
6. ReAct 只用于局部补证据，不做无限循环。
7. Reflection 只做质量检查和风险标注，不重新编造业务事实。
8. 所有计划、记忆命中、工具调用、观察结果、追加查询和自检结论都必须进入 Agent Trace。
9. 模型不可用时仍能 fallback 到规则模板和当前固定链路，保证演示稳定。

## 分阶段实施路线

### 阶段 8：Agent Runtime 内核

目标：把当前写死在 `AgentService` 中的流程抽象为显式 Agent Runtime，为后续工具系统、记忆系统、ReAct 和 Reflection 打地基。

建议新增核心对象：

- `AgentPlan`：一次请求的完整执行计划。
- `PlanStep`：单个计划步骤，包含 stepId、toolName、purpose、input、status。
- `StepObservation`：工具执行后的观察结果摘要。
- `AgentContext`：保存用户目标、会话信息、运行配置、推荐候选和中间状态。
- `ToolRegistry`：注册可用工具。
- `ToolExecutor`：统一执行工具，处理超时、异常和 Trace。
- `PlanExecutor`：按计划执行 step，并把 observation 写回上下文。

第一版 Planner 可以先用规则模板，不强依赖 LLM：

```text
1. interpret_goal
2. search_repos
3. fetch_readme
4. score_projects
5. generate_learning_plan
6. generate_answer
```

验收标准：

- `/api/agent/ask` 响应兼容现有字段。
- Trace 能看到 plan、step、observation。
- mock 模式和真实 GitHub 模式走同一套 executor。
- 每个 step 失败能记录状态，并决定是否继续。
- `mvn test` 覆盖 Planner、Executor、异常和 fallback。

### 阶段 9：工具系统升级

目标：把现有 Collector 调用和评分/学习计划能力升级为标准 Tool，而不是散落在 service method 中的普通调用。

建议工具：

- `RepoSearchTool`
- `RepoProfileTool`
- `ReadmeFetchTool`
- `BatchRepoProfileTool`
- `ReleaseFetchTool`
- `IssueSummaryTool`
- `ExampleDetectorTool`
- `ProjectScoreTool`
- `LearningPlanTool`

每个 Tool 必须定义：

- name
- description
- input schema
- output schema
- timeout
- retry policy
- rate limit policy
- error type
- trace summary
- sensitive data policy

阶段策略：

- 第一版先做工程层 Tool 抽象，不强依赖 Spring AI Tool Calling。
- 稳定后再决定哪些工具暴露给 LLM 选择调用。
- 所有 Tool 都必须可单元测试，不能只靠端到端 curl 验证。

验收标准：

- `AgentService` 不再直接调用具体 Collector 方法，而是通过 Tool Runtime 执行。
- 每个 Tool 的输入输出可序列化，可进入 Trace。
- GitHub 限流、超时、404、部分失败都有结构化错误。
- mock/real 数据源能复用同一个 tool interface。

### 阶段 10：Project Memory / RAG

目标：把 OpenScout 从“一次性 GitHub 检索流程”升级为“能沉淀和复用项目知识的 Agent”。

记忆系统不应只做普通文档问答 RAG，而应服务于项目推荐、学习计划和 Agent Trace 可观测。核心思路是：推荐前先检索历史知识，证据不足或过期时再触发实时 GitHub 工具调用。

记忆类型：

- 项目记忆：repo metadata、README 摘要、topics、release、issues、examples、评分证据、更新时间。
- 用户目标记忆：用户曾经关注的学习目标、选择过的项目、生成过的学习计划和任务状态。
- Agent 执行记忆：每次 plan、tool call、observation、失败原因、verification warning 和最终推荐摘要。

建议新增核心对象：

- `ProjectMemoryService`：统一读写项目知识。
- `MemoryRetriever`：根据用户目标和计划步骤检索历史知识。
- `MemoryEvidence`：记录命中的 repo、chunk、来源、更新时间、可信度和过期状态。
- `KnowledgeChunk`：README、分析摘要、release、issue、examples 等可检索文本片段。
- `MemoryFreshnessPolicy`：判断知识是否过期，决定是否需要补查 GitHub。

建议新增数据表：

- `repo_knowledge_chunk`：保存知识切片、chunk 类型、来源、hash、更新时间和可选 embedding id。
- `agent_memory_event`：保存计划、工具调用、观察和 verifier 结果的结构化摘要。
- `memory_retrieval_log`：保存每次 memory hit/miss、检索条件、命中数量和 freshness 判断。
- 可复用已有 `repo_info`、`repo_analysis`、`learning_goal`、`learning_task`、`agent_trace`。

实现节奏：

- 第一版：MySQL 关键词检索、repo full name 精确匹配、language/topics 过滤、更新时间排序、freshness 判断。
- 第二版：接入 Spring AI VectorStore，例如 Redis Vector、Qdrant、Milvus 或 pgvector；具体选型实现前需要重新核对当前 Spring AI 版本支持情况。

目标链路：

```text
用户目标
  -> Planner 生成计划
  -> MemoryRetriever 检索历史项目知识
  -> MemoryFreshnessPolicy 判断证据是否过期
  -> 证据足够：进入评分和回答生成
  -> 证据不足：交给 ReAct 追加 GitHub 工具调用
  -> 新观察结果写回 Project Memory
```

验收标准：

- 同一个 repo 被多次推荐时，可以复用已有项目画像和 README 摘要。
- Trace 能展示 memory hit、memory miss、freshness 判断和写回结果。
- 过期知识不会被当成新证据强行使用。
- 无向量库时仍能通过关键词检索跑通闭环。
- `mvn test` 覆盖命中、未命中、过期、写回和 fallback。

### 阶段 11：Evidence-aware ReAct

目标：当项目证据不足时，Agent 可以基于观察结果追加有限工具调用。

流程：

```text
已有 evidence
  -> EvidenceGapDetector 判断缺口
  -> FollowUpActionPlanner 生成补查动作
  -> ToolExecutor 执行
  -> Observation 写回
  -> 重新判断 evidence 是否足够
```

典型触发条件：

- README 太短或缺失。
- 项目评分接近，但证据不足以区分推荐优先级。
- 用户目标包含明确技术点，但 repo topics/README 未能确认。
- 学习计划需要 examples/docs 信息，但当前画像没有足够依据。
- Project Memory 命中结果过期，或者缺少当前计划需要的 chunk 类型。

建议新增：

- `EvidenceGapDetector`：判断每个推荐项目缺少哪些证据。
- `FollowUpActionPlanner`：根据证据缺口生成追加动作。
- `ReActLoopController`：控制最大轮数、最大工具调用数和超时。

约束：

- 默认最多追加 1-3 轮。
- 默认最多补查 Top 3 repo。
- 遇到 GitHub 限流、403、429 时立即停止追加探索。
- LLM 不能直接改分，只能建议补查动作。
- 追加观察只补充 evidence。
- 追加观察结果要写回 Project Memory，供后续请求复用。

验收标准：

- Trace 能展示 evidence gap、follow-up action、observation。
- README 缺失、限流、部分项目失败都有测试覆盖。
- Agent 能解释“为什么追加查询”和“查询后证据是否改善”。
- ReAct 循环有明确停止条件，不会无限调用工具。

### 阶段 12：Reflection Verifier

目标：在最终回答前加入自检，降低 LLM 编造和学习计划不可执行风险。

建议新增：

- `AnswerVerifier`：检查回答中的项目名称、分数、证据是否来自推荐结果。
- `LearningPlanVerifier`：检查学习计划是否引用了不存在的模块、API 或文件。
- `EvidenceVerifier`：检查推荐证据是否能支撑结论。
- `VerificationResult`：记录 passed、warnings、blockedReasons。

检查项：

- 回答中不得修改规则评分数字。
- 回答中不得出现未采集到的项目能力。
- 推荐理由必须引用 evidence。
- 学习计划必须基于 MemoryEvidence、README、topics、language、score evidence 或明确写成“阅读 README 确认”。
- 证据不足时必须输出 warning，而不是强行推荐。
- 引用 Project Memory 时必须能追溯到来源、chunk 类型和更新时间。

验收标准：

- LLM 输出含幻觉内容时能被拦截或降级。
- Trace 能展示 verifier 结果。
- 最终响应可以包含 `verificationWarnings` 或等价字段。
- 保持无模型 Key 时的模板 fallback。

### 阶段 13：Agent Trace 可视化与 Streaming

目标：让 Agent 的计划、工具调用、观察、补查和自检过程可以实时展示，提升调试与演示价值。

后端先支持 SSE，不急于做复杂前端。

建议新增 API：

- `POST /api/agent/runs`
- `GET /api/agent/runs/{runId}`
- `GET /api/agent/runs/{runId}/events`
- `GET /api/agent/runs/{runId}/trace`

事件类型：

```text
PLAN_CREATED
MEMORY_RETRIEVED
TOOL_STARTED
TOOL_FINISHED
OBSERVATION_CREATED
EVIDENCE_GAP_FOUND
FOLLOW_UP_PLANNED
VERIFICATION_FINISHED
ANSWER_READY
```

验收标准：

- 长请求可以通过事件流看到实时进度。
- 事件与最终 Trace 能通过 runId / traceId 关联。
- 工具失败、限流、fallback 都有对应事件。
- 事件流不输出完整 token、key、prompt 大文本或敏感数据。

### 阶段 14：Agent 评测体系

目标：建立可复现评测集和指标，避免 Agent 能力只靠主观 Demo 判断。

建议测试集：

- Spring AI 学习目标。
- Go 微服务学习目标。
- Redis 源码学习目标。
- Agent 框架对比目标。
- README 缺失项目。
- GitHub 限流场景。
- LLM 编造场景。
- memory 过期场景。

建议指标：

- 推荐结果相关性。
- evidence 覆盖率。
- hallucination 拦截率。
- 工具调用次数。
- memory hit rate。
- fallback 成功率。
- 平均延迟。
- GitHub API 调用节省比例。

验收标准：

- 有固定评测命令可本地复跑。
- 每个场景都有期望行为和失败边界。
- 评测结果能输出 JSON/Markdown 报告。
- 指标口径明确区分 mock、真实 GitHub、LLM enabled/disabled。

### 阶段 15：工程化收尾

目标：补齐生产化和长期维护需要的基础设施。

建议补齐：

- Flyway 或 Liquibase 数据库迁移。
- CI 自动跑 Java/Go 测试。
- Docker Compose 一键演示。
- OpenTelemetry 或结构化 Trace。
- API 鉴权。
- 多用户隔离。
- 配额限制。
- GitHub Token 安全管理。
- prompt/version 管理。
- demo 数据初始化脚本。

验收标准：

- 新环境可以通过文档和脚本稳定启动。
- 数据库 schema 变更不依赖手工修改。
- CI 能覆盖 Java、Go 和关键文档检查。
- 本地 Demo、mock Demo、真实 GitHub Demo 的边界清楚。

## 推荐执行顺序

不要先做前端，也不要先做泛化功能。优先顺序：

1. `openscout-agent-runtime`
2. `openscout-tool-runtime`
3. `openscout-project-memory-rag`
4. `openscout-evidence-react`
5. `openscout-reflection-verifier`
6. `openscout-agent-events-stream`
7. `openscout-agent-evaluation`
8. `openscout-production-hardening`

做到第 5 步，项目本质就会从“LLM 应用”变成“Agent 系统”。

后续每个 change 都应包含 `spec.md`、`tasks.md`、`test-spec.md` 和 `log.md`，并同步更新 `项目实施进度.md`。如果只做 proposal，不要自动进入 apply。

## 能力沉淀方向

完成上述升级后，项目应能稳定体现以下工程能力：

- Plan-and-Execute Agent 编排。
- 标准 Tool Runtime。
- Project Memory / RAG。
- Evidence-aware ReAct。
- Reflection Verifier。
- Agent Trace 与事件流。
- Agent 评测体系。
- 模型失败、外部 API 限流和数据过期的可控 fallback。

最关键的升级方向是围绕“Agent 决策闭环”建设：计划、工具、观察、记忆、补查、自检、追踪。
