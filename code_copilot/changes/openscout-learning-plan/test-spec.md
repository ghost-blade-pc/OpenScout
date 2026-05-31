# 测试计划 - 阶段 7 学习计划与任务持久化

## 1. 测试目标

验证阶段 7 能基于推荐项目生成 7 天学习计划，在持久化启用时保存并支持查询、状态更新，同时保持默认无 MySQL 环境下的 ask 主流程可运行。

## 2. 测试范围

- P0：
  - 学习计划生成器规则 fallback。
  - `AgentService` 追加 `learningPlan` 且不破坏原响应字段。
  - `learning_goal` / `learning_task` 保存、查询、状态更新。
  - 非法任务状态校验。
  - 持久化关闭时 `persisted=false`，主流程不失败。
- P1：
  - LLM 可用时文案增强，解析失败 fallback。
  - Trace 中出现 `learning_plan_generate` / `learning_plan_persist`。
  - curl 验证 ask -> 查询 -> 状态更新链路。
- 不覆盖：
  - 前端交互。
  - 多用户权限隔离。
  - Redis 缓存。
  - 真实 DeepSeek 输出质量评测。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| 有 Top 推荐项目生成 7 天任务 | 单元测试 | goal + `ProjectRecommendation` | 返回 7 条 dayNo=1..7 的任务 | P0 |
| 无推荐项目 | 单元测试 | 空 recommendations | 返回空计划或明确无法生成提示，不抛异常 | P0 |
| LLM 禁用 fallback | 单元测试 | `openscout.llm.enabled=false` | 使用规则模板生成 | P0 |
| ask 默认无 DB | 单元/集成测试 | `openscout.persistence.enabled=false` | 返回 `learningPlan.persisted=false`，ask 成功 | P0 |
| 持久化保存 | 持久化测试 | goal + 7 tasks | 插入 1 条 goal + 7 条 task | P0 |
| 查询 goal | Controller 测试 | 已存在 goalId | 返回 goal 和按 dayNo 排序的任务 | P0 |
| 查询不存在 goal | Controller 测试 | 不存在 goalId | HTTP 404 | P0 |
| 更新合法状态 | Controller 测试 | `{"status":"DONE"}` | 返回更新后的 task，DB status=DONE | P0 |
| 更新非法状态 | Controller 测试 | `{"status":"INVALID"}` | HTTP 400，不写 DB | P0 |
| Trace 记录 | 集成测试 | ask 生成计划 | Trace toolCalls 含学习计划工具调用摘要 | P1 |
| MySQL curl 验证 | 手工验证 | ask -> query -> patch | 三步均成功 | P1 |

## 4. 验证命令

```bash
cd openscout-agent-server && mvn test
cd openscout-repo-collector && go test ./...
docker compose -f deploy/docker-compose.yml config
```

启用 MySQL 的手工验证建议：

```bash
docker compose -f deploy/docker-compose.yml up -d mysql
docker exec -i openscout-mysql mysql -uopenscout -popenscout openscout < deploy/init.sql
cd openscout-agent-server && OPSCOUT_PERSISTENCE_ENABLED=true mvn spring-boot:run
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-05-31 | `cd openscout-agent-server && mvn test` | 通过 | 22 tests, 0 failures |
| 2026-05-31 | `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...` | 通过 | 使用项目本地 Go 1.26.3 |
| 2026-05-31 | `docker compose -f deploy/docker-compose.yml config` | 通过 | Compose 配置可解析 |
| 2026-05-31 | MySQL + Go + Java 长驻服务 curl 验证 | 通过 | ask 返回 `learningPlan.persisted=true`；goal 查询 7 条任务；PATCH task status 成功；DB 记录落库 |
