# 知识索引

本文件只做路由索引，不承载大段知识。新增知识必须来自真实代码、配置、测试、运行结果或已确认方案。

## 当前事实来源

- `OpenScout Agent 项目方案.md`：项目定位、技术栈、MVP 功能、Go Collector 接口草案、Java Tool 设计、评分规则、数据表草案、目录结构、一周开发计划。
- `code_copilot/changes/openscout-mvp-foundation/`：第一阶段 MVP 骨架与核心推荐闭环，已在阶段 3 mock e2e 验证后归档为 `done`。
- `code_copilot/changes/openscout-mock-e2e-demo/`：阶段 3 Java 调 Go mock 端到端验证记录，已归档为 `done`；包含 Go `/api/repos/mock`、Java `/api/agent/ask`、Trace 查询、Collector 不可用 502 场景、Spring AI 模型 provider 默认关闭配置。
- `code_copilot/changes/openscout-mybatis-persistence/`：阶段 4 MyBatis-Plus 持久化，已归档为 `done`；包含 agent_trace/repo_info/repo_analysis Entity/Mapper/Service、持久化开关、内存+MySQL 双查、幂等 upsert、脱敏和 MySQL 集成验证。
- `code_copilot/changes/openscout-github-real-api/`：阶段 5 GitHub 真实 API 集成，`done`；包含 Go 结构化错误、可配置参数、Java CollectorClient 真实方法、真实模式编排、Java 异常层级、双模式开关设计。
- `code_copilot/changes/openscout-spring-ai-deepseek-agent/`：阶段 6 Spring AI + DeepSeek Agent 编排，`done`；包含 ChatClient 手动配置、GoalInterpreter/AnswerGenerator、AgentService 重命名、LLM fallback 策略、Trace 增强。
- `code_copilot/changes/openscout-learning-plan/`：阶段 7 学习计划与任务持久化，`done`；包含 7 天学习任务生成、learning_goal/learning_task 持久化、`/api/agent/ask.learningPlan`、`/api/learning/*` 查询/状态更新、Trace 增强和真实 MySQL + curl 验证。
- `code_copilot/changes/openscout-agent-runtime/`：阶段 8 Agent Runtime 内核，`done`；包含规则模板 Planner、PlanExecutor、Runtime 模型、AgentService 委托、Trace toolName 事件和 Runtime/Trace/AgentService 回归测试。
- `code_copilot/changes/openscout-tool-runtime/`：阶段 9 Tool Runtime，`done`；包含 Java Agent Server 内部 Tool Runtime 契约、ToolRegistry/ToolExecutor、6 个固定 Tool、Trace `agent_tool_*` 事件、PlanExecutor 委托 Tool Runtime 和回归验证。
- `code_copilot/changes/openscout-project-memory-rag/`：阶段 10 Project Memory / RAG，`done`；包含 ProjectMemoryService（MySQL LIKE 关键词搜索 + repo_analysis 缓存查询 + freshness 判断）、CheckMemoryTool（先于 search_repos 执行）、Planner 计划变更（mock 6 step / real 7 step）、SearchReposTool memory 跳过、FetchReadmeTool README 缓存命中（含 hasExamples 正则修复和 readmeLength 推断）、ScoreProjectsTool memory_writeback、7 个新 Trace 事件和 50 测试回归。
- `code_copilot/changes/openscout-evidence-react/`：阶段 11 Evidence ReAct，`done`；包含 EvidenceGapDetector、ReadmeEvidenceEnricher、EvidenceReActTool、RecommendationScoringService、`openscout.react.*` 配置、Planner 插入 `evidence_react`、README-only 补查、重评分、同请求 README 失败去重、空 README `empty_readme` 处理、`fetch_readme` rate limit 停止语义，以及 70 测试回归。
- `code_copilot/changes/openscout-reflection-verifier/`：阶段 12 Reflection Verifier，`done`；包含 VerifyAnswerTool、ScoreIntegrityChecker、EvidenceClaimsChecker、LearningPlanChecker、`openscout.verifier.*` 配置、Planner 插入 `verify_answer`、Trace `verify_completed` 事件，以及 28 个新增测试（总 98 tests）。
- `code_copilot/changes/openscout-agent-evaluation/`：阶段 14 Agent Evaluation，`done`；包含 `com.openscout.evaluation` 评测模型/Runner/指标计算/报告生成、默认 fixture、默认 Maven 评测命令、JSON/Markdown 报告、seeded memory REAL plan、两轮 review fix，以及 8 个 evaluation tests / 124 个 Java tests / Go tests / Docker Compose config 验证。

## 已沉淀知识

- 阶段 3 mock e2e 的已验证闭环：Java `/api/agent/ask` 调 Go `/api/repos/mock`，返回 3 个 mock 推荐项目、规则评分和 `traceId`；随后 Java `/api/agent/traces/{traceId}` 可查到 `repo_search_mock` 工具调用摘要。
- 阶段 3 失败分支已验证：停止 Go Collector 后调用 Java `/api/agent/ask` 返回 HTTP 502，并保留失败 `traceId` 和 `Connection refused` 错误摘要。
- Spring AI 1.1.6 mock 模式约定：默认通过 `SPRING_AI_MODEL_* = none` 关闭模型 provider，避免无 Key 时 OpenAI 自动配置阻塞 Java 启动；真实 DeepSeek chat 接入时再显式设置 `SPRING_AI_MODEL_CHAT=openai` 和 `DEEPSEEK_API_KEY`。
- 阶段 3 结论边界：当时结果只证明本地 mock HTTP 链路、规则评分和内存 Trace 可演示；真实 GitHub API、真实模型调用和 MyBatis Trace 持久化已在后续阶段补齐，Redis adapter 仍未实现。

## 阶段 4 持久化知识（2026-05-30 集成验证通过）

### MyBatis-Plus 持久化架构

- **包结构约定**：Entity + Mapper 按表分包子包 `persistence/{trace,repo,analysis}/`，Service 类桥接 domain 对象与 entity。
- **Mapper 扫描**：`@MapperScan("com.openscout.persistence")` 在 `OpenScoutAgentApplication` 上，Mapper 接口继承 `BaseMapper<T>` 并用 `@Mapper` 标注。
- **JSON 序列化**：统一使用 Spring Boot 内置 `ObjectMapper`，不手写拼接 JSON。`topics_json` → `List<String>`，`score_breakdown_json` → `Map<String,Integer>`，`evidence_json` → `List<String>`，`tool_calls_json` → `List<TraceToolCall>`。
- **score_summary JSON 包装**：AgentTrace 中的 `scoreSummary` 是纯文本字符串（如 `"repo1=85, repo2=70"`），写入 MySQL JSON 列时必须包装为 `{"summary":"..."}` 对象，读取时解包还原。

### 持久化开关模式

- 配置键：`OPSCOUT_PERSISTENCE_ENABLED` (env) → `openscout.persistence.enabled` (YAML)，默认 `false`。
- `OpenScoutProperties.Persistence` 内部类持有 `boolean enabled = false`。
- 调用方（TraceService、MockAgentService）在调用持久化方法前检查 `properties.getPersistence().isEnabled()`。
- 持久化写入失败时 catch 异常并 `log.warn`，不影响 ask 主流程。`persistIfEnabled(Runnable)` 封装此模式，位于 `TraceService` 中。
- **目的**：保证未启动 MySQL 时阶段 3 mock 演示不退化。

### Trace 内存+MySQL 双查

- `TraceService.find(traceId)` → 先查 `ConcurrentHashMap`，命中直接返回。
- 内存未命中且持久化启用 → 查 MySQL（`AgentTraceMapper.selectOne`），命中后回填内存缓存。
- `start()` → 创建 AgentTrace 放入内存 → `persistIfEnabled(() -> insertTrace)`。
- `complete()`/`fail()` → 更新内存字段 → `persistIfEnabled(() -> updateTrace)`。
- `recordToolCall()` 只操作内存；tool_calls_json 在 `complete()`/`fail()` 时一次性落库。
- `AgentTrace` 新增 `id` 字段（Long），用于 MyBatis-Plus `updateById`。

