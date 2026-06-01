# 任务拆分 - 阶段 13 Agent Events Stream

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/knowledge/index.md`
- [x] 已检查工作区状态，确认当前分支为 `feature/12-agent-events-stream`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已确认本地验证命令或替代验证方式

## Task 1: Agent Run 模型与配置

- **目标**：建立 run 生命周期、响应 DTO 和 `openscout.events.*` 配置。
- **层级/模块**：应用服务 / 配置
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/run/`：新增 run 模型、状态、响应 DTO
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`：新增 `Events` 配置分组
  - `openscout-agent-server/src/main/resources/application.yml`：新增 `openscout.events.*`
- **依赖**：无
- **风险标记**：配置 / 并发
- **实现要点**：
  - 状态枚举：`QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`。
  - 创建响应包含 `runId`、`traceId`、`status`、`eventsUrl`。
  - 配置包含 enabled、sse-timeout-seconds、buffer-size、max-active-runs、executor-threads、heartbeat-seconds。
- **验收标准**：
  - 配置可通过 Spring Boot 绑定。
  - DTO 不包含敏感字段和内部异常。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest='*Run*Test,*Properties*Test'
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`agent/run/*`、`OpenScoutProperties.java`、`application.yml`
  - 验证结果：目标测试与 Java 全量测试通过

## Task 2: AgentEventPublisher 与事件 DTO

- **目标**：实现进程内事件发布、订阅、bounded buffer 和 emitter 清理。
- **层级/模块**：应用服务 / 入口层
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/event/`：新增事件 DTO、publisher、buffer
  - `openscout-agent-server/src/test/java/com/openscout/agent/event/`：新增单元测试
- **依赖**：Task 1
- **风险标记**：并发 / 安全
- **实现要点**：
  - 使用 `ConcurrentHashMap` 管理 runId -> subscriber/buffer。
  - 每个 run 的事件 buffer 按 `openscout.events.buffer-size` 截断。
  - emitter completion/timeout/error 后移除订阅。
  - payload 字段白名单化，summary/errorSummary 必须脱敏和截断。
- **验收标准**：
  - 发布失败不影响 Agent 主流程。
  - buffer 截断和 emitter 清理有单测。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=AgentEventPublisherTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`agent/event/AgentEvent.java`、`agent/event/AgentEventPublisher.java`、`AgentEventPublisherTest.java`
  - 验证结果：`AgentEventPublisherTest` 通过，覆盖 buffer、订阅、terminal cleanup、disabled

## Task 3: AgentRunService 异步执行

- **目标**：新增异步 run 创建、状态查询、成功/失败结果回填。
- **层级/模块**：应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/run/AgentRunService.java`：新增
  - `openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java`：必要时提取可复用同步执行方法
  - `openscout-agent-server/src/test/java/com/openscout/agent/run/AgentRunServiceTest.java`：新增
- **依赖**：Task 1、Task 2
- **风险标记**：并发 / 外部接口
- **实现要点**：
  - run 创建时先创建 Trace，随后后台执行 `PlanExecutor`。
  - 成功时写入 answer/recommendations/learningPlan/latency，并发布 `run_completed`。
  - 失败时复用现有 `AgentCallException`/`RateLimitException` 语义，发布 `run_failed`。
  - 达到 max-active-runs 时拒绝新 run，返回可解释错误。
- **验收标准**：
  - run 状态能从 queued/running 进入 succeeded 或 failed。
  - 失败不泄漏堆栈和敏感配置。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=AgentRunServiceTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`agent/run/AgentRunService.java`、`AgentRunServiceTest.java`
  - 验证结果：`AgentRunServiceTest` 通过，覆盖成功、失败、disabled、active limit

## Task 4: Trace 到事件流桥接

- **目标**：在现有 Trace 记录点发布实时事件，覆盖计划、步骤、工具、观察和完成/失败。
- **层级/模块**：Trace / 应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java`：注入或调用事件发布层
  - `openscout-agent-server/src/main/java/com/openscout/agent/event/`：补充事件映射
  - `openscout-agent-server/src/test/java/com/openscout/trace/TraceServiceTest.java` 或新增桥接测试
- **依赖**：Task 2、Task 3
- **风险标记**：安全 / 兼容
- **实现要点**：
  - `recordPlanCreated` -> `plan_created`。
  - `recordStepStarted` -> `step_started`。
  - `recordToolStarted/Finished/Failed` -> `tool_started/finished/failed`。
  - `recordObservation` -> `observation_created`。
  - `TraceService` 的 plan/step/tool/observation 记录点发布同名事件；`run_completed/run_failed` 由 `AgentRunService` 在更新 run 终态后发布。
  - 事件发布失败只记录 warn，不改变 Trace 写入和 Agent 执行。
- **验收标准**：
  - 现有 Trace 事件名称和 `/api/agent/traces/{traceId}` 输出不变。
  - 事件 payload 已脱敏并受长度限制。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest='TraceServiceTest,PlanExecutorTest'
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`TraceService.java`、`TraceServiceTest.java`
  - 验证结果：`TraceServiceTest` 和 `PlanExecutorTest` 通过，Trace 事件兼容且事件 payload 脱敏

## Task 5: Controller 接口

- **目标**：新增 run 创建、run 查询和 SSE 订阅接口。
- **层级/模块**：入口层
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java`：新增 endpoint
  - `openscout-agent-server/src/test/java/com/openscout/controller/AgentControllerTest.java`：新增 controller 测试
- **依赖**：Task 3、Task 4
- **风险标记**：接口 / 安全
- **实现要点**：
  - `POST /api/agent/runs` 复用 `AgentAskRequest`。
  - `GET /api/agent/runs/{runId}` 返回状态和结果快照。
  - `GET /api/agent/runs/{runId}/events` 返回 `SseEmitter`。
  - event stream 不输出完整 Trace 对象。
- **验收标准**：
  - 未找到 run 返回 404。
  - events endpoint content type 为 `text/event-stream`。
  - events disabled 时返回 404 或 503，并在文档中说明。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -Dtest=AgentControllerTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`AgentController.java`、`AgentControllerTest.java`
  - 验证结果：`AgentControllerTest` 通过，覆盖 create/get/events/404/disabled

## Task 6: 回归测试与文档同步

- **目标**：补齐测试计划执行记录，更新 README、项目实施进度和 change 日志。
- **层级/模块**：测试 / 文档
- **涉及文件**：
  - `README.md`
  - `项目实施进度.md`
  - `code_copilot/changes/openscout-agent-events-stream/test-spec.md`
  - `code_copilot/changes/openscout-agent-events-stream/log.md`
- **依赖**：Task 1-5
- **风险标记**：无
- **实现要点**：
  - 记录 run/SSE curl 示例和边界。
  - 标明第一版事件流为进程内、非持久化、非多实例。
  - 执行 Java、Go、Docker compose config 回归。
- **验收标准**：
  - `cd openscout-agent-server && mvn test` 通过。
  - `cd openscout-repo-collector && go test ./...` 通过。
  - `docker compose -f deploy/docker-compose.yml config` 通过。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  cd openscout-repo-collector && go test ./...
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`README.md`、`项目实施进度.md`、`spec.md`、`tasks.md`、`test-spec.md`、`log.md`
  - 验证结果：Java 116 tests 通过；Go tests 通过；Docker Compose config 因当前 WSL Docker Desktop integration 不可用阻塞

## 变更摘要

- **总文件数**：22
- **新增文件**：14
- **修改文件**：8
- **删除文件**：0
- **Spec-Plan 偏差记录**：无业务范围偏差；第一版按 spec 使用 Spring MVC `SseEmitter`、进程内 run store 和 bounded event buffer。Review fix 新增 `openscout.events.completed-retention-seconds`，用于兑现“短期 event buffer / 资源释放”边界；后续修复将 terminal SSE 状态统一为 Run 状态，并为 scheduled delay 加最小 1 秒保护。Terminal run 事件触发点已从 `TraceService.complete/fail` 反向同步为 `AgentRunService` 更新 run 终态后发布。Docker Compose config 未能在当前环境验证，已如实记录为环境阻塞。
- **未完成项**：跨实例事件广播、事件持久化、Redis/MQ、前端、生产鉴权和 Agent Evaluation 仍按 spec 留到后续阶段。
- **遗留风险**：进程内 run/event store 重启即失效；SSE 长连接依赖单实例内存；Docker Compose config 需在 Docker Desktop WSL integration 恢复后复验。
