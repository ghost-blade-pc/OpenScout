# 阶段 12 Reflection Verifier
> status: done
> created: 2026-06-01
> complexity: 中等

## 1. 背景与目标

阶段 11 已完成 Evidence ReAct，当前 `/api/agent/ask` 在评分和补查之后生成学习计划和最终回答。`AnswerGenerator` 和 `LearningPlanGenerator` 的系统提示已约束 LLM 不得修改评分、不得编造项目特性，但系统没有在最终回答前进行**执行后自检**：LLM 仍可能在回答中篡改分数数字、引用未经 evidence 支持的项目能力、或生成引用不存在项目的学习任务。

阶段 12 的目标是在 `generate_answer` 之后（或 `generate_learning_plan` 与 `generate_answer` 之间）加入 **Reflection Verifier** 步骤：读取 AgentContext 中的最终回答、推荐结果和学习计划，执行规则化自检，拦截明显编造和不可执行内容。Verifier 的运行结果和发现的问题仅记录到 Trace，**不阻断主流程**（continueOnFailure=true），即使无 LLM Key 也保留基于规则的基本自检能力。

可观察结果：

- Trace 展示 verifier 的检查结果：`verify_score_integrity`、`verify_evidence_claims`、`verify_learning_plan`。
- Verifier 可发现分数篡改（回答中分数与 `ProjectScore` 不一致）、无证据项目能力（回答声称某项目有某特性但 evidence 中无对应条目）、学习计划幻觉（任务引用不存在仓库名或编造 API/模块）。
- 无模型 Key 时，Verifier 使用基于规则的检查（正则、字符串匹配），仍然可以检测分数篡改和项目名引用不一致。
- Verifier 失败不阻塞 `/api/agent/ask` 响应，回答和学习计划正常返回给用户。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的 Agent Runtime -> Tool Runtime -> Reflection Verifier -> 回答/学习计划自检 -> Trace。
- 调用方向：HTTP -> AgentService -> PlanExecutor -> ToolExecutor -> VerifyAnswerTool -> AgentContext（读取 answer/recommendations/learningPlan）-> TraceService。
- 是否涉及高风险项：是。
- 高风险类型：模型输出可信度、分数篡改检测、学习计划可执行性验证、Trace 脱敏。

### 1.2 范围裁剪

- 本次包含：
  - 新增 `VerifyAnswerTool`，作为固定 Tool 插入 Agent 计划，`toolName="verify_answer"`。
  - 新增回答分数完整性检查：正则提取回答中提及的分数数字，与 `ProjectScore.totalScore` 对比。
  - 新增回答证据声明检查：LLM 回答中声称的项目能力是否在 `ProjectScore.evidence` 中有对应条目。
  - 新增学习计划可行性检查：学习任务引用的仓库名是否在推荐列表中；任务描述中的技术关键词是否与仓库语言/topics 匹配。
  - 新增 `openscout.verifier.*` 配置，默认 `enabled=true`。
  - Verifier 无 LLM Key 时仍执行基于规则的检查（规则检查不依赖 LLM）。
  - 可选：有 LLM Key 时用 LLM 做更深层的语义检查（如检测"听起来合理但无法验证的陈述"）。
  - Trace 记录 `verify_score_integrity`、`verify_evidence_claims`、`verify_learning_plan` 和 `verify_completed` 事件。
  - 单元测试覆盖分数篡改检测、证据声明缺失、学习计划幻觉、disabled 跳过、无 LLM Key fallback、部分检查失败不阻断。
  - 同步 README、`项目实施进度.md` 和 `code_copilot` change 文档。
- 本次不包含：
  - LLM 自动修正被检出的问题（仅报告，不重写回答）。
  - Verifier 阻止回答返回给用户（continueOnFailure=true，不变更 ask 响应状态码）。
  - 对 `generate_answer` 之前所有 LLM 输出（如 GoalInterpreter）做自检。
  - SSE / Streaming 事件流。
  - Agent Evaluation 指标集。
  - 向量检索、FULLTEXT、embedding。
  - 新增数据库表、Trace DDL 或迁移框架。
  - 生产鉴权、多用户隔离、前端展示。