### repo_info 幂等 upsert

- 以 `full_name` 为业务键，`deploy/init.sql` 已有 `UNIQUE KEY uk_repo_full_name`。
- 实现：先 `selectOne` 按 `full_name` 查，存在则 `updateById`，不存在则 `insert`。不使用 MySQL `ON DUPLICATE KEY UPDATE`（避免 MyBatis-Plus 与原生 SQL 混用）。
- 重复 mock ask 不会因唯一索引冲突导致 500。

### Trace 脱敏规则

- 正则：`(?i)(token|api[-_]?key|authorization|password|secret)\s*[:=]\s*[^\s,;]+` → `$1=<redacted>`。
- 长度截断：`openscout.trace.max-summary-length`（默认 800），超出加 `...<truncated>`。
- 脱敏在 `sanitize()` 中统一执行，所有写入 DB 和 API 返回的字段均经过脱敏。

### MySQL 集成验证命令集

```bash
# 启动 MySQL 并初始化
docker compose -f deploy/docker-compose.yml up -d mysql
docker exec -i openscout-mysql mysql -uopenscout -popenscout openscout < deploy/init.sql

# 启动 Java（持久化启用）
cd openscout-agent-server && OPSCOUT_PERSISTENCE_ENABLED=true mvn spring-boot:run

# 验证查询
docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select trace_id,status,latency_ms from agent_trace order by id desc limit 5;"
docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select full_name,total_score from repo_analysis order by id desc limit 5;"
```

### 已知约束

- Trace 只保存摘要和脱敏字段，不保存完整 README、完整 prompt、模型 Key 或 GitHub Token。
- `/api/agent/traces/{traceId}` 仅用于本地排障，未做鉴权，不应公网暴露。
- 持久化默认关闭时 `mvn test` 无需 MySQL。
- `repo_analysis` 每次 ask 追加新记录，不覆盖历史分析。

## 阶段 5 GitHub 真实 API 知识（2026-05-30 实现完成）

### Go 结构化错误模式

- **文件**：`openscout-repo-collector/internal/github/errors.go`、`internal/model/models.go`、`internal/api/router.go`
- `GitHubError` 结构体：`StatusCode int`、`Code string`（`RATE_LIMITED`/`FORBIDDEN`/`NOT_FOUND`/`API_ERROR`）、`Message string`、`RetryAfter int`
- `GitHubError` 实现 `error` 接口（`Error() string`），可被既有调用方透传。
- `newGitHubError(statusCode, body, retryAfterHeader)` 根据 HTTP 状态码自动分类。
- `parseRetryAfter(value)` 先按秒数解析，再按 HTTP 日期（`time.RFC1123`）解析。
- `model.ErrorResponse`：`Error string`、`Code string`、`RetryAfter int` — 供 router 返回 JSON。
- Router 层 `toErrorResponse(err)`：`errors.As(err, &ghErr)` 检测 `*GitHubError` 映射字段，普通 `error` → `Code: "INTERNAL"`。
- **关键点**：`getJSON` 中 404 放在条件链最前面检查，确保 `NOT_FOUND` 优先分类。

### Go 可配置参数约定

- **文件**：`openscout-repo-collector/cmd/server/main.go`
- 环境变量 → Go 类型辅助函数：`envInt(key, fallback, logger)`、`envFloat(key, fallback, logger)`
- 参数表：

| 环境变量 | Go 类型 | 默认值 | 用途 |
|---|---|---|---|
| `OPSCOUT_RATE_LIMIT_RPS` | `float64` → `rate.Limit` | 2 | GitHub API 每秒请求数 |
| `OPSCOUT_RATE_LIMIT_BURST` | `int` | 4 | 突发请求数 |
| `OPSCOUT_CACHE_TTL_MINUTES` | `int` → `time.Duration` | 10 | 缓存 TTL（分钟） |
| `OPSCOUT_WORKER_CONCURRENCY` | `int` | 4 | 批量采集并发数 |
| `OPSCOUT_HTTP_TIMEOUT_SECONDS` | `int` → `time.Duration` | 8 | HTTP 客户端超时（秒） |

- 解析失败 → `slog.Warn` + 静默回退到默认值，不阻塞启动。
- `NewRepoService` 新增第 5 个参数 `workerConcurrency int`，`BatchProfile` 使用 `s.workerConcurrency` 替代硬编码 `4`。

### Java CollectorClient 真实 API 调用模式

- **文件**：`openscout-agent-server/src/main/java/com/openscout/client/CollectorClient.java`
- 四个真实方法：`searchRepos(keyword, limit, mode)`、`getProfile(owner, repo, mode)`、`getReadme(owner, repo, mode)`、`batchProfile(repos, mode)`
- `mode` 参数通过 query param 传递给 Go Collector；`effectiveMode(modeOverride)` 优先用覆盖值，否则用 `openscout.collector-mode` 配置。
- 统一错误翻译：`executeWithErrorHandling(Supplier<T>)` 包装所有调用。
  - `ResourceAccessException`（连接超时/拒绝）→ `CollectorUnavailableException`
  - `RestClientResponseException`（非 2xx）→ `parseCollectorError()` → `RateLimitException` / `GitHubApiException`
- 错误解析双路径：优先解析 Go 新 `ErrorResponse`（`{"error":"...","code":"...","retryAfter":0}`），失败时 fallback 到旧版 `{"message":"..."}`。

### Java 异常层级设计

- **文件**：`openscout-agent-server/src/main/java/com/openscout/client/`
- 层级：`CollectorException(RuntimeException)` → `RateLimitException`、`CollectorUnavailableException`、`GitHubApiException`
- `RateLimitException`：携带 `retryAfterSeconds`，用于友好提示和 `Retry-After` 响应头。
- `CollectorUnavailableException`：Go Collector 连接故障，对应 HTTP 502。
- `GitHubApiException`：携带 `httpStatus` 和 `errorCode`（`FORBIDDEN`/`NOT_FOUND`/`API_ERROR`），README 获取失败时按 errorCode 决定是否跳过。
- `AgentController.handleAgentCallException()` 检测 cause 为 `RateLimitException` 时自动添加 `Retry-After` 响应头。

### 双模式开关设计

- **Go 侧**：`OPSCOUT_COLLECTOR_MODE` 环境变量 → Go `main.go` → `RepoService.useMock(mode)`。
- **Java 侧**：`OPSCOUT_MOCK_AGENT` 环境变量 → `application.yml` → `OpenScoutProperties.mockAgent` → `MockAgentService.ask()` 分支。
- Java 侧新增 `openscout.collector-mode: ${OPSCOUT_COLLECTOR_MODE:mock}` — 控制 Java 调用 Go 时的 `?mode=` 参数，与 Go 自身模式独立。
- 四种组合均有明确语义（详见 README.md 真实模式章节）。

