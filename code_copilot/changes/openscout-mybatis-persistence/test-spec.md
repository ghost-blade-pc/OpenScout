# 测试计划 - OpenScout MyBatis-Plus 持久化

## 1. 测试目标

验证阶段 4 的 MyBatis-Plus 持久化是否在不破坏阶段 3 mock 演示的前提下可用：默认持久化关闭时 Java 单测和 mock 链路保持可用；显式启用持久化并启动 MySQL 后，`agent_trace`、`repo_info`、`repo_analysis` 能保存阶段 3 mock ask 产生的数据，Trace 可跨 Java 进程查询。

## 2. 测试范围

- P0：默认配置下 `mvn test` 通过，不依赖 MySQL。
- P0：Docker Compose config 通过。
- P0：MySQL 启动后，启用 `OPSCOUT_PERSISTENCE_ENABLED=true` 的 Trace 持久化测试通过。
- P0：启用持久化后 `/api/agent/ask` 成功返回，同时 `agent_trace`、`repo_info`、`repo_analysis` 有数据。
- P0：Java 重启后 `/api/agent/traces/{traceId}` 仍可返回 Trace。
- P0：敏感字段脱敏和长度截断仍有效，数据库不保存 token/key/authorization/password/secret 原文。
- P1：重复 mock ask 不因 `repo_info.full_name` 唯一索引失败。
- P1：Collector 不可用时 Java 返回 502，并保存 FAILED Trace。
- 不覆盖：真实 GitHub API、Redis adapter、Spring AI DeepSeek、学习计划持久化、生产鉴权。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| 默认单测 | 单元测试 | `cd openscout-agent-server && mvn test` | 通过，不要求 MySQL 运行 | P0 |
| Compose 配置校验 | 配置测试 | `docker compose -f deploy/docker-compose.yml config` | 命令通过 | P0 |
| Trace 脱敏 | 单元测试 | 包含 token/key/authorization 的文本 | 输出和数据库摘要均不含敏感原文 | P0 |
| Trace 成功落库 | 集成测试 | 启用持久化后调用 Java ask | `agent_trace.status=SUCCESS`，含 tool call 摘要 | P0 |
| Trace 失败落库 | 集成测试 | 停止 Go 后调用 Java ask | HTTP 502，`agent_trace.status=FAILED`，含错误摘要 | P0 |
| Trace 重启后查询 | 端到端测试 | Java 重启后查询旧 traceId | HTTP 200，Trace 从 MySQL 恢复 | P0 |
| repo_info 幂等保存 | 集成测试 | 重复调用同一个 mock goal | 不触发唯一索引错误，项目基础信息可查 | P1 |
| repo_analysis 保存 | 集成测试 | mock ask 返回 3 个推荐项目 | 保存 total_score、score_breakdown_json、evidence_json | P1 |

## 4. 验证命令

```bash
docker compose -f deploy/docker-compose.yml config
docker compose -f deploy/docker-compose.yml up -d mysql

cd openscout-agent-server && mvn test

cd openscout-repo-collector && go run ./cmd/server

cd openscout-agent-server && OPSCOUT_PERSISTENCE_ENABLED=true mvn spring-boot:run

curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想一周内学习 Spring AI Agent，帮我找几个适合学习的开源项目"}'

curl http://localhost:8080/api/agent/traces/<traceId>

docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select trace_id,status,latency_ms from agent_trace order by id desc limit 5;"

docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select full_name,language,stars from repo_info order by id desc limit 5;"

docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select full_name,total_score from repo_analysis order by id desc limit 5;"
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-05-30 | docker compose config | 通过 | apply 阶段 |
| 2026-05-30 | mvn test | 通过 (2 tests) | apply 阶段 |
| 2026-05-30 | 持久化端到端 curl + MySQL 查询 | 全部通过（10项） | apply 阶段 |
