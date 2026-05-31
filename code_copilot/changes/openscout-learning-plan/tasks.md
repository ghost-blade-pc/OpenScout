# 任务拆分 - 阶段 7 学习计划与任务持久化

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已检查 `code_copilot/knowledge/index.md`
- [x] 已检查工作区状态，确认不会覆盖他人修改
- [x] 已创建阶段分支 `feature/06-learning-plan`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已确认本地验证命令或替代验证方式

## Task 1: 学习计划领域模型与生成器

- **目标**：定义学习计划响应、任务响应、状态枚举和 7 天任务生成逻辑。
- **层级/模块**：领域层 / 应用服务
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/learning/`：新增模型与生成器。
  - `openscout-agent-server/src/test/java/com/openscout/learning/`：新增生成器测试。
- **依赖**：无
- **风险标记**：模型输出可信度
- **实现要点**：
  - 根据用户目标、Top `ProjectRecommendation`、评分 evidence 生成 7 个 day task。
  - LLM 可用时只增强文案，失败 fallback 到规则模板。
  - 不编造项目模块；无法确定的信息写成“阅读 README/示例确认”。
- **验收标准**：
  - 有推荐项目时稳定生成 7 天任务。
  - 无推荐项目时返回空计划或明确提示，不抛出异常。
  - 单测覆盖 LLM 禁用 fallback。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/learning/LearningPlanGenerator.java`
    - `openscout-agent-server/src/main/java/com/openscout/learning/LearningPlanResponse.java`
    - `openscout-agent-server/src/main/java/com/openscout/learning/LearningTaskResponse.java`
    - `openscout-agent-server/src/main/java/com/openscout/learning/LearningTaskStatus.java`
    - `openscout-agent-server/src/main/java/com/openscout/learning/UpdateTaskStatusRequest.java`
    - `openscout-agent-server/src/test/java/com/openscout/learning/LearningPlanGeneratorTest.java`
  - 验证结果：`mvn test` 通过，学习计划生成器和状态枚举测试覆盖。

## Task 2: 学习计划持久化服务

- **目标**：实现 `learning_goal`、`learning_task` 的 Entity、Mapper、PersistenceService。
- **层级/模块**：基础设施 / 持久化
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/persistence/learning/`：新增 Entity、Mapper、Service。
  - `deploy/init.sql`：原则上不改表结构；如实现发现字段不足，先更新 spec 再改。
- **依赖**：Task 1
- **风险标记**：数据库 / 状态流转
- **实现要点**：
  - 保存 goal 后批量插入 7 条 task。
  - 按 goalId 查询目标和任务，按 day_no 排序。
  - 更新 task status 前校验枚举。
  - 持久化异常不影响 `/api/agent/ask` 主流程。
- **验收标准**：
  - 启用持久化时 ask 后可查到 goal 和 7 条 task。
  - 非法状态不写入数据库。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/persistence/learning/LearningGoalEntity.java`
    - `openscout-agent-server/src/main/java/com/openscout/persistence/learning/LearningTaskEntity.java`
    - `openscout-agent-server/src/main/java/com/openscout/persistence/learning/LearningGoalMapper.java`
    - `openscout-agent-server/src/main/java/com/openscout/persistence/learning/LearningTaskMapper.java`
    - `openscout-agent-server/src/main/java/com/openscout/persistence/learning/LearningPlanPersistenceService.java`
    - `openscout-agent-server/src/test/java/com/openscout/persistence/learning/LearningPlanPersistenceServiceTest.java`
  - 验证结果：`mvn test` 通过，保存 goal/tasks、查询排序、状态更新测试覆盖。

## Task 3: AgentService 集成学习计划

- **目标**：在推荐结果生成后创建学习计划，并追加到 ask 响应。
- **层级/模块**：应用服务 / API 契约
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/AgentAskResponse.java`
  - `openscout-agent-server/src/main/resources/application.yml`
- **依赖**：Task 1、Task 2
- **风险标记**：API 兼容 / 配置
- **实现要点**：
  - 新增 `openscout.learning.enabled`，默认 `true`。
  - 保留 `traceId`、`answer`、`recommendations`、`latencyMs` 现有字段。
  - 持久化关闭时返回 `learningPlan.persisted=false`，不写数据库。
  - 记录 `learning_plan_generate`、`learning_plan_persist` Trace。
- **验收标准**：
  - 老客户端仍可读取原字段。
  - 默认无 MySQL 环境下 `mvn test` 和 mock ask 不退化。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/AgentService.java`
    - `openscout-agent-server/src/main/java/com/openscout/agent/AgentAskResponse.java`
    - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`
    - `openscout-agent-server/src/main/resources/application.yml`
  - 验证结果：`mvn test` 通过；响应追加 `learningPlan`，Trace 记录 `learning_plan_generate` / `learning_plan_persist`。

## Task 4: 学习计划查询与任务状态 API

- **目标**：提供查询学习目标和更新任务状态的 HTTP 接口。
- **层级/模块**：API / 入口层
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/learning/LearningController.java`
  - `openscout-agent-server/src/main/java/com/openscout/learning/UpdateTaskStatusRequest.java`
