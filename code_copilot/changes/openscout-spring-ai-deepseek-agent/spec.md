# OpenScout Spring AI + DeepSeek Agent 编排

> status: done
> created: 2026-05-30
> complexity: 复杂

## 1. 背景与目标

根据 `项目实施进度.md` 的阶段 6，当前分支 `feature/05-spring-ai-deepseek-agent` 的目标是接入 Spring AI ChatClient + DeepSeek V4 Pro，将当前硬编码的 `buildMockAnswer()` / `buildRealAnswer()` 模板回答替换为 LLM 生成的自然语言回答。

当前状态：
- `pom.xml` 已有 `spring-ai-bom` 1.1.6 + `spring-ai-starter-model-openai`（optional）
- `application.yml` 已预留 DeepSeek 兼容 OpenAI 协议的配置（`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`）
- `MockAgentService` 的搜索和评分编排已就位，但回答生成是硬编码模板
- 模型 provider 默认关闭（`SPRING_AI_MODEL_CHAT=none`），避免无 Key 时启动阻塞

本阶段完成后应能证明：
- 配置 `DEEPSEEK_API_KEY` 后，Agent 回答由 DeepSeek V4 Pro 自然语言生成，而非模板拼接
- LLM 根据评分 evidence 生成个性化推荐理由，每个推荐项目有自然的解释文本
- LLM 能根据用户目标提取搜索关键词（替代当前直接用 question 当 keyword 的方式）
- 未配置 Key 或模型不可用时，自动 fallback 到模板回答，不影响现有演示链路
- LLM 调用详情（prompt 摘要、response 摘要、token 用量）记录到 AgentTrace
- 评分规则结果不被 LLM 改写——LLM 只负责解释和总结，规则分数是最终分数来源

### 1.1 业务边界

- 所属上下文：OpenScout Agent 阶段 6 Spring AI + DeepSeek 编排。
- 调用方向：用户 curl → Java `/api/agent/ask` → `AgentService` → [LLM 解释目标 → CollectorClient 搜索 → ProjectScoreService 评分 → LLM 生成回答] → respond。
- 是否涉及高风险项：是。
- 高风险类型：模型调用失败、prompt 注入、模型输出不可信、API Key 安全、token 用量控制。

### 1.2 范围裁剪

本次包含：

- Spring AI `ChatClient` 配置 Bean（OpenAI 协议 → DeepSeek endpoint）。
- `GoalInterpreter`：LLM 将用户自然语言目标转为 GitHub 搜索关键词。
- `AnswerGenerator`：LLM 根据评分结果和 evidence 生成推荐回答 + 每个项目的推荐理由。
- `AgentService`：整合 LLM 调用到现有编排流程（替代 `buildMockAnswer`/`buildRealAnswer`）。
- Fallback 策略：无 Key / 模型不可用 / 调用超时 → 回退到模板回答。
- Trace 增强：记录 LLM 调用次数、prompt 摘要、response 摘要、token 用量。
- 配置增强：模型超时、max_tokens 等参数可配置。
- 重命名：`MockAgentService` → `AgentService`（因其已同时承载 mock 和真实两条路径）。
- 文档与 code_copilot 制品同步。

本次不包含：

- Spring AI Tool Calling（`@Tool` 注解，让 LLM 自主决定调用 search/profile/readme）。原因：DeepSeek function calling 兼容性需验证，且当前 Java 编排逻辑已稳定——让 LLM 自主选择工具增加的复杂度 > 收益。本阶段聚焦 LLM 作为"解释器+生成器"。
- MCP 协议或外部 tool 注册。
- Streaming/SSE 回答（延后至后续阶段）。
- 向量检索、RAG、embedding。
- Redis adapter、ETag/304。
- 前端或管理后台。

后续可能拆分：
- `openscout-tool-calling`：Tool Calling 让 LLM 自主编排工具调用。
- `openscout-streaming-agent`：SSE 流式回答。

## 2. Research Findings

### 2.1 相关入口与链路

