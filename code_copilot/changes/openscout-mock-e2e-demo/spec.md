# OpenScout mock 端到端演示验证
> status: done
> created: 2026-05-30
> complexity: 中等

## 1. 背景与目标

根据 `项目实施进度.md` 的阶段 3，当前分支 `feature/02-mock-e2e-demo` 的目标不是新增业务能力，而是把已有 Java Agent Server 与 Go Repo Collector 的 mock 链路真正跑起来，并形成可复现的演示验证记录。

完成后应能证明：

- Go Collector 的 `GET /api/repos/mock` 可通过 curl 返回稳定 mock 项目列表。
- Java Agent Server 的 `POST /api/agent/ask` 可调用 Go mock 接口，返回推荐项目、规则评分、`traceId` 和回答。
- Java 的 `GET /api/agent/traces/{traceId}` 可查询本次请求的 Trace 摘要。
- 验证命令、样例请求、结果摘要和失败处理被记录到 change 文档与进度文档中。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 阶段 3 mock 端到端演示。
- 调用方向：用户 curl -> Java HTTP API -> Java `MockAgentService` -> Java `CollectorClient` -> Go HTTP API -> mock repo 数据；随后 curl 查询 Java Trace。
- 是否涉及高风险项：是。
- 高风险类型：跨服务 HTTP 调用、配置、Trace 脱敏、本地长驻进程、端口占用。

### 1.2 范围裁剪

本次包含：

- 启动或校验本地依赖：按 README 路径保留 MySQL/Redis Docker Compose 步骤，但本阶段核心验收不依赖持久化落库。
- 启动 Go Collector mock 服务并验证 `/health`、`/api/repos/mock`。
- 启动 Java Agent Server 并验证 `/api/agent/ask`。
- 使用 `/api/agent/traces/{traceId}` 查询上一步返回的 Trace。
- 必要时只修复阻塞演示的最小问题，例如端口、配置、启动命令或响应契约不一致。
- 同步 `code_copilot/changes/openscout-mock-e2e-demo/` 文档和 `项目实施进度.md`。

本次不包含：

- MyBatis-Plus Mapper 持久化改造。
- 真实 GitHub API 行为完善、Token 策略或 ETag。
- Spring AI + DeepSeek V4 Pro 真实 Agent 编排。
- Redis adapter。
- 新增前端页面、权限系统或生产部署方案。

后续可能拆分：

- `openscout-mybatis-persistence`：项目画像、分析结果和 Trace 持久化。
- `openscout-github-real-api`：真实 GitHub API、限流、错误处理和缓存。
- `openscout-spring-ai-deepseek-agent`：Spring AI 与 DeepSeek Agent 编排。

## 2. Research Findings

### 2.1 相关入口与链路

- 项目进度：`项目实施进度.md` 第 4 节将阶段 3 定义为 `feature/02-mock-e2e-demo`，验收标准是 Go `/api/repos/mock`、Java `/api/agent/ask` curl 验证通过，Trace 可查询。
- Java 入站 API：`openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java` 提供 `POST /api/agent/ask` 和 `GET /api/agent/traces/{traceId}`。
- Java 编排服务：`openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java` 校验问题、创建 Trace、调用 Collector、执行评分、返回 `AgentAskResponse`。
- Java 出站 Client：`openscout-agent-server/src/main/java/com/openscout/client/CollectorClient.java` 调用 `${openscout.collector-base-url}/api/repos/mock?keyword=...`。
- Java 配置：`openscout-agent-server/src/main/resources/application.yml` 默认 `openscout.collector-base-url` 为 `http://localhost:8081`，Java 端口默认 `8080`。
- Java Trace：`openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` 使用内存 `ConcurrentHashMap` 存储 Trace，并对 token、api key、authorization、password、secret 字段做脱敏和长度截断。
- Go 入站 API：`openscout-repo-collector/internal/api/router.go` 提供 `/health`、`/api/repos/mock`、`/api/repos/search`、`/api/repos/{owner}/{repo}/profile`、`/api/repos/{owner}/{repo}/readme`、`/api/repos/batch-profile`。
- Go 启动入口：`openscout-repo-collector/cmd/server/main.go` 默认端口 `8081`，默认 `OPSCOUT_COLLECTOR_MODE=mock`。
- 本地工具：`scripts/use-local-tools.sh` 将 `.tools/go/bin`、项目内 `GOCACHE` 和 `GOPATH` 加入当前 shell，可用于本地 Go 验证。
- 文档契约：`README.md`、`docs/api-contract.md`、`docs/demo-cases.md` 已记录 mock ask、Trace 查询和 Go mock curl 示例。

