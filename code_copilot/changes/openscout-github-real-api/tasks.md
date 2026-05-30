# 任务拆分 - OpenScout GitHub 真实 API 集成

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/project-context.md`
- [x] 已读取 `code_copilot/rules/coding-style.md`
- [x] 已读取 `code_copilot/rules/domain-rules.md`
- [x] 已读取 `code_copilot/rules/security.md`
- [x] 已读取 `code_copilot/knowledge/index.md`
- [x] 已读取 `项目实施进度.md`
- [x] 已检查工作区状态，当前分支为 `feature/04-github-real-api`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已确认本地验证命令或替代验证方式
- [x] 阶段 4 `feature/03-mybatis-persistence` 已合并到 `main`

## Task 1: Go — 结构化错误类型与 403/429/404 分离

- **目标**：替换 `fmt.Errorf` 为结构化 `GitHubError`，让 Go API 层返回 JSON `ErrorResponse`，使 Java 侧能区分限流（可重试）vs 404（永久）vs 上游故障。
- **层级/模块**：Go 基础设施 / GitHub 客户端
- **涉及文件**：
  - `openscout-repo-collector/internal/github/errors.go`（新增）：定义 `GitHubError` 结构体（`StatusCode`、`Code`、`Message`、`RetryAfter`），`IsRetryable()` 方法，`Error()` 接口实现。
  - `openscout-repo-collector/internal/github/client.go`（修改）：`getJSON()` 中 404 → `Code: "NOT_FOUND"`；403 → `Code: "FORBIDDEN"`；429 → `Code: "RATE_LIMITED"` 解析 `Retry-After` 头；其他非 2xx → `Code: "API_ERROR"`。
  - `openscout-repo-collector/internal/model/models.go`（修改）：新增 `ErrorResponse` 结构体（`Error string`、`Code string`、`RetryAfter int`）。
  - `openscout-repo-collector/internal/api/router.go`（修改）：所有错误路径返回 `model.ErrorResponse` JSON（code `http.StatusBadGateway` 保留现有状态码）。
- **依赖**：无。
- **风险标记**：向后兼容 / API 契约变更 / Go 错误感知。
- **实现要点**：
  - `GitHubError` 实现 `error` 接口（`Error() string`），可继续被既有 `fmt.Errorf` 风格的调用方消费。
  - `getJSON` 中 404 检查放在 `resp.StatusCode < 200 || resp.StatusCode >= 300` 之前，确保 404 优先被分类。
  - `Retry-After` 解析：先尝试 `strconv.Atoi` 解析秒数，失败则尝试 `time.Parse(time.RFC1123, ...)` 解析 HTTP 日期。
  - Router 层的 `gin.H{"message": err.Error()}` 替换为 `model.ErrorResponse` JSON；对于 `GitHubError`，直接映射 `Code` 和 `RetryAfter`；对于普通 `error`，`Code` 设为 `"INTERNAL"`。
  - Mock 路径完全不受影响 —— 结构化错误只影响真实 GitHub 客户端代码路径。
- **验收标准**：
  - 429 响应返回 `{"error":"...","code":"RATE_LIMITED","retryAfter":60}`。
  - 403 响应返回 `{"error":"...","code":"FORBIDDEN","retryAfter":0}`。
  - 404 响应返回 `{"error":"...","code":"NOT_FOUND","retryAfter":0}`。
  - 现有 Go 测试 `go test ./...` 通过。
- **验证命令**：
  ```bash
  cd openscout-repo-collector && go test ./...
  cd openscout-repo-collector && go vet ./...
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：internal/github/errors.go（新增）、internal/model/models.go（新增 ErrorResponse）、internal/github/client.go（getJSON 使用 GitHubError）、internal/api/router.go（toErrorResponse + 结构化 JSON）
  - 验证结果：go test ./... 通过；go vet ./... 通过

## Task 2: Go — 可配置基础设施参数

