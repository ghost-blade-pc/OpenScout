# 阶段 15 Production Hardening
> status: done
> created: 2026-06-02
> complexity: 复杂

## 1. 背景与目标

阶段 8-14 已完成 Agent Runtime、Tool Runtime、Project Memory / RAG、Evidence ReAct、Reflection Verifier、Agent Events Stream 和 Agent Evaluation。当前项目已经能在本地用 mock-first 方式演示、测试和评测，但还缺少进入可稳定交付状态所需的工程化基线：数据库迁移、CI 验证、演示脚本、最小访问保护、入站配额、配置治理和边界文档。

阶段 15 的目标是补齐第一版 Production Hardening，让新环境可以按文档稳定启动和验证，schema 变更有迁移路径，CI 能覆盖 Java/Go/Compose/Evaluation，mock、真实 GitHub、LLM enabled/disabled、持久化和事件流边界清楚。完成后应能用固定脚本和 CI workflow 证明本地交付基线可重复，而不是只依赖人工命令清单。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的工程化交付、配置安全、HTTP 入口保护、数据库迁移、CI 验证和演示运行。
- 调用方向：用户/脚本/CI -> Java Agent Server / Go Collector / MySQL / Redis / GitHub API / DeepSeek 兼容模型服务。
- 是否涉及高风险项：是。
- 高风险类型：数据库迁移、公开 HTTP API、敏感配置、外部 GitHub API、模型 Key、SSE 长连接资源、入站限流、CI 环境差异。

### 1.2 范围裁剪

- 本次包含：
  - 数据库迁移基线：引入可版本化 migration 文件，复用现有 `deploy/init.sql` 表结构，不重塑数据模型。
  - CI 基线：新增 GitHub Actions 或等价 CI workflow，覆盖 Java 全量测试、Agent Evaluation 默认命令、Go 测试和 Docker Compose config。
  - 演示与验证脚本：新增本地 mock 演示、默认验证、可选真实 GitHub/LLM 验证脚本或命令入口。
  - 最小 API 保护：新增可配置的 API Key 保护，默认关闭以保留本地 Demo；启用后保护 Java `/api/agent/*`、`/api/learning/*`，并为 Go Collector 提供同类可选保护。
  - 入站配额：新增进程内固定窗口或等价轻量限流，默认保守可配置，覆盖 Java ask/run 入口，避免演示环境被误刷。
  - 配置治理：补齐 `.env.example`、配置说明、启动前检查和配置边界；敏感值仅使用环境变量占位符。
  - README、`test-spec.md`、`log.md` 和 `项目实施进度.md` 同步。
- 本次不包含：
  - 完整登录注册、OAuth、RBAC、多租户、用户隔离、审计系统。
  - 云原生部署平台、Kubernetes、Helm、Terraform、线上灰度发布。
  - 分布式限流、Redis 配额、跨实例 run/event 广播、MQ。
  - 大规模生产压测、线上 SLA、真实生产安全认证结论。
  - 向量数据库、FULLTEXT、embedding、MCP、动态 Tool Planning 或新 Agent 能力。
- 后续可能拆分：
  - 完整用户体系和租户隔离。
  - Redis 分布式 quota、事件持久化和跨实例 SSE。
  - 线上可观测性、部署流水线和真实环境 benchmark。

## 2. Research Findings

### 2.1 相关入口与链路

