# 测试计划 - OpenScout MVP 基础骨架与核心推荐闭环

## 1. 测试目标

验证第一阶段 MVP 是否满足“能运行、能演示、能讲清楚”的目标，重点覆盖 mock 端到端链路、Go Collector 基础能力、Java 调用编排、规则评分、Trace 和配置安全。

## 2. 测试范围

- P0：服务启动、Docker 依赖、mock repo、Java `/api/agent/ask`、规则评分、基础 Trace。
- P1：真实 GitHub API 可选链路、进程内 TTL 缓存、Go batch 部分失败、模型未配置 fallback；Redis adapter 后续补齐。
- 不覆盖：复杂前端、完整多项目对比、生产部署、权限系统、MCP。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| Go mock repo | 接口测试 | `GET /api/repos/mock` | 返回固定 repo JSON | P0 |
| Java ask mock | 端到端测试 | `POST /api/agent/ask` + 学习目标 | 返回推荐项目、评分、traceId | P0 |
| 评分规则稳定 | 单元测试 | 固定 repo metadata | 总分和分项分数稳定，包含 evidence | P0 |
| Trace 脱敏 | 单元/集成测试 | 带配置环境的请求 | Trace 不包含 token/key/完整 README | P0 |
| Go batch 部分失败 | 单元/接口测试 | repo list 中包含非法 repo | 成功项与失败项同时返回 | P1 |
| GitHub Token 未配置 | 接口/配置测试 | 真实模式但无 token | 明确提示或降级，不崩溃 | P1 |
| Go 缓存命中 | 单元/集成测试 | 连续请求同一 repo | 第二次命中进程内缓存或减少外部调用 | P1 |
| 模型未配置 fallback | 集成测试 | 无模型 API Key | 返回规则评分和模型不可用提示 | P1 |

## 4. 验证命令

```bash
docker compose -f deploy/docker-compose.yml up -d
docker compose -f deploy/docker-compose.yml config
cd openscout-agent-server && mvn test
cd openscout-agent-server && mvn spring-boot:run
cd openscout-repo-collector && go test ./...
cd openscout-repo-collector && go run ./cmd/server
curl http://localhost:8081/api/repos/mock
curl -X POST http://localhost:8080/api/agent/ask -H 'Content-Type: application/json' -d '{"question":"我想学习 Spring AI Agent"}'
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-05-29 | `cd openscout-agent-server && mvn test` | 通过 | 2 个 Java 单测通过，覆盖评分和 Trace 摘要脱敏/截断 |
| 2026-05-29 | `docker compose -f deploy/docker-compose.yml config` | 通过 | Compose YAML 和 MySQL/Redis 服务配置可解析 |
| 2026-05-29 | `cd openscout-repo-collector && go version` | 失败 | `/bin/bash: line 1: go: command not found`，本机未安装 Go |
| 2026-05-29 | `cd openscout-repo-collector && gofmt -w cmd internal` | 失败 | `/bin/bash: line 1: gofmt: command not found`，本机未安装 Go |
| 2026-05-30 | `cd openscout-repo-collector && go test ./...` | 通过 | 阶段 2/3 使用项目本地 Go 工具链补跑，`internal/cache`、`internal/service` 测试通过 |
| 2026-05-30 | `curl http://localhost:8081/health` | 通过 | 阶段 3 回填验证，HTTP 200，`status=UP` |
| 2026-05-30 | `curl http://localhost:8081/api/repos/mock` | 通过 | 阶段 3 回填验证，HTTP 200，返回 3 个 mock 项目 |
| 2026-05-30 | `curl -X POST http://localhost:8080/api/agent/ask ...` | 通过 | 阶段 3 回填验证，HTTP 200，返回 `traceId`、3 个推荐项目和评分 |
| 2026-05-30 | `curl http://localhost:8080/api/agent/traces/<traceId>` | 通过 | 阶段 3 回填验证，HTTP 200，包含 `repo_search_mock` 工具调用 |
| 2026-05-30 | 停止 Go 后调用 Java `/api/agent/ask` | 通过 | 阶段 3 回填验证，HTTP 502，返回失败 traceId 和 `Connection refused` 摘要 |
