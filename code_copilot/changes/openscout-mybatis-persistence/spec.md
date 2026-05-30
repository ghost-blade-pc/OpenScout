# OpenScout MyBatis-Plus 持久化
> status: done
> created: 2026-05-30
> complexity: 复杂

## 1. 背景与目标

根据 `项目实施进度.md` 的阶段 4，当前分支 `feature/03-mybatis-persistence` 的目标是把阶段 3 已验证的内存 mock 链路推进到 MyBatis-Plus 持久化。阶段 3 已证明 Java `/api/agent/ask` 能调用 Go `/api/repos/mock`，并能通过内存 Trace 查询执行过程；但 Java 重启后 Trace 丢失，项目画像和评分结果也没有落库。

本阶段完成后应能证明：

- `agent_trace` 表能保存一次 Agent 请求的用户问题、工具调用摘要、评分摘要、最终回答、耗时、状态和错误摘要。
- Java `/api/agent/traces/{traceId}` 在启用持久化后可从 MySQL 查询 Trace，至少能覆盖同进程查询和重启后查询。
- `repo_info` 和 `repo_analysis` 可保存 mock 推荐链路中的项目基础信息、规则评分、score breakdown 和 evidence。
- 持久化不改变既有 `/api/agent/ask` 和 `/api/agent/traces/{traceId}` 的 HTTP 契约。
- Trace 仍然只保存摘要和脱敏字段，不保存完整 README、完整 prompt、模型 Key、GitHub Token 或其他敏感大字段。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 阶段 4 MyBatis-Plus 持久化。
- 调用方向：用户 curl -> Java `/api/agent/ask` -> `MockAgentService` -> `CollectorClient` -> Go `/api/repos/mock` -> 评分 -> MySQL `repo_info`/`repo_analysis`/`agent_trace` -> Java `/api/agent/traces/{traceId}`。
- 是否涉及高风险项：是。
- 高风险类型：数据库 schema/JSON 字段、Trace 脱敏、敏感配置、跨进程查询一致性、持久化开关、MySQL 本地依赖。

### 1.2 范围裁剪

本次包含：

- 基于现有 `deploy/init.sql` 的 `repo_info`、`repo_analysis`、`agent_trace` 表做 Java MyBatis-Plus Entity、Mapper 和 Repository/Service 封装。
- 将 `TraceService` 从纯内存实现扩展为“内存 + 可选 MySQL 持久化”的写入和查询。
- 在 `MockAgentService` 成功推荐后保存 `RepoSummary` 和 `ProjectScore` 的基础项目画像与分析结果。
- 增加持久化开关，例如 `OPSCOUT_PERSISTENCE_ENABLED`，避免未启动 MySQL 时破坏阶段 3 mock 演示。
- 更新 README/API 文档/进度文档中关于 Trace 生命周期和 MySQL 验证的说明。
- 增加 Java 单测和本地 MySQL 集成验证路径，记录实际执行结果。

本次不包含：

- 修改 Go Collector 的真实 GitHub API、限流、ETag、README 或目录采集逻辑。
- Redis adapter 或缓存持久化。
- Spring AI + DeepSeek Tool Calling。
- 学习计划 `learning_goal`、`learning_task` 的生成和持久化。
- 管理后台、Trace 可视化页面、权限系统或生产部署方案。

后续可能拆分：

- `openscout-github-real-api`：真实 GitHub API、错误分类、限流和缓存。
- `openscout-spring-ai-deepseek-agent`：Spring AI Tool Calling 与 DeepSeek 编排。
- `openscout-learning-plan`：学习路径和任务持久化。
- `openscout-trace-security`：Trace 查询鉴权、审计和公网暴露控制。

## 2. Research Findings

### 2.1 相关入口与链路

