# 阶段 13 Agent Events Stream
> status: apply
> created: 2026-06-01
> complexity: 复杂

## 1. 背景与目标

阶段 12 已在 Agent Runtime / Tool Runtime / Evidence ReAct / Reflection Verifier 之上形成可追踪的同步执行链路，但用户仍只能通过 `POST /api/agent/ask` 等待最终结果，或在完成后查询 `GET /api/agent/traces/{traceId}`。阶段 13 的目标是新增 Agent Run 与 SSE 事件流，让调用方能在一次 Agent 执行过程中实时观察计划、工具调用、观察结果、补查、自检、失败和 fallback。

完成后应保持现有 `/api/agent/ask` 响应兼容，同时新增 run 查询与事件流接口。第一版事件流仅面向本地演示和工程观测，不作为生产审计日志替代。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的 Agent Runtime -> Tool Runtime -> Trace -> Agent Run/Event Stream。
- 调用方向：HTTP -> AgentController -> AgentRunService -> PlanExecutor/TraceService -> AgentEventPublisher -> SSE。
- 是否涉及高风险项：是。
- 高风险类型：并发执行、长连接、Trace 脱敏、外部 GitHub API 限流、错误事件语义、内存资源释放。

### 1.2 范围裁剪

- 本次包含：
  - 新增 Agent Run 概念，用于异步提交、状态查询和关联 `traceId`。
  - 新增 SSE 事件流接口，输出计划、步骤、工具、观察、补查、自检、完成和失败事件。
  - 复用现有 Trace 摘要与脱敏规则，不输出完整 README、Prompt、Token、Key 或大文本。
  - 新增有限的内存 run store / event buffer，用于本地演示、完成态查询和短期事件回放。
  - 增加配置开关与资源限制，避免无限长连接或无限事件堆积。
  - 单元测试覆盖事件发布、SSE 连接生命周期、失败事件和 `/api/agent/ask` 兼容。
- 本次不包含：
  - 前端页面、WebSocket、RSocket、Kafka/MQ、Redis Pub/Sub。
  - 多实例事件广播、跨进程订阅、生产级鉴权、多用户隔离。
  - 新增 DDL 或持久化事件表；`agent_trace` 仍作为阶段内完成后排障载体。
  - Spring AI 动态 Tool Calling、MCP、Agent Evaluation。
- 后续可能拆分：
  - 阶段 14 Agent Evaluation 可复用 run/event 结构采集评测证据。
  - 生产强化阶段再补鉴权、配额、跨实例事件通道和事件持久化。

## 2. Research Findings

### 2.1 相关入口与链路

- HTTP/API：`openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java` 目前提供 `POST /api/agent/ask` 和 `GET /api/agent/traces/{traceId}`，没有 run 创建、run 查询或 SSE endpoint。
- Application Service：`openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java` 负责请求校验、`TraceService.start()`、调用 `PlanExecutor.execute()`，并在成功/失败时 `complete/fail` Trace。
- Runtime：`openscout-agent-server/src/main/java/com/openscout/agent/runtime/PlanExecutor.java` 已按 `AgentPlan` 顺序执行 `PlanStep`，每步调用 `traceService.recordStepStarted/recordStepFinished/recordObservation`。
- Tool Runtime：`openscout-agent-server/src/main/java/com/openscout/agent/tool/ToolExecutor.java` 已记录 `agent_tool_started`、`agent_tool_finished`、`agent_tool_failed`。
- Trace：`openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` 统一记录 plan/step/tool/observation，`sanitize()` 已做敏感字段脱敏与长度截断。
- Trace Domain：`openscout-agent-server/src/main/java/com/openscout/trace/AgentTrace.java` 保存 `traceId`、`toolCalls`、`scoreSummary`、`finalAnswer`、`status`、`errorMessage`。
- Config：`openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java` 和 `application.yml` 已有 `trace.max-summary-length`、`memory/react/verifier` 等配置分组，可扩展 `events` 配置。
- Dependency：`openscout-agent-server/pom.xml` 使用 `spring-boot-starter-web`，可基于 Spring MVC `SseEmitter` 实现 SSE，不需要引入 WebFlux。
- Test：`openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java` 已验证 Runtime 事件进入 Trace；当前未发现 `AgentControllerTest` 或 SSE 测试。

