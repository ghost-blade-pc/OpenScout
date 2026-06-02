# OpenScout Agent

面向开发者的开源项目情报分析与学习路径助手。输入学习目标，Agent 自动检索 GitHub、规则评分、LLM 生成推荐与 7 天学习计划，全程可追踪。

**技术栈**：Java 17 + Spring Boot 3.3 + MyBatis-Plus · Go 1.22 + Gin · MySQL + Redis · DeepSeek V4 Pro

---

## 快速开始

### 1. 启动基础设施

```bash
docker compose -f deploy/docker-compose.yml up -d   # MySQL + Redis
```

### 2. 配置环境变量

```bash
cp .env.example .env
# 编辑 .env：mock 模式无需任何密钥即可运行
# 真实模式需填入 GITHUB_TOKEN 和 DEEPSEEK_API_KEY
set -a && source .env && set +a
```

### 3. 启动服务

```bash
# Go Collector（8081 端口）
cd openscout-repo-collector && go run ./cmd/server &

# Java Agent Server（8080 端口）
cd openscout-agent-server && mvn spring-boot:run &
```

### 4. 调用

```bash
curl -X POST http://localhost:8080/api/agent/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"我想学习 Go 微服务，推荐几个适合进阶的开源项目"}'
```

响应包含：`traceId`、LLM 分析回答、评分排序的推荐列表（含评分证据）、7 天学习计划。

### 5. 查看 Trace

```bash
curl http://localhost:8080/api/agent/traces/<traceId>
```

---

## 一键脚本

| 脚本 | 用途 | 依赖 |
|---|---|---|
| `scripts/verify-local.sh` | 全量验证（Java + Go + Eval + Compose） | Java, Go, Docker |
| `scripts/demo-mock.sh` | Mock 模式演示（启动服务 + 示例请求） | Java |
| `scripts/demo-real-optional.sh` | 真实模式验证 | GitHub Token, LLM Key, Docker |

---

## 核心模式

项目通过环境变量控制运行模式，默认 **完全 mock**（零依赖、零密钥即可运行）。

| 模式 | 配置 | 说明 |
|---|---|---|
| Mock（默认） | `OPSCOUT_MOCK_AGENT=true OPSCOUT_COLLECTOR_MODE=mock` | 本地即可运行，无需任何外部服务 |
| 真实 GitHub | `OPSCOUT_COLLECTOR_MODE=github GITHUB_TOKEN=<token>` | 调用 GitHub Search/Profile/Readme API |
| LLM 增强 | `OPSCOUT_LLM_ENABLED=true DEEPSEEK_API_KEY=<key>` | DeepSeek V4 Pro 生成自然语言推荐 + 学习计划 |
| MySQL 持久化 | `OPSCOUT_PERSISTENCE_ENABLED=true` | Trace/Repo/Learning 数据落库 |

**核心开关**：

| 环境变量 | 默认值 | 作用 |
|---|---|---|
| `OPSCOUT_MOCK_AGENT` | `true` | Agent 编排模式 |
| `OPSCOUT_COLLECTOR_MODE` | `mock` | Go Collector 数据源 |
| `OPSCOUT_LLM_ENABLED` | `true` | LLM 自然语言生成 |
| `OPSCOUT_PERSISTENCE_ENABLED` | `false` | MySQL 持久化 |
| `OPSCOUT_SECURITY_ENABLED` | `false` | API Key 鉴权 |
| `OPSCOUT_QUOTA_ENABLED` | `false` | 入站配额限流 |

完整配置清单见 `.env.example`。

---

## API 参考

### Agent 主接口

| 方法 | 路径 | 说明 |
|---|---|---|
| `POST` | `/api/agent/ask` | 同步 Agent 请求，返回推荐 + 学习计划 |
| `GET` | `/api/agent/traces/{traceId}` | 查询 Agent Trace（步骤/工具/观察） |
| `POST` | `/api/agent/runs` | 创建异步 Agent Run |
| `GET` | `/api/agent/runs/{runId}` | 查询 Run 状态 |
| `GET` | `/api/agent/runs/{runId}/events` | SSE 事件流（实时观测 Agent 决策过程） |