- 项目进度：`项目实施进度.md` 第 4 节将阶段 4 定义为 `feature/03-mybatis-persistence`，目标是将项目画像、分析结果和 Trace 从内存实现推进到 MyBatis-Plus 持久化。
- Java 启动与配置：`openscout-agent-server/src/main/java/com/openscout/OpenScoutAgentApplication.java` 当前启用 `OpenScoutProperties`，尚未配置 `@MapperScan`。
- Java 数据源配置：`openscout-agent-server/src/main/resources/application.yml` 已配置 `spring.datasource.url`、`username`、`password`、`driver-class-name`，并开启 MyBatis-Plus 下划线转驼峰。
- Java 依赖：`openscout-agent-server/pom.xml` 已引入 `mybatis-plus-spring-boot3-starter` 和 `mysql-connector-j`。
- 现有 Trace：`openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` 使用 `ConcurrentHashMap<String, AgentTrace>` 保存 Trace，`start`、`recordToolCall`、`complete`、`fail` 只操作内存。
- Trace 脱敏：`TraceService.sanitize` 对 `token`、`api-key`、`authorization`、`password`、`secret` 做正则替换，并按 `openscout.trace.max-summary-length` 截断。
- Trace 查询入口：`openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java` 的 `GET /api/agent/traces/{traceId}` 调用 `TraceService.find(traceId)`。
- Agent 编排入口：`openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java` 在 `ask` 中创建 Trace、调用 `CollectorClient.fetchMockRepos`、评分、生成回答，并在异常时调用 `TraceService.fail`。
- 项目数据来源：`openscout-agent-server/src/main/java/com/openscout/client/RepoSummary.java` 包含 owner、repo、fullName、description、language、stars、forks、topics、license、openIssues、updatedAt、pushedAt、readmeLength、hasExamples、hasDocker、source。
- 评分结构：`openscout-agent-server/src/main/java/com/openscout/scoring/ProjectScore.java` 包含 totalScore、activityScore、docScore、matchScore、learningScore、resumeValueScore、evidence。
- 数据库 DDL：`deploy/init.sql` 已创建 `repo_info`、`repo_analysis`、`learning_goal`、`learning_task`、`agent_trace` 表；其中 `agent_trace.trace_id` 有唯一索引，`repo_info.full_name` 有唯一索引。
- 已验证基线：`code_copilot/changes/openscout-mock-e2e-demo/` 记录阶段 3 已通过 Java/Go 单测、Docker Compose config、Go `/health`、Go `/api/repos/mock`、Java `/api/agent/ask`、Trace 查询和 Collector 不可用 502。

### 2.2 现有实现摘要

- 当前 Java 服务已经能完成 mock 推荐和内存 Trace 查询，但 Trace 生命周期绑定单个 Java 进程。
- `deploy/init.sql` 的表结构已足够支撑第一版持久化，不需要本阶段先做破坏性 schema 迁移。
- `agent_trace.score_summary` 是 JSON 字段，而当前 Java `AgentTrace.scoreSummary` 是字符串摘要；落库时应封装为合法 JSON，例如 `{"summary":"spring-projects/spring-ai=84, ..."}`。
- `agent_trace.tool_calls_json` 可保存 `TraceToolCall` 的摘要数组，不应保存完整外部响应。
- `repo_info.topics_json`、`repo_analysis.score_breakdown_json`、`repo_analysis.evidence_json` 都需要通过结构化 JSON 序列化写入。
- 当前 `/api/agent/traces/{traceId}` 返回 `AgentTrace` 对象，阶段 4 不应改变响应结构。

### 2.3 发现的问题

- `TraceService` 当前没有持久化端口，直接把 MyBatis 写入散落到业务编排中会破坏职责边界。
- 启用 MySQL 写入后，如果 MySQL 未启动，可能破坏阶段 3 已验证的 mock 演示；需要显式持久化开关或降级策略。
- DDL 中 JSON 字段要求写入合法 JSON，不能直接把 `scoreSummary` 字符串写入 JSON 列。
- Trace 查询接口当前只适合本地演示和排障，持久化后更容易被误认为可长期公网查询；文档必须继续强调未做鉴权。
- `repo_info` 和 `repo_analysis` 的写入需要处理 `full_name` 唯一约束，重复 mock 请求不应导致整次 ask 失败。

### 2.4 风险初判

- 数据库风险：新增 Entity/Mapper 与 `deploy/init.sql` 字段不一致会导致运行时 SQL 错误。
- JSON 风险：JSON 字段写入非 JSON 字符串会在 MySQL 侧失败。
- 安全风险：Trace、repo analysis 不得保存 token/key/authorization/header、完整 README、完整 prompt 或大模型响应。
- 兼容性风险：默认启用持久化可能让没有 MySQL 的本地 mock 演示失败。
- 一致性风险：Trace 成功/失败状态更新与 repo 持久化之间如果没有清晰边界，可能出现 ask 成功但部分数据未落库。
- 测试风险：MySQL 集成验证依赖 Docker Compose，本阶段需要明确本地验证命令和失败 fallback。

