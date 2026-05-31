# 阶段 7 学习计划与任务持久化
> status: done
> created: 2026-05-31
> complexity: 中等

## 1. 背景与目标

阶段 6 已完成 Spring AI + DeepSeek Agent 编排，当前 `/api/agent/ask` 能根据用户目标检索项目、计算规则评分、生成推荐回答，并记录 Trace。阶段 7 按 `项目实施进度.md` 进入“生成并持久化学习路径与任务”：在推荐项目基础上生成 7 天学习计划，保存学习目标和任务，并提供查询与任务状态更新接口。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的学习路径上下文。
- 调用方向：Java Agent Server 内部从推荐结果生成学习计划；HTTP 客户端查询计划和更新任务状态。
- 是否涉及高风险项：是。
- 高风险类型：数据库持久化、API 契约、用户输入校验、LLM 输出可信度、Trace 脱敏。

### 1.2 范围裁剪

- 本次包含：
  - 基于用户目标、Top 推荐项目和规则评分证据生成 7 天学习任务。
  - 复用 `learning_goal`、`learning_task` 表持久化学习目标与任务。
  - `/api/agent/ask` 响应中追加学习计划摘要，保持原字段兼容。
  - 新增学习计划查询接口和任务状态更新接口。
  - 在 Trace 中记录学习计划生成与持久化摘要。
  - 单元测试覆盖生成、持久化映射、API 校验和禁用持久化 fallback。
- 本次不包含：
  - 前端页面、日历提醒、复杂任务调度。
  - 多用户、多租户、登录鉴权。
  - Redis 缓存、异步任务、MQ。
  - RAG、向量检索、Streaming/SSE。
  - 新增数据库迁移框架。
- 后续可能拆分：
  - 学习计划版本管理、计划重新生成。
  - 项目模块/目录结构深度拆解。
  - 结合真实完成记录的进度分析。

## 2. Research Findings

### 2.1 相关入口与链路

- HTTP/API：`openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java` 当前提供 `POST /api/agent/ask` 和 `GET /api/agent/traces/{traceId}`。
- Agent 编排：`openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java` 的 `askMock()` / `askReal()` 都完成 `goalInterpreter -> collector -> scoreAndRank -> persistReposIfEnabled -> answerGenerator -> trace complete`。
- 响应契约：`openscout-agent-server/src/main/java/com/openscout/agent/AgentAskResponse.java` 当前字段为 `traceId`、`answer`、`recommendations`、`latencyMs`。
- 推荐模型：`openscout-agent-server/src/main/java/com/openscout/agent/ProjectRecommendation.java` 包含 `fullName`、`description`、`language`、`stars`、`updatedAt`、`score`、`reason`。
- 评分证据：`openscout-agent-server/src/main/java/com/openscout/scoring/ProjectScoreService.java` 产出五维评分和 evidence，LLM 不得覆盖规则评分。
- 数据库表：`deploy/init.sql` 已存在 `learning_goal` 和 `learning_task`，字段足以支持 7 天任务、状态和查询。
- 持久化模式：`openscout-agent-server/src/main/java/com/openscout/persistence/repo/RepoPersistenceService.java` 与 `persistence/analysis/RepoAnalysisPersistenceService.java` 使用 Entity + Mapper + Service，失败时记录 warn，不阻塞主流程。
- 配置：`openscout-agent-server/src/main/resources/application.yml` 已有 `openscout.persistence.enabled` 默认 `false`，保证未启动 MySQL 时 ask 主流程可运行。
- Trace：`openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java` 提供 `recordToolCall()`、`complete()`、`fail()`，并统一做敏感字段脱敏和长度截断。
- 测试：现有 Java 测试覆盖 `AnswerGeneratorTest`、`GoalInterpreterTest`、`ProjectScoreServiceTest`、`TraceServiceTest`。

### 2.2 现有实现摘要