### 真实模式编排流程

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java`
- `askReal()` 流程：`searchRepos` → `enrichWithReadme`（前 5 个）→ `scoreAndRank` → `buildRealAnswer` → persist。
- `MAX_README_FETCH = 5` — 控制 README 获取上限，避免 N+1 耗尽 API 配额。
- `enrichWithReadme`：获取 README → 前 2000 字符正则检测 `hasExamples`（`(?i)\b(example|sample|demo|tutorial|quickstart)\b`）→ 从 topics 检测 `hasDocker` → 构造 enriched `RepoSummary`。
- 异常处理：`GitHubApiException`（404）和 `RateLimitException` 静默跳过，其他异常 `log.warn` 跳过——单个 README 失败不阻塞整体。

### 已知约束

- 阶段 5 不包含 Redis adapter、Spring AI DeepSeek、ETag/304、learning_goal/learning_task。
- 无 GitHub Token 时匿名限流 60 req/h；有 Token 时 5000 req/h。
- Go 错误响应兼容旧版 `{"message":"..."}` 格式，Java 侧双路径解析。
- `MockAgentService` 虽名含 "Mock"，实际同时承载 mock 和真实两条路径——这是有意设计，避免新增类。（阶段 6 已重命名为 `AgentService`。）

## 阶段 6 Spring AI + DeepSeek Agent 知识（2026-05-30 实现完成）

### LLM 配置模式

- **文件**：`openscout-agent-server/src/main/java/com/openscout/config/LlmConfig.java`
- 手动创建 `ChatClient` Bean，不依赖 Spring AI auto-config。
- 双重 guard：`DEEPSEEK_API_KEY` 缺失 → log.warn + return null；`ChatModel`（Spring AI auto-config 创建）不可用 → log.warn + return null。
- 调用方 `GoalInterpreter` / `AnswerGenerator` 使用 `@Autowired(required = false) ChatClient`，null 时自动 fallback 到模板回答。
- `application.yml` 中 `spring.ai.model.chat` 默认值改为 `openai`（原 `none`），使 Spring AI auto-config 默认创建 `OpenAiChatModel` Bean。
- LLM 可配置参数：`openscout.llm.enabled`（`true`）、`timeout-seconds`（30）、`max-tokens`（2000）、`temperature`（0.7）。

### GoalInterpreter 目标解释

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/GoalInterpreter.java`
- System prompt 约束 LLM 只返回 `{"keyword":"...","language":"...","domain":"..."}` JSON。
- `extractJson()` 处理 LLM 响应可能包裹的 markdown 代码块（`` ```json ... ``` ``），再交给 Jackson 反序列化。
- 目标解释固定使用低温度（`INTERPRET_TEMPERATURE = 0.3`）以保证 JSON 输出稳定；maxTokens 固定 300（目标解释只需要短输出）。
- Fallback 三步：ChatClient null → 直接 fallback；LLM 调用异常 → log.warn + fallback；JSON 解析失败 → log.warn + fallback。Fallback 结果 keyword = 原始 userGoal。
- 无 Trace 版本 `interpret(goal)` 委托到 `interpret(goal, null, null)`，LLM 调用逻辑提取到 `callLlm()` 私有方法。

### AnswerGenerator 回答生成

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/AnswerGenerator.java`
- System prompt 核心约束：评分由规则引擎计算不得修改、推荐理由必须引用评分证据、不得编造项目特性。
- `buildUserPrompt()` 将 `List<ProjectRecommendation>` 格式化为结构化文本（含总分、五维分、描述、语言、stars、evidence）。
- Evidence 截断 300 字符（`MAX_EVIDENCE_LENGTH`），description 截断 150 字符。
- 无 Trace 版本 `generate(goal, recs)` 委托到 `generate(goal, recs, null, null)`，LLM 调用逻辑提取到 `callLlm()` 私有方法。
- Fallback 模板回答以 "（LLM 不可用，返回模板回答。）" 结尾，便于排障。

### AgentService 重命名与 LLM 整合

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java`（原 `MockAgentService.java`）
- Spring `@Service` 默认 Bean 名从 `mockAgentService` → `agentService`。
- `AgentController` 字段类型和构造函数同步更新。
- 编排流程：`goalInterpreter.interpret(question)` → search repos → `enrichWithReadme` → `scoreAndRank` → `answerGenerator.generate(question, recs)`。
- 去除了 `buildMockAnswer`、`buildRealAnswer` 模板方法——回答生成统一由 `AnswerGenerator` 负责。
- `enrichWithReadme` 去除了阶段 5 时未使用的 `trace` 参数。

### LLM Trace 模式

- toolName：`llm_goal_interpret`、`llm_answer_generate`。
- inputSummary：截断的 user prompt 前 200 字符；outputSummary：截断的 LLM 响应前 300 字符。
- 时间度量统一使用 `Instant.now()` + `Duration.between()`（与 AgentService 其他 Trace 调用一致）。
- 仅当 `traceService != null && trace != null` 时记录 Trace（无 Trace 版本传 null）。

### 已知约束

- 阶段 6 不包含 Spring AI Tool Calling（`@Tool` 注解）、MCP 协议、Streaming/SSE、RAG。
- LLM 不可用时自动降级为模板回答，不影响搜索和评分链路。
- `OPSCOUT_LLM_ENABLED=false` 可强制关闭 LLM 使用模板模式。
- DeepSeek V4 Pro 通过 OpenAI 兼容协议接入，base-url 指向 `https://api.deepseek.com`。
- `DEEPSEEK_API_KEY` 仅通过环境变量传入，不在日志/Trace/代码中显式出现。

## 阶段 7 学习计划与任务持久化知识（2026-05-31 实现完成）

### 学习计划生成模式

- **文件**：`openscout-agent-server/src/main/java/com/openscout/learning/LearningPlanGenerator.java`
- 生成策略：规则模板为主，LLM 仅做任务文案增强；LLM 不可用、关闭、输出解析失败或 dayNo 不合规时自动 fallback 到规则模板。
- 默认计划长度固定为 7 天，任务来源是用户目标、Top 推荐项目、评分结果和 evidence，不修改推荐排序或规则评分。
- LLM JSON 提取支持 markdown 代码块包裹，避免模型返回 JSON fenced block 时解析失败。
- 空推荐列表返回未持久化的空计划，不抛异常，保证 ask 主流程可演示。

### 响应与配置契约

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/AgentAskResponse.java`、`openscout-agent-server/src/main/resources/application.yml`、`openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`
- `/api/agent/ask` 追加可空字段 `learningPlan`，保留 `traceId`、`answer`、`recommendations`、`latencyMs` 的兼容性。
- 配置键：`OPSCOUT_LEARNING_ENABLED` → `openscout.learning.enabled`，默认 `true`。
- 持久化关闭或写入失败时仍返回学习计划，但 `learningPlan.persisted=false`、`goalId` 可为空；查询和状态更新只面向已持久化计划。

### learning_goal / learning_task 持久化

- **文件**：`openscout-agent-server/src/main/java/com/openscout/persistence/learning/`
- `LearningGoalEntity`、`LearningTaskEntity`、`LearningGoalMapper`、`LearningTaskMapper`、`LearningPlanPersistenceService` 复用阶段 4 的 Entity + Mapper + Service 模式。
- 本阶段不改 `deploy/init.sql`，复用既有 `learning_goal`、`learning_task` 表；推荐项目上下文写入任务文本和 `target_stack`。
- 保存流程：先插入 goal，再插入 7 条 task；查询时按 `day_no` 升序返回；状态更新只接受枚举值。

### 学习计划 API

- **文件**：`openscout-agent-server/src/main/java/com/openscout/learning/LearningController.java`
- `GET /api/learning/goals/{goalId}`：返回已持久化目标和任务列表；不存在返回 404。
- `PATCH /api/learning/tasks/{taskId}/status`：请求体 `{"status":"TODO|DOING|DONE"}`；非法状态返回 400，合法状态返回更新后的任务。
- 当前接口无鉴权，只适合本地 Demo；公网暴露前必须补用户隔离和访问控制。

### Trace 与验证证据

- `AgentService` 在学习计划链路记录 `learning_plan_generate` 和 `learning_plan_persist` tool call 摘要；不记录完整 prompt、密钥或大对象。
- 自动化验证：
  - `cd openscout-agent-server && mvn test`：通过，22 tests。
  - `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...`：通过，使用项目本地 Go 1.26.3。
  - `docker compose -f deploy/docker-compose.yml config`：通过。
- 真实环境验证：MySQL + Go Collector + Java Agent 长驻服务启动后，`POST /api/agent/ask` 返回 `learningPlan.persisted=true` 和 7 条任务；`GET /api/learning/goals/1` 返回 7 条任务；`PATCH /api/learning/tasks/1/status` 更新为 `DONE`；Trace 查询包含 `repo_search_mock`、`learning_plan_generate`、`learning_plan_persist`。

### 已知约束

- 阶段 7 不包含前端、日历提醒、复杂任务调度、多用户/多租户、登录鉴权、Redis 缓存、RAG、Streaming/SSE 或数据库迁移框架。
- `learning_goal` 当前不显式关联 `trace_id` 或 repo 关系表；MVP 通过 `goalId` 查询，复杂关联和版本管理后续拆分。
- LLM 学习计划增强只能做文案辅助，不能编造项目模块或覆盖规则评分结果。

## 阶段 8 Agent Runtime 内核知识（2026-05-31 实现完成）

### Runtime 架构边界

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/`
- 第一版 Runtime 是“显式化当前固定 ask 流程”，不是自主 Agent，不使用 LLM 动态规划。
- `RuleBasedAgentPlanner` 生成固定计划：
  - mock：`interpret_goal -> search_repos -> score_projects -> generate_learning_plan -> generate_answer`
  - real：`interpret_goal -> search_repos -> fetch_readme -> score_projects -> generate_learning_plan -> generate_answer`
