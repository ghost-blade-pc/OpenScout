# 任务拆分 - 阶段 11 Evidence ReAct

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已检查 `code_copilot/knowledge/index.md`
- [x] 已检查工作区状态，确认不会覆盖他人修改
- [x] 已创建/切换阶段分支 `feature/10-evidence-react`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中待澄清项不阻塞 `/apply`
- [x] 用户确认进入 `/apply`
- [x] 已确认本地验证命令或替代验证方式

## Task 1: Evidence Gap 模型与检测器

- **目标**：把评分后的证据不足显式建模，识别哪些 Top 推荐项目需要追加 README 补查。
- **层级/模块**：Agent Runtime / 领域辅助模型
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/react/EvidenceGap.java`（新增）
  - `openscout-agent-server/src/main/java/com/openscout/agent/react/EvidenceGapDetector.java`（新增）
  - `openscout-agent-server/src/test/java/com/openscout/agent/react/EvidenceGapDetectorTest.java`（新增）
- **依赖**：无
- **风险标记**：规则边界 / evidence 可信度
- **实现要点**：
  - gap 字段至少包含 `fullName`、`gapType`、`reason`、`action`、`priority`。
  - 第一版 gap 类型聚焦 `MISSING_README` / `WEAK_DOC_EVIDENCE` / `CACHE_EVIDENCE_INCOMPLETE`。
  - 仅对 Top N recommendations 检测；N 由 `openscout.react.max-follow-up-repos` 限制。
  - 不调用 LLM 判断 gap，避免模型编造。
- **验收标准**：
  - README 长度为 0 或缺少 `docs:*` evidence 时能生成可行动 gap。
  - 证据充分时不生成 gap。
  - 输出顺序稳定，便于测试和 Trace。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=EvidenceGapDetectorTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/react/EvidenceGap.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/react/EvidenceGapType.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/react/EvidenceGapDetector.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/react/EvidenceGapDetectorTest.java`
  - 验证结果：`mvn test` 通过，包含 `EvidenceGapDetectorTest`。

## Task 2: ReAct 配置与 Planner 插入 evidence_react

- **目标**：提供 ReAct 开关和上限配置，并把固定 Tool 插入 Agent 计划。
- **层级/模块**：配置 / Agent Runtime
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`（修改）
  - `openscout-agent-server/src/main/resources/application.yml`（修改）
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`（修改）
- **依赖**：无
- **风险标记**：配置兼容 / 计划兼容
- **实现要点**：
  - 新增配置：`openscout.react.enabled=true`、`max-rounds=1`、`max-follow-up-repos=3`。
  - mock 计划：`interpret_goal -> check_memory -> search_repos -> score_projects -> evidence_react -> generate_learning_plan -> generate_answer`。
  - real 计划：`interpret_goal -> check_memory -> search_repos -> fetch_readme -> score_projects -> evidence_react -> generate_learning_plan -> generate_answer`。
  - `evidence_react` 设置 `continueOnFailure=true`，增强失败不阻塞 ask。
- **验收标准**：
  - Planner 测试确认 `evidence_react` 位置正确。
  - 配置默认值可通过 `OpenScoutProperties` 读取。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=RuleBasedAgentPlannerTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`
    - `openscout-agent-server/src/main/resources/application.yml`
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`
  - 验证结果：`mvn test` 通过，mock/real Planner 均包含 `evidence_react`。

## Task 3: README 补查能力复用

- **目标**：让 `FetchReadmeTool` 和 `EvidenceReActTool` 复用同一套 README enrichment 逻辑，避免异常处理和 evidence 推断分叉。
- **层级/模块**：Agent Tool / 外部接口 adapter
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/react/ReadmeEvidenceEnricher.java`（新增，名称可在实现时调整）
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/FetchReadmeTool.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/react/ReadmeEvidenceEnricherTest.java`（新增）
- **依赖**：Task 1
- **风险标记**：外部 API / 限流 / 部分失败
- **实现要点**：
  - 封装 `CollectorClient.getReadme(owner, repo, "github")`。
  - 成功时返回更新后的 `RepoSummary` 和 observation。
  - 404 / 普通异常返回 skipped observation；`RateLimitException` 返回 rate_limited observation。
  - 不记录完整 README，只保留 length、hasExamples、status、errorSummary。
