# 测试计划 - OpenScout GitHub 真实 API 集成

## P0 — 自动化测试（必须通过）

### Go 单测

```bash
cd openscout-repo-collector && go test ./... && go vet ./...
```

- `service.TestMockRepos` — mock repo 创建不因签名变更而断裂。
- `service.TestBatchProfileAllowsPartialFailure` — 部分失败容错。
- `go vet ./...` — 无编译/静态分析问题。

### Java 单测

```bash
cd openscout-agent-server && mvn test
```

- 默认 `openscout.mock-agent=true` 模式下所有现有测试通过。
- 新增 DTO 和异常类编译无误。

### Docker Compose 配置校验

```bash
docker compose -f deploy/docker-compose.yml config
```

- MySQL/Redis 配置不变。

## P1 — 集成测试（手动验证）

### Go mock + Java 真实模式

```bash
# Terminal 1: Go Collector mock 模式（默认）
cd openscout-repo-collector && go run ./cmd/server

# Terminal 2: Java Agent 真实模式
cd openscout-agent-server && OPSCOUT_MOCK_AGENT=false mvn spring-boot:run

# Terminal 3: curl
curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想学习 Spring AI Agent"}'
```

期望：返回 200 + traceId，Go 端返回 mock 数据但路径走 search → readme enrich → score → respond。

### Go 不可用验证

```bash
# 停止 Go Collector
# 启动 Java Agent 真实模式
cd openscout-agent-server && OPSCOUT_MOCK_AGENT=false mvn spring-boot:run

curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"test"}'
```

期望：返回 502 + 失败 traceId + 含 "Go Collector 服务不可用" 的错误消息。

### Go 真实模式 + 无 Token

```bash
cd openscout-repo-collector && OPSCOUT_COLLECTOR_MODE=github go run ./cmd/server

curl 'http://localhost:8081/api/repos/search?keyword=spring&limit=5&mode=github'
```

期望：返回结构化 ErrorResponse（`{"error":"...","code":"FORBIDDEN|RATE_LIMITED","retryAfter":0}`），不再返回 `{"message":"..."}`。

## P2 — 真实 GitHub API（需 Token）

```bash
export GITHUB_TOKEN=<secret>
export OPSCOUT_COLLECTOR_MODE=github
cd openscout-repo-collector && go run ./cmd/server

curl 'http://localhost:8081/api/repos/search?keyword=spring-ai&limit=5&mode=github'
```

期望：返回真实 GitHub 搜索结果（`items` 数组含真实 repo 数据）。

## 测试环境

- JDK 17 + Maven
- Go 1.26.3（通过 `scripts/use-local-tools.sh` 启用）
- Docker Compose（MySQL/Redis）
- GitHub Token（P2 测试需配置）