- `PlanExecutor` 统一执行 mock/real 计划步骤，复用既有 `GoalInterpreter`、`CollectorClient`、`ProjectScoreService`、`LearningPlanGenerator`、`AnswerGenerator`。
- `AgentService` 收敛为请求校验、Trace 生命周期和异常转换；具体执行委托给 `PlanExecutor`。

### Runtime 模型

- `AgentPlan`：包含 `planId`、`AgentRuntimeMode` 和步骤列表。
- `PlanStep`：包含 `stepId`、`toolName`、`purpose`、`inputSummary`、`continueOnFailure` 和 `PlanStepStatus`。
- `StepObservation`：包含 `stepId`、`toolName`、`status`、`outputSummary`、`errorSummary`、`latencyMs`。
- `AgentContext`：保存一次 ask 的 userGoal、目标解释、repo 列表、推荐结果、学习计划和最终回答。
- `AgentRuntimeResult`：返回 answer、recommendations、learningPlan 和 scoreSummary 给 `AgentService` 完成响应。

### Trace toolName 事件约定

- 用户已确认第一版不新增 Trace DDL，全部复用 `TraceToolCall` 承载 Runtime 事件。
- `TraceService` 新增事件方法：
  - `agent_plan_created`：记录 planId、mode、step 数。
  - `agent_step_started`：记录 stepId、toolName、purpose。
  - `agent_step_finished`：记录 step 状态和耗时。
  - `agent_observation_created`：记录 observation 输出摘要或错误摘要。
- Runtime 事件继续复用 `TraceService.sanitize()`，不保存完整 README、完整 prompt、模型 Key、GitHub Token 或大对象。
- `complete()` / `fail()` 仍通过既有 `tool_calls_json` 一次性落库，未新增数据库字段。

### 失败与兼容策略

- `search_repos` 失败为不可继续步骤，会沿用 `RateLimitException` / `CollectorUnavailableException` / `AgentCallException` 语义。
- `fetch_readme` 是可继续步骤；单个 README 404、限流或其他异常只记录跳过，不阻断评分、学习计划和回答。
- `/api/agent/ask` 响应保持兼容：保留 `traceId`、`answer`、`recommendations`、`learningPlan`、`latencyMs`。
- 阶段 8 不包含项目对比报告、前端、RAG、Project Memory、Evidence-aware ReAct、Reflection Verifier、SSE/Streaming、Spring AI Tool Calling、MCP、生产鉴权、多用户隔离或数据库迁移框架。

### 修正的实现细节

- 原 `AgentService.persistReposIfEnabled()` 按排序前 repos 下标取排序后 recommendations，存在 repo 和推荐结果错配风险。
- 阶段 8 在 `PlanExecutor.persistReposIfEnabled()` 中按 `ProjectRecommendation.fullName` 建索引，再与 `RepoSummary.fullName` 匹配，避免排序后下标错配。

### 验证证据

- `cd openscout-agent-server && mvn test`：通过，29 tests。
- `cd openscout-repo-collector && go test ./...`：通过，使用项目本地 Go 工具链和本地 cache/path。
- `docker compose -f deploy/docker-compose.yml config`：通过。

## 阶段 9 Tool Runtime 知识（2026-05-31 实现完成）

### Tool Runtime 边界

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/tool/`
- 第一版 Tool Runtime 只作为 Java Agent Server 内部抽象，不新增公开 Tool API、Spring AI `@Tool`、MCP、SSE、RAG、ReAct、Verifier 或 Trace DDL。
- Tool Runtime 只执行 `RuleBasedAgentPlanner` 生成的固定 `PlanStep.toolName`，不允许用户动态指定任意 Tool。
- `PlanExecutor` 仍负责 plan/step 生命周期、`AgentContext` 串联、`StepObservation` 记录和最终 `AgentRuntimeResult` 构造；具体能力委托给 `ToolExecutor`。

### 核心契约与注册执行

- `AgentTool`：所有内部 Tool 实现的统一接口，包含 `toolName()` 和 `execute(ToolRequest)`。
- `ToolRequest`：携带 `PlanStep`、`AgentContext`、`AgentTrace`。
- `ToolResult`：携带 `PlanStepStatus`、`outputSummary`、`errorSummary`、`latencyMs`；成功通过 `ToolResult.success()` 创建，recoverable failure 通过 `ToolResult.recoverableFailure()` 创建。
- `ToolRegistry`：从 Spring `List<AgentTool>` 构建 `toolName -> AgentTool` 映射，重复 toolName 直接抛 `IllegalStateException`，未知 toolName 抛 `unsupported tool`。
- `ToolExecutor`：统一记录 `agent_tool_started`、`agent_tool_finished`、`agent_tool_failed`，并保留 fatal exception 的原始异常语义。

### 固定 Tool 列表

| toolName | 实现类 | 责任 |
|---|---|---|
| `interpret_goal` | `InterpretGoalTool` | 调用 `GoalInterpreter`，把用户目标解释为搜索关键词并写回 `AgentContext` |
| `search_repos` | `SearchReposTool` | mock 模式调用 `fetchMockRepos`，real 模式调用 `searchRepos(..., "github")` |
| `fetch_readme` | `FetchReadmeTool` | real 模式为 Top 5 repo 补充 README evidence；单项 404、限流或普通异常 best-effort 跳过 |
| `score_projects` | `ScoreProjectsTool` | 使用 `ProjectScoreService` 生成规则评分和推荐排序，并保留 repo/analysis 持久化开关语义 |
| `generate_learning_plan` | `GenerateLearningPlanTool` | 生成 7 天学习计划；持久化关闭或失败时返回临时计划，不中断 ask |
| `generate_answer` | `GenerateAnswerTool` | 调用 `AnswerGenerator` 生成最终回答；LLM disabled 或失败时走模板 fallback |

### Trace 事件约定

- 用户已确认阶段 9 不新增 Trace DDL，继续复用 `TraceToolCall`。
- 阶段 8 的 Runtime 事件保留：`agent_plan_created`、`agent_step_started`、`agent_step_finished`、`agent_observation_created`。
- 阶段 9 新增 Tool Runtime 事件：
  - `agent_tool_started`：记录 stepId、toolName、输入摘要。
  - `agent_tool_finished`：记录 Tool 输出摘要、状态和耗时。
  - `agent_tool_failed`：记录 Tool 输出摘要、错误摘要和耗时。
- 所有 Tool 事件仍走 `TraceService.recordToolCall()`，因此继续复用 `sanitize()` 脱敏和 `openscout.trace.max-summary-length` 截断规则。
- 不保存完整 README、完整 prompt、完整模型响应、GitHub Token、模型 Key 或大对象。

### 失败与兼容策略

- `search_repos` 等 fatal step 抛出的 `RateLimitException`、`CollectorUnavailableException`、`GitHubApiException` 会保留原异常语义，再由 `AgentService` 包装成既有 `AgentCallException` 响应。
- `fetch_readme` 是可继续步骤；单个 README 获取失败只影响 enrichment，不阻断评分、学习计划和回答。
- `generate_learning_plan` 的持久化失败属于 fallback，不阻断 ask；返回 `persisted=false` 的临时计划。
- `/api/agent/ask` 响应兼容：保留 `traceId`、`answer`、`recommendations`、`learningPlan`、`latencyMs`。
- Review 低风险残留：fatal exception 分支中 `agent_step_finished` / `agent_observation_created` 的 latency 仍为 0；对应 `agent_tool_failed` 已记录真实耗时。后续若做阶段 13 事件流或 Trace 精度增强，可统一 step/tool latency。

### 验证证据

- `cd openscout-agent-server && mvn test`：通过，34 tests。
- `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...`：通过，使用项目本地 Go 1.26.3。
- `docker compose -f deploy/docker-compose.yml config`：通过。

## 阶段 10 Project Memory / RAG 知识（2026-05-31 实现完成）

### Project Memory 架构

- **文件**：`openscout-agent-server/src/main/java/com/openscout/memory/ProjectMemoryService.java`
- `ProjectMemoryService` 封装对 `repo_info` 和 `repo_analysis` 的只读查询，提供：
  - `searchByKeyword(keyword)`：按空白字符拆分关键词，对 `full_name`、`description`、`language` 做 `LIKE '%word%'` OR 匹配。单单词最小长度 2 字符，最大返回 20 条。
  - `getCachedAnalysis(fullName)`：按 `full_name` 查 `repo_analysis` 最新记录（`ORDER BY analyzed_at DESC LIMIT 1`）。
  - `isFresh(recordTime, freshnessHours)`：按配置的 TTL 判断记录是否新鲜。
  - 所有查询异常静默 fallback 为空，不阻塞 ask 主流程。
- `isEnabled()` 需要 `memory.enabled=true` 且 `persistence.enabled=true` 才真正启用。

### CheckMemoryTool — 新计划步骤

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/tool/CheckMemoryTool.java`
- `check_memory` 步骤在 `interpret_goal` 之后、`search_repos` 之前执行，`continueOnFailure=true`。
- 执行流程：keyword → `searchByKeyword` → 过滤 freshness → 新鲜非空则设 `context.setRepos()` + `context.setMemoryHit(true)`；空或过期则记录 miss。
- Trace 事件：`memory_check`（disabled）、`memory_hit`（命中新鲜结果）、`memory_miss`（未命中或全部过期）。