### 2.2 现有实现摘要

- 第一阶段已创建 Java、Go、Docker Compose、README、docs 和基础 change 文档。
- 第二阶段已在 `项目实施进度.md` 标记 Go 工具链配置、`gofmt` 和 `go test ./...` 已完成。
- 当前阶段已有源码路径支撑 mock 端到端链路，但尚未在阶段 3 分支记录长驻服务启动和 curl 验证结果。
- propose 前 `项目实施进度.md` 的“当前 Git 状态”仍写 `main`，与实际分支 `feature/02-mock-e2e-demo` 不一致；本次 propose 已同步为阶段 3 分支。

### 2.3 发现的问题

- 端到端 curl 依赖两个长驻服务同时运行，执行时需要明确进程管理、端口占用和停止方式。
- Java Trace 当前是内存实现，Trace 查询只在同一个 Java 进程生命周期内有效；重启后 Trace 不保留。
- 本阶段验收是 mock 链路，不应把真实 GitHub API、MySQL 持久化、Spring AI 接入混入验收口径。
- README 建议先启动 MySQL/Redis，但当前 mock Trace 是内存实现，Docker 依赖更多是保持本地启动路径一致，不是 Trace 查询的必要条件。

### 2.4 风险初判

- 端口风险：`8080`、`8081`、MySQL `3306`、Redis `6379` 可能被占用。
- 配置风险：Java 默认 Collector 地址为 `http://localhost:8081`，如果 Go 服务端口变化，需要同步 `OPSCOUT_COLLECTOR_BASE_URL`。
- Trace 风险：Trace 只保存摘要，验证时不能要求查询完整 README、完整 prompt 或敏感配置。
- 进程风险：`mvn spring-boot:run` 和 `go run ./cmd/server` 是长驻进程，验证完成后需要记录停止方式，避免残留影响后续阶段。

## 3. 功能点

