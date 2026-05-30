# OpenScout Agent

OpenScout Agent 是一个面向开发者的开源项目情报分析与学习路径助手。第一阶段先实现可运行 MVP：Java 服务接收学习目标，调用 Go Repo Collector mock 接口获取候选项目，执行规则评分，返回推荐结果并记录 Agent Trace。

## 当前范围

- Java 17 + Spring Boot + MyBatis-Plus：提供 `/api/agent/ask`，执行 mock Agent 编排、规则评分和 Trace。
- Go 1.22 + Gin：提供 Repo Collector 接口，支持 mock 模式和可选 GitHub API 模式。
- MySQL + Redis：通过 Docker Compose 提供本地依赖；**持久化默认关闭**，启用方式见下方"持久化"章节。
- Spring AI + DeepSeek V4 Pro：配置位已预留，后续阶段再接真实 Tool Calling。

## 目录

```text
openscout-agent-server/      Java Agent 编排服务
openscout-repo-collector/    Go Repo Collector 服务
deploy/                      MySQL/Redis compose 与 init.sql
docs/                        架构、接口、Demo、简历材料
code_copilot/                SpecAI 工作区
```

## 本地启动

启动 MySQL 和 Redis：

```bash
docker compose -f deploy/docker-compose.yml up -d
```

启动 Go Collector：

```bash
cd openscout-repo-collector
go run ./cmd/server
```

启动 Java Agent Server：

```bash
cd openscout-agent-server
mvn spring-boot:run
```

调用 mock Agent：

```bash
curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想一周内学习 Spring AI Agent"}'
```

查看 Trace：

```bash
curl http://localhost:8080/api/agent/traces/<traceId>
```

## Go Collector API

```bash
curl http://localhost:8081/api/repos/mock
curl 'http://localhost:8081/api/repos/search?keyword=spring-ai-agent&limit=10'
curl http://localhost:8081/api/repos/spring-projects/spring-ai/profile
curl http://localhost:8081/api/repos/spring-projects/spring-ai/readme
curl -X POST http://localhost:8081/api/repos/batch-profile \
  -H 'Content-Type: application/json' \
  -d '{"repos":["spring-projects/spring-ai","langchain4j/langchain4j","bad"]}'
```

真实 GitHub API 模式：

```bash
export OPSCOUT_COLLECTOR_MODE=github
export GITHUB_TOKEN=<secret>
go run ./cmd/server
```

未配置 `GITHUB_TOKEN` 时也可以访问公开 API，但会受到更严格的频率限制。演示优先使用 mock 模式。

Spring AI 模型默认不启用，避免 mock 演示在未配置 Key 时启动失败。后续接入 DeepSeek 时再显式开启：

```bash
export SPRING_AI_MODEL_CHAT=openai
export DEEPSEEK_API_KEY=<secret>
```

## 持久化（阶段 4）

`agent_trace`、`repo_info`、`repo_analysis` 可通过 MyBatis-Plus 写入 MySQL。**持久化默认关闭**，以保持阶段 3 的纯 mock 演示不依赖 MySQL。

启用持久化：

```bash
export OPSCOUT_PERSISTENCE_ENABLED=true
docker compose -f deploy/docker-compose.yml up -d mysql
cd openscout-agent-server && mvn spring-boot:run
```

调用 `/api/agent/ask` 后验证数据落库：

```bash
docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select trace_id,status,latency_ms from agent_trace order by id desc limit 5;"

docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select full_name,language,stars from repo_info order by id desc limit 5;"

docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select full_name,total_score from repo_analysis order by id desc limit 5;"
```

重启 Java 后仍可通过 `/api/agent/traces/{traceId}` 从 MySQL 查询历史 Trace。

**重要约束**：
- Trace 只保存摘要和脱敏字段，不保存完整 README、完整 prompt、模型 Key 或 GitHub Token。
- `/api/agent/traces/{traceId}` 仅用于本地排障，未做鉴权，不应公网暴露。
- 重复 mock ask 对 `repo_info.full_name` 做幂等 upsert，不会因唯一索引冲突而失败。

## 验证

```bash
cd openscout-repo-collector && go test ./...
cd openscout-agent-server && mvn test
```

## 现实约束

- GitHub API 有 rate limit，真实模式必须设置超时、限流和错误兜底。
- Agent Trace 只保存摘要，不保存完整 README、完整 prompt、Token 或模型 Key。
- 第一阶段评分由规则产生，LLM 只负责解释，避免模型主观改分。
