# 测试计划 - OpenScout mock 端到端演示验证

## 1. 测试目标

验证阶段 3 的 mock 端到端演示是否可复现：Go Collector 能提供 mock 项目列表，Java Agent Server 能调用 Go mock 接口完成规则评分和回答生成，Trace 查询能复盘本次工具调用。

## 2. 测试范围

- P0：Docker Compose 配置可解析。
- P0：Java 单测通过。
- P0：Go 单测通过。
- P0：Go `/health` 和 `/api/repos/mock` curl 通过。
- P0：Java `/api/agent/ask` curl 通过。
- P0：Java `/api/agent/traces/{traceId}` curl 通过。
- P1：Go Collector 不可用时，Java `/api/agent/ask` 返回 502 和错误摘要。

不覆盖：

- 真实 GitHub API。
- Spring AI + DeepSeek V4 Pro 真实模型调用。
- MyBatis-Plus 持久化 Mapper。
- Redis adapter。
- 生产部署和公网鉴权。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| Compose 配置校验 | 配置测试 | `docker compose -f deploy/docker-compose.yml config` | 命令通过 | P0 |
| Java 单测 | 单元测试 | `cd openscout-agent-server && mvn test` | 测试通过 | P0 |
| Go 单测 | 单元测试 | `cd openscout-repo-collector && go test ./...` | 测试通过 | P0 |
| Go 健康检查 | 接口测试 | `GET /health` | HTTP 200，`status=UP` | P0 |
| Go mock repo | 接口测试 | `GET /api/repos/mock` | HTTP 200，`items` 非空 | P0 |
| Java ask mock | 端到端测试 | `POST /api/agent/ask` | HTTP 200，返回 `traceId`、推荐项目和评分 | P0 |
| Trace 查询 | 接口测试 | `GET /api/agent/traces/{traceId}` | HTTP 200，`status=SUCCESS`，包含 `repo_search_mock` 工具调用 | P0 |
| Go 不可用降级 | 异常测试 | 停止 Go 后调用 Java ask | HTTP 502，响应含错误摘要和 `traceId` | P1 |

## 4. 验证命令

```bash
docker compose -f deploy/docker-compose.yml config

source scripts/use-local-tools.sh

(cd openscout-agent-server && mvn test)
(cd openscout-repo-collector && go test ./...)

cd openscout-repo-collector && go run ./cmd/server

curl http://localhost:8081/health
curl http://localhost:8081/api/repos/mock

cd openscout-agent-server && mvn spring-boot:run

curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想一周内学习 Spring AI Agent，帮我找几个适合学习的开源项目"}'

curl http://localhost:8080/api/agent/traces/<traceId>
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-05-30 | `docker compose -f deploy/docker-compose.yml config` | 通过 | review 后复核通过，MySQL/Redis 服务配置可解析 |
| 2026-05-30 | `cd openscout-agent-server && mvn test` | 通过 | 2 个测试，0 失败，0 错误 |
| 2026-05-30 | `cd openscout-repo-collector && go test ./...` | 通过 | `internal/cache`、`internal/service` 测试通过，其余包无测试文件 |
| 2026-05-30 | `curl http://localhost:8081/health` | 通过 | HTTP 200，`status=UP` |
| 2026-05-30 | `curl http://localhost:8081/api/repos/mock` | 通过 | HTTP 200，返回 3 个 mock 项目 |
| 2026-05-30 | `curl -X POST http://localhost:8080/api/agent/ask ...` | 通过 | HTTP 200，`traceId=3df5fa3e-2ba6-4305-9292-f837680f16e3`，推荐项目 3 个，第一推荐 `spring-projects/spring-ai`，评分 84 |
| 2026-05-30 | `curl http://localhost:8080/api/agent/traces/3df5fa3e-2ba6-4305-9292-f837680f16e3` | 通过 | HTTP 200，`status=SUCCESS`，包含 `repo_search_mock` 工具调用 |
| 2026-05-30 | 停止 Go 后调用 Java `/api/agent/ask` | 通过 | HTTP 502，返回失败 `traceId=def69eb9-1bae-4747-88f2-d770fb7873b6` 和 `Connection refused` 摘要 |
