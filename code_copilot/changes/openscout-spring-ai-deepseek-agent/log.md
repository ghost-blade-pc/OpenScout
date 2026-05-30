# 执行日志 - OpenScout Spring AI + DeepSeek Agent 编排

## 2026-05-30 — `/apply` 执行

### Task 1: ChatClient 配置 Bean ✅

- 修改 `application.yml`：`spring.ai.model.chat` → `openai`，新增 `openscout.llm.*` 配置块
- 修改 `OpenScoutProperties.java`：新增 `Llm` 内部类（`enabled`/`timeoutSeconds`/`maxTokens`/`temperature`）
- 新增 `LlmConfig.java`：手动创建 `ChatClient` Bean，无 Key 返回 null
- 验证：`mvn compile` 通过

### Task 2: GoalInterpreter ✅

- 新增 `GoalInterpretation.java`：record `(keyword, language, domain)`
- 新增 `GoalInterpreter.java`：
  - `interpret(userGoal)` → 无 Trace 版本
  - `interpret(userGoal, trace, traceService)` → 带 Trace 版本
  - System prompt 约束 LLM 只返回 JSON
  - `extractJson()` 处理 markdown 代码块包裹
  - JSON 解析失败 → fallback（keyword = userGoal）
- 验证：`mvn compile` + `mvn test` 通过

### Task 3: AnswerGenerator ✅

- 新增 `AnswerGenerator.java`：
  - `generate(userGoal, recommendations)` → 无 Trace 版本
  - `generate(userGoal, recommendations, trace, traceService)` → 带 Trace 版本
  - System prompt 严格保护评分（不修改分数、引用 evidence、不编造项目）
  - `buildUserPrompt()` 格式化评分数据为 LLM 输入
  - Evidence 截断 300 字符，description 截断 150 字符
- 验证：`mvn compile` + `mvn test` 通过

### Task 4: AgentService 重命名与 LLM 整合 ✅

- 删除 `MockAgentService.java`
- 新增 `AgentService.java`：
  - 构造函数新增 `GoalInterpreter`、`AnswerGenerator`
  - `askMock()`：LLM 解释目标 → 搜索 → 评分 → LLM 生成回答
  - `askReal()`：同理，使用真实 GitHub 数据
  - `enrichWithReadme` 去除已不用的 `trace` 参数
  - 去除了 `buildMockAnswer`、`buildRealAnswer` 模板方法
  - `scoreAndRank`、`buildScoreSummary`、`persistReposIfEnabled` 保持不变
- 修改 `AgentController.java`：`MockAgentService` → `AgentService`
- 验证：`mvn compile` + `mvn test` 通过，无破坏性变更

### Task 5: Trace 增强 ✅

- `GoalInterpreter.interpret()` 带 Trace 版本记录 `llm_goal_interpret` 工具调用
- `AnswerGenerator.generate()` 带 Trace 版本记录 `llm_answer_generate` 工具调用
- 输入摘要截断 200 字符，输出摘要截断 300 字符
- 异常情况下记录 errorMessage 和 FAILED 状态
- 利用现有 `TraceService.recordToolCall()` — 无需修改 TraceService

### Task 6: 测试 ✅

- 新增 `GoalInterpreterTest.java`：4 个测试用例
  - LLM 禁用 fallback
  - JSON markdown 代码块解析
  - 裸 JSON 解析
  - null 输入处理
- 新增 `AnswerGeneratorTest.java`：4 个测试用例
  - LLM 禁用模板回答
  - 空推荐列表
  - null 推荐列表
  - prompt 构建含评分详情
- 验证：`mvn test` 全部通过（含所有既有测试）

### Task 7: 文档同步 ✅

- 更新 `.env.example`：新增 LLM 配置变量和注释
- 更新 `README.md`：新增"LLM 智能回答"章节，含 Fallback 说明和参数表
- 更新 `项目实施进度.md`：阶段 6 完成记录
- 更新 `spec.md`：status → `apply-done`
- 填充 `tasks.md`：全部 Task 完成记录
- 新增 `test-spec.md`、`log.md`

### 变更摘要

- 总文件数：16（7 新增 + 7 修改 + 1 删除 + 1 重命名）
- 新增文件：
  - `LlmConfig.java`、`GoalInterpreter.java`、`GoalInterpretation.java`、`AnswerGenerator.java`、`AgentService.java`
  - `GoalInterpreterTest.java`、`AnswerGeneratorTest.java`
  - code_copilot: `test-spec.md`、`log.md`
- 修改文件：
  - `application.yml`、`OpenScoutProperties.java`、`AgentController.java`
  - `.env.example`、`README.md`、`项目实施进度.md`、`spec.md`、`tasks.md`
- 删除文件：`MockAgentService.java`（重命名为 `AgentService.java`）
- Spec-Plan 偏差记录：无重大偏差；所有实现严格遵循 spec.md 和 tasks.md
- 未完成项：P1/P2 手动集成测试（需 DeepSeek API Key）；Spring AI Tool Calling、MCP、Streaming 属于后续阶段
- 遗留风险：DeepSeek API 实际调用行为未在本机验证（需 API Key）；ChatClient Bean 在无 Key 时返回 null 的策略已验证（编译通过）