- HTTP/API：`openscout-agent-server/src/main/java/com/openscout/controller/AgentController.java` 暴露 `POST /api/agent/ask`、`GET /api/agent/traces/{traceId}`、`POST /api/agent/runs`、`GET /api/agent/runs/{runId}`、`GET /api/agent/runs/{runId}/events`；`openscout-agent-server/src/main/java/com/openscout/learning/LearningController.java` 暴露 `GET /api/learning/goals/{goalId}` 和 `PATCH /api/learning/tasks/{taskId}/status`。
- Go Collector API：`openscout-repo-collector/internal/api/router.go` 暴露 `/health`、`/api/repos/mock`、`/api/repos/search`、`/api/repos/{owner}/{repo}/profile`、`/api/repos/{owner}/{repo}/readme`、`/api/repos/batch-profile`。
- Config：`openscout-agent-server/src/main/resources/application.yml` 已有 `openscout.llm.*`、`learning`、`persistence`、`memory`、`react`、`verifier`、`events`、`trace` 配置；`OpenScoutProperties.java` 已有对应配置对象，但没有 security/quota/hardening 配置分组。
- Go 配置：`openscout-repo-collector/cmd/server/main.go` 已支持 `OPSCOUT_RATE_LIMIT_RPS`、`OPSCOUT_RATE_LIMIT_BURST`、`OPSCOUT_CACHE_TTL_MINUTES`、`OPSCOUT_WORKER_CONCURRENCY`、`OPSCOUT_HTTP_TIMEOUT_SECONDS`、`GITHUB_TOKEN` 和 `OPSCOUT_COLLECTOR_MODE`。
- Database：`deploy/init.sql` 使用 `CREATE TABLE IF NOT EXISTS` 管理 `repo_info`、`repo_analysis`、`learning_goal`、`learning_task`、`agent_trace`，当前没有 Flyway/Liquibase 或版本化迁移目录。
- Build/Test：`openscout-agent-server/pom.xml` 使用 Spring Boot 3.3.13、MyBatis-Plus 3.5.16、Spring AI 1.1.6、JUnit；当前没有 Flyway dependency。`README.md` 已记录 `mvn test -Dtest=AgentEvaluationCommandTest`、Java 全量测试、Go 测试和 Docker Compose config 命令。
- Deploy：`deploy/docker-compose.yml` 提供 MySQL 8.4 和 Redis 7.2，并挂载 `deploy/init.sql` 初始化数据库。
- CI：仓库当前没有 `.github/` 目录，也没有 GitHub Actions workflow。
- Scripts：当前只有 `scripts/use-local-tools.sh`，用于启用项目本地 Go 工具链；没有统一 verify/demo 脚本。
- Security 文档事实：`README.md` 已明确 `/api/agent/traces/{traceId}` 和 `/api/learning/*` 当前未鉴权，不应公网暴露；Trace/事件/评测报告不得输出 Token、Key、完整 README、完整 prompt 或完整模型响应。

### 2.2 现有实现摘要

- Java 与 Go 当前均是本地 Demo 优先，HTTP API 默认无登录鉴权。
- Go 侧已有出站 GitHub API 限流和 worker concurrency；Java 侧没有入站 ask/run 配额。
- Java Events 已有 `OPSCOUT_EVENTS_MAX_ACTIVE_RUNS`、buffer size、heartbeat 和 completed retention 配置，能约束部分 SSE/run 资源，但不是通用 API quota。
- 数据库 schema 当前由 `deploy/init.sql` 初始化；已有表结构可作为 migration V1 基线。
- Agent Evaluation 已提供 mock-first 默认评测命令，适合作为 CI 中的可复现质量门。
- README 已明确真实 GitHub / LLM enabled 是可选能力，不应作为默认必过项。

### 2.3 发现的问题

- 新环境只能通过 `deploy/init.sql` 初始化 schema，后续 schema 变更缺少版本化迁移和回滚说明。
- 缺少 CI workflow，Java/Go/Compose/Evaluation 是否通过只能依赖本地手动执行。
- 缺少统一本地验证和演示脚本，新协作者需要从 README 复制多段命令。
- Java `/api/agent/*` 和 `/api/learning/*` 默认无 API Key 保护；Trace、run、learning plan 等排障/状态接口不适合公网暴露。
- Java ask/run 没有入站配额，真实模式下可能间接放大 GitHub API 或模型调用成本。
- 配置项较多，但缺少 `.env.example` 和统一边界说明，容易混淆 mock/real、LLM enabled/disabled、persistence enabled/disabled。

### 2.4 风险初判