### 学习计划

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/learning/goals/{goalId}` | 查询学习目标及 7 天任务 |
| `PATCH` | `/api/learning/tasks/{taskId}/status` | 更新任务状态（TODO/DOING/DONE） |

### Go Collector

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/health` | 健康检查 |
| `GET` | `/api/repos/mock` | Mock 仓库列表 |
| `GET` | `/api/repos/search?keyword=&limit=` | GitHub 搜索 |
| `GET` | `/api/repos/:owner/:repo/profile` | 仓库详情 |
| `GET` | `/api/repos/:owner/:repo/readme` | README 内容 |
| `POST` | `/api/repos/batch-profile` | 批量获取仓库详情 |

---

## Agent 决策流程

真实模式计划（9 步固定管道）：

```text
interpret_goal → check_memory → search_repos → fetch_readme
→ score_projects → evidence_react → ┐
                                      ├─ generate_learning_plan  ┬→ verify_answer
                                      └─ generate_answer         ┘   (规则自检)
                                   (LLM 并行，延迟 -38%)
```

| 步骤 | 工具 | 职责 |
|---|---|---|
| 1 | `interpret_goal` | LLM 提取 GitHub 搜索关键词 |
| 2 | `check_memory` | MySQL 关键词检索，命中则跳过搜索 |
| 3 | `search_repos` | Go Collector → GitHub Search API |
| 4 | `fetch_readme` | 补充 Top 5 项目的 README |
| 5 | `score_projects` | 规则引擎评分（活跃度/文档/匹配/学习/简历） |
| 6 | `evidence_react` | 检测证据缺口，有限补查 |
| 7-8 | `generate_learning_plan` + `generate_answer` | LLM 并行：学习计划 + 推荐回答 |
| 9 | `verify_answer` | 规则自检（分数一致性/证据声明/学习计划可行性） |

关键约束：**评分由规则引擎计算，LLM 不得修改分数**。LLM 不可用时自动 fallback 到模板回答。

---

## 项目结构

```text
openscout-agent-server/         Java Agent 编排服务（Spring Boot）
  src/main/java/com/openscout/
    agent/                      Agent 核心（Runtime/Tool/ReAct/Verifier/Event/Run）
    client/                     Go Collector HTTP 客户端
    config/                     配置、安全、限流
    evaluation/                 可复现 Agent 评测
    learning/                   学习计划生成与 API
    memory/                     Project Memory / RAG
    persistence/                MyBatis-Plus 持久化
    scoring/                    规则评分引擎
    trace/                      Agent Trace
openscout-repo-collector/       Go Repo Collector（Gin）
  internal/
    api/                        HTTP 路由与中间件
    cache/                      进程内 TTL 缓存
    github/                     GitHub REST API 客户端
    limiter/                    速率限制
    service/                    业务编排
    worker/                     并发 Worker Pool
deploy/                         Docker Compose + init.sql
scripts/                        验证与演示脚本
code_copilot/                   SpecAI 工作区（规则/变更记录/知识库）
```

---

## 开发

### 运行测试

```bash
# Java（146 tests）
cd openscout-agent-server && mvn test

# Go
cd openscout-repo-collector && go test ./...

# Agent Evaluation
cd openscout-agent-server && mvn test -Dtest=AgentEvaluationCommandTest
```

### CI

`.github/workflows/ci.yml`：`java-test` + `go-test` + `compose-config`，默认不依赖外部服务或密钥。

### 数据库迁移

DDL 基线：`deploy/init.sql`（Docker 首次初始化）和 `V1__init_schema.sql`（Flyway，需显式启用 `FLYWAY_ENABLED=true`）。后续 schema 变更通过 `V2__xxx.sql` 等迁移文件管理。

---

## 路线图

- **V1（已完成）**：Agent 决策闭环（Runtime → Tool → Memory → ReAct → Verifier → Events → Eval → Hardening），16 个阶段，146 tests
- **V2（规划中）**：详见 [`V2版本项目方案.md`](V2版本项目方案.md) — 线程安全加固、Go 优雅关闭、测试覆盖补齐、向量检索、分布式 Quota、动态 Tool Planning

---

## 约束与安全

- GitHub API 限流：匿名 60 次/小时，认证 5000 次/小时。429 自动降级。
- Trace 不保存完整 README、完整 prompt、Token 或 API Key，仅存储脱敏摘要。
- `/api/learning/*` 和 `/api/agent/traces/*` 为本地 MVP 接口，未做鉴权，不应公网暴露。
- API Key 和入站配额默认关闭，本地 Demo 和 CI 不退化。
- 评分由规则引擎产生，LLM 只负责解释，不覆盖评分数字。
