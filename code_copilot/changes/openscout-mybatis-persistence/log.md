# 执行日志 - OpenScout MyBatis-Plus 持久化

## 基本信息

- change：`openscout-mybatis-persistence`
- status：done
- created：2026-05-30
- last_updated：2026-05-30

## Research 记录

- 已读取 `code_copilot/README.md`、`code_copilot/rules/project-context.md`、`code_copilot/rules/domain-rules.md`、`code_copilot/rules/coding-style.md`、`code_copilot/rules/security.md`、`code_copilot/knowledge/index.md` 和 `code_copilot/agents/copilot-prompt.md`。
- 已读取 `项目实施进度.md`，阶段 4 为 `feature/03-mybatis-persistence`，目标是将项目画像、分析结果和 Trace 从内存实现推进到 MyBatis-Plus 持久化。
- 已从 `main` 创建并切换到 `feature/03-mybatis-persistence`。
- Java `pom.xml` 已有 `mybatis-plus-spring-boot3-starter` 和 `mysql-connector-j`。
- Java `application.yml` 已配置 MySQL datasource 和 MyBatis-Plus `map-underscore-to-camel-case`。
- `deploy/init.sql` 已预留 `repo_info`、`repo_analysis`、`agent_trace` 表；`repo_info.full_name` 和 `agent_trace.trace_id` 有唯一索引。
- `TraceService` 当前使用 `ConcurrentHashMap` 保存 Trace，重启后不可查询历史 Trace。
- `TraceService.sanitize` 已实现敏感字段脱敏和摘要截断，持久化必须复用该规则。
- `AgentController` 通过 `GET /api/agent/traces/{traceId}` 查询 Trace，不应改变 HTTP 响应契约。
- `MockAgentService` 是持久化落点：成功路径已有 `RepoSummary`、`ProjectScore`、`ProjectRecommendation`，失败路径已有 `traceService.fail`。
- 阶段 3 `openscout-mock-e2e-demo` 已验证 mock ask、Trace 查询、Collector 不可用 502 和 Spring AI mock 默认关闭模型 provider。

## 执行记录

| 时间 | 动作 | 文件 | 结果 |
|---|---|---|---|
| 2026-05-30 | 创建阶段 4 分支 | Git | 已从 `main` 创建 `feature/03-mybatis-persistence` |
| 2026-05-30 | 创建阶段 4 propose | `code_copilot/changes/openscout-mybatis-persistence/` | 已创建 spec、tasks、test-spec、log |
| 2026-05-30 | 同步阶段状态 | `项目实施进度.md` | 已标记当前分支和阶段 4 propose 状态 |
| 2026-05-30 | Task 1：建立 MyBatis-Plus 基础设施 | `OpenScoutProperties.java`、`OpenScoutAgentApplication.java`、`application.yml`、Entity/Mapper 类 | `mvn test` 通过（默认持久化关闭），6 个新文件创建 |
| 2026-05-30 | Task 2：实现 agent_trace 持久化 | `TracePersistenceService.java`、`TraceService.java`、`AgentTrace.java`、`TraceServiceTest.java` | `mvn test` 通过；start/complete/fail 支持持久化写入，find 支持内存+MySQL 双查 |
| 2026-05-30 | Task 3：实现 repo_info 幂等保存 | `RepoPersistenceService.java`、`MockAgentService.java` | `mvn test` 通过；full_name 幂等 upsert |
| 2026-05-30 | Task 4：实现 repo_analysis 评分保存 | `RepoAnalysisPersistenceService.java` | `mvn test` 通过；score_breakdown_json 和 evidence_json 写入 |
| 2026-05-30 | Task 5：更新文档 | `README.md`、`项目实施进度.md`、`spec.md`、`tasks.md`、`log.md` | 文档已同步；Docker Compose config 通过 |

## 决策记录

- change id 使用 `openscout-mybatis-persistence`，对应分支 `feature/03-mybatis-persistence`。
- 阶段 4 自主选择 P0 覆盖 `agent_trace` 持久化，P1 覆盖 `repo_info` 和 `repo_analysis` 的基础写入；不做 `learning_goal`、`learning_task`。
- 真实 GitHub API、Redis adapter、Spring AI DeepSeek 接入继续保持后续阶段，不混入本阶段。
- 持久化建议默认关闭，使用 `OPSCOUT_PERSISTENCE_ENABLED=true` 显式启用，避免未启动 MySQL 时破坏阶段 3 mock 演示。
- HTTP 契约保持兼容：`/api/agent/ask` 和 `/api/agent/traces/{traceId}` 响应结构不因持久化改变。
- Trace 存储继续只保存摘要和脱敏字段，不保存完整 README、完整 prompt、完整模型响应或敏感配置。

## 验证记录

- `/apply` 阶段已执行：`mvn compile` 通过、`mvn test` 通过（默认持久化关闭）、`docker compose -f deploy/docker-compose.yml config` 通过。
- MySQL 集成验证已执行（2026-05-30）：Docker MySQL 8.4 启动、init.sql 初始化、Go Collector + Java Agent Server 启动（`OPSCOUT_PERSISTENCE_ENABLED=true`）。
- 端到端验证结果（全部通过）：
  1. `/api/agent/ask` → HTTP 200，agent_trace 有 SUCCESS 记录（含 tool_calls_json、score_summary）
  2. `repo_info` 表 3 条记录（含 topics_json）
  3. `repo_analysis` 表 3 条记录（含 score_breakdown_json、evidence_json）
  4. `/api/agent/traces/{traceId}` → HTTP 200，响应结构与阶段 3 兼容
  5. Java 重启后查询同一 traceId → HTTP 200，从 MySQL 恢复
  6. 重复 mock ask → repo_info 仍为 3 条（幂等 upsert）
  7. 停止 Go Collector → HTTP 502，agent_trace.status=FAILED
  8. 敏感字段脱敏：token/api-key → `<redacted>`（DB 和 API 均脱敏）

## 遗留问题

- 无阻塞遗留问题。MySQL 集成验证已全部通过。
- 持久化默认关闭时不能证明数据落库；必须在验收命令中显式启用 `OPSCOUT_PERSISTENCE_ENABLED=true`。
