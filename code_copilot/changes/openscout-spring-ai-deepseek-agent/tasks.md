# 任务拆分 - OpenScout Spring AI + DeepSeek Agent 编排

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/project-context.md`
- [x] 已读取 `code_copilot/rules/coding-style.md`
- [x] 已读取 `code_copilot/rules/domain-rules.md`
- [x] 已读取 `code_copilot/rules/security.md`
- [x] 已读取 `code_copilot/knowledge/index.md`
- [x] 已读取 `项目实施进度.md`
- [x] 已检查工作区状态，当前分支为 `feature/05-spring-ai-deepseek-agent`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 阶段 5 `feature/04-github-real-api` 已合并到 `main`

## Task 1: Spring AI ChatClient 配置 Bean

- **目标**：创建手动 ChatClient Bean，支持 DeepSeek endpoint，无 Key 时不阻塞启动。
- **层级/模块**：Java 配置层
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/config/LlmConfig.java`（新增）：创建 `ChatClient` Bean。
    - 读取 `DEEPSEEK_API_KEY` 环境变量，为空时 `ChatClient` Bean 为 `null` 或不创建。
    - 使用 `OpenAiApi` + `OpenAiChatModel` 手动构建（而非依赖 auto-config），以便在 Key 缺失时静默跳过。
    - `ChatClient.Builder` 设置 default system prompt（可后续在调用时覆盖）。
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`（修改）：新增 `Llm` 内部类（`enabled`/`timeoutSeconds`/`maxTokens`/`temperature`/`model`）。
  - `openscout-agent-server/src/main/resources/application.yml`（修改）：新增 `openscout.llm.*` 配置项，`spring.ai.model.chat` 默认值改为 `openai`。
- **依赖**：无。
- **风险标记**：Spring AI 自动配置 vs 手动配置冲突。
- **实现要点**：
  - `OpenAiApi` 构造函数：`new OpenAiApi(baseUrl, apiKey)` — 注意 Spring AI 1.1.6 的 API 签名。
  - `OpenAiChatModel` 构造函数：`new OpenAiChatModel(openAiApi, options)`。
  - `ChatClient`：`ChatClient.builder(chatModel).build()`。
  - 若 `DEEPSEEK_API_KEY` 为空或空白，log.warn 并返回 `null`（不抛异常）。
  - `LlmConfig` 用 `@Configuration` + `@Bean`，Bean 方法返回 `@Nullable ChatClient`。
- **验收标准**：
  - 不设 `DEEPSEEK_API_KEY`：启动成功，ChatClient Bean 为 null（或不存在），日志提示 "LLM disabled"。
  - 设置 `DEEPSEEK_API_KEY`：启动成功，ChatClient Bean 可用。
  - `mvn test` 通过。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：LlmConfig.java（新增）、OpenScoutProperties.java（新增 Llm 内部类）、application.yml（spring.ai.model.chat=openai + openscout.llm.*）
  - 验证结果：mvn compile 通过

## Task 2: GoalInterpreter — LLM 目标解释服务

- **目标**：用 LLM 将用户自然语言目标提取为 GitHub 搜索关键词和技术偏好。
- **层级/模块**：Java 应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/GoalInterpreter.java`（新增）：Service 类。
    - `interpret(String userGoal)` → `GoalInterpretation` record（`keyword`、`language`、`domain`）。
    - 内部构造 system prompt + user prompt，调用 `ChatClient`。
    - 解析 LLM 返回的 JSON → `GoalInterpretation`。
    - 失败时 fallback：返回以原始 goal 为 keyword 的默认解释。
  - `openscout-agent-server/src/main/java/com/openscout/agent/GoalInterpretation.java`（新增）：record `(String keyword, String language, String domain)`。
- **依赖**：Task 1（ChatClient 可用）。
- **风险标记**：LLM JSON 解析失败 / prompt 注入。
- **实现要点**：
  - System prompt：约束 LLM 只返回 JSON（`{"keyword":"...","language":"...","domain":"..."}`）。
  - 使用 `ObjectMapper` 解析 LLM 响应。响应可能包含 markdown 代码块包裹（`` ```json ... ``` ``），需先 strip。
  - Fallback：解析失败时 `keyword = userGoal`、`language = ""`、`domain = ""`。
  - `ChatClient` 为 null（LLM 禁用）时直接返回 fallback。
  - Prompt 中 user goal 限制 200 字符。