- **Spring AI BOM**：`pom.xml` 中已声明 `spring-ai-bom` 1.1.6，含 `spring-ai-starter-model-openai`（optional）。
- **DeepSeek 配置**：`application.yml` 中 `spring.ai.openai.api-key` → `DEEPSEEK_API_KEY`，`base-url` → `DEEPSEEK_BASE_URL`，`chat.options.model` → `deepseek-chat`。
- **模型开关**：`spring.ai.model.chat` 默认 `none`，需显式设为 `openai` 才会创建 `ChatClient` Bean。
- **MockAgentService**：`com.openscout.agent.MockAgentService` 中 `askMock()`/`askReal()` 编排逻辑成熟，`buildMockAnswer()`/`buildRealAnswer()` 是待替换的模板方法。
- **ProjectScoreService**：`com.openscout.scoring.ProjectScoreService` 计算五维评分（activity/doc/match/learning/resumeValue），返回 `ProjectScore`（含 `totalScore`、各维度分、`evidence` 列表）。
- **ProjectRecommendation**：`com.openscout.agent.ProjectRecommendation` record 含 `fullName`、`description`、`score`、`reason`。
- **TraceService**：`com.openscout.trace.TraceService` 已支持 `recordToolCall(trace, toolName, inputSummary, outputSummary, latencyMs)`，可直接用于记录 LLM 调用。
- **OpenScoutProperties**：`com.openscout.config.OpenScoutProperties` 已有 `mockAgent`、`collectorMode`、`persistence`、`trace` 配置。

### 2.2 Spring AI ChatClient 关键 API

```java
// 构建 ChatClient
ChatClient chatClient = ChatClient.builder(chatModel)
    .defaultSystem("你是一个开源项目推荐专家...")
    .build();

// 同步调用
String response = chatClient.prompt()
    .user(userMessage)
    .call()
    .content();

// 带选项
String response = chatClient.prompt()
    .user(userMessage)
    .options(OpenAiChatOptions.builder()
        .model("deepseek-chat")
        .temperature(0.7)
        .maxTokens(2000)
        .build())
    .call()
    .content();
```

### 2.3 发现的问题

- **DeepSeek 兼容性**：DeepSeek V4 Pro 兼容 OpenAI 协议，但 `spring-ai-starter-model-openai` 默认发往 `api.openai.com`。通过 `spring.ai.openai.base-url` 指向 `https://api.deepseek.com` 即可。已在阶段 3 验证此配置不阻塞启动（默认 `chat=none`）。
- **模型不可用处理**：当前无 Key 时 `chat=none` 阻止 ChatClient Bean 创建，但阶段 6 需要一个"有 Key 时使用、无 Key 时 fallback"的模式。方案：Java 代码中检测 `DEEPSEEK_API_KEY` 是否配置，未配置时直接用模板回答。
- **Token 用量**：每次 ask 至少 2 次 LLM 调用（目标解释 + 回答生成），每次消耗约 500-2000 token。需记录用量便于成本跟踪。
- **Prompt 注入风险**：用户 `question` 直接进入 prompt。需要在构造 prompt 时做长度限制和角色明确（system prompt 约束 LLM 行为边界）。
- **评分不可被改写**：spec 强调 LLM 只解释不修改评分。需要在 system prompt 中明确禁止 LLM 修改评分数字，只允许解释。
- **MockAgentService 命名**：类名含 "Mock" 但当前已承载真实 GitHub 路径。阶段 6 是合适的重命名时机。需要同步更新 `AgentController`、Spring Bean 引用和测试。

### 2.4 风险初判

- **外部 API 风险**：DeepSeek API 可能不可用、超时、返回非预期格式。必须有 fallback。
- **安全风险**：`DEEPSEEK_API_KEY` 只能通过环境变量传入，不在日志/Trace/响应中泄露。Prompt 中的用户输入需做基本清洗。
- **模型输出风险**：LLM 可能生成不存在的项目名、夸大评分、或偏离评分结果。System prompt 需明确边界。
- **成本风险**：每次 ask 消耗 token，需限制 prompt 长度和 max_tokens。
- **兼容性风险**：DeepSeek API 版本升级可能改变行为。保持 OpenAI 协议兼容路径作为抽象层。
- **重命名风险**：`MockAgentService` → `AgentService` 需同步更新所有引用（Controller、测试、Spring Bean 名称）。

## 3. 功能点