## 3. 功能点

- [ ] 功能 1：建立 MyBatis-Plus 基础设施，包含 Mapper 扫描、Entity、Mapper、Repository/Service 分层和 JSON 序列化工具。
- [ ] 功能 2：实现 `agent_trace` 持久化，支持 start/complete/fail 后写入或更新 MySQL，并支持 `/api/agent/traces/{traceId}` 从 MySQL 查询。
- [ ] 功能 3：实现 mock 推荐成功后的 `repo_info` upsert，按 `full_name` 幂等保存项目基础元数据。
- [ ] 功能 4：实现 mock 推荐成功后的 `repo_analysis` 写入或更新，保存规则评分、score breakdown 和 evidence。
- [ ] 功能 5：增加 `openscout.persistence.enabled` 配置和 README/API 文档说明，保证未启用持久化时阶段 3 mock 演示不退化。
- [ ] 功能 6：补充单元测试和本地 MySQL 集成验证记录，证明持久化启用时 Trace 可跨进程查询，持久化关闭时仍可内存查询。
- [ ] 功能 7：同步 `项目实施进度.md`、`tasks.md`、`test-spec.md` 和 `log.md`。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| 配置 | `openscout.persistence.enabled` / `OPSCOUT_PERSISTENCE_ENABLED` | 控制 MySQL 持久化是否启用，建议默认 `false`，阶段 4 验证时显式设为 `true` | 保持阶段 3 无 MySQL mock 演示可用 | 关闭配置回到内存 Trace |
| 数据库 | `agent_trace` | 使用既有表保存 Trace 摘要、工具调用 JSON、评分摘要 JSON、最终回答、耗时、状态、错误摘要 | 既有 DDL 已存在，无破坏性变更 | 删除本地测试数据或关闭持久化 |
| 数据库 | `repo_info` | 使用既有表按 `full_name` 幂等保存 mock 项目基础信息 | 既有 DDL 已存在，无破坏性变更 | 删除本地测试数据 |
| 数据库 | `repo_analysis` | 使用既有表保存每次分析摘要、评分 JSON 和 evidence JSON | 既有 DDL 已存在，无破坏性变更 | 删除本地测试数据 |
| Java | Entity/Mapper/Repository | 新增 MyBatis-Plus 数据访问层，不改变 HTTP DTO | 对外 API 兼容 | 回滚新增类和配置 |
| 文档 | README / docs / 项目实施进度 | 说明持久化启用方式、验证命令和边界 | 文档变更 | 按 Git diff 回滚 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | 既有 `AgentAskRequest`，含 `question` 或 `goal` | 既有 `AgentAskResponse`，含 `traceId`、`answer`、`recommendations`、`latencyMs` | MVP 本地演示无鉴权 | 响应结构不变；启用持久化后增加落库副作用 |
| `/api/agent/traces/{traceId}` | GET | path：`traceId` | 既有 `AgentTrace` 或 404 | 仅本地演示和排障；非本地暴露需后续补鉴权 | 响应结构不变；查询来源从内存扩展为内存 + MySQL |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector | `GET /api/repos/mock?keyword=...` | `CollectorClient.fetchMockRepos` 拼接 keyword | `RepoSummary` 列表 | 沿用当前 `RestClient` 行为 | Collector 不可用时 Java 返回 502，并持久化失败 Trace 摘要 |
| MySQL | MyBatis-Plus Mapper | `AgentTrace`、`RepoSummary`、`ProjectScore` 映射后的实体 | insert/update/select 结果 | 本阶段不做复杂重试 | 持久化关闭时不调用；持久化开启但 DB 异常时需返回可解释错误或记录降级策略 |

### 5.3 MQ/Event

本阶段不涉及 MQ/Event。

## 6. 风险与关注点

