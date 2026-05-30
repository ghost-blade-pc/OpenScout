# OpenScout GitHub 真实 API 集成
> status: done
> created: 2026-05-30
> complexity: 复杂

## 1. 背景与目标

根据 `项目实施进度.md` 的阶段 5，当前分支 `feature/04-github-real-api` 的目标是完善真实 GitHub API 模式，包括 search/profile/readme、限流、错误处理和缓存策略。

Go Collector 已有完整的 GitHub 客户端（`internal/github/client.go`）、限流器（`internal/limiter/limiter.go`）、内存缓存（`internal/cache/memory.go`）和工作池（`internal/worker/pool.go`），且 HTTP 路由（`internal/api/router.go`）已暴露所有真实端点。Java 侧 `CollectorClient` 仅有 `fetchMockRepos()`，`MockAgentService` 硬编码使用 mock。

本阶段完成后应能证明：

- Go Collector 的 GitHub 客户端能正确区分 403/429/404 错误，返回结构化 JSON 错误响应。
- Go 限流、缓存 TTL、工作池并发和 HTTP 超时均可通过环境变量配置。
- Java `CollectorClient` 可调用 Go 的 search、profile、readme、batch-profile 四个真实端点。
- `MockAgentService` 在 `openscout.mock-agent=false` 时能编排 search → enrich → score → respond 的真实链路。
- 无 GitHub Token 时 mock 模式完全不受影响；有 Token 时真实 API 可用。
- 403/429 错误能被 Java 侧正确识别并给出用户友好提示。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 阶段 5 GitHub 真实 API 集成。
- 调用方向：用户 curl -> Java `/api/agent/ask` -> `MockAgentService` -> `CollectorClient` -> Go `/api/repos/search` -> GitHub REST API -> Go `/api/repos/{owner}/{repo}/readme` -> Java 评分 -> 回答。
- 是否涉及高风险项：是。
- 高风险类型：外部 API 限流、错误分类、Token 安全、缓存一致性、部分失败容忍。

### 1.2 范围裁剪

本次包含：

- Go 结构化错误类型（`GitHubError`），支持 403/429/404 分离和 `Retry-After` 解析。
- Go 错误响应 JSON 化（`ErrorResponse` 结构体），替代 `gin.H{"message":...}`。
- Go 可配置基础设施：限流、缓存 TTL、工作池并发、HTTP 超时。
- Java 侧新增 `ReadmeResponse`、`BatchProfileRequest`、`BatchProfileResponse`、`RepoError` DTO。
- Java `CollectorClient` 新增 `searchRepos`、`getProfile`、`getReadme`、`batchProfile` 方法。
- Java `MockAgentService` 真实模式编排：search → 获取 README → enrich → score → respond。
- Java 结构化异常层级：`RateLimitException`、`CollectorUnavailableException`、`GitHubApiException`。
- 文档与 code_copilot 制品同步。

本次不包含：

- Redis adapter 或缓存持久化（延后至后续阶段）。
- Spring AI + DeepSeek Tool Calling（延后至阶段 6）。
- 学习计划 `learning_goal`、`learning_task` 的生成（延后至阶段 7）。
- ETag/304 条件请求（P1 可选项，不阻塞交付）。
- 管理后台、Trace 可视化页面、权限系统或生产部署方案。

后续可能拆分：

- `openscout-spring-ai-deepseek-agent`：Spring AI Tool Calling 与 DeepSeek 编排。
- `openscout-learning-plan`：学习路径和任务持久化。
- `openscout-redis-adapter`：Redis 缓存替换进程内 TTL 缓存。
- `openscout-etag-optimization`：ETag 条件请求减少 API 调用。

## 2. Research Findings

### 2.1 相关入口与链路