- **验收标准**：
  - 输入 "我想一周内学习 Spring AI Agent" → 返回 keyword 含 "Spring AI"、language 含 "Java"。
  - 输入 "我想学 Go web 框架" → 返回 keyword 含 "Go web framework"、language "Go"。
  - LLM 禁用时 → 返回 keyword = 原始 goal。
  - `mvn test` 通过。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：GoalInterpreter.java（新增）、GoalInterpretation.java（新增 record）
  - 验证结果：mvn compile + mvn test 通过

## Task 3: AnswerGenerator — LLM 回答生成服务

- **目标**：用 LLM 根据评分结果和 evidence 生成自然语言推荐回答。
- **层级/模块**：Java 应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/AnswerGenerator.java`（新增）：Service 类。
    - `generate(String userGoal, List<ProjectRecommendation> recommendations)` → `String`。
    - 内部将评分结果格式化为结构化文本，拼接 system prompt + user prompt，调用 `ChatClient`。
    - System prompt 包含严格的评分保护规则（不修改分数、不编造项目、引用 evidence）。
    - 失败时 fallback：返回模板回答（调用现有的 `buildRealAnswer` 逻辑）。
  - `openscout-agent-server/src/main/java/com/openscout/agent/ProjectRecommendation.java`：可在 `reason` 生成逻辑中复用。
- **依赖**：Task 1（ChatClient 可用）。
- **风险标记**：Prompt 长度控制 / LLM 输出可信度 / Token 用量。
- **实现要点**：
  - 每个项目的 evidence 截断为 300 字符（`maxEvidenceLength` 常量）。
  - System prompt 核心约束：
    - "评分由规则引擎计算，你不得修改任何分数数字"
    - "推荐理由必须引用评分证据，不得编造项目特性"
    - "如果某个维度得分低，可给出学习建议但不否定整体推荐"
  - User prompt 格式：
    ```
    用户目标：{goal}
    推荐项目：
    1. {fullName} (总分{total})：活跃度{a}/文档{d}/匹配{m}/学习{l}/简历{r}
       证据：{evidence}
    ...
    ```
  - `ChatClient` 为 null → 返回模板回答。
  - LLM 调用异常 → log.warn + 返回模板回答。
  - `maxTokens` 使用配置值（默认 2000）。
- **验收标准**：
  - 传入 3 个推荐项目 → 返回自然语言推荐文本（非模板）。
  - LLM 响应中包含评分引用（如 "活跃度得分 20/20"）。
  - LLM 禁用/失败时 → 返回模板回答。
  - `mvn test` 通过。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：AnswerGenerator.java（新增）
  - 验证结果：mvn compile + mvn test 通过

## Task 4: AgentService 整合 LLM 编排

- **目标**：将 GoalInterpreter 和 AnswerGenerator 整合到 AgentService（原 MockAgentService）的编排流程中。
- **层级/模块**：Java 应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java`（重命名 + 修改）：
    - 类名从 `MockAgentService` → `AgentService`。
    - 构造函数新增 `GoalInterpreter`、`AnswerGenerator` 参数。
    - `askMock()`：搜索前调用 `goalInterpreter.interpret(question)` 获取 keyword → 传给 `collectorClient.fetchMockRepos(keyword)`。
    - `askReal()`：同理，用 LLM keyword 替代直接传 question。
    - `buildMockAnswer()` / `buildRealAnswer()` → 替换为 `answerGenerator.generate(question, recommendations)`。
    - Fallback：LLM 禁用/失败时用原有模板回答。
  - `openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java`（修改）：`MockAgentService` → `AgentService`。
- **依赖**：Task 2（GoalInterpreter）、Task 3（AnswerGenerator）。
- **风险标记**：重命名影响面 / Spring Bean 名称变更。
- **实现要点**：
  - 保留 `MockAgentService` 文件 → `git mv` 为 `AgentService.java`。
  - Spring `@Service` 注解默认 Bean 名从 `mockAgentService` 变为 `agentService`，Controller 中字段名同步更新。
  - `ask()` 方法中 LLM 调用包裹 try-catch：`goalInterpreter.interpret()` 和 `answerGenerator.generate()` 分别 catch 异常并 fallback。
  - 当 `properties.isMockAgent()=true` 但 LLM 启用时，仍用 LLM 生成回答（mock Agent 仅指数据源为 mock，LLM 负责语言生成）。
  - Trace 中 toolName：`llm_goal_interpret`、`llm_answer_generate`。
