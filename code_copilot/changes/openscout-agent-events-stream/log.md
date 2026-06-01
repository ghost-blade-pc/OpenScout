# 执行日志 - 阶段 13 Agent Events Stream

## 基本信息

- change：`openscout-agent-events-stream`
- status：achieve
- created：2026-06-01
- last_updated：2026-06-01

## Research 记录

- `AgentController` 当前只有 `POST /api/agent/ask` 和 `GET /api/agent/traces/{traceId}`，没有 run 或 SSE endpoint。
- `AgentService.ask()` 当前同步执行：创建 Trace -> 调用 `PlanExecutor.execute()` -> complete/fail Trace -> 返回 `AgentAskResponse` 或抛 `AgentCallException`。
- `PlanExecutor` 已在每个 step 前后记录 `agent_step_started`、`agent_step_finished` 和 `agent_observation_created`。
- `ToolExecutor` 已记录 `agent_tool_started`、`agent_tool_finished` 和 `agent_tool_failed`。
- `TraceService` 已统一记录 `TraceToolCall`，并通过 `sanitize()` 做敏感字段脱敏与长度截断。
- `pom.xml` 使用 `spring-boot-starter-web`，阶段 13 可选 Spring MVC `SseEmitter`，不必引入 WebFlux。

## 执行记录

| 时间 | 动作 | 文件 | 结果 |
|---|---|---|---|
| 2026-06-01 | 创建阶段分支 | `feature/12-agent-events-stream` | 已从 `main` 创建并切换 |
| 2026-06-01 | 创建 proposal | `spec.md`、`tasks.md`、`test-spec.md`、`log.md` | 已完成 |
| 2026-06-01 | 同步项目进度 | `项目实施进度.md` | 已标记阶段 13 proposal 进行中 |
| 2026-06-01 | apply Task 1-2 | `OpenScoutProperties`、`application.yml`、`agent/event/*`、`agent/run/*` | 完成 run/event 模型、配置、SSE publisher 和 bounded buffer |
| 2026-06-01 | apply Task 3-5 | `AgentRunService`、`TraceService`、`AgentController` | 完成异步 run、Trace 事件桥接和 run/SSE endpoint |
| 2026-06-01 | apply Task 6 | `README.md`、`test-spec.md`、`log.md`、`项目实施进度.md` | 完成文档同步和验证记录 |
| 2026-06-01 | review fix | `AgentRunService`、`AgentEventPublisher`、`TraceService`、`OpenScoutProperties`、`application.yml`、测试与文档 | 修复 terminal event order、active limit 原子性和 completed cleanup |
| 2026-06-01 | review fix 2 | `AgentRunService`、`AgentEventPublisher`、`AgentRunServiceTest`、`AgentEventPublisherTest`、README 与 change 文档 | 修复 terminal event status 不一致、scheduled delay 0ms 风险和异步测试静默超时 |
| 2026-06-01 | review fix 3 | `AgentRunServiceTest`、`spec.md`、`tasks.md`、`test-spec.md`、`log.md` | 修复 `run_completed` 测试竞态，并同步 terminal run 事件触发点契约 |
| 2026-06-01 | review fix 4 | `AgentRunService.java`、`AgentEventPublisher.java` | 修复 releaseActiveRunSlot 使用 getAndUpdate 检测 double-release、executeRun 成功路径后处理移出 try-catch、cleanupCompletedRuns 添加 enabled 守卫、subscribe 先 snapshot 再注册 emitter 消除重复事件竞态、extractStepId 重命名为 tryExtractStepId |

## 决策记录