- 项目进度：`项目实施进度.md` 第 4 节将阶段 5 定义为 `feature/04-github-real-api`。
- Go 启动入口：`openscout-repo-collector/cmd/server/main.go` 通过 `OPSCOUT_COLLECTOR_MODE` 控制 mock/real 模式，创建 `http.Client`、`cache.MemoryCache`、`limiter.Limiter`、`github.Client`、`service.RepoService`，然后注册 Gin 路由。
- Go GitHub 客户端：`openscout-repo-collector/internal/github/client.go` 已实现 `SearchRepos`、`Profile`、`Readme`，以及 `getJSON` 通用请求方法。403/429 当前合并为一个 `fmt.Errorf`。
- Go 限流器：`openscout-repo-collector/internal/limiter/limiter.go` 基于 `golang.org/x/time/rate`，硬编码 2 req/s、burst 4。
- Go 缓存：`openscout-repo-collector/internal/cache/memory.go` 提供按 key 的 TTL 缓存，硬编码 10 分钟。
- Go 工作池：`openscout-repo-collector/internal/worker/pool.go` 硬编码 4 并发，支持部分失败。
- Go 路由：`openscout-repo-collector/internal/api/router.go` 已暴露 `/api/repos/search`、`/api/repos/:owner/:repo/profile`、`/api/repos/:owner/:repo/readme`、`/api/repos/batch-profile`，所有支持 `?mode=` query param。
- Go 服务层：`openscout-repo-collector/internal/service/repo_service.go` 通过 `useMock(mode)` 决定 mock/real 分支，支持 `?mode=` 覆盖。
- Java CollectorClient：`openscout-agent-server/src/main/java/com/openscout/client/CollectorClient.java` 仅 `fetchMockRepos()`。
- Java MockAgentService：`openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java` 硬编码调用 `fetchMockRepos`。
- Java 配置：`openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java` 已有 `mockAgent` 布尔值。
- Java REST 客户端：`openscout-agent-server/src/main/java/com/openscout/config/RestClientConfig.java` 配置了 3s 连接超时 / 8s 读取超时。
- 数据模型对齐：Java `RepoSummary` 与 Go `model.RepoSummary` JSON 序列化完全兼容。
- 已验证基线：`code_copilot/changes/openscout-mybatis-persistence/` 记录阶段 4 持久化已完成。

### 2.2 现有实现摘要

- Go 侧 GitHub 客户端代码已 80% 可用："按部就班"地调用 GitHub REST API 的逻辑就位，缺少结构化错误和可配置参数。
- Go 侧 `getJSON` 已处理速率限制等待（`limiter.Wait`）、`User-Agent` 设置、Token 注入（`Authorization: Bearer`），但 403/429 混为一谈。
- Go 侧 router 中所有错误通过 `gin.H{"message": err.Error()}` 返回，缺少结构化字段。
- Java 侧 `RepoS全文mary` 与 Go 模型完全对齐，无需适配层。
- `MockAgentService` 已有清晰的生命周期（start → recordToolCall → complete/fail），真实模式可直接复用。
- Go 侧 `BatchProfile` 已通过 worker pool 支持部分失败，Java 只需正确映射 `BatchProfileResponse`。

### 2.3 发现的问题

- Go 所有错误是裸 `fmt.Errorf` 字符串，Java 无法通过 HTTP 响应区分限流（可重试）、404（缺少 README 正常）和上游故障。
- 硬编码参数（限流 2/4、缓存 10min、并发 4、超时 8s）在真实 GitHub API 场景下可能需要调整以适应不同 Token 等级（无 Token 60 req/h，有 Token 5000 req/h）。
- `MockAgentService` 当前只有一个 mock 路径，缺乏真实搜索 + README 增强的编排。
- Java `CollectorClient` 缺少真实 API 调用方法，且无错误分类解释（始终抛 `RestClientResponseException`）。
- Go `Readme` 方法硬编码截断 8000 字符 README，对于大仓库可能丢失关键信息。
- `OPSCOUT_COLLECTOR_MODE` 和 `openscout.mock-agent` 是两个独立开关，需文档说明各自职责。

### 2.4 风险初判