- 数据库迁移风险：必须保持现有 Docker init 和 MyBatis Entity 兼容，不应在本阶段重命名表或字段。
- 认证风险：第一版 API Key 只能作为本地演示和非公网保护，不等价于完整生产鉴权或用户隔离。
- 配额风险：进程内限流不能跨实例共享；真实生产仍需要网关或 Redis 分布式 quota。
- 外部 API 风险：真实 GitHub/LLM 验证受 token、限流、网络和模型波动影响；默认 CI 不应依赖外网和密钥。
- 敏感信息风险：脚本、CI、日志、Trace、事件和报告都不得打印完整 Token/Key。
- 兼容性风险：默认本地 mock 演示必须保持可用；新增保护和配额默认值不能让已有测试和演示突然失败。

## 3. 功能点

- [ ] 功能 1：建立数据库 migration 基线，保留 `deploy/init.sql` 可用，并记录 schema 变更流程。
- [ ] 功能 2：新增 CI workflow，覆盖 Java `mvn test`、Agent Evaluation 默认命令、Go `go test ./...` 和 `docker compose -f deploy/docker-compose.yml config`。
- [ ] 功能 3：新增本地验证与演示脚本，支持 mock-first 验证；真实 GitHub/LLM enabled 作为 optional。
- [ ] 功能 4：新增可配置 API Key 保护，默认关闭；启用后要求 `X-OpenScout-Api-Key` 或等价 header。
- [ ] 功能 5：新增 Java 入站配额，覆盖 `/api/agent/ask` 和 `/api/agent/runs`，并保留可配置开关和阈值。
- [ ] 功能 6：补齐配置治理：`.env.example`、README 配置矩阵、敏感值占位符、默认模式说明。
- [ ] 功能 7：补充单元测试、脚本 smoke test 和文档同步，明确哪些结论可写、哪些仍是非生产保证。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| Maven Dependency | `openscout-agent-server/pom.xml` | 可选引入 Flyway 或等价 migration 工具 | 默认基于现有 schema 创建 V1，不改业务表语义 | 移除依赖和 migration 配置，继续使用 init.sql |
| Migration | `openscout-agent-server/src/main/resources/db/migration/V1__init_schema.sql` | 从 `deploy/init.sql` 抽取 Java 侧版本化 schema 基线 | 与现有表保持一致 | 回退 migration 文件 |
| Deploy SQL | `deploy/init.sql` | 继续服务 Docker 首次初始化，必要时与 V1 保持同步 | 保持 Compose 可用 | 回退同步改动 |
| Java Config | `openscout.security.*` | API Key 开关、header、key 值占位符 | 默认 disabled，不影响本地测试 | 关闭开关 |
| Java Config | `openscout.quota.*` | 入站 ask/run 每窗口请求数、窗口秒数、key 维度 | 默认可配置，测试环境可关闭 | 关闭开关 |
| Go Config | `OPSCOUT_COLLECTOR_API_KEY` 等 | Collector 可选 API Key 保护 | 默认 disabled，不影响 mock API | 清空 key |
| CI | `.github/workflows/ci.yml` | Java/Go/Compose/Evaluation 验证 | 新增，不影响运行时 | 删除 workflow |
| Scripts | `scripts/*.sh` | verify/demo/optional real 命令 | 新增辅助入口 | 删除脚本 |
| Env Example | `.env.example` | 只使用占位符和安全默认值 | 不提交真实密钥 | 删除或修正示例 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | `AgentAskRequest` | `AgentAskResponse` | 新增可选 API Key；新增入站 quota | 默认关闭保护，响应字段不变 |
| `/api/agent/runs` | POST | `AgentAskRequest` | `AgentRunCreateResponse` | 新增可选 API Key；新增入站 quota | 默认关闭保护，响应字段不变 |
| `/api/agent/runs/{runId}` | GET | path runId | `AgentRunResponse` | 新增可选 API Key | 默认关闭保护，响应字段不变 |
| `/api/agent/runs/{runId}/events` | GET/SSE | path runId | SSE stream | 新增可选 API Key，不改变事件格式 | 默认关闭保护，事件字段不变 |
| `/api/agent/traces/{traceId}` | GET | path traceId | `AgentTrace` | 新增可选 API Key | 默认关闭保护，响应字段不变 |
| `/api/learning/*` | GET/PATCH | 既有请求 | 既有响应 | 新增可选 API Key | 默认关闭保护，响应字段不变 |
| Go `/api/repos/*` | GET/POST | 既有请求 | 既有响应 | 新增可选 API Key | 默认关闭保护，响应字段不变 |

