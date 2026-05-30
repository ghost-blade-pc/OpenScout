# 任务拆分 - OpenScout MVP 基础骨架与核心推荐闭环

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已检查工作区状态，当前 Git 仓库有效，分支为 `main`
- [x] 已确认本地验证命令或替代验证方式

## Task 1: 创建 monorepo 基础结构

- **目标**：创建 Java 服务、Go 服务、部署和文档目录，保证结构与方案一致。
- **层级/模块**：项目骨架 / 配置 / 文档
- **涉及文件**：
  - `openscout-agent-server/`：新增 Java Spring Boot 服务骨架。
  - `openscout-repo-collector/`：新增 Go Gin 服务骨架。
  - `deploy/docker-compose.yml`：新增 MySQL + Redis 本地依赖。
  - `deploy/init.sql`：新增基础表结构。
  - `README.md`：新增启动说明。
- **依赖**：无。
- **风险标记**：配置 / 数据库。
- **实现要点**：
  - Java 和 Go 服务先保证能启动。
  - MySQL/Redis 端口、账号密码使用本地演示配置和环境变量占位符。
  - 不引入复杂业务实现。
- **验收标准**：
  - 目录结构存在。
  - Docker Compose 能启动 MySQL/Redis。
  - README 有本地启动步骤。
- **验证命令**：
  ```bash
  docker compose -f deploy/docker-compose.yml up -d
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`openscout-agent-server/`、`openscout-repo-collector/`、`deploy/docker-compose.yml`、`deploy/init.sql`、`README.md`、`.env.example`
  - 验证结果：`docker compose -f deploy/docker-compose.yml config` 初始阶段通过；Java `mvn test` 通过；Go 工具链、Go 单测和 mock 服务启动已在阶段 2/3 回填验证。

## Task 2: Go Collector mock 接口

- **目标**：提供稳定 mock 数据，支撑端到端演示。
- **层级/模块**：Go 入口层 / 服务层
- **涉及文件**：
  - `openscout-repo-collector/cmd/server/`：新增启动入口。
  - `openscout-repo-collector/internal/api/`：新增 router/handler。
  - `openscout-repo-collector/internal/service/`：新增 mock repo service。
  - `openscout-repo-collector/go.mod`：新增 Go module，module path 为 `github.com/LiPeicheng/openscout-repo-collector`。
- **依赖**：Task 1。
- **风险标记**：无。
- **实现要点**：
  - `GET /api/repos/mock` 返回固定 repo JSON。
  - 预留 search/profile/readme/batch-profile 路由。
  - handler 输出统一 JSON 结构。
- **验收标准**：
  - Go 服务启动。
  - curl mock 接口返回固定项目数据。
- **验证命令**：
  ```bash
  go test ./...
  curl http://localhost:8081/api/repos/mock
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`openscout-repo-collector/cmd/server/main.go`、`internal/api/router.go`、`internal/service/`、`internal/model/`、`go.mod`
  - 验证结果：初始 apply 时本机未安装 Go；后续阶段 2 已补齐本地 Go 工具链并通过 `gofmt`、`go test ./...`；阶段 3 已启动 Go 服务并通过 `/health`、`/api/repos/mock` curl 验证。

## Task 3: Java Agent Server mock 链路

- **目标**：Java 服务提供 `POST /api/agent/ask`，调用 Go mock 接口并返回推荐结果。
- **层级/模块**：Java 入口层 / 应用服务 / HTTP Client
- **涉及文件**：
  - `openscout-agent-server/pom.xml`：新增 Java 17、Spring Boot、Spring AI、MyBatis-Plus 依赖。
  - `openscout-agent-server/src/main/java/.../Application.java`：启动入口。
  - `controller/`：新增 Agent API。
  - `client/`：新增 Go Collector client。
  - `agent/`：新增 mock agent 编排服务。
- **依赖**：Task 2。
- **风险标记**：外部接口 / 配置。
- **实现要点**：
  - Collector base URL 通过配置注入。
  - Go 调用失败时返回清晰错误并记录 Trace。
  - 第一阶段先使用 mock Agent 编排，不强制接真实 Spring AI Tool Calling。
- **验收标准**：
  - Java 服务启动。
  - 调用 `/api/agent/ask` 能返回 mock 推荐项目。
- **验证命令**：
  ```bash
  mvn test
  curl -X POST http://localhost:8080/api/agent/ask -H 'Content-Type: application/json' -d '{"question":"我想学习 Spring AI Agent"}'
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`openscout-agent-server/pom.xml`、`OpenScoutAgentApplication.java`、`controller/AgentController.java`、`client/CollectorClient.java`、`agent/`、`config/RestClientConfig.java`
  - 验证结果：`cd openscout-agent-server && mvn test` 通过。

## Task 4: 基础评分与 evidence

- **目标**：实现可测试的规则评分，不依赖 LLM 直接打分。
- **层级/模块**：Java 领域/业务层
- **涉及文件**：
  - `openscout-agent-server/src/main/java/.../scoring/`：新增评分服务和值对象。
  - `openscout-agent-server/src/test/java/.../scoring/`：新增评分单测。
- **依赖**：Task 3。
- **风险标记**：模型输出可信度。
- **实现要点**：
  - 活跃度、文档完整度、技术匹配度、学习友好度、简历价值分开计算。
  - 每个得分项返回 evidence。
  - 总分范围可控。
- **验收标准**：
  - 固定 mock repo 输入得到稳定评分。
  - LLM 解释不能改写分数。
- **验证命令**：
  ```bash
  mvn test -Dtest=*Score*
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`openscout-agent-server/src/main/java/com/openscout/scoring/`、`openscout-agent-server/src/test/java/com/openscout/scoring/ProjectScoreServiceTest.java`
  - 验证结果：`cd openscout-agent-server && mvn test` 通过，包含评分单测。

