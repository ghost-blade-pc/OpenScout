# 知识索引

本文件只做路由索引，不承载大段知识。新增知识必须来自真实代码、配置、测试、运行结果或已确认方案。

## 当前事实来源

- `OpenScout Agent 项目方案.md`：项目定位、技术栈、MVP 功能、Go Collector 接口草案、Java Tool 设计、评分规则、数据表草案、目录结构、一周开发计划。
- `code_copilot/changes/openscout-mvp-foundation/`：第一阶段 MVP 骨架与核心推荐闭环，已在阶段 3 mock e2e 验证后归档为 `done`。
- `code_copilot/changes/openscout-mock-e2e-demo/`：阶段 3 Java 调 Go mock 端到端验证记录，已归档为 `done`；包含 Go `/api/repos/mock`、Java `/api/agent/ask`、Trace 查询、Collector 不可用 502 场景、Spring AI 模型 provider 默认关闭配置。
- `code_copilot/changes/openscout-mybatis-persistence/`：阶段 4 MyBatis-Plus 持久化，已归档为 `done`；包含 agent_trace/repo_info/repo_analysis Entity/Mapper/Service、持久化开关、内存+MySQL 双查、幂等 upsert、脱敏和 MySQL 集成验证。
- `code_copilot/changes/openscout-github-real-api/`：阶段 5 GitHub 真实 API 集成，`apply-done`；包含 Go 结构化错误、可配置参数、Java CollectorClient 真实方法、真实模式编排、Java 异常层级、双模式开关设计。

## 已沉淀知识

- 阶段 3 mock e2e 的已验证闭环：Java `/api/agent/ask` 调 Go `/api/repos/mock`，返回 3 个 mock 推荐项目、规则评分和 `traceId`；随后 Java `/api/agent/traces/{traceId}` 可查到 `repo_search_mock` 工具调用摘要。
- 阶段 3 失败分支已验证：停止 Go Collector 后调用 Java `/api/agent/ask` 返回 HTTP 502，并保留失败 `traceId` 和 `Connection refused` 错误摘要。
- Spring AI 1.1.6 mock 模式约定：默认通过 `SPRING_AI_MODEL_* = none` 关闭模型 provider，避免无 Key 时 OpenAI 自动配置阻塞 Java 启动；真实 DeepSeek chat 接入时再显式设置 `SPRING_AI_MODEL_CHAT=openai` 和 `DEEPSEEK_API_KEY`。
- 当前阶段结论边界：结果只证明本地 mock HTTP 链路、规则评分和内存 Trace 可演示；不证明真实 GitHub API、真实模型调用、Redis adapter 或 MyBatis Trace 持久化完成。

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
- `MockAgentService` 虽名含 "Mock"，实际同时承载 mock 和真实两条路径——这是有意设计，避免新增类。

## 待沉淀主题

- TODO: Spring AI ChatClient、Tool Calling、Advisor、结构化输出与 DeepSeek V4 Pro 的实际版本和项目用法。
- TODO: GitHub REST API ETag/304 条件请求优化（P1 可选项，延后至后续阶段）。
- [x] GitHub REST API 限流、错误分类（403/429/404）、README 获取策略 → 已沉淀到阶段 5 知识。
- [x] Go Collector worker pool、rate limiter、cache 的实现约定 → 已沉淀到阶段 5 知识（可配置参数表）。
- TODO: OpenScout 项目评分公式和 evidence JSON 结构（当前评分规则硬编码在 `ProjectScoreService` 中）。
- [x] Agent Trace 字段、脱敏策略和查询方式 → 已沉淀到阶段 4 知识。

## 索引规则

- 有源码后，知识条目必须附真实路径、类名、方法名、配置键或测试命令。
- 不确定内容写 TODO，不把推测写成事实。
- 已完成 change 归档时，再把可复用经验追加到本索引。