- 后续可能拆分：
  - `openscout-agent-events-stream`：把 plan/tool/observation/ReAct/verifier 过程输出为 SSE 事件。
  - `openscout-agent-evaluation`：评测 verifier 拦截率、误报率、hallucination 覆盖率。

## 2. Research Findings

### 2.1 相关入口与链路

- **回答生成**：`AnswerGenerator.generate()` 返回 LLM 生成的 Markdown 回答文本，包含推荐理由和分数。系统提示已要求"不得修改分数数字"，但无执行后校验。
- **学习计划生成**：`LearningPlanGenerator.generate()` 返回 7 天任务列表，每项任务包含 `title`、`description`、`projectName`、`dayNumber`。系统提示已要求"不得编造项目模块、API 或功能"，但无执行后校验。
- **AgentContext**：保存 `answer`、`recommendations`、`learningPlan`、`repos`、`scoreSummary`，是 Verifier 读取被检数据的承载点。
- **现有 Tool 模式**：`EvidenceReActTool` 已实现配置开关、停因记录、部分失败不阻断；`VerifyAnswerTool` 可复用此模式。
- **Trace**：`TraceService.recordToolCall()` 已能承载新事件，不需要改 `agent_trace` DDL。
- **配置**：`OpenScoutProperties` 已有 `memory`、`react` 等分组，可新增 `verifier` 分组。
- **测试基线**：`RuleBasedAgentPlannerTest`、`PlanExecutorTest`、`EvidenceReActToolTest`、`AgentServiceTest` 已覆盖当前 Runtime / Tool / ask 链路。

### 2.2 现有实现摘要

- `AnswerGenerator` 的模板回答通过 `ProjectRecommendation.score().totalScore()` 拼接分数；LLM 回答由模型生成文本，分数数字可能在 LLM 输出中被改写。
- `LearningPlanGenerator` 的模板任务和 LLM 增强任务都通过 `projectName` 字段关联仓库；`targetStack` 从推荐仓库的 `language` 聚合。
- 当前 `ProjectScore.evidence()` 是字符串列表（如 `"docs:README 5000+ chars"`、`"learning:has examples"`），可以作为"项目是否真的具备某能力"的基准。
- `AgentContext` 的 `getAnswer()` 和 `getRecommendations()` 在 PlanExecutor 返回后即被消费，Verifier 需要在 `generate_answer` 之后、PlanExecutor 返回之前执行。

### 2.3 发现的问题

- **分数篡改风险**：LLM 可能将 `totalScore=75` 改写为 `80` 或 `"评分约 80 分（满分 100）"`，当前无校验。
- **能力编造风险**：LLM 可能在回答中说"该项目有完善的微服务架构文档"，但 evidence 中无对应条目。
- **学习计划幻觉**：LLM 可能在任务描述中引用不在推荐列表中的仓库名或编造 API 方法名。
- **无 LLM Key 时无校验**：当前 `AnswerGenerator` 在无 Key 时走模板，模板本身不编造，但无独立校验步骤。

### 2.4 风险初判

- **误报风险**：规则化检查的正则/字符串匹配可能产生误报（如分数 75 出现两次、evidence 关键词部分匹配），Verifier 记录 warning 但不篡改回答。
- **LLM 依赖**：深层语义检查需要 LLM Key，无 Key 时只做规则检查。规则检查的覆盖度较低但无依赖。
- **性能影响**：规则检查毫秒级；若启用 LLM 语义检查，增加一次 ChatClient 调用（~1-3s），通过独立 timeout 控制。
- **计划兼容**：新增 `verify_answer` step 改变 Trace step 数量但不改 `/api/agent/ask` 响应字段。
- **Verifier 修改回答**：第一版只报告不修改，避免 Verifier 引入新的错误。

## 3. 功能点