### 计划结构变更

- **Mock**（6 step）：`interpret_goal → check_memory → search_repos → score_projects → generate_learning_plan → generate_answer`
- **Real**（7 step）：`interpret_goal → check_memory → search_repos → fetch_readme → score_projects → generate_learning_plan → generate_answer`
- `RuleBasedAgentPlanner` 使用 `idx++` 计数器替代旧的硬编码 `offset` 计算。

### Memory 跳过与缓存逻辑

- **SearchReposTool**：`isMemoryHit() && !getRepos().isEmpty()` 时跳过 Collector 调用，记录 `search_repos_skipped`。
- **FetchReadmeTool**：每个 repo 先查 `getCachedAnalysis()` + `isFresh()`，命中则从 evidence 推断 `hasExamples` / `readmeLength`，跳过 GitHub；未命中走 `enrichWithReadme()`。
  - `hasExamples` 推断：用正则 `\bexample[s]?\b` 等匹配 evidence 字符串（如 `"learning: examples directory found"`）。
  - `readmeLength` 推断：`inferReadmeLength(evidence)` — `"README length >= 2000"` → 2000，`"README exists but is short"` → 500，否则 0。
  - `hasDockerTopic(repo)` 提取为共享静态方法消除重复。
- **ScoreProjectsTool**：`persistReposIfEnabled()` 增强为计数成功/失败数，记录 `memory_writeback` Trace 事件。

### 配置约定

| 环境变量 | YAML 键 | 默认值 | 用途 |
|---|---|---|---|
| `OPSCOUT_MEMORY_ENABLED` | `openscout.memory.enabled` | `true` | Memory 总开关 |
| `OPSCOUT_MEMORY_FRESHNESS_HOURS` | `openscout.memory.freshness-hours` | `24` | 缓存新鲜度 TTL（小时） |

### Trace 事件约定

| 事件名 | 来源 Tool | 含义 |
|---|---|---|
| `memory_check` | CheckMemoryTool | memory disabled 或异常 |
| `memory_hit` | CheckMemoryTool | 命中新鲜缓存 |
| `memory_miss` | CheckMemoryTool | 未命中或全部过期 |
| `search_repos_skipped` | SearchReposTool | 因 memory 命中跳过 Collector |
| `readme_cache_hit` | FetchReadmeTool | 单 repo README 从缓存命中 |
| `memory_writeback` | ScoreProjectsTool | 评分结果回写持久化完成 |

所有 memory 事件继续复用 `TraceToolCall`，走 `sanitize()` 脱敏，不新增 DDL。

### 已知约束

- 第一版仅使用 MySQL `LIKE '%keyword%'` 关键词检索，不做向量/FULLTEXT。数据量 < 10000 行时性能可接受。
- freshness TTL 全局单一阈值（默认 24h），不按维度（stars/README）做差异化。
- `check_memory` 作为固定 Tool 插入计划，不开放动态 Tool 选择或用户指定 toolName。
- N+1 DB 查询：`CheckMemoryTool` 对每个缓存 repo 做单次 `getCachedAnalysis()`，最多 20 次额外查询。MVP 数据量下无实际影响，后续可增加批量 `getCachedAnalyses(List<String>)` 方法。
- `readmeLength` 从 evidence 推断而非存储原始值：长 README (>=2000) 能正确命中阈值，短 README (<2000) 使用 500 作为保守估算值。

## 阶段 11 Evidence ReAct 知识（2026-06-01 实现完成）

### Evidence ReAct 架构

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/react/EvidenceGapDetector.java`、`openscout-agent-server/src/main/java/com/openscout/agent/tool/EvidenceReActTool.java`
- `evidence_react` 是固定 Tool，不开放用户动态指定 Tool，不接 Spring AI `@Tool`、MCP 或 SSE。
- Planner 结构：
  - Mock：`interpret_goal → check_memory → search_repos → score_projects → evidence_react → generate_learning_plan → generate_answer`
  - Real：`interpret_goal → check_memory → search_repos → fetch_readme → score_projects → evidence_react → generate_learning_plan → generate_answer`
- Mock 模式中 ReAct 只记录 `mode_not_real` 停止原因，不调用 GitHub。

### Gap 检测与 README 补查

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/react/EvidenceGap.java`、`EvidenceGapType.java`、`ReadmeEvidenceEnricher.java`
- 第一版只补 README evidence，不做 release、目录结构、issues 等额外 GitHub 调用。
- gap 类型：`MISSING_README`、`WEAK_DOC_EVIDENCE`、`CACHE_EVIDENCE_INCOMPLETE`。
- 补查成功后更新 `AgentContext.repos` 中对应 `RepoSummary`，再通过 `RecommendationScoringService` 重新评分并排序。
- `ReadmeEvidenceEnricher` 复用 `CollectorClient.getReadme(owner, repo, "github")`，只返回 README 长度、examples 命中、状态和错误摘要，不暴露完整 README。
- `FetchReadmeTool` 会把同一次 ask 内 README 失败 observation 记录到 `AgentContext`；`EvidenceReActTool` 看到已失败 repo 会记录 `skipped_previous_<status>` observation，不再重复调用 Collector。
- `FetchReadmeTool` 遇到 `rate_limited` 会停止后续 Top 5 README 调用，并把 `retryAfterSeconds` 传给 `EvidenceReActTool`；ReAct 复用该 observation 输出 `rate_limited` 和 retryAfter 后停止补查。
- 空 README 不算补查成功；`ReadmeEvidenceEnricher` 在 `readmeLength <= 0` 时返回 `empty_readme` observation。