### 2.2 现有实现摘要

- 现有同步路径：`AgentController.ask()` -> `AgentService.ask()` -> `PlanExecutor.execute()` -> `ToolExecutor.execute()` -> `TraceService.recordToolCall()` -> 返回最终 `AgentAskResponse`。
- Trace 已包含面向事件流的原始材料：`agent_plan_created`、`agent_step_started`、`agent_tool_started`、`agent_tool_finished`、`agent_step_finished`、`agent_observation_created`、`evidence_*`、`verify_completed`。
- 当前 `TraceService.recordToolCall()` 只写内存 Trace，不向外发布事件；`AgentService.ask()` 是阻塞式调用，不适合直接承载长连接推送。
- 当前 Trace 持久化默认关闭，且只在 `complete/fail` 更新时写入完整 toolCalls；因此实时事件不能依赖 MySQL 查询。

### 2.3 发现的问题

- 缺少 run 生命周期模型，调用方无法区分 “已提交但仍执行中”、“已完成”、“失败”。
- 缺少事件订阅层，Agent 执行过程只能完成后从 Trace 里回看。
- 如果直接把 Trace 暴露为 SSE，容易把内部字段、过长摘要或失败堆栈直接输出，需要事件 DTO 白名单。
- 长连接和异步执行会引入线程池、超时、订阅清理和事件 buffer 上限问题。

### 2.4 风险初判

- 并发风险：多个 run 并发执行时，run 状态、event buffer 和 SseEmitter 列表必须线程安全。
- 安全风险：事件 payload 必须复用 `TraceService.sanitize()`，不能输出完整密钥、完整 README、完整 Prompt 或异常堆栈。
- 外部 API 风险：真实 GitHub 模式下 SSE 连接可能持续等待限流/超时，必须发布明确 `run_failed` 或 `tool_failed`。
- 兼容风险：不能改变 `/api/agent/ask` 响应结构和现有 Trace 事件名称。
- 资源风险：SSE emitter、线程池和 event buffer 必须有上限与清理策略。

## 3. 功能点