- [x] 功能 1：新增 `VerifyAnswerTool`，实现 `AgentTool` 接口，`toolName="verify_answer"`，`continueOnFailure=true`。
- [x] 功能 2：新增分数完整性检查：从回答文本正则提取分数数字，与 `recommendations` 中 `totalScore` 对比。
- [x] 功能 3：新增回答证据声明检查：LLM 回答中声称的项目能力是否在 `ProjectScore.evidence` 中有对应条目。
- [x] 功能 4：新增学习计划可行性检查：任务引用的仓库名是否在推荐列表中，任务关键词是否与仓库语言/topics 匹配。
- [x] 功能 5：新增 `openscout.verifier.*` 配置（`enabled`、`llm-enabled`），无 LLM Key 时 fallback 到纯规则检查。
- [x] 功能 6：修改 `RuleBasedAgentPlanner`，在 `generate_answer` 之后插入 `verify_answer`。
- [x] 功能 7：Trace 记录 verifier 各项检查结果和最终状态。
- [x] 功能 8：补齐 Verifier 单元测试：分数篡改、证据缺失、计划幻觉、disabled、无 LLM fallback、部分失败不阻断。
- [x] 功能 9：同步 `项目实施进度.md`、change 文档和知识索引。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| Java Tool | `VerifyAnswerTool` | 新增固定 Tool，执行回答/计划自检 | 内部新增，不改 API | 从 Planner 移除 step 或关闭配置 |
| Java Model | `VerificationResult` | 新增内部模型，描述各项检查结果和问题列表 | 内部新增 | 删除模型和 Tool |
| Java Config | `openscout.verifier.enabled` | 默认 `true`，控制 Verifier 是否启用 | 新增配置 | 设为 `false` 回退旧流程 |
| Java Config | `openscout.verifier.llm-enabled` | 默认 `false`，是否用 LLM 做深层语义检查 | 新增配置 | 第一版默认关闭 |
| Trace | `TraceToolCall` 事件 | 新增 `verify_score_integrity`、`verify_evidence_claims`、`verify_learning_plan`、`verify_completed` 事件 | 不新增 DDL | 关闭 Verifier 或删除事件记录 |
| DB | 无 | 不新增表，不改 `deploy/init.sql` | 无迁移风险 | 无 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | 复用 `AgentAskRequest` | 保留既有字段；Verifier 不修改响应 | 不新增鉴权 | 必须兼容 |
| `/api/agent/traces/{traceId}` | GET | path `traceId` | `toolCalls` 追加 verify 相关事件 | 当前仍为本地调试接口 | 兼容追加事件 |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| LLM（可选） | ChatClient.call() | verifier prompt + answer/recommendations/plan | 结构化检查结果 | 独立 timeout 30s；不重试 | LLM 调用失败 fallback 到规则检查 |

## 6. 风险与关注点

- **误报与漏报**：规则检查（正则分数提取、evidence 关键词匹配）存在误报风险。Verifier 只记录问题到 Trace，不修改回答，避免误报影响用户体验。
- **LLM 语义检查**：第一版默认关闭（`llm-enabled=false`），防止 Verifier 自身引入 LLM 幻觉。
- **Trace 体积**：Verifier 输出只记录检查摘要和发现的问题数量，不记录完整回答或完整 evidence 列表。
- **continueOnFailure=true**：Verifier 任何失败（包括 NPE）都不应阻断 ask 流程。`PlanExecutor` 对 `continueOnFailure=true` 的 step 已正确处理。
- **分数提取准确性**：正则需要处理多种分数表示格式：`75分`、`75/100`、`评分 75`、`**75**`。不匹配时记录 `score_format_unrecognized` 而非误报篡改。

## 7. 测试策略

- 单元测试：
  - `VerifyAnswerToolTest`：分数匹配、分数篡改、分数格式无法识别、evidence 声明匹配、evidence 声明缺失、学习计划仓库名匹配、学习计划幻觉仓库名、disabled 跳过、LLM fallback 到规则检查、部分检查异常不阻断。
  - `RuleBasedAgentPlannerTest`：mock / real 计划包含 `verify_answer`，位置在 `generate_answer` 之后。
  - `PlanExecutorTest`：`verify_answer` 失败不阻断 ask；Trace 包含 verifier 事件。
- 回归验证：
  - `cd openscout-agent-server && mvn test`
  - `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...`
  - `docker compose -f deploy/docker-compose.yml config`
- 可选本地集成：
  - 启动 Go + Java，构造回答中包含篡改分数的场景，观察 Trace 中 verify 事件。

## 8. 待澄清

