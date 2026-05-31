# 执行日志 - 阶段 7 学习计划与任务持久化

## 基本信息

- change：`openscout-learning-plan`
- status：`done`
- created：2026-05-31
- last_updated：2026-05-31

## Research 记录

- 读取 `code_copilot/README.md`、`code_copilot/rules/project-context.md`、`code_copilot/rules/coding-style.md`、`code_copilot/rules/domain-rules.md`、`code_copilot/rules/security.md`。
- 读取 `code_copilot/agents/copilot-prompt.md` 和 `code_copilot/knowledge/index.md`。
- 读取 `项目实施进度.md`，确认阶段 7 分支为 `feature/06-learning-plan`，阶段目标为生成并持久化学习路径与任务。
- 检查当前分支和工作区状态：从 `main` 创建并切换到 `feature/06-learning-plan`，创建前工作区干净。
- 代码事实：
  - `AgentController` 当前只有 `/api/agent/ask` 与 `/api/agent/traces/{traceId}`。
  - `AgentService` 当前完成推荐编排、评分、回答生成和 Trace 完成记录。
  - `AgentAskResponse` 当前仅包含 `traceId`、`answer`、`recommendations`、`latencyMs`。
  - `deploy/init.sql` 已包含 `learning_goal` 与 `learning_task` 表。
  - Java 侧尚无 `learning` package、学习计划 Mapper、Service、Controller。

## 执行记录

| 时间 | 动作 | 文件 | 结果 |
|---|---|---|---|
| 2026-05-31 | 创建阶段分支 | Git `feature/06-learning-plan` | 成功 |
| 2026-05-31 | 创建 proposal | `code_copilot/changes/openscout-learning-plan/spec.md` | 已创建 |
| 2026-05-31 | 创建任务拆分 | `code_copilot/changes/openscout-learning-plan/tasks.md` | 已创建 |
| 2026-05-31 | 创建测试计划 | `code_copilot/changes/openscout-learning-plan/test-spec.md` | 已创建 |
| 2026-05-31 | 创建执行日志 | `code_copilot/changes/openscout-learning-plan/log.md` | 已创建 |
| 2026-05-31 | 实现学习计划生成器和响应模型 | `openscout-agent-server/src/main/java/com/openscout/learning/` | 已完成 |
| 2026-05-31 | 实现学习计划持久化 | `openscout-agent-server/src/main/java/com/openscout/persistence/learning/` | 已完成 |
| 2026-05-31 | 集成 ask 响应和 Trace | `AgentService.java`, `AgentAskResponse.java`, `application.yml` | 已完成 |
| 2026-05-31 | 新增学习计划查询和任务状态接口 | `LearningController.java` | 已完成 |
| 2026-05-31 | 新增阶段 7 测试 | `src/test/java/com/openscout/learning/`, `src/test/java/com/openscout/persistence/learning/` | 已完成 |
| 2026-05-31 | 同步用户文档 | `README.md`, `docs/api-contract.md`, `项目实施进度.md` | 已完成 |
| 2026-05-31 | review 补充真实环境验证 | MySQL + Go Collector + Java Agent + curl | 通过 |
| 2026-05-31 | achieve 沉淀阶段知识 | `code_copilot/knowledge/index.md`, `项目实施进度.md` | 已完成 |

## 决策记录

- change id 使用 `openscout-learning-plan`，对应实施进度阶段 7。
- 本阶段不改 DDL，优先复用已存在的 `learning_goal`、`learning_task` 表。
- 学习计划生成采用“规则模板为主、LLM 文案增强可选”的策略，保证无 Key 和模型失败时可复现。
- `/api/agent/ask` 只追加 `learningPlan` 字段，避免破坏现有响应契约。
- 持久化关闭时仍生成临时计划，但返回 `persisted=false`；查询和状态更新仅面向已持久化计划。
- LLM 学习计划增强增加 JSON markdown block 提取和 dayNo 范围校验；解析失败或输出不合规时自动 fallback 到规则模板。

## 验证记录

- `cd openscout-agent-server && mvn test`：通过，22 tests，0 failures。
- `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...`：通过，使用项目本地 Go 1.26.3。
- `docker compose -f deploy/docker-compose.yml config`：通过。
- `docker compose -f deploy/docker-compose.yml up -d mysql`：通过，MySQL 容器启动。
- `docker exec openscout-mysql mysql -uopenscout -popenscout openscout -e "source /docker-entrypoint-initdb.d/init.sql"`：通过，schema 初始化。
- `source ../scripts/use-local-tools.sh && go run ./cmd/server`：通过（提升权限启动本地 8081），`GET /health` 返回 200 `{"status":"UP"}`。
- `OPSCOUT_PERSISTENCE_ENABLED=true OPSCOUT_LLM_ENABLED=false SPRING_AI_MODEL_CHAT=none mvn spring-boot:run`：通过（提升权限启动本地 8080），Java Agent 启动并连接 MySQL。
- `POST /api/agent/ask`：HTTP 200；响应 `traceId=9170526e-eb01-4c83-9056-db402e997880`、`learningPlan.goalId=1`、`learningPlan.persisted=true`、`tasks=7`、`firstTaskId=1`、`recommendations=3`。
- `GET /api/learning/goals/1`：HTTP 200；返回 7 条任务，`dayNo=1..7`，初始状态 `TODO`。
- `PATCH /api/learning/tasks/1/status {"status":"DONE"}`：HTTP 200；返回 `id=1`、`dayNo=1`、`status=DONE`。
- `GET /api/agent/traces/9170526e-eb01-4c83-9056-db402e997880`：HTTP 200；Trace status=`SUCCESS`，toolCalls 包含 `repo_search_mock`、`learning_plan_generate`、`learning_plan_persist`。
- MySQL 查询验证：`learning_goal_count=1`、`learning_task_count where goal_id=1 = 7`、`learning_task id=1 status=DONE`、对应 `agent_trace` 记录数为 1、`repo_info_count=3`。
- 负向接口验证：`PATCH /api/learning/tasks/1/status {"status":"INVALID"}` 返回 HTTP 400；`GET /api/learning/goals/999999` 返回 HTTP 404。
- 清理：本轮启动的 Java Agent、Go Collector 已停止；MySQL 容器已 stop。

## 遗留问题

- MVP 阶段学习计划接口无鉴权，仅适合本地 Demo，后续如对外暴露需补权限和用户隔离。
- 未做前端、计划版本管理、多用户隔离，均为本阶段明确不做范围。

## 归档记录

- 归档时间：2026-05-31。
- 归档状态：`done`。
- 知识沉淀：阶段 7 的学习计划生成、持久化、API 契约、Trace、真实环境验证和边界已写入 `code_copilot/knowledge/index.md`。
- 下一步建议：提交并合并 `feature/06-learning-plan` 回 `main`；公网暴露前补鉴权与用户隔离。