- **依赖**：Task 2
- **风险标记**：接口安全 / 输入校验
- **实现要点**：
  - `GET /api/learning/goals/{goalId}`：返回 goal 和 task 列表。
  - `PATCH /api/learning/tasks/{taskId}/status`：只允许 `TODO`、`DOING`、`DONE`。
  - 不存在记录返回 404；非法 status 返回 400。
  - 文档说明 MVP 无鉴权，仅限本地 Demo。
- **验收标准**：
  - 查询存在 goal 返回 200。
  - 查询不存在 goal 返回 404。
  - 非法状态返回 400，合法状态更新成功。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/learning/LearningController.java`
    - `openscout-agent-server/src/main/java/com/openscout/learning/UpdateTaskStatusRequest.java`
    - `openscout-agent-server/src/test/java/com/openscout/learning/LearningControllerTest.java`
  - 验证结果：`mvn test` 通过；查询成功、404、状态更新、400 响应测试覆盖。

## Task 5: 测试与本地验证

- **目标**：补齐阶段 7 自动化测试和最小 curl 验证口径。
- **层级/模块**：测试
- **涉及文件**：
  - `openscout-agent-server/src/test/java/com/openscout/learning/`
  - 可能更新既有 `AgentService` / Controller 测试。
- **依赖**：Task 1-4
- **风险标记**：回归验证
- **实现要点**：
  - 生成器单测。
  - 持久化服务单测或轻量 slice 测试。
  - Controller 校验测试。
  - 回归 Java、Go、Docker config。
- **验收标准**：
  - `mvn test` 通过。
  - `go test ./...` 通过。
  - `docker compose config` 通过。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  cd openscout-repo-collector && go test ./...
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/test/java/com/openscout/learning/LearningPlanGeneratorTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/learning/LearningControllerTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/persistence/learning/LearningPlanPersistenceServiceTest.java`
  - 验证结果：`mvn test` 通过（22 tests）；`go test ./...` 通过；`docker compose -f deploy/docker-compose.yml config` 通过。

## Task 6: 文档与 code_copilot 同步

- **目标**：同步 README、API 契约、实施进度和 change 文档。
- **层级/模块**：文档 / SpecAI
- **涉及文件**：
  - `README.md`
  - `docs/api-contract.md`
  - `项目实施进度.md`
  - `code_copilot/changes/openscout-learning-plan/spec.md`
  - `code_copilot/changes/openscout-learning-plan/tasks.md`
  - `code_copilot/changes/openscout-learning-plan/test-spec.md`
  - `code_copilot/changes/openscout-learning-plan/log.md`
- **依赖**：Task 1-5
- **风险标记**：文档与实现一致性
- **实现要点**：
  - 文档记录 `learningPlan.persisted`、查询接口、状态更新接口。
  - 实施进度记录阶段 7 结果、验证命令和未完成项。
  - `/apply` 完成后回填任务完成记录和实际验证结果。
- **验收标准**：
  - 文档中的接口和实际代码一致。
  - `log.md` 有真实执行记录。
- **验证命令**：
  ```bash
  rg -n "learningPlan|/api/learning|阶段 7" README.md docs/api-contract.md 项目实施进度.md code_copilot/changes/openscout-learning-plan
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `README.md`
    - `docs/api-contract.md`
    - `项目实施进度.md`
    - `code_copilot/changes/openscout-learning-plan/spec.md`
    - `code_copilot/changes/openscout-learning-plan/tasks.md`
    - `code_copilot/changes/openscout-learning-plan/test-spec.md`
    - `code_copilot/changes/openscout-learning-plan/log.md`
  - 验证结果：文档已记录 `learningPlan`、`/api/learning/*`、验证命令和剩余边界。

## 变更摘要

> `/apply` 完成后填写。

- **总文件数**：25
- **新增文件**：18
- **修改文件**：7
- **删除文件**：0
- **Spec-Plan 偏差记录**：未修改 DDL，按 proposal 复用已有 `learning_goal` / `learning_task`；review 阶段已补充 MySQL + Go + Java 长驻服务 curl 验证。
- **未完成项**：未做前端、鉴权、多用户隔离、计划版本管理，均属于 proposal 明确不做范围。
- **遗留风险**：`/api/learning/*` 仍是本地 Demo 接口，公网暴露前必须补鉴权和用户隔离；LLM 计划增强只作为文案辅助，失败自动 fallback。