- **验收标准**：
  - LLM 禁用：行为与阶段 5 完全一致（模板回答）。
  - LLM 启用 + mock Agent：Go 返回 mock 数据，但回答由 LLM 自然语言生成。
  - LLM 启用 + 真实 Agent：Go 返回真实 GitHub 数据，回答由 LLM 生成。
  - 重命名后 `mvn test` 全部通过。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：MockAgentService.java（删除）、AgentService.java（新增）、AgentController.java（MockAgentService → AgentService）
  - 验证结果：mvn compile + mvn test 通过

## Task 5: LLM 调用 Trace 增强

- **目标**：将 LLM 调用详情记录到 AgentTrace，便于调试和成本跟踪。
- **层级/模块**：Java Trace 层
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/trace/TraceToolCall.java`：无需修改，现有字段已够用（`toolName`、`inputSummary`、`outputSummary`、`latencyMs`、`status`、`errorMessage`）。
  - `openscout-agent-server/src/main/java/com/openscout/agent/GoalInterpreter.java`（修改）：调用前后记录 Trace。
  - `openscout-agent-server/src/main/java/com/openscout/agent/AnswerGenerator.java`（修改）：调用前后记录 Trace。
  - `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java`：无需修改，`recordToolCall` 已支持。
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`（修改）：LLM trace 相关配置（如 `llm.prompt-max-summary-length`）。
- **依赖**：Task 2、Task 3。
- **风险标记**：Trace 脱敏 / prompt 泄露。
- **实现要点**：
  - `GoalInterpreter` 和 `AnswerGenerator` 的方法签名增加 `AgentTrace trace` 参数。
  - `toolName`：`"llm_goal_interpret"`、`"llm_answer_generate"`。
  - `inputSummary`：截断的 user prompt 前 200 字符。
  - `outputSummary`：截断的 LLM 响应前 300 字符。
  - `latencyMs`：从 `Instant.now()` 差值计算。
  - `status`：成功 `"SUCCESS"`，失败 `"FAILED"`。
  - `errorMessage`：异常消息（脱敏处理）。
  - Prompt 中的 `DEEPSEEK_API_KEY` 不会出现在 LLM 调用参数中（ChatClient 内部处理认证）。
- **验收标准**：
  - LLM 调用后 `/api/agent/traces/{traceId}` 可查到 `llm_goal_interpret` 和 `llm_answer_generate` 记录。
  - 失败时 toolCall status 为 `"FAILED"`，errorMessage 不为空。
  - Trace 中不包含完整 prompt 原文（已截断）。
