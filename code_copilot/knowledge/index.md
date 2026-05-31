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

## 索引规则

- 有源码后，知识条目必须附真实路径、类名、方法名、配置键或测试命令。
- 不确定内容写 TODO，不把推测写成事实。
- 已完成 change 归档时，再把可复用经验追加到本索引。
