# 任务拆分 - 阶段 12 Reflection Verifier

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已检查 `code_copilot/knowledge/index.md`
- [x] 已检查工作区状态，确认不会覆盖他人修改
- [x] 已创建/切换阶段分支 `feature/11-reflection-verifier`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中待澄清项不阻塞 `/apply`
- [x] 用户确认进入 `/apply`
- [x] 已确认本地验证命令或替代验证方式

## Task 1: Verifier 配置与 OpenScoutProperties

- **目标**：新增 `openscout.verifier.*` 配置分组，提供 Verifier 开关。
- **层级/模块**：配置
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`（修改）
  - `openscout-agent-server/src/main/resources/application.yml`（修改）
- **依赖**：无
- **风险标记**：配置兼容
- **实现要点**：
  - 新增 `Verifier` 内部类，含 `enabled`（默认 `true`）、`llmEnabled`（默认 `false`）。
  - 在 `application.yml` 中添加 `openscout.verifier.enabled: true`、`openscout.verifier.llm-enabled: false`。
  - 遵循 `Memory` / `React` 的配置模式。
- **验收标准**：
  - `OpenScoutProperties.getVerifier()` 返回配置对象。
  - 环境变量 `OPSCOUT_VERIFIER_ENABLED=false` 可覆盖。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```

## Task 2: 验证结果模型

- **目标**：定义 Verifier 的检查结果数据结构，承载各项检查的通过/警告/失败和问题描述。
- **层级/模块**：Agent Runtime / 领域辅助模型
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/verifier/VerificationResult.java`（新增）
  - `openscout-agent-server/src/main/java/com/openscout/agent/verifier/VerificationIssue.java`（新增）
- **依赖**：Task 1
- **风险标记**：模型设计
- **实现要点**：
  - `VerificationResult` 包含 `scoreIntegrityOk`、`evidenceClaimsOk`、`learningPlanOk` 三项布尔值，以及 `List<VerificationIssue> issues`。
  - `VerificationIssue` 包含 `checkType`（如 `SCORE_TAMPERING`、`EVIDENCE_MISSING`、`PLAN_HALLUCINATION`）、`severity`（`WARNING`/`ERROR`）、`description`、`detail`。
  - 提供 `allOk()` 便捷方法。
- **验收标准**：
  - 模型可序列化为 Trace 输出摘要。
  - issues 列表支持多项检查结果聚合。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```

## Task 3: 分数完整性检查器

- **目标**：从最终回答文本中提取分数数字，与 `ProjectScore.totalScore` 对比，检测 LLM 是否篡改。
- **层级/模块**：Agent Tool / Verifier
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/verifier/ScoreIntegrityChecker.java`（新增）
  - `openscout-agent-server/src/test/java/com/openscout/agent/verifier/ScoreIntegrityCheckerTest.java`（新增）
- **依赖**：Task 2
- **风险标记**：规则准确性 / 误报
- **实现要点**：
  - 从每个推荐仓库的 `fullName` 在回答中定位上下文，正则提取附近分数数字。
  - 支持格式：`75分`、`75/100`、`评分 75`、`**75**`、`总分 75`。
  - 对比回答中分数与 `ProjectScore.totalScore()`：不匹配时生成 `SCORE_TAMPERING` issue。
  - 无法提取分数时生成 `SCORE_FORMAT_UNRECOGNIZED` warning（非 error）。
  - 不依赖 LLM。
- **验收标准**：
  - 分数匹配时不产生 issue。
  - 分数不一致时产生 `SCORE_TAMPERING` issue。
  - 回答不含分数时不产生 issue。
  - 不依赖 `ChatClient`。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=ScoreIntegrityCheckerTest
  ```

## Task 4: 证据声明检查器

- **目标**：检查 LLM 回答中对项目能力的声称是否有 `ProjectScore.evidence` 支持。
- **层级/模块**：Agent Tool / Verifier
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/verifier/EvidenceClaimsChecker.java`（新增）
  - `openscout-agent-server/src/test/java/com/openscout/agent/verifier/EvidenceClaimsCheckerTest.java`（新增）
- **依赖**：Task 2
- **风险标记**：规则准确性 / 漏报
- **实现要点**：
  - 从 evidence 列表提取关键能力词（如 `docs:README` → README 完整度；`learning:has examples` → 有示例代码；`match:language` → 语言匹配）。
  - 在回答中搜索对这些能力的声称，检查是否与 evidence 匹配。
  - 第一版使用启发式规则：如果回答声称"完善的文档"但 evidence 中无 `docs:` 条目，产生 `EVIDENCE_UNVERIFIED` warning。
  - 如果回答声称"有示例代码"但 evidence 中无 `learning:*` 或 `examples` 条目，产生 `EVIDENCE_UNVERIFIED` warning。
  - 如果回答声称"生产级/企业级"但 evidence 不支持此结论，产生 `EVIDENCE_UNVERIFIED` warning。
  - 不依赖 LLM。
- **验收标准**：
  - evidence 充分时声称不产生 issue。
  - evidence 缺失对应条目时产生 warning。
  - 回答未做任何声称时不产生 issue。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=EvidenceClaimsCheckerTest
  ```

## Task 5: 学习计划可行性检查器