### 配置约定

| 环境变量 | YAML 键 | 默认值 | 用途 |
|---|---|---|---|
| `OPSCOUT_REACT_ENABLED` | `openscout.react.enabled` | `true` | Evidence ReAct 总开关 |
| `OPSCOUT_REACT_MAX_ROUNDS` | `openscout.react.max-rounds` | `1` | 最大补查轮数 |
| `OPSCOUT_REACT_MAX_FOLLOW_UP_REPOS` | `openscout.react.max-follow-up-repos` | `3` | 单轮最多补查 repo 数 |

### Trace 事件约定

| 事件名 | 含义 |
|---|---|
| `evidence_gap_detected` | 记录本轮 gap 数量、repo 和 gapType |
| `evidence_follow_up_started` | 记录补查 action 和原因 |
| `evidence_follow_up_observed` | 记录补查状态、README 长度、限流或错误摘要 |
| `evidence_rescore_completed` | 补查成功后重新评分完成 |
| `evidence_react_stopped` | 记录停止原因，如 disabled、mode_not_real、no_actionable_gap、rate_limited、max_rounds_reached |

所有 ReAct 事件继续复用 `TraceToolCall` 和 `TraceService.sanitize()`，不新增 DDL。

### 已知约束

- ReAct 是增强能力，单 repo 404 或普通异常不破坏 ask 主流程；遇到限流停止后续补查并记录 `retryAfterSeconds`。
- 默认最大一轮、最多 3 个 repo，避免 GitHub API 配额和延迟失控。
- 阶段 11 不做 Reflection Verifier；LLM 仍不得覆盖规则评分。
- Review fix 已关闭：`fetch_readme` 失败后同请求不再重复补查同一 repo README；空 README 返回 `empty_readme`，不计为 fetched。
- Review 复查 fix 已关闭：`FetchReadmeTool` rate limit 停止语义已补齐，当前无阶段 11 阻塞 deferred。

## 阶段 12 Reflection Verifier 知识（2026-06-01 实现完成）

### Verifier 架构

- **文件**：`openscout-agent-server/src/main/java/com/openscout/agent/tool/VerifyAnswerTool.java`、`openscout-agent-server/src/main/java/com/openscout/agent/verifier/`
- `verify_answer` 是固定 Tool，在 `generate_answer` 之后执行，`continueOnFailure=true`。
- Planner 结构（阶段 12 最终）：
  - Mock：`interpret_goal → check_memory → search_repos → score_projects → evidence_react → generate_learning_plan → generate_answer → verify_answer`
  - Real：`interpret_goal → check_memory → search_repos → fetch_readme → score_projects → evidence_react → generate_learning_plan → generate_answer → verify_answer`

### 三项规则检查

| 检查器 | 文件 | 责任 |
|---|---|---|
| `ScoreIntegrityChecker` | `agent/verifier/ScoreIntegrityChecker.java` | 正则提取回答中分数数字，与 `ProjectScore.totalScore` 对比 |
| `EvidenceClaimsChecker` | `agent/verifier/EvidenceClaimsChecker.java` | 检查回答声称的项目能力（文档完善/示例代码/生产级）是否有 evidence 支持 |
| `LearningPlanChecker` | `agent/verifier/LearningPlanChecker.java` | 检查学习任务引用的仓库名是否在推荐列表中 |

- 三项检查互不依赖，分别运行；单项异常不阻断其他检查。
- 所有检查均不依赖 LLM（第一版 `llm-enabled=false`），纯规则化实现。
- 发现问题产出 `VerificationIssue`（WARNING 或 ERROR 级别），但不修改回答。

### 配置约定

| 环境变量 | YAML 键 | 默认值 | 用途 |
|---|---|---|---|
| `OPSCOUT_VERIFIER_ENABLED` | `openscout.verifier.enabled` | `true` | Verifier 总开关 |
| `OPSCOUT_VERIFIER_LLM_ENABLED` | `openscout.verifier.llm-enabled` | `false` | LLM 语义检查开关（第一版关闭） |

### Trace 事件约定

- `verify_completed`：记录三项检查结果摘要（`scoreIntegrity=ok/issues`、`evidenceClaims=ok/issues`、`learningPlan=ok/issues`）和 `allOk` 状态。
- 所有 Verifier 事件继续复用 `TraceToolCall`，不新增 DDL。

### 已知约束

- 第一版只做规则检查，不做 LLM 语义检查（`llm-enabled=false`）。
- Verifier 只报告问题到 Trace，不修改回答、不阻断 ask。
- 分数提取依赖正则匹配中文评分格式，可能存在漏匹配；未匹配到时产生 `SCORE_FORMAT_UNRECOGNIZED` warning。
- 证据声明检查使用启发式关键词匹配，存在误报风险。

## 阶段 14 Agent Evaluation 知识（2026-06-02 实现完成）

### Evaluation 模块与固定命令

- **包路径**：`openscout-agent-server/src/main/java/com/openscout/evaluation/`
- 核心类：
  - `EvaluationCaseLoader`：从 `src/test/resources/evaluation/cases.json` 读取固定 case，并校验 id/question/mode/expectations/thresholds。
  - `AgentEvaluationRunner`：按 case 调用 `AgentService.ask()`，切换 `OpenScoutProperties.mockAgent`，收集 `AgentAskResponse` 和 `AgentTrace`。
  - `EvaluationMetricCalculator`：把 response + Trace toolName 事件转成推荐相关性、evidence 覆盖、Verifier issue、Memory hit、fallback、延迟和 GitHub 调用统计。
  - `EvaluationReportWriter`：输出 `target/openscout-evaluation/agent-evaluation-report.json` 和 `.md`。
- 默认命令：

```bash
cd openscout-agent-server && mvn test -Dtest=AgentEvaluationCommandTest
```

### 默认 fixture 口径

- 默认 fixture 是 mock-first、本地可复现，不依赖 `GITHUB_TOKEN`、`DEEPSEEK_API_KEY`、Docker 或外网。
- 默认必跑 case：
  - `mock-spring-ai-agent`：MOCK mode，覆盖 mock search、推荐相关性、evidence、fallback 和 verifier Trace。
  - `real-memory-hit-react`：REAL mode，但使用测试内 seeded `ProjectMemoryService`，覆盖 `memory_hit`、`search_repos_skipped`、`readme_cache_hit` 和 GitHub 调用节省，不访问真实 GitHub。
- `optional-real-github-memory` 是 optional REAL case，默认跳过；真实 GitHub / LLM enabled 评测后续单独执行，不作为默认必过项。

### 指标口径与 review 修复

- `githubReadmeFetchCalls` 不直接按 `readme_fetch_github` 事件数统计；`FetchReadmeTool` 即使 cache hit 也会记录该事件，因此评测按 output summary 的 `fetched=N` 求和，cache hit 不计入真实 README 调用。
- `githubCallSavings` 当前按 `search_repos_skipped + readme_cache_hit` 估算，表示基于 Trace 的调用节省，不是 GitHub 配额账单。
- JSON / Markdown sample 必须包含 `mode`，避免混合 MOCK/REAL case 时被全局 `environment.mockAgent` 误导。
- optional skipped 样本使用 `skipped=true`、`passed=false`；summary 会排除 skipped 统计，机器消费 JSON 时不得把 skipped 当成真实通过。
- raw `eventCounts` 是 Trace 事件计数，不等同于 API 调用次数；对外汇报调用数应使用 `EvaluationMetrics` / `EvaluationSummary`。