- [ ] 功能 1：Spring AI `ChatClient` 配置 Bean，支持 DeepSeek endpoint（OpenAI 协议），通过 `spring.ai.model.chat=openai` 启用。
- [ ] 功能 2：`GoalInterpreter` 服务——LLM 将用户自然语言目标提取为 GitHub 搜索关键词 + 技术偏好。
- [ ] 功能 3：`AnswerGenerator` 服务——LLM 根据评分结果 + evidence 生成自然语言推荐回答和每个项目的推荐理由。
- [ ] 功能 4：`AgentService`（原 `MockAgentService`）整合 LLM 调用到编排流程，替代模板回答方法。
- [ ] 功能 5：Fallback 策略——无 Key / 模型超时 / 返回异常 → 降级到模板回答。
- [ ] 功能 6：Trace 增强——记录 LLM 调用（toolName=`llm_goal_interpret`/`llm_answer_generate`），含 prompt 摘要、response 摘要、token 用量、耗时。
- [ ] 功能 7：重命名 `MockAgentService` → `AgentService`，同步更新 Controller 和 Spring Bean 引用。
- [ ] 功能 8：文档与 code_copilot 制品同步。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| 配置 | `spring.ai.model.chat` | 从 `none` 改为 `${SPRING_AI_MODEL_CHAT:openai}` | 变更默认值；未配置 Key 时 ChatClient Bean 创建失败导致启动失败 | 代码中检测 ChatClient Bean 是否存在，不存在时 fallback |
| 配置 | `openscout.llm.timeout-seconds` | 新增，默认 30 | 向后兼容 | 恢复默认值 |
| 配置 | `openscout.llm.max-tokens` | 新增，默认 2000 | 向后兼容 | 恢复默认值 |
| 配置 | `openscout.llm.temperature` | 新增，默认 0.7 | 向后兼容 | 恢复默认值 |
| 配置 | `openscout.llm.enabled` | 新增，默认 `true`；设为 `false` 强制模板模式 | 向后兼容 | 设为 `false` |
| 重命名 | `MockAgentService` → `AgentService` | 类名和文件名变更；Controller 引用更新 | 破坏性变更（类名） | 恢复旧类名 |
| Java | `GoalInterpreter` | 新增 Service | 新增 | 删除 |
| Java | `AnswerGenerator` | 新增 Service | 新增 | 删除 |
| 文档 | `.env.example`、`README.md` | 新增 LLM 配置说明 | 文档变更 | Git diff 回滚 |

## 5. 接口与消息契约

### 5.1 入站接口

本阶段不新增或修改 REST 端点。`POST /api/agent/ask` 和 `GET /api/agent/traces/{traceId}` 的请求/响应结构不变。

### 5.2 LLM Prompt 结构

**GoalInterpreter prompt：**

```
System: 你是一个开源项目搜索专家。用户会用自然语言描述学习目标，你需要提取：
1. 最适合在 GitHub 搜索的关键词（1-3 个词，英文）
2. 偏好的编程语言（如果用户提到）
3. 关注的技术领域（如 AI、web、数据库等）

只返回 JSON，不要其他文字：
{"keyword": "...", "language": "...", "domain": "..."}

User: 我想一周内学习 Spring AI Agent
```

**AnswerGenerator prompt：**

```
System: 你是一个开源项目推荐顾问。你会收到一组基于规则评分排序的项目推荐，
每个项目包含名称、描述、语言、stars、评分详情和评分证据。

你的任务是为用户生成个性化的推荐回答。规则：
- 评分由规则引擎计算，你不得修改任何分数
- 你可以解释每个项目的优缺点，但必须引用评分证据
- 推荐理由必须基于实际数据，不得编造
- 如果某个维度得分低，可以给出学习建议

格式：先给出总体推荐摘要（100 字内），
然后对每个推荐项目给一句推荐理由（50 字内）。

User: 用户目标：我想一周内学习 Spring AI Agent
推荐项目：
1. spring-projects/spring-ai (总分85)：活跃度20/文档16/匹配30/学习10/简历9
   证据：updated within 180 days; README length >= 2000; Docker artifact found; language matches goal; matched 3 goal keywords; examples directory found
...
```

### 5.3 出站调用

| 目标服务 | 方式 | Request | Response | 超时 | 失败处理 |
|---|---|---|---|---|---|
| DeepSeek API | Spring AI `ChatClient` | Chat prompt（system + user）| String content | 30s（可配置） | Fallback 到模板回答 |
| Go Collector | HTTP REST | 既有（不变）| 既有 | 8s | 既有异常层级 |

## 6. 风险与关注点

