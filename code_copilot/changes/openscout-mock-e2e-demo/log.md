# 执行日志 - OpenScout mock 端到端演示验证

## 基本信息

- change：`openscout-mock-e2e-demo`
- status：done
- created：2026-05-30
- last_updated：2026-05-30

## Research 记录

- 已读取 `code_copilot/README.md`、`code_copilot/rules/project-context.md`、`code_copilot/rules/domain-rules.md`、`code_copilot/rules/coding-style.md`、`code_copilot/rules/security.md`、`code_copilot/knowledge/index.md` 和 `code_copilot/agents/copilot-prompt.md`。
- 已读取 `项目实施进度.md`，阶段 3 为 `feature/02-mock-e2e-demo`，目标是跑通 Java 调 Go 的 mock 端到端演示，验收标准是 Go `/api/repos/mock` 和 Java `/api/agent/ask` curl 验证通过，Trace 可查询。
- 已确认当前分支为 `feature/02-mock-e2e-demo`，工作区在 propose 前无未提交 diff。
- Java `AgentController` 已提供 `POST /api/agent/ask` 和 `GET /api/agent/traces/{traceId}`。
- Java `MockAgentService` 已通过 `CollectorClient.fetchMockRepos` 调用 Go mock repo，并记录 `repo_search_mock` 工具调用摘要。
- Java `TraceService` 当前为内存 Trace 实现，支持脱敏和长度截断，Trace 重启后不保留。
- Go `router.go` 已提供 `/health` 和 `/api/repos/mock`；Go `main.go` 默认端口 `8081`，默认 mock 模式。
- README、`docs/api-contract.md` 和 `docs/demo-cases.md` 已有 mock ask、Trace 查询和 Go mock API 示例。

## 执行记录

| 时间 | 动作 | 文件 | 结果 |
|---|---|---|---|
| 2026-05-30 | 创建阶段 3 propose | `code_copilot/changes/openscout-mock-e2e-demo/` | 已创建 spec、tasks、test-spec、log |
| 2026-05-30 | 同步阶段状态 | `项目实施进度.md` | 已在 propose 阶段更新当前分支和阶段 3 状态 |
| 2026-05-30 | 同步归档确认 | `spec.md`、`tasks.md`、`log.md`、`项目实施进度.md` | 已确认阶段 3 完成后归档 `openscout-mvp-foundation` |
| 2026-05-30 | 执行基础验证 | Java/Go/Docker | Java `mvn test` 通过；Go `go test ./...` 通过；Docker config 已在 review 后复核通过 |
| 2026-05-30 | 启动并验证 Go Collector | `openscout-repo-collector/` | `/health` 和 `/api/repos/mock` 均返回 HTTP 200 |
| 2026-05-30 | 修复 Java 无 Key 启动阻塞 | `application.yml`、`.env.example`、`README.md` | Spring AI 模型 provider 默认设为 `none`，避免 mock 模式无 Key 启动失败 |
| 2026-05-30 | 验证 Java ask 和 Trace | `openscout-agent-server/` | `/api/agent/ask` 返回 HTTP 200；Trace 查询返回 HTTP 200，包含 `repo_search_mock` |
| 2026-05-30 | 验证 Collector 不可用场景 | Java ask API | 停止 Go 后 Java 返回 HTTP 502，响应含失败 traceId 和 `Connection refused` 摘要 |
| 2026-05-30 | 停止长驻服务 | Go/Java 进程 | 已停止并确认 `8080`、`8081` 连接拒绝 |
| 2026-05-30 | review 后修正文档证据 | `spec.md`、`tasks.md`、`test-spec.md`、`log.md`、`项目实施进度.md` | Docker Compose config 已复核通过，移除过期 blocker 和未收口状态 |
| 2026-05-30 | achieve 归档沉淀 | `spec.md`、`log.md`、`code_copilot/knowledge/index.md` | change 状态标记为 `done`，阶段 3 可复用结论已写入知识索引 |

## 决策记录

- change id 使用 `openscout-mock-e2e-demo`，对应分支 `feature/02-mock-e2e-demo`。
- 本阶段优先做真实本地启动和 curl 验证，不主动扩大到 MyBatis 持久化、真实 GitHub API 或 Spring AI DeepSeek 接入。
- Docker Compose 步骤保留为本地启动路径的一部分，但本阶段核心验收是 Go/Java HTTP mock 链路和内存 Trace 查询。
- 如果端口冲突，允许临时调整端口，但必须同步记录 `PORT`、`SERVER_PORT` 和 `OPSCOUT_COLLECTOR_BASE_URL`。
- 阶段 3 完成后归档 `openscout-mvp-foundation`，不在当前 propose 阶段提前归档。
- Spring AI 1.1.6 的 OpenAI 自动配置在 `spring.ai.model.*` 缺失时默认匹配 `openai`，mock 模式应默认关闭模型 provider；后续真实 DeepSeek 接入时再显式设置 `SPRING_AI_MODEL_CHAT=openai`。

## 验证记录

- `cd openscout-agent-server && mvn test` 通过：2 个测试，0 失败，0 错误。
- `cd openscout-repo-collector && go test ./...` 通过：`internal/cache`、`internal/service` 测试通过，其余包无测试文件。
- `docker compose -f deploy/docker-compose.yml config` 通过：MySQL/Redis 服务配置可解析。
- `curl http://localhost:8081/health` 通过：HTTP 200，`status=UP`。
- `curl http://localhost:8081/api/repos/mock` 通过：HTTP 200，返回 3 个 mock 项目。
- `curl -X POST http://localhost:8080/api/agent/ask ...` 通过：HTTP 200，`traceId=3df5fa3e-2ba6-4305-9292-f837680f16e3`，推荐项目 3 个，第一推荐 `spring-projects/spring-ai`，评分 84。
- `curl http://localhost:8080/api/agent/traces/3df5fa3e-2ba6-4305-9292-f837680f16e3` 通过：HTTP 200，`status=SUCCESS`，`toolCalls` 包含 `repo_search_mock`，`scoreSummary` 为 `spring-projects/spring-ai=84, langchain4j/langchain4j=66, gin-gonic/gin=60`。
- 停止 Go 后调用 Java `/api/agent/ask` 通过异常验证：HTTP 502，失败 `traceId=def69eb9-1bae-4747-88f2-d770fb7873b6`，错误摘要为 `Connection refused`。

## 遗留问题

- Trace 当前是内存实现，重启 Java 后无法查询历史 Trace；持久化属于阶段 4。
- 当前无阻塞阶段 3 验收的遗留问题。

## 知识沉淀

- 阶段 3 的可复现 mock 链路为：用户 curl -> Java `/api/agent/ask` -> `MockAgentService` -> `CollectorClient` -> Go `/api/repos/mock` -> Java 内存 Trace 查询。
- 本阶段能支撑的结论是 mock 端到端演示通过，不代表真实 GitHub API、真实模型调用或 MyBatis 持久化已经完成。
- Spring AI 1.1.6 在 OpenAI starter 存在且 `spring.ai.model.*` 缺省时会尝试创建 OpenAI 模型 bean；mock 模式应默认设置 `SPRING_AI_MODEL_*` 为 `none`，真实 DeepSeek chat 接入时再显式设置 `SPRING_AI_MODEL_CHAT=openai`。
- 端到端验证最小证据组合为：`mvn test`、`go test ./...`、`docker compose config`、Go `/health`、Go `/api/repos/mock`、Java `/api/agent/ask`、Java `/api/agent/traces/{traceId}`、Collector 不可用时 Java 502。