- [x] 功能 1：新增 Agent Run 模型与状态枚举，状态至少包含 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`。
- [x] 功能 2：新增 `POST /api/agent/runs`，请求复用 `AgentAskRequest`，返回 `runId`、`traceId`、`status`、`eventsUrl`。
- [x] 功能 3：新增 `GET /api/agent/runs/{runId}`，返回 run 状态、`traceId`、最终 answer/recommendations/learningPlan、错误摘要和耗时。
- [x] 功能 4：新增 `GET /api/agent/runs/{runId}/events` SSE，事件类型覆盖 `run_started`、`plan_created`、`step_started`、`tool_started`、`tool_finished`、`tool_failed`、`observation_created`、`step_finished`、`run_completed`、`run_failed`、`heartbeat`。
- [x] 功能 5：新增事件发布层，在 Trace 记录点同步发布白名单事件；失败、限流、fallback 和 verifier 结果都能进入事件流。
- [x] 功能 6：新增内存 run store 和短期 event buffer，支持连接后回放最近事件和完成态查询。
- [x] 功能 7：新增 `openscout.events.*` 配置，至少包含 enabled、sse-timeout-seconds、buffer-size、max-active-runs、executor-threads、heartbeat-seconds。
- [x] 功能 8：补充测试与文档，证明 `/api/agent/ask` 兼容、run/SSE 路径可用、事件脱敏和资源释放可控。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| Java Config | `openscout.events.enabled` | 控制 run/SSE 能力总开关，默认 `true` | 新增配置，不影响旧接口 | 设为 `false` 可关闭新接口 |
| Java Config | `openscout.events.sse-timeout-seconds` | 单个 SSE 连接超时时间 | 新增配置 | 回退默认值 |
| Java Config | `openscout.events.buffer-size` | 每个 run 保留的最大事件数 | 新增配置 | 降低保留数量 |
| Java Config | `openscout.events.max-active-runs` | 内存中最多保留活跃 run 数 | 新增配置 | 超限返回 429 或 503 |
| Java Config | `openscout.events.executor-threads` | 异步 run 线程数 | 新增配置 | 调小并发 |
| Java Config | `openscout.events.heartbeat-seconds` | SSE 心跳间隔 | 新增配置 | 关闭或调大 |
| Java Config | `openscout.events.completed-retention-seconds` | 已完成 run 与事件 buffer 的保留秒数 | 新增配置 | 调低可更快释放内存 |
| Database | 无 | 不新增 DDL，不新增事件表 | 无迁移风险 | 不适用 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | `AgentAskRequest` | `AgentAskResponse` | MVP 无鉴权，不应公网暴露 | 保持不变 |
| `/api/agent/traces/{traceId}` | GET | path: `traceId` | `AgentTrace` | MVP 无鉴权，不应公网暴露 | 保持不变 |
| `/api/agent/runs` | POST | `AgentAskRequest` | `AgentRunCreateResponse` | MVP 无鉴权，不应公网暴露 | 新增 |
| `/api/agent/runs/{runId}` | GET | path: `runId` | `AgentRunResponse` | MVP 无鉴权，不应公网暴露 | 新增 |
| `/api/agent/runs/{runId}/events` | GET | path: `runId` | `text/event-stream` | MVP 无鉴权，不应公网暴露 | 新增 |

### 5.2 SSE 事件契约

| Event | Data 字段 | 触发点 | 失败处理 |
|---|---|---|---|
| `run_started` | `runId`,`traceId`,`status`,`createdAt` | run 创建并开始执行 | 无 |
| `plan_created` | `runId`,`traceId`,`mode`,`stepCount`,`summary` | `recordPlanCreated` | 发布失败不阻断 Agent |
| `step_started` | `runId`,`traceId`,`stepId`,`toolName`,`summary` | `recordStepStarted` | 发布失败不阻断 Agent |
| `tool_started` | `runId`,`traceId`,`stepId`,`toolName`,`summary` | `recordToolStarted` | 发布失败不阻断 Agent |
| `tool_finished` | `runId`,`traceId`,`stepId`,`toolName`,`status`,`summary`,`latencyMs` | `recordToolFinished` | 发布失败不阻断 Agent |
| `tool_failed` | `runId`,`traceId`,`stepId`,`toolName`,`status`,`errorSummary`,`latencyMs` | `recordToolFailed` 或工具失败 Trace | 发布失败不阻断 Agent |
| `observation_created` | `runId`,`traceId`,`stepId`,`toolName`,`status`,`summary` | `recordObservation` | 发布失败不阻断 Agent |
| `step_finished` | `runId`,`traceId`,`stepId`,`toolName`,`status`,`latencyMs` | `recordStepFinished` | 发布失败不阻断 Agent |
| `run_completed` | `runId`,`traceId`,`status`,`latencyMs` | `AgentRunService` 更新 run 为 `SUCCEEDED` 后 | 完成后清理 emitter |
| `run_failed` | `runId`,`traceId`,`status`,`errorSummary`,`latencyMs` | `AgentRunService` 更新 run 为 `FAILED` 后 | 完成后清理 emitter |
| `heartbeat` | `runId`,`traceId`,`status`,`createdAt` | 定时心跳 | 发送失败时移除 emitter |

### 5.3 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector | 既有 search/profile/readme/batch-profile | 不变 | 不变 | 沿用现有 timeout/限流处理 | 失败事件映射为 `tool_failed`/`run_failed` |
| DeepSeek/OpenAI 兼容接口 | 既有 ChatClient 调用 | 不变 | 不变 | 沿用 `openscout.llm.*` | fallback 事件通过现有 Trace 摘要体现 |

## 6. 风险与关注点

- 长连接资源释放：`SseEmitter` 必须注册 completion/timeout/error 回调，移除订阅者。
- 异步 run 泄漏：完成或失败后 run store 只保留短期状态，event buffer 按数量截断。
- 事件顺序：同一 run 内按发布顺序追加；不承诺跨 run 全局顺序。
- 事件脱敏：所有 summary/errorSummary 统一经过 `TraceService.sanitize()` 或等价白名单 DTO 过滤。
- 兼容性：同步 `/api/agent/ask` 不依赖 SSE；新事件发布失败不能影响现有 ask 主流程。
- 真实模式限流：`RateLimitException` 要在 run 查询和 SSE 中提供可解释错误，不泄漏内部堆栈。

## 7. 测试策略

- 单元测试：
  - AgentEventPublisher：订阅、发布、buffer 截断、完成后清理、发送失败移除 emitter。
  - AgentRunService：创建 run、状态转移、成功/失败结果回填、超限处理。
  - Trace/Event bridge：plan/step/tool/observation 发布正确事件且脱敏；terminal run 事件由 `AgentRunService` 在状态更新后发布。
- Controller 测试：
  - `POST /api/agent/runs` 返回 `runId`、`traceId`、`eventsUrl`。
  - `GET /api/agent/runs/{runId}` 返回 running/succeeded/failed。
  - `GET /api/agent/runs/{runId}/events` 返回 `text/event-stream` 并能收到至少开始和完成事件。
- 回归测试：
  - 现有 `PlanExecutorTest` 和 `/api/agent/ask` 兼容测试不退化。
  - `cd openscout-agent-server && mvn test`。
  - `cd openscout-repo-collector && go test ./...`。
  - `docker compose -f deploy/docker-compose.yml config`。

## 8. 待澄清

- 无阻塞待澄清项。默认第一版使用 Spring MVC `SseEmitter` + 进程内 run/event store，不做跨实例广播和事件持久化。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| SSE 实现 | Spring MVC `SseEmitter` | WebFlux `Flux<ServerSentEvent<?>>` | 当前依赖是 `spring-boot-starter-web`，无需引入 WebFlux | 仍需管理 emitter 生命周期 |
| run 存储 | 进程内 `ConcurrentHashMap` + bounded event buffer | MySQL/Redis | 阶段 13 聚焦本地演示和实时观测，不新增 DDL | 多实例和重启后不可恢复 |
| 事件来源 | 复用 Trace 记录点发布白名单事件 | 直接暴露 `TraceToolCall` | Trace 已覆盖计划/工具/观察，自带脱敏入口；白名单 DTO 更安全 | 需要在 TraceService 或桥接层中注入 publisher |
| 旧接口兼容 | `/api/agent/ask` 不变 | 改造成默认异步 | 降低回归风险，保留已有测试和 Demo | 新调用方需要使用 run API 才能流式观察 |
| 失败语义 | 发布 `tool_failed` / `run_failed`，不输出堆栈 | SSE 直接断开 | 调用方能展示失败原因和 traceId | 需要统一错误摘要 |

## 10. 确认记录

- 确认时间：2026-06-01
- 确认人：用户
- 确认范围：按 proposal 执行阶段 13 第一版 Agent Run + SSE 事件流；保持 `/api/agent/ask` 兼容，不新增 DDL、Redis/MQ、跨实例广播或前端。

## 11. Apply 结果

- 已新增 `agent.run` 模块：`AgentRun`、状态、创建/查询响应 DTO 和 `AgentRunService`。
- 已新增 `agent.event` 模块：事件 DTO、`AgentEventPublisher`、SSE 订阅、bounded buffer、terminal cleanup 和 heartbeat。
- 已在 `TraceService` 的现有 Trace 记录点旁路发布事件；同步 `/api/agent/ask` 不依赖事件流，事件发布失败不阻断 Agent 主流程。
- 已在 `AgentController` 增加 `POST /api/agent/runs`、`GET /api/agent/runs/{runId}`、`GET /api/agent/runs/{runId}/events`。
- 已新增 `openscout.events.*` 配置和 README 说明。
- 已新增/更新测试：`AgentEventPublisherTest`、`AgentRunServiceTest`、`AgentControllerTest`、`TraceServiceTest`。
- 验证结果：Java `mvn test` 通过 112 tests；Go `go test ./...` 通过；Docker Compose config 因当前 WSL Docker Desktop 集成不可用未通过环境验证。

## 12. Review Fix 结果

- 已修复 terminal SSE 事件发布顺序：`AgentRunService` 先更新 run 为 `SUCCEEDED` / `FAILED`，再发布 `run_completed` / `run_failed`。
- 已修复 `max-active-runs` 非原子检查：改为 `AtomicInteger` reservation，run 结束后释放 active slot。
- 已补 completed run 与 event buffer 清理：新增 `openscout.events.completed-retention-seconds`，定时清理已超过保留期的 run，并调用 `AgentEventPublisher.unregisterRun()` 移除 trace/run 映射、buffer 和 subscriber。
- 已补测试：terminal event order、active slot release、concurrent active limit、completed run/event cleanup。
- 验证结果：Java `mvn test` 通过 116 tests；目标测试 `mvn clean test -Dtest='AgentRunServiceTest,AgentEventPublisherTest,TraceServiceTest,AgentControllerTest,PlanExecutorTest,AgentServiceTest'` 通过 24 tests。

## 13. Review Fix 2 结果

- 已修复 terminal SSE 事件 payload 状态不一致：`run_completed` / `run_failed` 改为使用 `AgentRunStatus`，与 run 查询接口保持 `SUCCEEDED` / `FAILED` 语义。
- 已修复调度配置边界：heartbeat 与 completed cleanup 的 `@Scheduled` fixed delay 使用 SpEL 做最小 1 秒保护，避免配置为 0 时形成 0ms 调度循环。
- 已补强异步测试：`awaitCondition` 和 `awaitTerminal` 超时后显式失败，并断言 terminal event payload status。

## 14. Review Fix 3 结果

- 已修复 `AgentRunServiceTest.shouldCreateRunAndStoreSuccessfulResult` 的异步竞态：先等待 `run_completed` 写入 buffer，再断言事件集合。
- 已反向同步 SSE 契约：`run_completed` / `run_failed` 的触发点从 `TraceService.complete/fail` 调整为 `AgentRunService` 更新 run 终态后发布。

## 15. Review Fix 4 结果

- 已修复 `releaseActiveRunSlot` 使用 `getAndUpdate`（返回旧值）检测 double-release，替换原 `updateAndGet`（返回新值，永远为 0 导致误报）。
- 已重构 `executeRun`：将 `markSucceeded`、`releaseActiveRunSlot`、`publishRunCompleted` 移出 plan executor 的 try-catch，仅 executor 执行阶段在 try 内，避免后处理异常被 `markRunFailed` 错误覆盖。
- 已为 `cleanupCompletedRuns` 添加 `events.enabled` 守卫，与 `publishHeartbeats` 行为对齐。
- 已修复 `subscribe()` 重复事件竞态：先获取 buffer 快照，再将 emitter 注册到 subscribers，消除重放期间事件重复送达的窗口。
- 已重命名 `extractStepId` → `tryExtractStepId`，准确反映其对非 step 事件返回 null 的语义。
- 验证结果：Java `mvn test` 通过 116 tests，零 WARN 日志。