- [x] `/apply` 前确认：第一版 `llm-enabled=false`，只做规则检查。→ 已确认，用户允许进入 `/apply`。
- [x] `/apply` 前确认：`verify_answer` 放在 `generate_answer` 之后（对最终回答做自检）。→ 已确认。
- [x] `/apply` 前确认：Verifier 发现问题后仅记录 Trace，不在回答末尾追加警告标记。→ 已确认。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| Verifier 入口 | 新增固定 `verify_answer` Tool | 修改 `AnswerGenerator` 内部自检 | 符合 Tool Runtime 约定，Trace 和测试边界清晰；Verifier 独立于 AnswerGenerator | 计划 step 数 +1 |
| 执行位置 | `generate_answer` 之后 | `generate_learning_plan` 与 `generate_answer` 之间 | 最终回答是最容易产生幻觉的环节，应对其做最完整的检查 | Verifier 输入包含回答全文 |
| 检查方式 | 第一版默认规则检查，LLM 语义检查可选关闭 | 全 LLM 检查或全规则检查 | 规则检查无外部依赖、无成本、可预测；LLM 检查作为可演进增强 | 规则检查覆盖度低于 LLM，但无误报风险 |
| 失败策略 | `continueOnFailure=true`，只记录 Trace | Verifier 失败时返回错误 | Verifier 是增强能力，不应破坏主流程 | 可能漏过编造内容 |
| 是否修改回答 | 第一版不修改 | 追加警告标记或自动修正 | 避免 Verifier 引入新错误 | 用户看到的回答不变 |
| Trace 存储 | 复用 `TraceToolCall` | 新增 verify_results 表 | 阶段 12 不做 DDL | 事件仍是摘要型 |

## 10. 确认记录

- 确认时间：2026-06-01。
- 确认人：用户。
- 确认范围：`verify_answer` 放在 `generate_answer` 之后；第一版 `llm-enabled=false`，只做规则检查；发现问题仅记录 Trace，不在回答中追加警告标记。开始 `/apply`。

## 11. 收尾与 Deferred

- Apply 结果：已完成 VerifyAnswerTool（含 ScoreIntegrityChecker、EvidenceClaimsChecker、LearningPlanChecker、VerificationResult、VerificationIssue、VerifierUtils）、Planner step `verify_answer`、`openscout.verifier.*` 配置、Trace `verify_completed` 事件。
- Review 结果：7 轮 review 共发现 28 项问题，全部已修复。关键修复包括：
  - 异常处理 catch 块补齐 `set*Ok(false)` 标志
  - 新增 `SCORE_FORMAT_UNRECOGNIZED` warning（spec 要求）
  - 短名称冲突修复（fullName 优先 → shortName fallback）
  - 提取共享 `VerifierUtils`（消除 extractShortName×3 + truncate×6 重复）
  - `quick.?start` 字面量拆分、英文关键词补充
  - LearningPlanChecker 短名引用检测 + 复合词过滤 + owner 路径识别
  - EvidenceClaimsChecker **否定声明过滤**（"文档不完善" 不再误匹配 "文档完善"）
  - REPO_PATTERN 尾随标点修复
  - `VerifyAnswerTool.runXxxCheck` DRY 重构为泛化 `runCheck()`
  - `VerificationIssue` 构造函数可见性收紧
  - ScoreIntegrityChecker + EvidenceClaimsChecker 上下文窗口 nameLen 一致性
- 验证结果：`cd openscout-agent-server && mvn test` 通过（98 tests，含 28 个新增 Verifier 测试）；`cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...` 通过。
- 新增文件：`VerifierUtils.java`、`VerificationResult.java`、`VerificationIssue.java`、`ScoreIntegrityChecker.java`、`EvidenceClaimsChecker.java`、`LearningPlanChecker.java`、`VerifyAnswerTool.java`，及其对应 4 个测试类。
- 修改文件：`OpenScoutProperties.java`、`application.yml`、`RuleBasedAgentPlanner.java`、`PlanExecutorTest.java`、`RuleBasedAgentPlannerTest.java`。
- 归档状态：阶段 12 已标记为 `done`；当前无阶段 12 阻塞 deferred。
- Deferred 项：LLM 语义检查（`llm-enabled=false` 第一版关闭）、自动修正被检出问题、GoalInterpreter 自检、SSE/Streaming、Agent Evaluation 均按 spec 留到后续阶段。