### 报告边界

- 报告只保留 answer preview、top recommendation snapshot、traceId、eventCounts、metrics 和 failureReasons，不输出完整 README、完整 prompt、完整模型响应或异常堆栈。
- `EvaluationReportWriter` 使用 secret 正则兜底脱敏 token/key/password/secret/authorization。
- 默认报告可写结论：本地 fixture 下可重复生成评测报告，覆盖 memory hit 和 GitHub 调用节省指标。
- 不能夸大：默认报告不代表生产 SLA、线上准确率、大规模 benchmark，也不代表真实 GitHub / LLM enabled 评测结果。

### 验证结果

- `cd openscout-agent-server && mvn test -Dtest='Evaluation*Test,AgentEvaluation*Test'`：8 tests 通过。
- `cd openscout-agent-server && mvn test`：124 tests 通过。
- `PATH=/home/lpc/project/OpenScout/.tools/go/bin:$PATH GOCACHE=/home/lpc/project/OpenScout/.tools/go-cache GOPATH=/home/lpc/project/OpenScout/.tools/go-path go test ./...`：通过。
- `docker compose -f deploy/docker-compose.yml config`：通过。

## 阶段 15 Production Hardening 知识（2026-06-02 实现完成）

### 数据库迁移基线

- **文件**：`openscout-agent-server/src/main/resources/db/migration/V1__init_schema.sql`、`application.yml`、`pom.xml`
- 迁移工具：Flyway（`flyway-core` + `flyway-mysql`）。
- V1 基线复用 `deploy/init.sql` 现有表结构（`repo_info`、`repo_analysis`、`learning_goal`、`learning_task`、`agent_trace`），不新增业务语义。
- **配置契约**：Flyway 配置必须在 `spring.flyway.*` 命名空间（非 `openscout.flyway.*`）供 Spring Boot auto-config 读取。
  - `spring.flyway.enabled: ${FLYWAY_ENABLED:false}` — 默认关闭，与 `OPSCOUT_PERSISTENCE_ENABLED=false` 一致。
  - `spring.flyway.baseline-on-migrate: true` — 允许已有数据库的表继续使用 Migration。
- `deploy/init.sql` 继续服务 Docker 首次初始化，与 V1 保持同步。
- 后续 schema 变更约定：新建 `V2__xxx.sql` 等迁移文件，禁止直接修改 V1。

### CI 基线

- **文件**：`.github/workflows/ci.yml`
- 三步 Job：
  - `java-test`：JDK 17 + `mvn test`（全量）+ `mvn test -Dtest=AgentEvaluationCommandTest`
  - `go-test`：Go 1.23（匹配 `go.mod` 最低版本）+ `go test ./...`
  - `compose-config`：`docker compose -f deploy/docker-compose.yml config --quiet`
- **关键约束**：CI 默认不依赖 `GITHUB_TOKEN`、`DEEPSEEK_API_KEY`、MySQL 长驻服务或外网业务调用。
- Go 版本必须与 `go.mod` 的 `go` 指令匹配；版本不一致会导致 CI 永久失败。

### 验证与演示脚本

- **文件**：`scripts/verify-local.sh`、`scripts/demo-mock.sh`、`scripts/demo-real-optional.sh`
- `verify-local.sh`：一键验证 Java/Go/Compose/Evaluation，优先使用项目本地 Go 工具链。
- `demo-mock.sh`：启动 Java mock 模式 + 示例 curl 请求；需要 `spring-boot-starter-actuator` 做健康检查轮询。
- `demo-real-optional.sh`：检查 `GITHUB_TOKEN` + `DEEPSEEK_API_KEY` 环境变量，缺失时给出明确提示并正常退出（exit 0）。
- **敏感值约定**：所有脚本不得使用 `set -x` 打印环境变量；不得硬编码 token/key。

### 最小 API Key 保护

- **Java 侧**：`ApiKeyFilter`（`OncePerRequestFilter`）→ 注册于 `SecurityConfig`，`addUrlPatterns("/api/*")`，`order=1`。
- **Go 侧**：`apiKeyMiddleware`（Gin middleware）→ 条件注册于 `NewRouter`，仅 `OPSCOUT_COLLECTOR_API_KEY != ""` 时启用。
- **豁免路径**：`/health`、`/actuator/health`（Java）；`/health`（Go）。
- **配置契约**：
  | 环境变量 | YAML 键 | 默认值 |
  |---|---|---|
  | `OPSCOUT_SECURITY_ENABLED` | `openscout.security.enabled` | `false` |
  | `OPSCOUT_API_KEY` | `openscout.security.api-key` | `""` |
  | `OPSCOUT_API_KEY_HEADER` | `openscout.security.header-name` | `X-OpenScout-Api-Key` |
  | `OPSCOUT_COLLECTOR_API_KEY` | (Go 直接读 env) | `""` |
- **错误响应**：401 `{"error":"API key required/invalid","code":"UNAUTHORIZED"}`，不包含 key 值。
- **NPE 防御**：使用 `Objects.equals(expectedKey, actualKey)` 而非 `expectedKey.equals()`。
- **已知限制**：非恒定时间比较（理论上存在时序侧信道）；API Key 是最小访问门，不等价于完整生产鉴权。

### Java 入站配额

- **文件**：`RateLimitService.java`、`QuotaFilter.java`
- **算法**：进程内固定窗口（`ConcurrentHashMap` + `volatile long` count）。
- **配额键**：优先 `X-OpenScout-Api-Key` header（需 security enabled），其次 `request.getRemoteAddr()`。
- **受保护端点**：`POST /api/agent/ask`、`POST /api/agent/runs`。
- **超限响应**：429 `{"error":"Too many requests...","code":"QUOTA_EXCEEDED"}`。
- **配置契约**：
  | 环境变量 | YAML 键 | 默认值 |
  |---|---|---|
  | `OPSCOUT_QUOTA_ENABLED` | `openscout.quota.enabled` | `false` |
  | `OPSCOUT_QUOTA_MAX_REQUESTS` | `openscout.quota.max-requests-per-window` | `30` |
  | `OPSCOUT_QUOTA_WINDOW_SECONDS` | `openscout.quota.window-seconds` | `60` |
- **内存管理**：`MAX_MAP_SIZE=10000` 阈值触发 `sweepExpired()` 清理过期条目，防止 OOM。
- **时钟防御**：`Math.max(0, now - windowStart)` 防御 `System.currentTimeMillis()` NTP 向后跳变。
- **已知限制**：重启清空、多实例不共享、固定窗口存在边界突增。

### 配置治理与文档

- **文件**：`.env.example`、`README.md` Production Hardening 章节
- `.env.example`：所有值使用 `<your-xxx>` 占位符，无真实密钥。
- README 配置矩阵明确 7 个维度的默认值和边界。
- 明确声明：API Key 是最小访问门、进程内 quota 非分布式、默认 CI 不依赖外网。
- 敏感值扫描命令确保文档/脚本/change 不泄漏 token/key。

### 三层 Review 修复总结

- 三轮 review（7 角度）共发现 **22 项问题**，修复 **15 项**，7 项不阻塞合并。
- 关键修复包括：Flyway 配置命名空间、`mybatis-plus` 键丢失、ApiKeyFilter NPE、RateLimitService 内存泄漏/时钟回退、CI Go 版本不匹配、actuator 缺失、错误码语义冲突、手写 `contains` 替代标准库、Go header 名硬编码。

### 验证记录

