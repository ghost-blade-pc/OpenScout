# 任务拆分 - OpenScout mock 端到端演示验证

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已读取 `code_copilot/knowledge/index.md`
- [x] 已读取 `项目实施进度.md`
- [x] 已检查工作区状态，当前分支为 `feature/02-mock-e2e-demo`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已确认本地验证命令或替代验证方式

## Task 1: 基础验证命令复核

- **目标**：确认进入端到端演示前，Java、Go 和 Docker Compose 的基础验证仍然可用。
- **层级/模块**：测试 / 本地环境
- **涉及文件**：
  - `openscout-agent-server/`：Java 单测。
  - `openscout-repo-collector/`：Go 单测。
  - `deploy/docker-compose.yml`：Compose 配置校验。
  - `scripts/use-local-tools.sh`：本地 Go 工具链入口。
- **依赖**：无。
- **风险标记**：配置 / 本地环境。
- **实现要点**：
  - 使用项目本地 Go 工具链时先执行 `source scripts/use-local-tools.sh`。
  - 不修改业务代码；如验证失败，先记录失败命令和原因。
- **验收标准**：
  - Java 单测通过。
  - Go 单测通过，或明确记录工具链/依赖 blocker。
  - Compose config 通过，或明确记录 Docker blocker。
- **验证命令**：
  ```bash
  docker compose -f deploy/docker-compose.yml config
  source scripts/use-local-tools.sh
  (cd openscout-agent-server && mvn test)
  (cd openscout-repo-collector && go test ./...)
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：无
  - 验证结果：`cd openscout-agent-server && mvn test` 通过，2 个测试 0 失败；`cd openscout-repo-collector && go test ./...` 通过；`docker compose -f deploy/docker-compose.yml config` 已在 review 后复核通过。

## Task 2: 启动并验证 Go Collector mock 服务

- **目标**：启动 Go Collector，确认 mock repo API 能返回稳定项目列表。
- **层级/模块**：Go 入口层 / HTTP API
- **涉及文件**：
  - `openscout-repo-collector/cmd/server/main.go`：Go 服务启动入口。
  - `openscout-repo-collector/internal/api/router.go`：`/health` 和 `/api/repos/mock` 路由。
  - `openscout-repo-collector/internal/service/repo_service.go`：mock repo 数据来源。
- **依赖**：Task 1 的 Go 工具链可用。
- **风险标记**：端口 / 本地长驻进程。
- **实现要点**：
  - 默认使用 `PORT=8081` 和 `OPSCOUT_COLLECTOR_MODE=mock`。
  - 如果 `8081` 被占用，换端口并在 Task 3 同步 Java `OPSCOUT_COLLECTOR_BASE_URL`。
  - 记录服务启动命令和停止方式。
- **验收标准**：
  - `GET /health` 返回健康状态。
  - `GET /api/repos/mock` 返回非空 `items`。
- **验证命令**：
  ```bash
  source scripts/use-local-tools.sh
  cd openscout-repo-collector && go run ./cmd/server
  curl http://localhost:8081/health
  curl http://localhost:8081/api/repos/mock
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：无
  - 验证结果：Go Collector 在 `8081` 端口启动成功；`GET /health` 返回 HTTP 200 和 `{"status":"UP"}`；`GET /api/repos/mock` 返回 HTTP 200，`items` 共 3 个 mock 项目。

## Task 3: 启动并验证 Java Agent mock ask

- **目标**：启动 Java Agent Server，确认 `/api/agent/ask` 能调用 Go mock 接口并返回推荐结果。
- **层级/模块**：Java 入口层 / 应用服务 / HTTP Client / 评分
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java`：`POST /api/agent/ask`。
  - `openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java`：mock Agent 编排。
  - `openscout-agent-server/src/main/java/com/openscout/client/CollectorClient.java`：调用 Go mock API。
  - `openscout-agent-server/src/main/resources/application.yml`：Java 端口和 Collector base URL。
- **依赖**：Task 2 Go Collector 正常运行。
- **风险标记**：跨服务 HTTP / 配置 / 端口。
- **实现要点**：
  - 默认 Java 端口为 `8080`。
  - 默认 Collector 地址为 `http://localhost:8081`。
  - 如果 Go 使用非默认端口，Java 启动时设置 `OPSCOUT_COLLECTOR_BASE_URL`。
- **验收标准**：
  - `POST /api/agent/ask` 返回 HTTP 200。
  - 响应包含非空 `traceId`、`answer`、`recommendations`。
  - 至少一个推荐项包含 `fullName`、`score.totalScore` 和 `reason`。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn spring-boot:run
  curl -X POST http://localhost:8080/api/agent/ask \
    -H 'Content-Type: application/json' \
    -d '{"question":"我想一周内学习 Spring AI Agent，帮我找几个适合学习的开源项目"}'
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`openscout-agent-server/src/main/resources/application.yml`、`.env.example`、`README.md`
  - 验证结果：首次启动失败，原因是 Spring AI OpenAI 自动配置在无 Key 时创建 `OpenAiAudioSpeechModel`；已将 Spring AI 模型 provider 默认设为 `none` 并保留后续显式开启配置。修复后 `mvn test` 通过，Java 服务在 `8080` 启动成功；`POST /api/agent/ask` 返回 HTTP 200，`traceId=3df5fa3e-2ba6-4305-9292-f837680f16e3`，推荐项目 3 个，第一推荐 `spring-projects/spring-ai`，评分 84。

