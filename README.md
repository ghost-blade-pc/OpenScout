# OpenScout Agent

OpenScout Agent 是一个面向开发者的开源项目情报分析与学习路径助手。第一阶段先实现可运行 MVP：Java 服务接收学习目标，调用 Go Repo Collector mock 接口获取候选项目，执行规则评分，返回推荐结果并记录 Agent Trace。

## 当前范围

- Java 17 + Spring Boot + MyBatis-Plus：提供 `/api/agent/ask`，通过 Agent Runtime 执行 mock/real 编排、规则评分、学习计划和 Trace。
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

## 真实模式使用（阶段 5）

本阶段提供了两个独立的模式开关，分别控制 Go Collector 和 Java Agent 的行为：

| 开关 | 作用 | 默认值 |
|---|---|---|
| `OPSCOUT_COLLECTOR_MODE` (Go) | `mock` / `github` — 控制 Go Collector 数据源 | `mock` |
| `OPSCOUT_MOCK_AGENT` (Java) | `true` / `false` — 控制 Java Agent 编排路径 | `true` |

四种组合：

| Go `OPSCOUT_COLLECTOR_MODE` | Java `OPSCOUT_MOCK_AGENT` | 行为 |
|---|---|---|
| `mock` | `true` | 完全 mock（默认，无需 Token） |
| `github` | `true` | Java 仍调 mock Agent（不推荐，Go 启动真实模式但 Java 不调用） |
| `mock` | `false` | Java 调 Go 真实端点，Go 返回 mock 数据（用于测试 Java → Go 链路） |
| `github` | `false` | 完全真实模式（需要 Token，生产演示） |

**真实模式完整启动步骤：**

```bash
# 1. 设置 Go Collector 为真实模式
export OPSCOUT_COLLECTOR_MODE=github
export GITHUB_TOKEN=<secret>
# 可选：调整限流/缓存/并发
export OPSCOUT_RATE_LIMIT_RPS=2
export OPSCOUT_CACHE_TTL_MINUTES=10
export OPSCOUT_WORKER_CONCURRENCY=4
export OPSCOUT_HTTP_TIMEOUT_SECONDS=8

# 2. 启动 Go Collector
cd openscout-repo-collector && go run ./cmd/server &

# 3. 设置 Java Agent 为非 mock 模式
export OPSCOUT_MOCK_AGENT=false

# 4. 启动 Java Agent Server
cd openscout-agent-server && mvn spring-boot:run &

# 5. 调用真实模式 Agent
curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想学习 Spring AI Agent"}'
```

**速率限制说明：**

- 未配置 `GITHUB_TOKEN`：60 次/小时（GitHub 匿名限制）。
- 已配置 `GITHUB_TOKEN`：5000 次/小时（GitHub 认证用户限制）。
- 遇到限流（429）时，Java 会返回带 `Retry-After` 头的友好提示。
- README 获取限制为前 5 个 repo，避免 N+1 次调用快速耗尽配额。

**Go Collector 可配置参数：**

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `OPSCOUT_RATE_LIMIT_RPS` | `2` | GitHub API 每秒请求数 |
| `OPSCOUT_RATE_LIMIT_BURST` | `4` | 突发请求数 |
| `OPSCOUT_CACHE_TTL_MINUTES` | `10` | 进程内缓存 TTL（分钟） |
| `OPSCOUT_WORKER_CONCURRENCY` | `4` | 批量采集并发数 |
| `OPSCOUT_HTTP_TIMEOUT_SECONDS` | `8` | HTTP 客户端超时（秒） |

## LLM 智能回答（阶段 6）

本阶段接入 DeepSeek V4 Pro 作为自然语言生成层。LLM 负责两件事：
- **目标解释**：将用户自然语言目标（如"我想一周内学 Spring AI"）提取为 GitHub 搜索关键词
- **回答生成**：基于规则评分结果生成个性化的自然语言推荐文本

**Fallback 机制**：未配置 `DEEPSEEK_API_KEY` 或 LLM 调用失败时，自动降级为模板回答，不影响搜索和评分链路。

启用 LLM：