- `cd openscout-agent-server && mvn test`：145 tests, 0 failures。
- `cd openscout-repo-collector && go test ./...`：通过。
- `docker compose -f deploy/docker-compose.yml config`：通过。
- 敏感值扫描：README、`.env.example`、scripts、change 目录均无真实密钥。

## 阶段 16 Phase 0 "Real User Ready" 知识（2026-06-02 实现完成）

### Docker 多阶段构建

- **文件**：`openscout-agent-server/Dockerfile`、`openscout-repo-collector/Dockerfile`
- Java：`maven:3.9-eclipse-temurin-17-alpine` → `eclipse-temurin:17-jre-alpine`，非 root `openscout` 用户，HEALTHCHECK `wget /actuator/health`
- Go：`golang:1.23-alpine` → `alpine:3.19`，`CGO_ENABLED=0`，`-ldflags="-s -w"` 减小编译产物
- 均使用多阶段构建，构建依赖不进入运行镜像

### docker-compose 全服务化

- **文件**：`deploy/docker-compose.yml`
- 4 服务：mysql + redis + collector + agent
- agent 依赖 mysql (healthy) + collector (started)
- 所有服务 `restart: unless-stopped`
- agent 容器内 `FLYWAY_ENABLED` / `OPSCOUT_PERSISTENCE_ENABLED` 默认 false，生产部署时按需开启
- 服务间通过 Docker DNS 通信（`collector:8081`、`mysql:3306`）

### Go 优雅关闭

- **文件**：`cmd/server/main.go`
- `signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)` + `http.Server.Shutdown(10s)`
- `defer cache.Stop()` 清理 eviction goroutine

### CORS + Swagger

- **文件**：`config/CorsConfig.java`
- `WebMvcConfigurer` 对 `/api/**`、`/swagger-ui/**`、`/v3/api-docs/**` 设置 CORS
- `allowedOrigins` 默认为 `*`，可配置 `OPSCOUT_CORS_ALLOWED_ORIGINS`
- `springdoc-openapi-starter-webmvc-ui:2.6.0` 自动生成 OpenAPI 文档

### Go 内存缓存 eviction

- **文件**：`internal/cache/memory.go`
- 后台 goroutine：每 `TTL/2`（最小 1 分钟）扫描三个 map 删除过期条目
- `maxEntries = 10000` — Set 操作前检查，超限拒绝写入
- `Stop()` 方法关闭 `done` channel，停止 goroutine

### V2 数据库迁移

- **文件**：`db/migration/V2__add_users_and_isolation.sql`
- 新建 `users` 表（id, api_key, name, role, enabled, created_at）
- `learning_goal`、`learning_task`、`agent_trace` 新增 `user_id BIGINT NULL` + 索引
- 全部使用 `IF NOT EXISTS` 保证幂等

### 用户体系与多用户隔离

- **文件**：`persistence/user/UserEntity.java`、`UserMapper.java`、`controller/AdminController.java`
- `AdminController`：`POST /api/admin/users`（生成 UUID Key）、`GET /api/admin/users`（列表，Key 仅显示前 8 位）、`DELETE /api/admin/users/{id}`（吊销）
- `/api/admin/**` 仅允许 `role=admin` 的 Key 访问

### ApiKeyFilter DB 改造

- **文件**：`config/ApiKeyFilter.java`、`config/SecurityConfig.java`
- 优先从 `users` 表查询 API Key → 命中则注入 `userId` + `userRole` 到 request attribute
- 兼容旧版预共享密钥（`OPSCOUT_API_KEY` 环境变量）
- `SecurityConfig` 使用 `@Autowired(required = false) UserMapper` — 持久化关闭时不会阻塞启动

### userId 全链路传播

- 传播路径（8 层）：`ApiKeyFilter` → request attribute → `AgentController` / `LearningController` → `AgentService` / `AgentRunService` → `TraceService.start()` → `AgentTrace.userId` → `GenerateLearningPlanTool` → `LearningPlanPersistenceService.savePlan(userId)`
- `LearningGoalEntity`、`LearningTaskEntity`、`AgentTraceEntity` 新增 `userId` 字段
- userId 为 NULLABLE，保持向后兼容

### Web UI

- **文件**：`src/main/resources/static/index.html`
- 单文件 SPA：HTML + 内联 CSS + 内联 JS
- 四个视图：问题输入 → 实时进度（SSE `EventSource`）→ 推荐列表（分数着色）→ 学习计划（状态切换按钮）
- 无需 Node.js、构建工具或外部 CDN

### 线程安全修复

- `AgentTrace.toolCalls`：`ArrayList` → `CopyOnWriteArrayList`（并行 LLM 步骤安全写入）
- `AgentContext`：setter 使用 `synchronized` + 防御性拷贝，getter 返回 `Collections.unmodifiableList`
- `GoalInterpreter`：LLM 返回空/blank 响应时自动重试 1 次

### 验证记录

- `cd openscout-agent-server && mvn test`：146 tests, 0 failures。
- `cd openscout-repo-collector && go test ./...`：通过。
- `curl http://localhost:8080/` → 200 (Web UI)
- `curl http://localhost:8080/swagger-ui.html` → 200 (API 文档)
- `curl http://localhost:8081/health` → `{"status":"UP"}`

## 待沉淀主题

- TODO: Spring AI Tool Calling（`@Tool` 注解）、Advisor、结构化输出与 DeepSeek V4 Pro 的实际版本和项目用法（Function Calling 兼容性待验证）。
- [x] Spring AI ChatClient 手动配置、DeepSeek OpenAI 协议接入、LLM fallback 策略 → 已沉淀到阶段 6 知识。
- TODO: GitHub REST API ETag/304 条件请求优化（P1 可选项，延后至后续阶段）。
- [x] GitHub REST API 限流、错误分类（403/429/404）、README 获取策略 → 已沉淀到阶段 5 知识。
- [x] Go Collector worker pool、rate limiter、cache 的实现约定 → 已沉淀到阶段 5 知识（可配置参数表）。
- TODO: OpenScout 项目评分公式和 evidence JSON 结构（当前评分规则硬编码在 `ProjectScoreService` 中）。
- [x] Agent Trace 字段、脱敏策略和查询方式 → 已沉淀到阶段 4 知识。
- [x] 学习计划生成、learning_goal/learning_task 持久化、任务状态 API → 已沉淀到阶段 7 知识。
- [x] Java Agent Server 内部 Tool Runtime、固定 Tool、Trace `agent_tool_*` 事件 → 已沉淀到阶段 9 知识。
- [x] Project Memory / RAG、MySQL LIKE 关键词检索、freshness 判断、memory hit/miss/write-back Trace 事件 → 已沉淀到阶段 10 知识。
- [x] Evidence ReAct、README-only 补查、有限轮数、gap/follow-up/observation Trace 事件 → 已沉淀到阶段 11 知识。
- [x] Reflection Verifier、分数完整性/证据声明/学习计划三项规则自检、`verify_answer` Tool、`verify_completed` Trace 事件 → 已沉淀到阶段 12 知识。
- [x] Agent Evaluation、固定评测集、JSON/Markdown 报告、指标口径、seeded memory REAL plan、默认评测边界 → 已沉淀到阶段 14 知识。
- [x] Production Hardening、数据库迁移基线、CI、验证脚本、API Key 保护、入站配额、配置治理 → 已沉淀到阶段 15 知识。
- [x] Phase 0 "Real User Ready"、Docker 全栈部署、Web UI、多用户体系、线程安全、优雅关闭、CORS/Swagger、Go 缓存 eviction → 已沉淀到阶段 16 知识。

## 索引规则

- 有源码后，知识条目必须附真实路径、类名、方法名、配置键或测试命令。
- 不确定内容写 TODO，不把推测写成事实。
- 已完成 change 归档时，再把可复用经验追加到本索引。