- **验收标准**：
  - `FetchReadmeTool` 既有行为不退化。
  - 限流、404、普通异常都有可测试的 observation。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=ReadmeEvidenceEnricherTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/react/ReadmeEnrichmentResult.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/react/ReadmeEvidenceEnricher.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/FetchReadmeTool.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/react/ReadmeEvidenceEnricherTest.java`
  - 验证结果：`mvn test` 通过，README 成功、404、限流和 evidence 推断均覆盖。

## Task 4: EvidenceReActTool 有限补查与重评分

- **目标**：实现 `evidence_react` Tool，检测 gap、执行有限补查、更新上下文、重新评分。
- **层级/模块**：Agent Tool / Runtime
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/EvidenceReActTool.java`（新增）
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/EvidenceReActToolTest.java`（新增）
  - 可能抽取 `RecommendationScoringService` 或等价内部服务（新增/修改）
- **依赖**：Task 1、Task 2、Task 3
- **风险标记**：循环控制 / 评分一致性 / 持久化一致性
- **实现要点**：
  - disabled、mock mode、无推荐、无可行动 gap 时快速返回 success，并记录 stop reason。
  - 每轮按 gap 优先级最多处理 `max-follow-up-repos` 个 repo。
  - 成功补查后更新 `AgentContext.repos` 中对应 repo，并重新生成 recommendations。
  - 若持久化开启，复用评分持久化逻辑写回 `repo_info` / `repo_analysis`，避免 memory 下次仍命中旧 evidence。
  - 遇到限流停止后续补查，但不抛出阻塞异常。
- **验收标准**：
  - 补查成功后 recommendations 的 docs/learning evidence 可变化。
  - 部分失败不影响成功项和最终 ask。
  - 最大轮数和最大 repo 数生效。
  - Trace 可看到 gap、action、observation、rescore、stop reason。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=EvidenceReActToolTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/EvidenceReActTool.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/recommendation/RecommendationScoringService.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ScoreProjectsTool.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/tool/EvidenceReActToolTest.java`
  - 验证结果：`mvn test` 通过，覆盖 disabled、mock、无推荐、无 gap、成功补查、部分失败、限流、max-follow-up-repos。

## Task 5: PlanExecutor 与主链路回归

- **目标**：验证新增 `evidence_react` 后主流程仍兼容，学习计划和回答使用补查后的 recommendations。
- **层级/模块**：Agent Runtime / 测试
- **涉及文件**：
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/AgentServiceTest.java`（按需修改）
- **依赖**：Task 4
- **风险标记**：回归验证
- **实现要点**：
  - mock 模式新增 step 但不调用外部 API。
  - real 模式中 README 部分失败仍继续生成推荐、学习计划和回答。
  - search fatal 失败仍保持原异常语义。
- **验收标准**：
  - 现有测试通过。
  - Trace 包含 `evidence_react` step 和 ReAct 事件。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=PlanExecutorTest,AgentServiceTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/AgentServiceTest.java`（未修改，响应兼容测试保持通过）
  - 验证结果：`mvn test` 通过，PlanExecutor mock/real 回归和 AgentService 响应兼容通过。

## Task 6: 文档与阶段同步

- **目标**：同步 README、项目实施进度和 change 文档，明确 Evidence ReAct 边界和验证命令。
- **层级/模块**：文档 / SpecAI
- **涉及文件**：
  - `README.md`（修改）
  - `项目实施进度.md`（修改）
  - `code_copilot/changes/openscout-evidence-react/spec.md`（更新）
  - `code_copilot/changes/openscout-evidence-react/tasks.md`（更新）
  - `code_copilot/changes/openscout-evidence-react/test-spec.md`（更新）
  - `code_copilot/changes/openscout-evidence-react/log.md`（更新）
- **依赖**：Task 1-5
- **风险标记**：文档一致性
- **实现要点**：
  - README 说明新增 ReAct step、配置、Trace 事件和不做项。
  - `项目实施进度.md` 记录验证结果、未完成项和下一步。
  - `log.md` 记录命令结果和实现偏差。
- **验收标准**：
  - 文档没有把 proposal 阶段写成已实现。
  - 验证结果只记录实际执行过的命令。
- **验证命令**：
  ```bash
  rg -n "openscout-evidence-react|Evidence ReAct|evidence_react|feature/10-evidence-react" README.md 项目实施进度.md code_copilot/changes/openscout-evidence-react
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `README.md`
    - `项目实施进度.md`
    - `code_copilot/knowledge/index.md`
    - `code_copilot/changes/openscout-evidence-react/spec.md`
    - `code_copilot/changes/openscout-evidence-react/tasks.md`
    - `code_copilot/changes/openscout-evidence-react/test-spec.md`
    - `code_copilot/changes/openscout-evidence-react/log.md`
  - 验证结果：`rg` 文档校验通过；Java、Go、Docker 验证均通过。

## 变更摘要

> `/apply` 完成后填写。

- **总文件数**：24
- **新增文件**：14
- **修改文件**：10
- **删除文件**：0
- **Spec-Plan 偏差记录**：实现中将评分+memory 回写抽取为 `RecommendationScoringService`，用于 `ScoreProjectsTool` 和 `EvidenceReActTool` 复用，符合 spec 中“复用或提取现有持久化逻辑”的决策。
- **未完成项**：Reflection Verifier、SSE/Streaming、Agent Evaluation、向量检索、FULLTEXT、release/tree/issues 补查、生产鉴权均按 spec 留到后续阶段。
- **Review fix 记录**：已关闭 2 个 deferred；同一请求内 README 失败会写入 `AgentContext` 并让 ReAct 跳过重复补查，空 README 返回 `empty_readme` 而不是 fetched。
- **Review 复查记录**：已关闭 1 个非阻塞 deferred；`FetchReadmeTool` 遇到 `rate_limited` 会停止后续 Top 5 README，并把 `retryAfterSeconds` 传给 ReAct observation。
- **遗留风险**：ReAct 默认只补 README，且最大一轮、最多 3 个 repo；若 README 仍缺失或 GitHub 限流，Trace 会记录 stop reason，但不会继续扩展调用。