- 外部 API 风险：GitHub 限流策略（无 Token 60 req/h，有 Token 5000 req/h）可能影响演示体验；需要清晰的 fallback 和错误提示。
- 安全风险：GitHub Token 通过环境变量传递，不得在日志/Trace/响应中泄露。
- 兼容性风险：Go 错误响应从 `gin.H` 变为结构化的 `ErrorResponse`，可能影响 Java 的错误解析。
- 性能风险：真实模式下 search → N 次 getReadme 可能慢（N+1 问题），需限制 README 获取数量。
- 部分失败风险：单个 repo README 缺失不应导致整体请求失败。
- 测试风险：真实 GitHub API 验证依赖 Token，未配置 Token 时只能验证 mock 路径。

## 3. 功能点

- [ ] 功能 1：Go 结构化错误类型（`GitHubError`），区分 403/429/404，解析 `Retry-After`，API 层返回 JSON `ErrorResponse`。
- [ ] 功能 2：Go 可配置基础设施参数（限流 RPS/Burst、缓存 TTL、工作池并发、HTTP 超时）。
- [ ] 功能 3：Java 新增 DTO（`ReadmeResponse`、`BatchProfileRequest`、`BatchProfileResponse`、`RepoError`）和 `CollectorClient` 四个真实 API 方法。
- [ ] 功能 4：Java `MockAgentService` 真实模式编排（search → getReadme → enrich → score → respond）。
- [ ] 功能 5：Java 结构化异常层级（`RateLimitException`、`CollectorUnavailableException`、`GitHubApiException`）和错误分类解析。
- [ ] 功能 6：文档与 code_copilot 制品同步（`.env.example`、`README.md`、`docs/api-contract.md`、`项目实施进度.md`、spec/tasks/test-spec/log）。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| 配置 | Go `OPSCOUT_RATE_LIMIT_RPS/BURST` | 新增，默认 2/4 | 向后兼容，不设时行为不变 | 恢复默认值 |
| 配置 | Go `OPSCOUT_CACHE_TTL_MINUTES` | 新增，默认 10 | 向后兼容 | 恢复默认值 |
| 配置 | Go `OPSCOUT_WORKER_CONCURRENCY` | 新增，默认 4 | 向后兼容 | 恢复默认值 |
| 配置 | Go `OPSCOUT_HTTP_TIMEOUT_SECONDS` | 新增，默认 8 | 向后兼容 | 恢复默认值 |
| 配置 | Java `openscout.collector-mode` | 新增，默认 `mock` | 向后兼容 | 恢复默认值 |
| Go API | 错误响应形状 | 从 `{"message":"..."}` 改为 `{"error":"...","code":"...","retryAfter":0}` | Go 调用方无影响；Java 需适配解析 | 保留 `message` 字段作 fallback |
| Java | `CollectorClient` 新增方法 | 4 个新方法，不改变 `fetchMockRepos` | 向后兼容 | 删除新方法 |
| Java | `MockAgentService.ask()` 分支 | 新增 `else` 分支，不改变 mock 路径 | 向后兼容 | 恢复 `mockAgent=true` |
| Java | 新增异常类 | `CollectorException` 层级 | 向后兼容 | 删除新类 |
| 文档 | `.env.example`、`README.md`、`docs/api-contract.md` | 新增配置说明和切换步骤 | 文档变更 | 按 Git diff 回滚 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | 既有 `AgentAskRequest`，含 `question` 或 `goal` | 既有 `AgentAskResponse`，含 `traceId`、`answer`、`recommendations`、`latencyMs` | MVP 本地演示无鉴权 | 响应结构不变；`mockAgent=false` 时走真实 GitHub 链路 |
| `/api/agent/traces/{traceId}` | GET | path：`traceId` | 既有 `AgentTrace` 或 404 | 仅本地演示和排障 | 响应结构不变 |
| Go `/api/repos/search` | GET | 既有 query params，新增 `?mode=` | `RepoListResponse` 结构不变；错误返回 `ErrorResponse` | 无鉴权 | 错误响应形状变更 |
| Go `/api/repos/:owner/:repo/profile` | GET | 既有 path params，新增 `?mode=` | `RepoSummary` 结构不变；错误返回 `ErrorResponse` | 无鉴权 | 错误响应形状变更 |
| Go `/api/repos/:owner/:repo/readme` | GET | 既有 path params，新增 `?mode=` | `ReadmeResponse` 结构不变；错误返回 `ErrorResponse` | 无鉴权 | 错误响应形状变更 |
| Go `/api/repos/batch-profile` | POST | 既有 `BatchProfileRequest`，新增 `?mode=` | `BatchProfileResponse` 结构不变 | 无鉴权 | 错误响应形状变更 |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector | `GET /api/repos/search?keyword=&limit=&mode=github` | keyword、limit、mode | `RepoListResponse` | 8s/无重试 | 抛出 `GitHubApiException`，Java 返回 502 |
| Go Collector | `GET /api/repos/:owner/:repo/readme?mode=github` | owner、repo、mode | `ReadmeResponse` | 8s/无重试 | 单 README 失败跳过，不阻断整体 |
| Go Collector | `POST /api/repos/batch-profile?mode=github` | `BatchProfileRequest` | `BatchProfileResponse` | 8s/无重试 | 由 Go worker pool 处理部分失败 |
| GitHub REST API | `GET /search/repositories` | q, sort, order, per_page | GitHub search response | Go HTTP 超时可配置 | 返回结构化 `GitHubError` |
| GitHub REST API | `GET /repos/{owner}/{repo}` | owner, repo | GitHub repo response | Go HTTP 超时可配置 | 返回结构化 `GitHubError` |
| GitHub REST API | `GET /repos/{owner}/{repo}/readme` | owner, repo | GitHub readme response | Go HTTP 超时可配置 | 返回结构化 `GitHubError` |