## Task 5: 基础 Agent Trace

- **目标**：记录端到端请求的关键执行过程，体现 Agent 工程化。
- **层级/模块**：Java trace / 数据库
- **涉及文件**：
  - `deploy/init.sql`：新增 `agent_trace` 表。
  - `openscout-agent-server/src/main/java/.../trace/`：新增 Trace service/model。
  - `openscout-agent-server/src/main/java/.../repository/`：新增持久化或内存实现。
- **依赖**：Task 3、Task 4。
- **风险标记**：安全 / 数据库。
- **实现要点**：
  - 保存用户问题、工具名、工具入参摘要、返回摘要、评分摘要、最终回答、耗时、异常。
  - 不保存 token、完整 README、完整 prompt 或大对象。
  - MVP 可先请求级 JSON 字段，后续再拆 step 表。
- **验收标准**：
  - `/api/agent/ask` 返回 traceId。
  - Trace 中能查到本次工具调用摘要。
  - Trace 不包含敏感配置。
- **验证命令**：
  ```bash
  mvn test -Dtest=*Trace*
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`deploy/init.sql`、`openscout-agent-server/src/main/java/com/openscout/trace/`、`AgentController.java`、`MockAgentService.java`
  - 验证结果：`cd openscout-agent-server && mvn test` 通过，包含 Trace 脱敏/截断单测。

## Task 6: Go 真实 GitHub API 可选链路

- **目标**：在 mock 链路稳定后，提供可选真实 GitHub API 采集能力。
- **层级/模块**：Go 基础设施 / 外部 API / 缓存 / 并发
- **涉及文件**：
  - `internal/github/`：新增 GitHub client。
  - `internal/cache/`：新增进程内 TTL cache；Redis adapter 延后。
  - `internal/limiter/`：新增 rate limiter。
  - `internal/worker/`：新增 worker pool。
  - `internal/api/`：补齐 search/profile/readme/batch-profile handler。
- **依赖**：Task 2。
- **风险标记**：外部接口 / 限流 / 并发 / 缓存 / 敏感配置。
- **实现要点**：
  - `GITHUB_TOKEN` 可选配置。
  - HTTP client timeout 必须设置。
  - 403/429 不盲目重试。
  - batch-profile 部分失败不中断整体。
  - 进程内缓存 TTL 固定为 10 分钟；Redis TTL 配置延后。
- **验收标准**：
  - 不配置 Token 时可用 mock 模式。
  - 配置 Token 时可调用真实 search/profile/readme。
  - batch-profile 返回成功项和失败项。
- **验证命令**：
  ```bash
  go test ./...
  ```
- **完成记录**：
  - 状态：部分完成，真实 GitHub API 完整验证和 Redis adapter 延后
  - 实际改动文件：`internal/github/client.go`、`internal/cache/memory.go`、`internal/limiter/limiter.go`、`internal/worker/pool.go`、`internal/api/router.go`
  - 验证结果：后续阶段 2 已补齐 Go 工具链并通过 `go test ./...`；真实 GitHub API 未带 Token 实测，留给后续 `feature/04-github-real-api`。

## Task 7: README 与 Demo Case

- **目标**：交付可运行、可演示、能讲清楚的项目文档。
- **层级/模块**：文档
- **涉及文件**：
  - `README.md`：启动说明、架构、环境变量、API 示例、限制说明。
  - `docs/api-contract.md`：接口契约。
  - `docs/demo-cases.md`：至少 3 个演示输入输出。
  - `docs/resume.md`：简历描述和面试讲解点。
- **依赖**：Task 1-6。
- **风险标记**：文档准确性。
- **实现要点**：
  - 明确 mock 模式和真实 GitHub API 模式。
  - 明确 GitHub 限流和 Token。
  - 演示命令可复制执行。
- **验收标准**：
  - README 能指导新用户本地跑通。
  - Demo Case 与实际接口一致。
- **验证命令**：
  ```bash
  docker compose -f deploy/docker-compose.yml config
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`README.md`、`docs/api-contract.md`、`docs/demo-cases.md`、`docs/resume.md`
  - 验证结果：README 中命令已与当前项目路径和接口对齐；阶段 3 已启动 Go/Java 长驻服务并完成 mock ask、Trace 查询和 Collector 不可用 502 场景验证。

## 变更摘要

- **总文件数**：应用/部署/文档源码文件 39 个，不含 Maven `target/`。
- **新增文件**：Java 服务、Go Collector、部署脚本、根级 README、`.env.example`、docs 文档、change 文档同步。
- **修改文件**：`spec.md`、`tasks.md`、`test-spec.md`、`log.md`、`code_copilot/rules/project-context.md`。
- **删除文件**：无。
- **Spec-Plan 偏差记录**：第一阶段 Go 缓存采用进程内 TTL cache，未接 Redis adapter；Java Trace 采用内存实现，数据库表先预留。
- **未完成项**：真实 GitHub API 未带 Token 实测；Redis adapter、Spring AI Tool Calling 和 MyBatis-Plus Mapper 持久化未在本 change 完成。
- **遗留风险**：真实 GitHub API 限流、Redis adapter、Spring AI Tool Calling 和持久化 Mapper 需要后续 change 补齐。