- 当前推荐流程只返回推荐结果和自然语言回答，不生成学习计划。
- `learning_goal` / `learning_task` 已在 DDL 中定义，但 Java 侧没有 Entity、Mapper、Service、Controller。
- 持久化默认关闭；阶段 7 需要保持默认 mock 演示不依赖 MySQL，同时在启用持久化时满足“可查询、可更新状态”。
- 阶段 6 引入 LLM fallback 模式，阶段 7 可以复用该模式，但学习计划任务必须以规则评分、项目元数据和已有证据为事实来源。

### 2.3 发现的问题

- `AgentAskResponse` 没有学习计划字段，客户端无法在 ask 后直接拿到 7 天任务。
- 当前 API 没有学习计划查询和任务状态更新入口。
- 现有 DDL 没有用户/会话维度；MVP 只能按 `goal_id` 查询，不做用户隔离。
- `learning_goal` 没有显式关联 `trace_id` 或 `repo_full_name` 字段；本阶段先把推荐项目上下文写入 task 文本和 `target_stack`，不改 DDL，降低迁移风险。
- 默认 `openscout.persistence.enabled=false` 时不能持久化学习计划；需要在响应中明确 `persisted=false`，查询/更新接口只在持久化启用且有数据时可用。

### 2.4 风险初判

- 数据库：新增 Mapper/Entity/Service 需要保证 JSON 或文本字段长度、状态更新条件和不存在数据时的 404 行为。
- API 契约：`/api/agent/ask` 只能做向后兼容字段追加，不移除或改名现有字段。
- LLM 输出可信度：LLM 只能辅助生成任务表达，不能编造项目能力；默认规则模板必须可用。
- 安全：任务内容和 Trace 摘要不得保存完整 prompt、Key、Token 或超长 README。
- 状态流转：任务状态限制为 `TODO`、`DOING`、`DONE`，非法状态返回 400。

## 3. 功能点

- [x] 功能 1：新增学习计划领域模型，表示 `LearningPlanResponse`、`LearningTaskResponse`、任务状态和持久化状态。
- [x] 功能 2：新增学习计划生成器，根据用户目标、Top 推荐项目、评分证据生成固定 7 天任务；LLM 可用时只做文案增强，失败自动 fallback。
- [x] 功能 3：新增 `learning_goal` / `learning_task` Java Entity、Mapper、PersistenceService，复用 MyBatis-Plus 模式。
- [x] 功能 4：在 `AgentService` 中生成学习计划，并将计划摘要追加到 `AgentAskResponse`；持久化关闭时返回 `persisted=false`。
- [x] 功能 5：新增学习计划查询接口，按 `goalId` 返回目标和任务列表。
- [x] 功能 6：新增任务状态更新接口，支持 `TODO`、`DOING`、`DONE`。
- [x] 功能 7：Trace 增强，记录 `learning_plan_generate` 和 `learning_plan_persist` 工具调用摘要。
- [x] 功能 8：补充测试、README、API 契约和项目实施进度。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| DB | `learning_goal` | 复用已有字段：`goal_text`、`target_stack`、`duration_days` | 不改 DDL | 删除新增 Java 代码即可回退 |
| DB | `learning_task` | 复用已有字段：`goal_id`、`day_no`、`task_title`、`task_detail`、`expected_output`、`status` | 不改 DDL | 删除新增 Java 代码即可回退 |
| Config | `openscout.learning.enabled` | 新增学习计划生成开关，默认 `true` | 新增配置不影响旧配置 | 设置为 `false` 关闭 |
| Response | `AgentAskResponse.learningPlan` | 追加可空字段 | 向后兼容 | 客户端可忽略新字段 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | 复用 `AgentAskRequest` | 追加 `learningPlan` 字段，包含 `goalId`、`persisted`、`durationDays`、`tasks` | MVP 不新增鉴权 | 向后兼容字段追加 |
| `/api/learning/goals/{goalId}` | GET | path `goalId` | 学习目标和任务列表 | MVP 不新增鉴权，文档标注不应公网暴露 | 新接口 |
| `/api/learning/tasks/{taskId}/status` | PATCH | `{"status":"TODO|DOING|DONE"}` | 更新后的任务 | MVP 不新增鉴权，校验状态值 | 新接口 |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| DeepSeek/OpenAI 兼容 ChatClient | 复用 Spring AI ChatClient | 用户目标 + Top 推荐摘要 + 评分证据摘要 | 任务文案 JSON 或纯文本 | 复用 `openscout.llm.timeout-seconds` | 失败 fallback 到规则模板 |
| MySQL | MyBatis-Plus Mapper | `learning_goal` / `learning_task` insert/select/update | 影响行数/实体 | 无重试 | 写入失败时 `ask` 主流程不失败，响应 `persisted=false` |