## Task 4: 验证 Trace 查询

- **目标**：使用 Task 3 返回的 `traceId` 查询 Java 内存 Trace，确认演示链路可复盘。
- **层级/模块**：Java Trace / HTTP API
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java`：`GET /api/agent/traces/{traceId}`。
  - `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java`：Trace 内存存储和脱敏。
  - `openscout-agent-server/src/main/java/com/openscout/trace/AgentTrace.java`：Trace 响应结构。
- **依赖**：Task 3 返回有效 `traceId`，Java 进程未重启。
- **风险标记**：Trace 脱敏 / 内存生命周期。
- **实现要点**：
  - 查询同一个 Java 进程内刚生成的 `traceId`。
  - 只要求摘要字段存在，不要求完整 README、prompt 或敏感配置。
- **验收标准**：
  - Trace 查询返回 HTTP 200。
  - 响应包含 `status=SUCCESS`、`toolCalls`、`scoreSummary`、`latencyMs`。
  - `toolCalls` 中包含 `repo_search_mock`。
- **验证命令**：
  ```bash
  curl http://localhost:8080/api/agent/traces/<traceId>
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：无
  - 验证结果：`GET /api/agent/traces/3df5fa3e-2ba6-4305-9292-f837680f16e3` 返回 HTTP 200；`status=SUCCESS`；`toolCalls` 包含 `repo_search_mock`；`scoreSummary=spring-projects/spring-ai=84, langchain4j/langchain4j=66, gin-gonic/gin=60`；无错误字段。

## Task 5: 同步阶段文档和验证记录

- **目标**：把本阶段实际执行结果沉淀为可复现记录。
- **层级/模块**：文档 / code_copilot
- **涉及文件**：
  - `code_copilot/changes/openscout-mock-e2e-demo/spec.md`
  - `code_copilot/changes/openscout-mock-e2e-demo/tasks.md`
  - `code_copilot/changes/openscout-mock-e2e-demo/test-spec.md`
  - `code_copilot/changes/openscout-mock-e2e-demo/log.md`
  - `项目实施进度.md`
- **依赖**：Task 1-4。
- **风险标记**：文档准确性。
- **实现要点**：
  - 只记录关键命令、状态码、关键字段和失败原因，不粘贴大段响应。
  - 明确哪些结果属于 mock 链路，哪些不代表真实 GitHub API、真实模型或持久化完成。
- **验收标准**：
  - `tasks.md` 对应 task 状态更新。
  - `test-spec.md` 记录实际执行结果。
  - `log.md` 记录命令、结果、偏差和遗留问题。
  - `项目实施进度.md` 阶段 3 状态和变更记录同步。
- **验证命令**：
  ```bash
  rg -n "feature/02-mock-e2e-demo|openscout-mock-e2e-demo|Trace" 项目实施进度.md code_copilot/changes/openscout-mock-e2e-demo
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`code_copilot/changes/openscout-mock-e2e-demo/spec.md`、`tasks.md`、`test-spec.md`、`log.md`、`项目实施进度.md`、`code_copilot/changes/openscout-mvp-foundation/spec.md`、`code_copilot/changes/openscout-mvp-foundation/log.md`、`code_copilot/knowledge/index.md`
  - 验证结果：阶段 3 验证结果已同步；按用户确认，`openscout-mvp-foundation` 已在阶段 3 完成后归档为 `done`。

## 变更摘要

- **总文件数**：13 个
- **新增文件**：`code_copilot/changes/openscout-mock-e2e-demo/spec.md`、`tasks.md`、`test-spec.md`、`log.md`
- **修改文件**：`openscout-agent-server/src/main/resources/application.yml`、`.env.example`、`README.md`、`项目实施进度.md`、`code_copilot/changes/openscout-mvp-foundation/spec.md`、`tasks.md`、`test-spec.md`、`log.md`、`code_copilot/knowledge/index.md`、本 change 文档
- **删除文件**：无
- **Spec-Plan 偏差记录**：为满足 mock 模式无 Key 启动，新增 Spring AI 模型 provider 默认关闭配置。
- **未完成项**：无；Trace 持久化、真实 GitHub API、Redis adapter 和 Spring AI DeepSeek 接入属于后续阶段。
- **遗留风险**：Trace 仍为内存实现，重启 Java 后不可查询历史 Trace；MyBatis 持久化属于阶段 4。