### 5.2 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| GitHub REST API | search/profile/readme | 既有 | 既有 | 沿用 Go `OPSCOUT_HTTP_TIMEOUT_SECONDS` 和 limiter | 限流/403/404 继续结构化返回 |
| DeepSeek/OpenAI 兼容接口 | Chat API | 既有 | 既有 | 沿用 `openscout.llm.timeout-seconds` | 缺 Key 或失败继续 fallback |
| MySQL | schema migration | migration SQL | migration history | 启动时执行 | migration 失败阻止持久化启动并记录原因 |

## 6. 风险与关注点

- API Key 保护只能作为最小访问门，不解决用户身份、权限、审计和多租户。
- quota 第一版若使用进程内状态，重启后计数清空，横向扩容也不共享；必须在 README 中标注。
- migration V1 必须和当前 `deploy/init.sql` 一致，避免本地 Docker 初始化和 Java migration 出现双标准。
- 默认 CI 不应要求 `GITHUB_TOKEN`、`DEEPSEEK_API_KEY`、MySQL 长驻服务或外网；真实验证只能 optional。
- 脚本必须避免 `set -x` 打印敏感环境变量。
- 新增配置默认值不能破坏阶段 14 默认 evaluation 命令和既有 124 个 Java tests。
- `repo_info.description VARCHAR(1000)` 曾在真实 GitHub 数据中暴露截断风险；本阶段若不改 DDL，需要在风险中保留，不得假装已解决。

## 7. 测试策略

- 单元测试：
  - API Key filter/interceptor：disabled 透传、enabled 缺 header 401、错误 key 401、正确 key 通过，错误响应不含敏感值。
  - quota：窗口内允许、超过阈值 429、窗口刷新、按 key/IP 隔离、disabled 透传。
  - 配置绑定：`openscout.security.*`、`openscout.quota.*` 默认值和边界值。
  - Go Collector API Key middleware：disabled、missing、invalid、valid。
- 脚本/CI 验证：
  - `cd openscout-agent-server && mvn test`
  - `cd openscout-agent-server && mvn test -Dtest=AgentEvaluationCommandTest`
  - `cd openscout-repo-collector && go test ./...`
  - `docker compose -f deploy/docker-compose.yml config`
  - `scripts/verify-local.sh` 或等价脚本。
- 文档验证：
  - README 配置矩阵和 `.env.example` 不包含真实密钥。
  - Production Hardening 说明明确 mock/real/LLM/persistence/security/quota 的默认值和 optional 边界。

## 8. 待澄清

- 无阻塞待澄清项。默认第一版做本地/演示生产化基线，不做完整用户体系、线上部署平台、分布式限流或生产 SLA。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| 迁移工具 | Java 侧 Flyway 或等价轻量 migration | 继续只用 init.sql | 版本化 schema 更适合后续阶段 | 需要保证和 Docker init 同步 |
| 认证方式 | 可选 API Key header | 完整登录/OAuth/RBAC | 阶段 15 只做最小保护，降低复杂度 | 不能声明完整生产鉴权 |
| 配额方式 | Java 进程内固定窗口 | Redis 分布式 quota | 当前 Redis adapter 未实现，第一版先保护本地演示 | 多实例不共享 |
| CI 默认能力 | mock-first、无密钥、无外网 | 默认真实 GitHub/LLM | 保持可复现和稳定 | 真实验证留 optional |
| 脚本定位 | verify/demo helper | 替代 README 全部说明 | 脚本减少人工步骤，但文档仍要解释边界 | 需要测试脚本可执行 |
| Go Collector 保护 | 可选 API Key middleware | 只保护 Java | Collector 也有公开 API 和 GitHub Token 风险 | 默认关闭以保持本地开发 |

## 10. 确认记录

- 确认时间：2026-06-02
- 确认人：Li Peicheng
- 确认范围：阶段 15 Production Hardening 已完成 apply + 三轮 review + achieve。全部 7 个 Task 完成，Java 145 tests、Go tests 通过，敏感值扫描通过。