## 6. 风险与关注点

- 规则评分仍是事实来源：学习计划不得修改推荐排序、评分或 evidence。
- LLM 输出需要 JSON 解析保护和长度控制；解析失败不影响主流程。
- `/api/learning/*` 当前无鉴权，只适合本地 Demo；README 必须明确不建议公网暴露。
- 持久化关闭时无法查询/更新 `goalId=null` 的临时计划，响应需要让客户端可判断。
- 任务状态更新必须校验状态枚举，避免任意字符串污染数据。
- 如果 MySQL 已存在旧表，本阶段不做 schema migration；因为本 proposal 不改表结构。

## 7. 测试策略

- 单元测试：
  - 学习计划生成器：有推荐项目生成 7 天任务；无推荐项目返回空计划或明确提示。
  - LLM 不可用 / 禁用时 fallback 到规则模板。
  - 状态枚举解析和非法状态校验。
- 持久化测试：
  - Entity/Service 映射：保存 goal + 7 tasks，按 goalId 查询，更新 task status。
  - 持久化关闭时 `ask` 主流程不依赖数据库。
- Controller 测试：
  - 查询不存在 goal 返回 404。
  - 非法 status 返回 400。
- 回归验证：
  - `cd openscout-agent-server && mvn test`
  - `cd openscout-repo-collector && go test ./...`
  - `docker compose -f deploy/docker-compose.yml config`
  - 启用 MySQL 后 curl 验证 ask 返回 `learningPlan.persisted=true`，查询与状态更新成功。

## 8. 待澄清

- [x] 无阻塞待澄清项。本阶段默认先实现本地 Demo 级学习计划，不引入用户体系和公网鉴权。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| 计划生成方式 | 规则模板为主，LLM 可用时文案增强 | 完全 LLM 生成 | 保证可测试、可复现，避免模型编造事实 | 任务内容更稳定 |
| 表结构 | 复用已有 `learning_goal` / `learning_task` | 新增 trace/repo 字段 | 当前验收不要求复杂关联，减少迁移风险 | repo 上下文写入 task 文本和 target_stack |
| ask 响应 | 追加 `learningPlan` 可空字段 | 新建独立生成接口 | ask 后用户可直接看到下一步学习任务 | 需要更新响应 DTO 和文档 |
| 持久化关闭行为 | 生成临时计划，`persisted=false` | 直接跳过学习计划 | 保持默认无 DB demo 体验 | 查询/更新仅对 persisted 计划有效 |
| 状态枚举 | `TODO`、`DOING`、`DONE` | 自由文本 | 降低状态污染和前端适配成本 | 需要 400 校验 |

## 10. 确认记录

- 确认时间：2026-05-31。
- 确认人：用户确认执行 `/apply`。
- 确认范围：阶段 7 学习计划生成、持久化、查询和任务状态更新。
- `/apply` 完成时间：2026-05-31。全部 6 个 Task 实现完成，Java/Go/Compose 自动化验证通过；review 阶段补充真实 MySQL + Go + Java 长驻服务 curl 验证，ask/query/patch 链路通过。