- **目标**：检查学习计划任务引用的仓库名是否在推荐列表中，防止 LLM 编造不存在的项目和不可执行任务。
- **层级/模块**：Agent Tool / Verifier
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/verifier/LearningPlanChecker.java`（新增）
  - `openscout-agent-server/src/test/java/com/openscout/agent/verifier/LearningPlanCheckerTest.java`（新增）
- **依赖**：Task 2
- **风险标记**：规则准确性
- **实现要点**：
  - 检查每项学习任务的 `projectName` 是否在 `recommendations` 的 `fullName` 列表中。
  - 检查任务 `description` 中是否提到不在推荐列表中的仓库名（正则提取 `owner/repo` 格式）。
  - 检查 `targetStack` 的技术栈是否与推荐仓库的 `language`/`topics` 一致。
  - 任务编造内容产生 `PLAN_HALLUCINATION` warning。
  - 不依赖 LLM。
- **验收标准**：
  - 任务正确引用推荐仓库时不产生 issue。
  - 任务引用不存在仓库时产生 `PLAN_HALLUCINATION` issue。
  - 学习计划为空时不产生 issue。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=LearningPlanCheckerTest
  ```

## Task 6: VerifyAnswerTool 实现

- **目标**：实现 `verify_answer` Tool，组装三项检查、记录 Trace、处理异常。
- **层级/模块**：Agent Tool / Runtime
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/VerifyAnswerTool.java`（新增）
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/VerifyAnswerToolTest.java`（新增）
- **依赖**：Task 1、Task 3、Task 4、Task 5
- **风险标记**：异常处理 / Trace 记录
- **实现要点**：
  - `toolName() = "verify_answer"`。
  - 从 `AgentContext` 读取 `answer`、`recommendations`、`learningPlan`。
  - disabled 时快速返回 `ToolResult.success("verifier_disabled")`。
  - 依次调用 `ScoreIntegrityChecker`、`EvidenceClaimsChecker`、`LearningPlanChecker`。
  - 每个 checker 的异常独立捕获，一项失败不影响其他检查。
  - 聚合 `VerificationResult` 为 Trace 输出摘要。
  - 通过 `traceService.recordToolCall()` 记录各项检查结果。
  - 始终返回 `ToolResult.success(...)`（不因发现问题返回 failure）。
- **验收标准**：
  - 各项检查结果聚合正确。
  - 单项 checker 异常不影响其他检查。
  - disabled 时快速返回。
  - Trace 可看到 verifier 各项检查结果。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=VerifyAnswerToolTest
  ```

## Task 7: Planner 插入 verify_answer 步骤

- **目标**：在 Agent 计划中 `generate_answer` 之后插入 `verify_answer`，`continueOnFailure=true`。
- **层级/模块**：Agent Runtime
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`（修改）
- **依赖**：Task 6
- **风险标记**：计划兼容
- **实现要点**：
  - mock 计划：`... -> generate_learning_plan -> generate_answer -> verify_answer`。
  - real 计划：`... -> generate_learning_plan -> generate_answer -> verify_answer`。
  - `verify_answer` 设置 `continueOnFailure=true`。
- **验收标准**：
  - Planner 测试确认 `verify_answer` 在 `generate_answer` 之后。
  - mock/real 计划均包含 `verify_answer`。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=RuleBasedAgentPlannerTest
  ```

## Task 8: PlanExecutor 与主链路回归

- **目标**：验证新增 `verify_answer` 后主流程仍兼容，Verifier 失败不阻断 ask。
- **层级/模块**：Agent Runtime / 测试
- **涉及文件**：
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/AgentServiceTest.java`（按需修改）
- **依赖**：Task 7
- **风险标记**：回归验证
- **实现要点**：
  - mock/real 模式新增 step 但不改变 ask 响应。
  - Verifier 异常（如 NPE）不阻断 ask。
  - Trace 包含 `verify_answer` step 和 verifier 事件。
- **验收标准**：
  - 现有测试通过。
  - 新增 Verifier 失败不阻断 ask 的场景测试。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=PlanExecutorTest,AgentServiceTest
  ```

## Task 9: 文档与阶段同步

- **目标**：同步 README、项目实施进度和 change 文档，明确 Reflection Verifier 边界和验证命令。
- **层级/模块**：文档 / SpecAI
- **涉及文件**：
  - `README.md`（修改）
  - `项目实施进度.md`（修改）
  - `code_copilot/knowledge/index.md`（修改）
  - `code_copilot/changes/openscout-reflection-verifier/spec.md`（更新）
  - `code_copilot/changes/openscout-reflection-verifier/tasks.md`（更新）
  - `code_copilot/changes/openscout-reflection-verifier/test-spec.md`（更新）
  - `code_copilot/changes/openscout-reflection-verifier/log.md`（更新）
- **依赖**：Task 1-8
- **风险标记**：文档一致性
- **实现要点**：
  - README 说明新增 Verifier step、配置、Trace 事件和不做项。
  - `项目实施进度.md` 记录验证结果、未完成项和下一步。
  - `log.md` 记录命令结果和实现偏差。
- **验收标准**：
  - 文档没有把 proposal 阶段写成已实现。
  - 验证结果只记录实际执行过的命令。
- **验证命令**：
  ```bash
  rg -n "openscout-reflection-verifier|Reflection Verifier|verify_answer|feature/11-reflection-verifier" README.md 项目实施进度.md code_copilot/knowledge/index.md code_copilot/changes/openscout-reflection-verifier
  ```

## 变更摘要

> `/apply` 完成后填写。

- **总文件数**：17
- **新增文件**：10
- **修改文件**：7
- **删除文件**：0
- **Spec-Plan 偏差记录**：无。实现严格遵循 spec：`verify_answer` 放在 `generate_answer` 之后，第一版 `llm-enabled=false`（纯规则检查），发现问题仅记录 Trace 不修改回答。
- **未完成项**：LLM 语义检查（`llm-enabled=false` 第一版关闭）、自动修正问题、GoalInterpreter 自检、SSE/Streaming、Agent Evaluation 均按 spec 留到后续阶段。
- **遗留风险**：规则检查（正则分数提取、evidence 关键词匹配）存在误报风险，但 Verifier 只记录 Trace 不修改回答，影响可控。