- 第一版使用 Spring MVC `SseEmitter`，因为当前依赖为 `spring-boot-starter-web`，不引入 WebFlux。
- 第一版使用进程内 run store 和 bounded event buffer，不新增 DDL，不做 Redis/MQ/跨实例广播。
- 事件 payload 使用白名单 DTO，并复用 Trace 脱敏/截断规则，避免直接暴露完整 `AgentTrace`。
- `/api/agent/ask` 保持同步兼容，新 run/SSE API 作为新增能力。
- Review fix 后 terminal run 事件由 `AgentRunService` 在更新 run 状态后发布；`TraceService.complete/fail` 只更新 Trace，不直接发布 terminal run 事件。
- `max-active-runs` 使用原子 reservation 控制并发；completed run 通过 `openscout.events.completed-retention-seconds` 控制保留期并清理事件 buffer。
- Terminal SSE event 的 `status` 使用 `AgentRunStatus`，因此成功事件为 `SUCCEEDED`，失败事件为 `FAILED`；Trace 的 `SUCCESS` / `FAILED` 仍只用于 trace 记录。
- `heartbeat-seconds` 和 `completed-retention-seconds` 即使配置为 0，也通过 `@Scheduled` SpEL 最小 1 秒 delay 避免后台调度空转。
- 测试不能只等待 run 状态进入终态后就断言 terminal event；实现有意先更新 run 状态，再发布 terminal event，因此测试需要等待 `run_completed` / `run_failed` 进入 event buffer。
- Review fix 4 将 `executeRun` 成功路径的后处理（`markSucceeded`、`releaseActiveRunSlot`、`publishRunCompleted`）移出 plan executor 的 try-catch，避免 `markSucceeded` 抛异常时被 `markRunFailed` 错误捕获导致 Trace 状态冲突。
- `releaseActiveRunSlot` 改用 `getAndUpdate`（返回旧值）检测 double-release，当旧值 ≤0 时记录 warn。
- `cleanupCompletedRuns` 与 `publishHeartbeats` 保持一致，在 events disabled 时提前返回。
- `subscribe()` 先获取 buffer 快照再将 emitter 注册到 subscribers，消除重放期间事件重复送达的竞态。
- `extractStepId` 重命名为 `tryExtractStepId`，更准确反映其可能返回 null 的语义。

## 验证记录

- `cd openscout-agent-server && mvn test -Dtest='AgentEventPublisherTest,AgentRunServiceTest,AgentControllerTest,TraceServiceTest,PlanExecutorTest,AgentServiceTest'`：通过，20 tests。
- `cd openscout-agent-server && mvn test`：通过，112 tests。
- `source scripts/use-local-tools.sh && cd openscout-repo-collector && go test ./...`：通过；系统 PATH 中 `go` 不存在，已使用项目本地 Go 1.26.3。
- `docker compose -f deploy/docker-compose.yml config`：阻塞；当前 WSL 2 distro 未启用 Docker Desktop integration，Docker CLI 不可用。使用 `/mnt/e/Docker/resources/bin/docker.exe` 也因 WSL vsock 错误失败。
- `cd openscout-agent-server && mvn clean test -Dtest='AgentRunServiceTest,AgentEventPublisherTest,TraceServiceTest,AgentControllerTest,PlanExecutorTest,AgentServiceTest'`：通过，24 tests。
- `cd openscout-agent-server && mvn test`：通过，116 tests。
- `cd openscout-agent-server && mvn test -Dtest='AgentRunServiceTest,AgentEventPublisherTest'`：通过，12 tests。
- `cd openscout-agent-server && mvn test -Dtest='AgentRunServiceTest,AgentEventPublisherTest,TraceServiceTest,AgentControllerTest,PlanExecutorTest,AgentServiceTest'`：通过，24 tests。
- `cd openscout-agent-server && mvn test`：通过，116 tests。
- `OPSCOUT_EVENTS_COMPLETED_RETENTION_SECONDS=0 OPSCOUT_EVENTS_HEARTBEAT_SECONDS=0 mvn spring-boot:run -Dspring-boot.run.arguments=--spring.main.web-application-type=none`：Spring 容器启动成功，scheduled SpEL 最小 delay 保护可解析；验证后已终止进程。
- `cd openscout-agent-server && mvn test -Dtest='AgentRunServiceTest,AgentEventPublisherTest,TraceServiceTest,AgentControllerTest,PlanExecutorTest,AgentServiceTest'`：通过，24 tests；修复三次 review 发现的 `run_completed` 测试竞态后复验。
- `cd openscout-agent-server && mvn test`：通过，116 tests；三次 review fix 后 Java 全量回归。
- `cd openscout-agent-server && mvn test -Dtest='AgentRunServiceTest,AgentEventPublisherTest,TraceServiceTest,AgentControllerTest,PlanExecutorTest,AgentServiceTest'`：通过，24 tests；review fix 4 后无 WARN 日志。
- `cd openscout-agent-server && mvn test`：通过，116 tests；review fix 4 后 Java 全量回归。

## 遗留问题

- Docker Compose config 尚需在 Docker Desktop WSL integration 恢复后复验。
- 第一版 run/event store 为进程内能力，仍不支持跨实例广播、重启恢复或事件持久化。
