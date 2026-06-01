# 测试计划 - 阶段 13 Agent Events Stream

## 1. 测试目标

验证阶段 13 新增的 Agent Run 与 SSE 事件流能在不破坏现有 `/api/agent/ask` 的前提下，实时展示计划、步骤、工具、观察、补查、自检、完成和失败事件；同时保证事件 payload 脱敏、buffer 有界、长连接可清理。

## 2. 测试范围

- P0：
  - AgentRunService 成功/失败状态转移。
  - AgentEventPublisher 订阅、发布、buffer 截断、emitter 清理。
  - Trace 记录点映射到事件流。
  - Controller run 创建、run 查询、events SSE endpoint。
  - `/api/agent/ask` 兼容回归。
- P1：
  - 限流、Collector 不可用、Verifier 发现问题时的事件语义。
  - heartbeat 事件和 SSE 超时处理。
  - events disabled / max-active-runs 超限。
- 不覆盖：
  - 前端展示、WebSocket、跨实例广播、事件持久化、生产鉴权、真实浏览器兼容矩阵。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| 创建 run | Controller | `POST /api/agent/runs` with goal | 返回 `runId`、`traceId`、`status`、`eventsUrl` | P0 |
| 查询运行中 run | Controller/Service | 刚创建的 run | 返回 `QUEUED` 或 `RUNNING`，不返回内部异常 | P0 |
| 查询完成 run | Controller/Service | mock Agent 成功完成 | 返回 `SUCCEEDED`、answer、recommendations、learningPlan、latencyMs | P0 |
| SSE 基础事件 | Controller/Event | 订阅 `/events` 后执行 mock run | 收到 `run_started`、`plan_created`、`step_started`、`tool_started`、`tool_finished`、`observation_created`、`run_completed` | P0 |
| 工具失败事件 | Service/Event | CollectorClient 抛 `CollectorUnavailableException` | 收到 `tool_failed` 和 `run_failed`，run 查询为 `FAILED` | P0 |
| 限流错误事件 | Service/Event | `RateLimitException` | 事件与 run 查询包含友好限流摘要，不泄漏堆栈 | P1 |
| 事件脱敏 | Unit | summary 含 `apiKey=secret` / `authorization=...` | SSE data 中为 `<redacted>`，长文本被截断 | P0 |
| buffer 截断 | Unit | 单 run 发布超过 buffer-size 的事件 | 仅保留最近 N 条，顺序稳定 | P0 |
| emitter 清理 | Unit | completion/timeout/error | subscriber 从 publisher 中移除 | P0 |
| events disabled | Controller | `openscout.events.enabled=false` | run/SSE endpoint 返回清晰错误，不影响 `/ask` | P1 |
| `/api/agent/ask` 回归 | Regression | 既有 mock ask | 响应结构、traceId、learningPlan 与阶段 12 兼容 | P0 |

## 4. 验证命令

```bash
cd openscout-agent-server && mvn test -Dtest='AgentEventPublisherTest,AgentRunServiceTest,AgentControllerTest,TraceServiceTest,PlanExecutorTest'
cd openscout-agent-server && mvn test
cd openscout-repo-collector && go test ./...
docker compose -f deploy/docker-compose.yml config
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-06-01 | `cd openscout-agent-server && mvn test -Dtest='AgentEventPublisherTest,AgentRunServiceTest,AgentControllerTest,TraceServiceTest,PlanExecutorTest,AgentServiceTest'` | 通过 | 20 tests，覆盖新增 run/event/controller/trace 桥接与关键回归 |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | 112 tests |
| 2026-06-01 | `source scripts/use-local-tools.sh && cd openscout-repo-collector && go test ./...` | 通过 | 使用项目本地 Go 1.26.3；默认 PATH 中 `go` 不存在 |
| 2026-06-01 | `docker compose -f deploy/docker-compose.yml config` | 阻塞 | 当前 WSL 2 distro 未启用 Docker Desktop integration，Docker CLI 不可用 |
| 2026-06-01 | `cd openscout-agent-server && mvn clean test -Dtest='AgentRunServiceTest,AgentEventPublisherTest,TraceServiceTest,AgentControllerTest,PlanExecutorTest,AgentServiceTest'` | 通过 | 24 tests；覆盖 review fix 的 terminal event order、active slot 原子保留、completed cleanup |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | 116 tests |
| 2026-06-01 | `cd openscout-agent-server && mvn test -Dtest='AgentRunServiceTest,AgentEventPublisherTest'` | 通过 | 12 tests；覆盖 terminal event status、失败事件 status 和异步等待超时失败 |
| 2026-06-01 | `OPSCOUT_EVENTS_COMPLETED_RETENTION_SECONDS=0 OPSCOUT_EVENTS_HEARTBEAT_SECONDS=0 mvn spring-boot:run -Dspring-boot.run.arguments=--spring.main.web-application-type=none` | 通过 | Spring 容器启动成功，验证 scheduled SpEL 最小 delay 保护可解析；启动后已手动终止 |
| 2026-06-01 | `cd openscout-agent-server && mvn test -Dtest='AgentRunServiceTest,AgentEventPublisherTest,TraceServiceTest,AgentControllerTest,PlanExecutorTest,AgentServiceTest'` | 通过 | 24 tests；二次 review fix 后目标回归 |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | 116 tests；二次 review fix 后 Java 全量回归 |
| 2026-06-01 | `cd openscout-agent-server && mvn test -Dtest='AgentRunServiceTest,AgentEventPublisherTest,TraceServiceTest,AgentControllerTest,PlanExecutorTest,AgentServiceTest'` | 通过 | 24 tests；修复三次 review 发现的 `run_completed` 测试竞态后复验 |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | 116 tests；三次 review fix 后 Java 全量回归 |