- **ChatClient Bean 条件创建**：`spring.ai.model.chat=openai` 时 Spring AI 自动配置 `OpenAiChatModel`。但若 `DEEPSEEK_API_KEY` 为空，OpenAI auto-config 可能因 key 缺失而失败。解决方案：不依赖 Spring AI 自动配置，手动创建 `ChatClient` Bean 并加 `@ConditionalOnProperty` 或 try-catch。
- **Prompt 长度控制**：评分 evidence 可能较长，需在拼接 prompt 前截断。每个项目的 evidence 限制为 300 字符。
- **LLM 调用不应阻塞评分**：即使 LLM 回答生成失败，评分结果仍应返回（用模板回答填充 answer 字段）。
- **Token 成本**：每次 ask 的 token 消耗应可追踪。Spring AI 1.1.6 的 `ChatResponse` 包含 `Usage` 对象，可用于记录。
- **System prompt 安全约束**：必须在 system prompt 中明确禁止 LLM 修改评分、编造项目、泄露敏感信息。
- **重命名影响面**：`MockAgentService` → `AgentService` 需更新 Controller 中的字段类型和方法调用、Spring `@Service` 默认 Bean 名称、以及可能的测试引用。

## 7. 测试策略

- P0：`mvn test` — 默认 LLM 禁用时所有现有测试通过。
- P0：Go `go test ./...` — Go Collector 侧无变更，测试通过。
- P0：`docker compose -f deploy/docker-compose.yml config` — MySQL/Redis 配置不变。
- P1：LLM 禁用模式 curl 验证：不设 `DEEPSEEK_API_KEY`，调用 `/api/agent/ask` → 返回 200 + 模板回答。
- P1：LLM 启用模式 curl 验证：设置 `DEEPSEEK_API_KEY`，调用 `/api/agent/ask` → 返回 200 + LLM 自然语言回答。
- P1：LLM 超时 fallback：设置极短超时 → 调用后 fallback 到模板回答。
- P2：对比验证：同一 question 在 LLM 模式和模板模式下的回答质量差异（人工评估）。

## 8. 待澄清

- [ ] DeepSeek V4 Pro 的 function calling 兼容性是否已验证？自主决策：不依赖 function calling，本阶段只用 Chat Completion。
- [ ] 是否要同时支持多个 LLM provider？自主决策：当前只支持 DeepSeek（OpenAI 协议），通过 base-url 可切换。
- [ ] `MockAgentService` 重命名是否需要单独的 refactor commit？自主决策：同阶段完成重命名，作为 Task 7。
- [ ] 是否需要 prompt 模板外部化（如 YAML 文件）？自主决策：MVP 阶段 prompt 硬编码在 Java 常量中，后续可外部化。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| change id | `openscout-spring-ai-deepseek-agent` | `openscout-llm-agent` | 与阶段目标直接对应 | N/A |
| LLM 调用方式 | Spring AI `ChatClient`（同步） | 原生 OkHttp + Jackson | 复用 Spring AI 生态，统一配置管理，自动获取 token 用量 | 依赖 Spring AI 1.1.6 与 DeepSeek 的兼容性 |
| Tool Calling | 暂不做 | 用 `@Tool` 注解让 LLM 选择工具 | DeepSeek function calling 兼容性未知，手动编排更可控 | 阶段 6 只做 NL 生成，不做 Tool Calling |
| ChatClient Bean 创建 | 手动 `@Bean` + try-catch | 依赖 Spring AI auto-config | 无 Key 时 auto-config 可能抛异常阻塞启动；手动创建可控 | 需要显式的 `ChatClient` 配置类 |
| 重命名 | `MockAgentService` → `AgentService` | 保留旧名 | 类名不再反映实际功能（已同时承载 mock+real） | 同步更新 Controller 和测试 |
| Fallback 策略 | 检测 `DEEPSEEK_API_KEY` + try-catch LLM 调用 | 仅依赖 Spring AI auto-config | 双重保护：配置缺失 + 运行时异常都能 fallback | 每个 LLM 调用点需要 try-catch |
| Prompt 管理 | Java 常量 + `String.format` | 外部 YAML 文件 | MVP 快速迭代，prompt 调优在代码中更方便 | prompt 变更需重新编译 |

## 10. 确认记录

- 确认时间：待用户确认。
- 确认人：待用户确认。
- 确认范围：从 `main` 创建 `feature/05-spring-ai-deepseek-agent`，为阶段 6 创建 Spring AI + DeepSeek Agent 编排 proposal；不自动进入 `/apply`。
- `/apply` 完成时间：2026-05-30。全部 7 个 Task 实现完成，Java 自动化测试通过，文档和 code_copilot 制品同步。
- Review 修复：消除 GoalInterpreter/AnswerGenerator 代码重复，统一 Instant.now() 时间度量，修复进度文件格式，具名常量加注释。
- `/achieve` 完成时间：2026-05-30。阶段 6 知识已沉淀到 `code_copilot/knowledge/index.md`，spec 归档为 `done`。