- **目标**：将硬编码的限流、缓存、并发、超时参数改为环境变量驱动，无需重新编译即可适配不同部署场景。
- **层级/模块**：Go 配置 / 入口层
- **涉及文件**：
  - `openscout-repo-collector/cmd/server/main.go`（修改）：读取 5 个新 env var（`OPSCOUT_RATE_LIMIT_RPS` 默认 2、`OPSCOUT_RATE_LIMIT_BURST` 默认 4、`OPSCOUT_CACHE_TTL_MINUTES` 默认 10、`OPSCOUT_WORKER_CONCURRENCY` 默认 4、`OPSCOUT_HTTP_TIMEOUT_SECONDS` 默认 8），失败时静默回退并 `slog.Warn`。
  - `openscout-repo-collector/internal/service/repo_service.go`（修改）：`NewRepoService` 新增 `workerConcurrency int` 参数；`RepoService` 结构体新增 `workerConcurrency` 字段；`BatchProfile` 使用 `s.workerConcurrency` 替代硬编码 `4`。
- **依赖**：无（可与 Task 1 并行）。
- **风险标记**：配置 / 低。
- **实现要点**：
  - 使用 `strconv.Atoi` / `strconv.ParseFloat` 解析 env，失败时 log warning 并用默认值。
  - `NewRepoService` 签名变更为 `NewRepoService(mode string, githubClient *github.Client, cache *cache.MemoryCache, logger *slog.Logger, workerConcurrency int)`。
  - `main.go` 中 `worker.NewPool(4)` → `worker.NewPool(workerConcurrency)`。
  - 默认值与当前硬编码值完全一致，行为不变。
- **验收标准**：
  - 不设 env var 时行为与当前一致（`go test ./...` 通过）。
  - 设置 `OPSCOUT_RATE_LIMIT_RPS=5` 后限流器使用新值。
  - 设置 `OPSCOUT_CACHE_TTL_MINUTES=30` 后缓存 TTL 为 30 分钟。
- **验证命令**：
  ```bash
  cd openscout-repo-collector && go test ./...
  cd openscout-repo-collector && go vet ./...
  ```
  - 状态：已完成
  - 实际改动文件：cmd/server/main.go（新增 envInt、envFloat，读取 5 个环境变量）、internal/service/repo_service.go（NewRepoService 新增 workerConcurrency，BatchProfile 使用可配置并发）、internal/service/repo_service_test.go（适配新签名）
  - 验证结果：go build ./... + go test ./... + go vet ./... 全部通过
  - 验证结果：

## Task 3: Java — DTO 与 CollectorClient 真实 API 方法

- **目标**：补齐 Java 调用 Go 四个真实端点所需的 DTO 和客户端方法。
- **层级/模块**：Java 客户端 / 基础设施
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/client/ReadmeResponse.java`（新增）：record `(String fullName, String readme, int length, String source)`。
  - `openscout-agent-server/src/main/java/com/openscout/client/BatchProfileRequest.java`（新增）：record `(List<String> repos)`。
  - `openscout-agent-server/src/main/java/com/openscout/client/BatchProfileResponse.java`（新增）：record `(List<RepoSummary> items, List<RepoError> errors)`，含 `itemsOrEmpty()` 和 `errorsOrEmpty()`。
  - `openscout-agent-server/src/main/java/com/openscout/client/RepoError.java`（新增）：record `(String fullName, String message)`。
  - `openscout-agent-server/src/main/java/com/openscout/client/CollectorClient.java`（修改）：新增 4 个方法 —— `searchRepos(keyword, limit, mode)`、`getProfile(owner, repo, mode)`、`getReadme(owner, repo, mode)`、`batchProfile(repos, mode)`。每个方法通过 `RestClient` 调用对应 Go 端点，`mode` 作为 query param 传递。
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`（修改）：新增 `String collectorMode` 字段（默认 `"mock"`），getter/setter。
  - `openscout-agent-server/src/main/resources/application.yml`（修改）：新增 `openscout.collector-mode: ${OPSCOUT_COLLECTOR_MODE:mock}`。