```bash
export SPRING_AI_MODEL_CHAT=openai
export DEEPSEEK_API_KEY=<secret>
```

强制禁用 LLM（使用模板回答）：

```bash
export OPSCOUT_LLM_ENABLED=false
```

LLM 调用详情（prompt 摘要、response 摘要、耗时）记录在 `/api/agent/traces/{traceId}` 中。

**LLM 可配置参数：**

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `OPSCOUT_LLM_ENABLED` | `true` | 设为 `false` 强制模板模式 |
| `OPSCOUT_LLM_TIMEOUT_SECONDS` | `30` | LLM API 调用超时（秒） |
| `OPSCOUT_LLM_MAX_TOKENS` | `2000` | 回答最大 token 数 |
| `OPSCOUT_LLM_TEMPERATURE` | `0.7` | LLM 温度参数 |

Spring AI 模型默认不启用，避免 mock 演示在未配置 Key 时启动失败。后续接入 DeepSeek 时再显式开启：

```bash
export SPRING_AI_MODEL_CHAT=openai
export DEEPSEEK_API_KEY=<secret>
```

## Agent Runtime（阶段 8）

`/api/agent/ask` 当前通过规则模板 Agent Runtime 执行固定计划。第一版 Runtime 只显式化现有链路，不引入动态 Tool Calling、RAG、SSE、前端或生产鉴权。

mock 模式计划：

```text
interpret_goal -> search_repos -> score_projects -> generate_learning_plan -> generate_answer
```

真实 GitHub 模式计划：

```text
interpret_goal -> search_repos -> fetch_readme -> score_projects -> generate_learning_plan -> generate_answer
```

Trace 中会通过 `TraceToolCall.toolName` 事件记录 Runtime 过程：

| toolName | 含义 |
|---|---|
| `agent_plan_created` | 计划创建，包含 planId、mode 和 step 数 |
| `agent_step_started` | 单个 step 开始执行 |
| `agent_step_finished` | 单个 step 执行完成或失败 |
| `agent_observation_created` | step observation 摘要 |

这些事件仍复用既有 Trace 脱敏和长度截断规则，不新增数据库表或 DDL。`/api/agent/ask` 响应字段保持兼容。

## 学习计划（阶段 7）

`/api/agent/ask` 会在推荐结果中追加 `learningPlan` 字段，基于 Top 推荐项目和规则评分证据生成 7 天学习任务。学习计划默认启用，规则模板可在无模型 Key 时稳定生成；LLM 可用时只做文案增强，不修改评分和 evidence。

关闭学习计划生成：

```bash
export OPSCOUT_LEARNING_ENABLED=false
```

默认持久化关闭时，`learningPlan.persisted=false`，计划只随本次响应返回：

```json
{
  "learningPlan": {
    "goalId": null,
    "goal": "我想一周内学习 Spring AI Agent",
    "targetStack": "Java",
    "durationDays": 7,
    "persisted": false,
    "tasks": [
      {
        "id": null,
        "dayNo": 1,
        "title": "明确目标与项目范围",
        "detail": "围绕用户目标阅读推荐项目 README、快速开始和目录结构...",
        "expectedOutput": "写出项目定位、核心模块猜测和 3 个待验证问题。",
        "status": "TODO"
      }
    ]
  }
}
```

启用 MySQL 持久化后，ask 会保存 `learning_goal` 和 `learning_task`，并返回可查询的 `goalId`：

```bash
export OPSCOUT_PERSISTENCE_ENABLED=true
docker compose -f deploy/docker-compose.yml up -d mysql
cd openscout-agent-server && mvn spring-boot:run
```

查询学习计划：

```bash
curl http://localhost:8080/api/learning/goals/<goalId>
```

更新任务状态：

```bash
curl -X PATCH http://localhost:8080/api/learning/tasks/<taskId>/status \
  -H 'Content-Type: application/json' \
  -d '{"status":"DONE"}'
```

任务状态只支持 `TODO`、`DOING`、`DONE`。`/api/learning/*` 当前是 MVP 本地 Demo 接口，未实现登录鉴权，不应直接公网暴露。

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