### 5.3 Go ErrorResponse 新契约

```json
{
  "error": "github rate limit exceeded",
  "code": "RATE_LIMITED",
  "retryAfter": 60
}
```

`code` 可选值：`"RATE_LIMITED"`、`"FORBIDDEN"`、`"NOT_FOUND"`、`"API_ERROR"`、`"INTERNAL"`。
`retryAfter` 仅在 `code=RATE_LIMITED` 时有效（秒），其余为 0。

### 5.4 MQ/Event

本阶段不涉及 MQ/Event。

## 6. 风险与关注点

- 模式双开关独立：Go 的 `OPSCOUT_COLLECTOR_MODE` 和 Java 的 `openscout.mock-agent` 各自控制各自侧的模式，四种组合均有效但需明确文档说明。
- 真实模式 N+1 问题：search 返回 N 个 repo 后对前 5 个逐个调用 getReadme，在低 Token 或无 Token 场景下可能快速耗尽限额。默认限制前 5 个。
- GitHub Token 安全：Token 只通过 `GITHUB_TOKEN` 环境变量传入，不写入任何配置文件、日志、Trace 或数据库。Go 日志和 Java Trace 中的 `Authorization` header 继续受既有脱敏规则保护。
- Go 错误响应变更兼容：错误从 `{"message":"..."}` 迁移到 `{"error":"...","code":"...","retryAfter":0}` 后，Java 侧需先尝试解析新结构，失败时 fallback 解析 `message` 字段。
- 无 Token 真实模式：Go 在 `GITHUB_TOKEN` 为空时仍可调用 GitHub API，但限流更严格（60 req/h）；Java 应在日志中提示 "GitHub token not configured, expect strict rate limiting"。
- 单个 README 缺失：部分仓库无 README 或 README 获取失败时，仅跳过该 repo 的 README 增强，不影响评分和回答。
- `/api/agent/traces/{traceId}` 仍是本地排障接口，不应公网暴露。

## 7. 测试策略