- **依赖**：Task 1（Go ErrorResponse 形状稳定后 Java 可正确解析）。
- **风险标记**：HTTP 契约对齐 / REST 客户端错误处理。
- **实现要点**：
  - `searchRepos` → `GET {baseUrl}/api/repos/search?keyword={}&limit={}&mode={}`。
  - `getProfile` → `GET {baseUrl}/api/repos/{owner}/{repo}/profile?mode={}`。
  - `getReadme` → `GET {baseUrl}/api/repos/{owner}/{repo}/readme?mode={}`。
  - `batchProfile` → `POST {baseUrl}/api/repos/batch-profile?mode={}`，body 为 `BatchProfileRequest`。
  - `fetchMockRepos` 保持不变，确保向后兼容。
  - 所有新方法不吞异常 —— 让 `RestClient` 异常传播给调用方。
  - 使用 `URLEncoder.encode` 处理 path/query 参数中的特殊字符。
- **验收标准**：
  - 4 个新方法编译通过（`mvn compile`）。
  - 状态：已完成
  - 实际改动文件：ReadmeResponse.java、BatchProfileRequest.java、BatchProfileResponse.java、RepoError.java（新增）；CollectorClient.java（新增 searchRepos/getProfile/getReadme/batchProfile）；OpenScoutProperties.java（新增 collectorMode）；application.yml（新增 openscout.collector-mode）
  - 验证结果：mvn compile + mvn test 通过
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：待开始
  - 实际改动文件：
  - 验证结果：

## Task 4: Java — MockAgentService 真实模式编排

- **目标**：当 `openscout.mock-agent=false` 时，编排真实的 search → getReadme → enrich → score → respond 链路。
- **层级/模块**：Java 应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java`（修改）：`ask()` 中增加 `if (properties.isMockAgent())` 分支：
    - `true` → 现有 mock 路径完全不变。
    - `false` → 新路径：
      1. `searchRepos(question, 10, "github")` 获取候选列表。
      2. 对前 5 个 repo：`getReadme(owner, repo, "github")` 获取 README。失败则 log.warn 并跳过。
      3. 从 README 文本推断 `hasExamples`（含 `example|sample|demo|tutorial|quickstart` 关键词，忽略大小写），从 topics 推断 `hasDocker`。
      4. 用 `new RepoSummary(...)` 构造 enriched 副本（设置 `readmeLength`、`hasExamples`、`hasDocker`）。
      5. 评分、排序、生成回答，工具调用记录为 `repo_search_github` + `readme_fetch_github`。
- **依赖**：Task 3（`CollectorClient` 新方法可用）。
- **风险标记**：业务逻辑 / N+1 性能 / 部分失败。
- **实现要点**：
  - `RepoSummary` 是 Java `record`，需要 `new RepoSummary(...)` 构造 enriched 副本。
  - `MAX_README_FETCH = 5` 作为常量，控制 GitHub API 调用次数。
  - 单个 `getReadme` 失败时 catch 异常，log.warn，用原始 `RepoSummary`（`readmeLength=0, hasExamples=false, hasDocker=false`）继续。
  - `hasExamples` 检查 README 文本前 2000 字符（大小写不敏感正则 `(?i)\b(example|sample|demo|tutorial|quickstart)\b`）。
  - `hasDocker` 从 `repo.topics()` 中检查是否包含 `"docker"`（大小写不敏感）。
  - 工具调用 `toolName`：搜索用 `"repo_search_github"`，README 获取用 `"readme_fetch_github"`。
  - 评分和 persist 逻辑复用现有代码。
- **验收标准**：
  - `openscout.mock-agent=true`（默认）：行为完全不变，返回 mock 数据。
  - `openscout.mock-agent=false` + Go mock 模式：调用 Go real 端点但获得 mock 数据，返回 200 + traceId。
  - 单个 README 失败不阻塞整体请求。
- **验证命令**：
  ```bash
  - 状态：已完成
  - 实际改动文件：MockAgentService.java（新增 askMock/askReal/enrichWithReadme/scoreAndRank/buildRealAnswer）
  - 验证结果：mvn compile + mvn test 通过
  - 状态：待开始
  - 实际改动文件：
  - 验证结果：