- 持久化默认值必须谨慎：建议默认关闭，避免未启动 MySQL 时阶段 3 mock 演示无法启动或无法 ask；阶段 4 验证命令显式启用。
- `agent_trace` 只保存摘要。`tool_calls_json` 保存 tool name、input summary、output summary、latency、status、error summary；不保存完整 Go 响应、README、prompt 或敏感配置。
- `score_summary` 是 JSON 字段，不能直接保存纯文本；应保存结构化对象或数组。
- `repo_info` upsert 必须以 `full_name` 为幂等键，重复请求不应违反唯一索引。
- `repo_analysis` 是否保留多次分析历史需明确：本阶段建议按 `full_name` 追加分析记录，或者按最新记录更新；实现时必须在 log 中记录选择。
- `/api/agent/traces/{traceId}` 仍是本地排障接口，持久化不等于可以公网暴露。
- Docker Compose MySQL 可能已有旧数据；测试前需要确认是否清理或使用唯一 traceId 避免污染断言。

## 7. 测试策略

- P0：`cd openscout-agent-server && mvn test`，确保默认持久化关闭时现有单测和 mock 配置不退化。
- P0：`docker compose -f deploy/docker-compose.yml config`，确认 MySQL/Redis 配置仍可解析。
- P0：启动 MySQL 后执行 DDL，启用 `OPSCOUT_PERSISTENCE_ENABLED=true`，跑 Java 持久化相关测试或本地 curl 验证。
- P0：启用持久化后调用 `/api/agent/ask`，确认 `agent_trace`、`repo_info`、`repo_analysis` 有对应数据。
- P0：使用返回 `traceId` 查询 `/api/agent/traces/{traceId}`，确认 HTTP 200 且包含 `repo_search_mock`。
- P0：重启 Java 后再次查询同一 `traceId`，确认可从 MySQL 查回 Trace。
- P0：提交带 `Authorization`、`token` 或 `api-key` 字样的问题或工具摘要，确认数据库 Trace 中被脱敏和截断。
- P1：重复调用同一个 mock goal，确认 `repo_info.full_name` 唯一约束不导致 ask 失败。
- P1：停止 Go Collector 后调用 Java `/api/agent/ask`，确认返回 502，并在启用持久化时保存失败 Trace。

## 8. 待澄清

- [x] 阶段 4 是否只做 Trace 持久化，还是同时持久化 `repo_info`、`repo_analysis`。自主决策：P0 覆盖 `agent_trace`，P1 在同一 change 覆盖 `repo_info` 和 `repo_analysis` 的基础写入；`learning_goal`、`learning_task` 不做。
- [x] 阶段 4 是否混入真实 GitHub API、Redis adapter 或 Spring AI DeepSeek。自主决策：不混入，保持后续阶段。
- [x] 持久化默认是否启用。自主决策：默认关闭，验证和演示持久化时显式开启，保护阶段 3 mock 链路。

当前无阻塞 `/apply` 的待澄清项；但进入 `/apply` 前仍需用户确认执行。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| change id | `openscout-mybatis-persistence` | `openscout-trace-persistence` | 阶段 4 同时覆盖 Trace 和项目画像基础持久化 | 与分支 `feature/03-mybatis-persistence` 对齐 |
| 阶段范围 | P0 `agent_trace`，P1 `repo_info`/`repo_analysis` | 只做 Trace | 进度文档阶段目标包含项目画像、分析结果和 Trace | 实现复杂度中高，需要清晰任务拆分 |
| 持久化开关 | 默认关闭，验证显式开启 | 默认开启 | 避免未启动 MySQL 时破坏 mock 演示和本地单测 | README 和测试命令需说明 |
| Trace 查询 | 先查内存，再查 MySQL | 只查 MySQL | 保留当前同进程行为，同时支持重启后查询 | `TraceService.find` 需要适配持久化端口 |
| JSON 写入 | 使用 Jackson 序列化结构化对象 | 手写字符串拼接 | 降低 JSON 字段写入错误和转义风险 | 需要封装 JSON 工具或 repository 方法 |
| repo_info 写入 | 以 `full_name` 幂等 upsert | 每次 insert | 避免唯一索引冲突 | 需要 Mapper/Service 处理重复数据 |
| repo_analysis 写入 | 保存规则评分和 evidence JSON | 只保存 total_score | 后续报告和简历表达需要可解释证据 | 注意不要保存大对象 |

## 10. 确认记录

- 确认时间：2026-05-30。
- 确认人：用户授权“自行创建下一步分支，并且执行 propose”。
- 确认范围：从 `main` 创建 `feature/03-mybatis-persistence`，并为阶段 4 创建 MyBatis-Plus 持久化 proposal；不进入 `/apply`。