- [x] 功能 1：确认本地基础命令可用，包含 Java `mvn test`、Go `go test ./...` 和 Docker Compose config。Java、Go 和 Docker Compose config 均已验证通过。
- [x] 功能 2：启动 Go Collector mock 服务，验证 `/health` 和 `/api/repos/mock`。
- [x] 功能 3：启动 Java Agent Server，验证 `/api/agent/ask` 可返回推荐结果、评分和 `traceId`。
- [x] 功能 4：使用返回的 `traceId` 查询 `/api/agent/traces/{traceId}`，确认包含工具调用摘要、评分摘要、状态和耗时。
- [x] 功能 5：将实际命令、结果摘要、异常处理、未完成项同步到 `tasks.md`、`test-spec.md`、`log.md` 和 `项目实施进度.md`。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| 配置 | `OPSCOUT_COLLECTOR_BASE_URL` | 必要时用于指定 Java 调 Go 的地址，默认 `http://localhost:8081` | 兼容默认配置 | 删除环境变量或恢复默认 |
| 配置 | `SERVER_PORT` | 必要时用于避开 Java 端口冲突，默认 `8080` | 兼容默认配置 | 删除环境变量或恢复默认 |
| 配置 | `PORT` | 必要时用于避开 Go 端口冲突，默认 `8081` | 若修改需同步 Java Collector 地址 | 删除环境变量或恢复默认 |
| 配置 | `SPRING_AI_MODEL_*` | Spring AI 模型默认设为 `none`，避免 mock 模式无 Key 启动失败；后续真实模型接入时显式启用 | mock 模式兼容；真实模型需设置 `SPRING_AI_MODEL_CHAT=openai` | 恢复默认模型配置 |
| 数据 | Java 内存 Trace | 验证过程中新增进程内 Trace 记录 | 重启即清空 | 无需迁移 |
| 文档 | `项目实施进度.md` | 更新阶段 3 状态、验证结果和变更记录 | 文档变更 | 按 Git diff 回滚 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/repos/mock` | GET | 可选 `keyword` query | `items` 项目列表 | 本地演示无鉴权 | 既有接口，只验证 |
| `/api/agent/ask` | POST | JSON：`question` 或 `goal`，可选 `mode` | `traceId`、`answer`、`recommendations`、`latencyMs` | MVP 本地演示无鉴权 | 既有接口，只验证 |
| `/api/agent/traces/{traceId}` | GET | path：`traceId` | `AgentTrace` 或 404 | 仅本地演示和排障 | 既有接口，只验证 |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector | `GET /api/repos/mock?keyword=...` | Java `CollectorClient.fetchMockRepos` 拼接 keyword | `RepoListResponse` | 当前基于 `RestClient`，本阶段只验证默认行为 | Go 不可用时 Java 返回 502 和 `traceId` |

### 5.3 MQ/Event

本阶段不涉及 MQ/Event。

## 6. 风险与关注点

- 不把 mock 端到端验证结果描述成真实 GitHub API、真实模型或持久化链路已完成。
- Trace 查询只验证当前 Java 进程内存数据，不能承诺跨进程、跨重启或数据库查询。
- 如端口被占用，可以使用临时端口，但必须同步记录实际端口和对应环境变量。
- 如 Docker 不可用，本阶段仍可先验证 Java/Go HTTP 链路，但需要把 Docker blocker 记录到 `log.md`。
- curl 输出只记录摘要和关键字段，不复制完整大响应到长期文档。

## 7. 测试策略

- P0：`docker compose -f deploy/docker-compose.yml config`、`cd openscout-repo-collector && go test ./...`、`cd openscout-agent-server && mvn test`。
- P0：启动 Go 服务后 curl `/health` 与 `/api/repos/mock`。
- P0：启动 Java 服务后 curl `/api/agent/ask`，检查 HTTP 200、非空 `traceId`、非空 `recommendations`、评分字段。
- P0：使用返回的 `traceId` curl `/api/agent/traces/{traceId}`，检查 HTTP 200、`status=SUCCESS`、`toolCalls` 包含 `repo_search_mock`。
- P1：模拟 Go Collector 不可用时调用 Java `/api/agent/ask`，确认返回 502 和错误摘要；是否执行视时间和进程状态决定。

## 8. 待澄清

- [x] 阶段 3 完成后是否立即把 `openscout-mvp-foundation` 归档，还是等 mock e2e 验证结果回填后统一归档。已确认：阶段 3 完成后归档 `openscout-mvp-foundation`。

当前无阻塞 `/apply` 的待澄清项。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| 本阶段目标 | 以真实启动和 curl 验证为主 | 新增业务能力 | 与 `项目实施进度.md` 阶段 3 一致 | 代码改动应尽量少 |
| 依赖启动 | 优先按 README 启动 MySQL/Redis、Go、Java | 只启动 Go/Java | 保持演示路径完整；但验收核心仍是 HTTP mock 链路 | Docker 不可用时可记录 blocker 并继续 HTTP 验证 |
| Trace 验收 | 验证内存 Trace 查询 | 本阶段接 MyBatis 持久化 | 持久化属于阶段 4 | Trace 重启丢失需写清楚 |
| API 模式 | mock 模式 | GitHub 真实模式 | 避免限流和 Token 影响演示 | 真实 API 留给阶段 5 |
| Spring AI 自动配置 | mock 模式默认关闭模型 provider | 给空 Key 或强制配置假 Key | 空 Key 会导致 OpenAI 自动配置启动失败；假 Key 容易误导真实模型状态 | 后续接 DeepSeek 时显式设置 `SPRING_AI_MODEL_CHAT=openai` |

## 10. 确认记录

- 确认时间：2026-05-30。
- 确认人：用户。
- 确认范围：`feature/02-mock-e2e-demo` 阶段 mock 端到端演示验证。
