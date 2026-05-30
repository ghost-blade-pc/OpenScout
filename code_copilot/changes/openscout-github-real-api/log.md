# 执行日志 - OpenScout GitHub 真实 API 集成

## 2026-05-30 — `/apply` 执行

### Task 1: Go 结构化错误类型 ✅

- 新增 `internal/github/errors.go`：`GitHubError` 结构体、`newGitHubError()`、`parseRetryAfter()`
- 新增 `model.ErrorResponse` 结构体
- 修改 `client.go` `getJSON()`：统一使用 `newGitHubError()` 替代裸 `fmt.Errorf`
- 修改 `api/router.go`：所有错误路径使用 `toErrorResponse()` 返回 JSON `ErrorResponse`
- 验证：`go test ./...` + `go vet ./...` 全部通过

### Task 2: Go 可配置参数 ✅

- 修改 `cmd/server/main.go`：新增 `envInt()`、`envFloat()` 辅助函数，读取 5 个环境变量
- 修改 `service/repo_service.go`：`NewRepoService` 新增 `workerConcurrency` 参数，`BatchProfile` 使用 `s.workerConcurrency`
- 修改 `service/repo_service_test.go`：`NewRepoService` 调用适配新签名
- 验证：`go build ./...` + `go test ./...` + `go vet ./...` 全部通过

### Task 3: Java DTO 与 CollectorClient ✅

- 新增 `ReadmeResponse.java`、`BatchProfileRequest.java`、`BatchProfileResponse.java`、`RepoError.java`
- 修改 `CollectorClient.java`：新增 `searchRepos()`、`getProfile()`、`getReadme()`、`batchProfile()`
- 修改 `OpenScoutProperties.java`：新增 `collectorMode` 字段
- 修改 `application.yml`：新增 `openscout.collector-mode` 配置项
- 验证：`mvn compile` + `mvn test` 全部通过

### Task 4: Java 真实模式编排 ✅

- 修改 `MockAgentService.java`：
  - `ask()` 新增 `if (mockAgent)` 分支
  - `askReal()` 实现 search → README enrich → score → respond 链路
  - `enrichWithReadme()` 获取 README，推断 `hasExamples`/`hasDocker`
  - 常量 `MAX_README_FETCH = 5`，单 README 失败不阻塞
- 验证：`mvn compile` + `mvn test` 全部通过

### Task 5: Java 错误处理 ✅

- 新增 `CollectorException.java`、`RateLimitException.java`、`CollectorUnavailableException.java`、`GitHubApiException.java`
- 修改 `CollectorClient.java`：新增 `executeWithErrorHandling()`、`parseCollectorError()`、`ErrorResponseDto`/`LegacyErrorDto`
- 修改 `MockAgentService.java`：`ask()` 单独捕获 `RateLimitException`，`enrichWithReadme()` 捕获 `GitHubApiException`/`RateLimitException`
- 修改 `AgentController.java`：`handleAgentCallException()` 检测 `RateLimitException` 并添加 `Retry-After` 头
- 验证：`mvn compile` + `mvn test` 全部通过

### Task 6: 文档同步 ✅

- 更新 `.env.example`：新增 Go Collector 可配置参数注释
- 更新 `README.md`：新增"真实模式使用"章节，含模式组合表、启动步骤、速率限制说明、参数表
- 更新 `docs/api-contract.md`：新增 ErrorResponse 契约和 code 枚举表
- 更新 `项目实施进度.md`：阶段 5 完成记录、变更记录
- 更新 `spec.md`：status → `apply-done`
- 填充 `tasks.md`：全部 Task 完成记录
- 新增 `test-spec.md`、`log.md`

### 变更摘要

- 总文件数：24（11 新增 + 13 修改）
- 新增文件：
  - Go: `internal/github/errors.go`
  - Java: `ReadmeResponse.java`, `BatchProfileRequest.java`, `BatchProfileResponse.java`, `RepoError.java`, `CollectorException.java`, `RateLimitException.java`, `CollectorUnavailableException.java`, `GitHubApiException.java`
  - code_copilot: `test-spec.md`, `log.md`
- 修改文件：
  - Go: `cmd/server/main.go`, `internal/model/models.go`, `internal/api/router.go`, `internal/github/client.go`, `internal/service/repo_service.go`, `internal/service/repo_service_test.go`
  - Java: `CollectorClient.java`, `MockAgentService.java`, `OpenScoutProperties.java`, `AgentController.java`, `application.yml`
  - 文档: `.env.example`, `README.md`, `docs/api-contract.md`, `项目实施进度.md`, `spec.md`, `tasks.md`
- 删除文件：无
- Spec-Plan 偏差记录：无重大偏差；所有实现严格遵循 spec.md 和 tasks.md
- 未完成项：P1/P2 手动集成测试（需启动服务验证）；Redis adapter、Spring AI DeepSeek、ETag/304 属于后续阶段
- 遗留风险：无 Token 真实模式验证未在本机执行（需 GitHub Token）；真实模式下 N+1 README 获取受限流影响