## Task 5: Java — 错误处理与优雅降级

- **目标**：基于 Go 结构化错误，Java 侧做智能分类与用户友好提示。
- **层级/模块**：Java 客户端 / 应用服务 / 控制器
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/client/CollectorException.java`（新增）：异常层级 —— `CollectorException` 基类（`traceId`）、`RateLimitException`（`retryAfterSeconds`）、`CollectorUnavailableException`、`GitHubApiException`（`httpStatus`、`errorCode`）。
  - `openscout-agent-server/src/main/java/com/openscout/client/CollectorClient.java`（修改）：新增 `ErrorResponseDto` 私有 record 用于解析 Go 错误 JSON；`onStatus` 处理中先尝试解析新 `ErrorResponse`（`code` 字段），失败则 fallback 到 `message`。`ResourceAccessException`（连接超时/拒绝）→ `CollectorUnavailableException`。
  - `openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java`（修改）：`ask()` 中捕获 `RateLimitException` 生成友好提示；单个 `getReadme` 的 `GitHubApiException`（特别是 404）不阻断整体；搜索端点异常 → `throw new AgentCallException`。
  - `openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java`（修改）：`AgentCallException` 处理中检查 cause 是否为 `RateLimitException`，如是则添加 `Retry-After` 响应头。
- **依赖**：Task 1（Go ErrorResponse）、Task 3、Task 4。
- **风险标记**：错误解析 / HTTP 状态码 / 向后兼容。
- **实现要点**：
  - `ErrorResponseDto` 用 Jackson `@JsonIgnoreProperties(ignoreUnknown = true)` 解析，字段 `error`、`code`、`retryAfter`。
  - `CollectorClient` 中新增私有方法 `parseCollectorError(RestClientResponseException ex)`，尝试 `objectMapper.readValue(ex.getResponseBodyAsByteArray(), ErrorResponseDto.class)`。
  - `CollectorException` 构造函数接收 `traceId` 供 Trace 关联。
  - Mock 路径完全不引入新异常路径。
- **验收标准**：
  - Go 不可用 → 抛出 `CollectorUnavailableException` → HTTP 502 + 失败 traceId。
  - Go 返回 `{"code":"RATE_LIMITED","retryAfter":60}` → 抛出 `RateLimitException` → 用户友好提示。
  - Go 返回 `{"code":"NOT_FOUND"}`（README 缺失）→ 抛出 `GitHubApiException` → 跳过该 repo。
  - Mock 模式行为不变。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  # 集成：停止 Go → curl /api/agent/ask → 502
  # 集成：Go 无 Token 真实搜索 → 验证错误消息合理
  - 状态：已完成
  - 实际改动文件：CollectorException.java、RateLimitException.java、CollectorUnavailableException.java、GitHubApiException.java（新增）；CollectorClient.java（新增 executeWithErrorHandling/parseCollectorError/ErrorResponseDto）；MockAgentService.java（ask 捕获 RateLimitException，enrichWithReadme 捕获 GitHubApiException/RateLimitException）；AgentController.java（handleAgentCallException 检测 RateLimitException 添加 Retry-After 头）
  - 验证结果：mvn compile + mvn test 通过；编译无异常层级引用错误
  - 实际改动文件：
  - 验证结果：

## Task 6: 文档与 code_copilot 制品同步

- **目标**：记录切换方式、边界与验证结果，使真实模式和 mock 模式的切换可复现。
- **层级/模块**：文档 / code_copilot
- **涉及文件**：
  - `.env.example`（修改）：补充新变量 —— Go 侧 5 个 `OPSCOUT_*`，Java 侧 `OPSCOUT_COLLECTOR_MODE` 注释。
  - `README.md`（修改）：新增"真实模式使用"章节 —— 如何设置 `GITHUB_TOKEN`，如何切换 Java/Go 模式，速率限制说明。
  - `docs/api-contract.md`（修改）：更新 Go 错误响应形状为 `ErrorResponse`，澄清 `?mode=` query param 行为。
  - `项目实施进度.md`（修改）：阶段 5 状态更新为"进行中"，完成后标记"已完成"。
  - `code_copilot/changes/openscout-github-real-api/spec.md`（修改）：更新 status 和确认记录。
  - `code_copilot/changes/openscout-github-real-api/tasks.md`（修改，本文件）：填充完成记录。
  - `code_copilot/changes/openscout-github-real-api/test-spec.md`（新增）。
  - `code_copilot/changes/openscout-github-real-api/log.md`（新增）。
- **依赖**：Task 1-5。
- **风险标记**：文档准确性。
- **实现要点**：
  - 文档必须明确声明阶段 5 的范围边界（不涉及 Redis、不涉及 Spring AI DeepSeek、不涉及 ETag）。
  - 包含真实模式和 mock 模式之间的切换步骤。
  - 记录所有新环境变量及默认值。
  - `项目实施进度.md` 中阶段 5 状态按模板更新。
- **验收标准**：
  - `.env.example` 包含所有新 Go/Java 配置变量及注释。
  - `README.md` 包含可工作的 curl 命令。
  - `项目实施进度.md` 与 change 状态一致。
  - `code_copilot/changes/openscout-github-real-api/` 下 4 个制品齐全。
- **验证命令**：
  ```bash
  rg -n "OPSCOUT_COLLECTOR_MODE|OPSCOUT_MOCK_AGENT|openscout-github-real-api|GITHUB_TOKEN|OPSCOUT_RATE_LIMIT" README.md .env.example docs 项目实施进度.md code_copilot/changes/openscout-github-real-api
  - 状态：已完成
  - 实际改动文件：.env.example（新增 Go 可配置参数注释）、README.md（新增真实模式使用章节）、docs/api-contract.md（新增 ErrorResponse 契约）、项目实施进度.md（阶段 5 完成记录）、spec.md（status → apply-done）、tasks.md（填充完成记录）、test-spec.md（新增）、log.md（新增）
  - 验证结果：rg 验证关键配置项出现在目标文件中
  - 实际改动文件：
  - 验证结果：