- P0：`cd openscout-repo-collector && go test ./...`，确保 mock 路径和新错误类型不破坏现有 Go 测试。
- P0：`cd openscout-repo-collector && go vet ./...`，确保代码无明显问题。
- P0：`cd openscout-agent-server && mvn test`，确保默认 mock 模式下现有 Java 测试全通过。
- P0：`docker compose -f deploy/docker-compose.yml config`，确认 MySQL/Redis 配置不变。
- P1：Go mock 模式 + Java 真实模式 curl 验证：Go 启动 `OPSCOUT_COLLECTOR_MODE=mock`，Java 启动 `OPSCOUT_MOCK_AGENT=false`，调用 `/api/agent/ask` 返回 200 + traceId。
- P1：Go 不可用验证：停止 Go，调用 Java 真实模式 `/api/agent/ask`，确认返回 502 和失败 traceId。
- P1：Go 真实模式 + 无 Token 验证：Go 启动 `OPSCOUT_COLLECTOR_MODE=github` 但不设 `GITHUB_TOKEN`，调用 Go `/api/repos/search`，确认返回合理限流错误（403 或 rate limit 消息）。
- P2：Go 真实模式 + 有 Token 验证：配置 `GITHUB_TOKEN`，调用 Go `/api/repos/search?keyword=spring&mode=github`，确认返回真实 GitHub 搜索结果。

## 8. 待澄清

- [x] 阶段 5 是否只做 Go 侧完善而不碰 Java。自主决策：Go 侧和 Java 侧均在本次完成；Go 侧已有 80% 代码，Java 侧补齐调用能力和编排。
- [x] 阶段 5 是否混入 Redis adapter 或 Spring AI DeepSeek。自主决策：不混入，保持后续阶段。
- [x] Java 真实模式是否复用 `MockAgentService` 或新建 `RealAgentService`。自主决策：复用 `MockAgentService`，通过 `openscout.mock-agent` 开关分支，减少新增类。
- [x] ETag/304 是否在本次实现。自主决策：P1 可选项，不阻塞交付，视时间决定。
- [ ] `MockAgentService` 在真实模式下是否需要改名或重构。建议暂不改名，避免破坏既有测试和 Spring Bean 注入。

当前无阻塞 `/apply` 的待澄清项。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| change id | `openscout-github-real-api` | `openscout-real-github-api` | 与分支 `feature/04-github-real-api` 对齐 | N/A |
| Go 错误结构 | 自定义 `GitHubError` 结构体 | `fmt.Errorf` + `%w` wrapping | 需要携带结构字段（Code、RetryAfter）供 API 层序列化 | Go 侧新增 `errors.go`，Router 侧返回 JSON |
| Java 异常层级 | `CollectorException` 基类 + 3 个子类 | 统一 `AgentCallException` | 不同错误类型需要不同用户提示和重试策略 | `Controller` 和 `MockAgentService` 的异常处理需要更新 |
| Java Agent 真实模式 | 复用 `MockAgentService` 加分支 | 新建 `RealAgentService` | 减少类数量，评分和 Trace 逻辑完全可复用 | `MockAgentService` 方法变长，需提取 helper |
| 模式开关 | Go `OPSCOUT_COLLECTOR_MODE` + Java `openscout.mock-agent` 双开关独立 | 单一开关控制两端 | 两个服务可独立部署和测试；四种组合均有清晰语义 | 文档需明确说明两种开关的职责 |
| README 获取上限 | 前 5 个 repo | 不限制或全部获取 | 控制 GitHub API 调用次数，避免无 Token 时快速耗尽 | 评分可能因缺少 README 数据而不完整 |
| Java 错误解析 | 尝试新 `ErrorResponse` 结构，fallback 到 `message` | 只解析新结构 | 向后兼容 Go 旧版响应 | `CollectorClient` 错误解析需双路径 |

## 10. 确认记录

- 确认时间：2026-05-30。
- 确认人：用户通过 `ExitPlanMode` 批准实施计划。
- 确认范围：从 `main` 创建 `feature/04-github-real-api`，并为阶段 5 创建 GitHub 真实 API 集成 proposal；不自动进入 `/apply`。
- `/apply` 完成时间：2026-05-30。全部 6 个 Task 实现完成，Go/Java 自动化测试通过，文档和 code_copilot 制品同步。
- `/achieve` 完成时间：2026-05-30。阶段 5 知识已沉淀到 `code_copilot/knowledge/index.md`，spec 归档为 `done`。