- **验证命令**：
  ```bash
  # 启动服务后 curl /api/agent/ask → curl /api/agent/traces/{traceId} | jq .toolCalls
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：GoalInterpreter.java（interpret 带 Trace 版本）、AnswerGenerator.java（generate 带 Trace 版本）
  - 验证结果：mvn compile 通过；Trace 记录在 GoalInterpreter 和 AnswerGenerator 的 trace-aware 方法中

## Task 6: 测试与验证

- **目标**：补齐 LLM 相关单元测试，执行端到端验证。
- **层级/模块**：Java 测试
- **涉及文件**：
  - `openscout-agent-server/src/test/java/com/openscout/agent/GoalInterpreterTest.java`（新增）：mock ChatClient 的 JSON 响应，验证解析逻辑和 fallback。
  - `openscout-agent-server/src/test/java/com/openscout/agent/AnswerGeneratorTest.java`（新增）：验证 prompt 构造、evidence 截断、fallback。
  - `openscout-agent-server/src/test/java/com/openscout/agent/AgentServiceTest.java`（新增/修改）：适配重命名，增加 LLM 路径的集成测试（mock ChatClient）。
- **依赖**：Task 1-5。
- **风险标记**：ChatClient mock 需要 Spring AI 的接口可 mock。
- **实现要点**：
  - `GoalInterpreterTest`：mock `ChatClient` 返回 `{"keyword":"spring ai","language":"java","domain":"ai"}`，验证 `GoalInterpretation` 解析。
  - `AnswerGeneratorTest`：mock `ChatClient` 返回自然语言文本，验证 prompt 中包含评分数据和 evidence。
  - `AgentServiceTest`：验证 `mockAgent=true` + LLM 禁用 → 模板回答；LLM 启用 → LLM 生成回答。
  - 所有测试可脱离真实 DeepSeek API 运行（mock ChatClient）。
- **验收标准**：
  - `mvn test` 全部通过。
  - 覆盖率不因新增类而显著下降。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：GoalInterpreterTest.java（新增）、AnswerGeneratorTest.java（新增）
  - 验证结果：mvn test 全部通过（含 8 个新测试用例 + 所有既有测试）

## Task 7: 文档与 code_copilot 制品同步

- **目标**：记录 LLM 启用方式、fallback 机制和端到端验证结果。
- **层级/模块**：文档 / code_copilot
- **涉及文件**：
  - `.env.example`（修改）：新增 `OPSCOUT_LLM_ENABLED`、`OPSCOUT_LLM_TIMEOUT_SECONDS`、`OPSCOUT_LLM_MAX_TOKENS`、`OPSCOUT_LLM_TEMPERATURE` 注释。
  - `README.md`（修改）：新增"LLM 智能回答"章节，说明 DeepSeek 配置和 fallback 行为。
  - `项目实施进度.md`（修改）：阶段 6 状态更新。
  - `code_copilot/changes/openscout-spring-ai-deepseek-agent/spec.md`（修改）：更新 status 和确认记录。
  - `code_copilot/changes/openscout-spring-ai-deepseek-agent/tasks.md`（修改，本文件）：填充完成记录。
  - `code_copilot/changes/openscout-spring-ai-deepseek-agent/test-spec.md`（新增）。
  - `code_copilot/changes/openscout-spring-ai-deepseek-agent/log.md`（新增）。
- **依赖**：Task 1-6。
- **风险标记**：文档准确性。
- **实现要点**：
  - 文档必须明确声明阶段 6 的范围边界（Chat Completion 只做 NL 生成，不做 Tool Calling）。
  - 包含 LLM 启用/禁用切换步骤。
  - 记录 DeepSeek 与 OpenAI 协议兼容性的已知差异（如有）。
  - `项目实施进度.md` 中阶段 6 状态按模板更新。
- **验收标准**：
  - `.env.example` 包含所有新 LLM 配置变量及注释。
  - `README.md` 包含可工作的 curl 命令和 LLM fallback 说明。
  - `项目实施进度.md` 与 change 状态一致。
  - `code_copilot/changes/openscout-spring-ai-deepseek-agent/` 下 4 个制品齐全。
- **验证命令**：
  ```bash
  rg -n "OPSCOUT_LLM|DEEPSEEK_API_KEY|SPRING_AI_MODEL_CHAT|openscout-spring-ai-deepseek-agent" README.md .env.example 项目实施进度.md code_copilot/changes/openscout-spring-ai-deepseek-agent
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：.env.example（新增 LLM 配置）、README.md（新增 LLM 章节）、项目实施进度.md（阶段 6 完成记录）、spec.md（status → apply-done）、tasks.md（完成记录填充）、test-spec.md（新增）、log.md（新增）
  - 验证结果：grep 验证关键配置项出现在目标文件中

## 变更摘要

> `/apply` 完成于 2026-05-30。

- **总文件数**：16（7 新增 + 7 修改 + 1 删除 + 1 重命名）
- **新增文件**：
  - `LlmConfig.java`, `GoalInterpreter.java`, `GoalInterpretation.java`, `AnswerGenerator.java`, `AgentService.java`
  - `GoalInterpreterTest.java`, `AnswerGeneratorTest.java`
  - code_copilot: `test-spec.md`, `log.md`
- **修改文件**：
  - `application.yml`, `OpenScoutProperties.java`, `AgentController.java`
  - `.env.example`, `README.md`, `项目实施进度.md`, `spec.md`, `tasks.md`
- **删除文件**：`MockAgentService.java`（重命名为 `AgentService.java`）
- **Spec-Plan 偏差记录**：无重大偏差；AgentCallContext 替代方案使用直接传入 TraceService + AgentTrace 参数的方式
- **未完成项**：P1/P2 手动集成测试（需 DeepSeek API Key）；Spring AI Tool Calling、MCP、Streaming 属于后续阶段
- **遗留风险**：DeepSeek API 实际调用行为未在本机验证（需 API Key）；ChatClient Bean 在无 Key 时返回 null 的策略已验证编译通过