## 变更摘要

> `/apply` 完成于 2026-05-30。

- **总文件数**：24（11 新增 + 13 修改）
- **新增文件**：
  - Go: `internal/github/errors.go`
  - Java: `ReadmeResponse.java`, `BatchProfileRequest.java`, `BatchProfileResponse.java`, `RepoError.java`, `CollectorException.java`, `RateLimitException.java`, `CollectorUnavailableException.java`, `GitHubApiException.java`
  - code_copilot: `test-spec.md`, `log.md`
- **修改文件**：
  - Go: `cmd/server/main.go`, `internal/model/models.go`, `internal/api/router.go`, `internal/github/client.go`, `internal/service/repo_service.go`, `internal/service/repo_service_test.go`
  - Java: `CollectorClient.java`, `MockAgentService.java`, `OpenScoutProperties.java`, `AgentController.java`, `application.yml`
  - 文档: `.env.example`, `README.md`, `docs/api-contract.md`, `项目实施进度.md`, `spec.md`, `tasks.md`
- **删除文件**：无
- **Spec-Plan 偏差记录**：无重大偏差；所有实现严格遵循 spec.md 和 tasks.md
- **未完成项**：P1/P2 手动集成测试（需启动服务验证）；功能代码和单元测试全部完成
- **遗留风险**：无 Token 真实模式验证未在本机执行（需 GitHub Token）；真实模式下 N+1 README 获取受限流影响