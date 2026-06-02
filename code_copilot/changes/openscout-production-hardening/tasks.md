# 任务拆分 - 阶段 15 Production Hardening

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已读取 `code_copilot/knowledge/index.md` 相关索引
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已检查工作区状态，确认不会覆盖他人修改
- [x] 已确认本地验证命令或替代验证方式

## Task 1: 数据库迁移基线

- **目标**：建立版本化 schema 基线，并保持 Docker `init.sql` 可用。
- **层级/模块**：基础设施 / 配置 / 文档
- **涉及文件**：
  - `openscout-agent-server/pom.xml`：migration 依赖。
  - `openscout-agent-server/src/main/resources/db/migration/`：V1 schema。
  - `openscout-agent-server/src/main/resources/application.yml`：migration 配置。
  - `deploy/init.sql`：与 V1 保持一致。
- **依赖**：无
- **风险标记**：数据库迁移 / 配置
- **实现要点**：
  - V1 基线复用现有表结构，不新增业务语义。
  - 保留 Docker 首次初始化路径。
  - README 记录 schema 变更流程和失败处理。
- **验收标准**：
  - 新环境可由 Docker init 或 Java migration 建表。
  - 既有持久化测试和默认测试不退化。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`.github/workflows/ci.yml`
  - 验证结果：workflow 语法正确；覆盖 Java `mvn test` + `AgentEvaluationCommandTest`、Go `go test ./...`、Compose config；不依赖 token/key/外网

- **目标**：新增默认 CI workflow，覆盖 Java、Go、Compose 和 Agent Evaluation。
- **层级/模块**：CI / 测试
- **涉及文件**：
  - `.github/workflows/ci.yml`：CI workflow。
  - `README.md`：CI 说明。
- **依赖**：Task 1 可并行。
- **风险标记**：配置 / 外部环境
- **实现要点**：
  - 默认不依赖 GitHub Token、DeepSeek Key、外网业务调用或长驻服务。
  - Go 使用仓库脚本或 CI 安装的 Go 工具链。
  - Docker Compose 只做 config 校验。
- **验收标准**：
  - CI 包含 Java `mvn test`、`AgentEvaluationCommandTest`、Go `go test ./...`、Compose config。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  cd openscout-agent-server && mvn test -Dtest=AgentEvaluationCommandTest
  cd openscout-repo-collector && go test ./...
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`.github/workflows/ci.yml`
  - 验证结果：workflow 语法正确；覆盖 Java `mvn test` + `AgentEvaluationCommandTest`、Go `go test ./...`、Compose config；不依赖 token/key/外网

## Task 3: 本地验证与演示脚本

- **目标**：提供一键式 mock-first 验证入口，并把真实 GitHub/LLM 验证标为 optional。
- **层级/模块**：脚本 / 文档
- **涉及文件**：
  - `scripts/verify-local.sh`：默认验证。
  - `scripts/demo-mock.sh`：mock 演示。
  - `scripts/demo-real-optional.sh` 或 README 命令：可选真实验证。
  - `README.md`：脚本说明。
- **依赖**：Task 2 可复用命令。
- **风险标记**：外部接口 / 敏感配置
- **实现要点**：
  - 脚本不得打印完整密钥。
  - 默认脚本不依赖外网和真实 token。
  - 真实脚本检查必要环境变量，缺失时给出明确提示。
- **验收标准**：
  - 默认脚本可执行并覆盖 Java/Go/Compose/Evaluation。
  - optional 脚本不会误把缺 token 当成失败。
- **验证命令**：
  ```bash
  scripts/verify-local.sh
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`scripts/verify-local.sh`、`scripts/demo-mock.sh`、`scripts/demo-real-optional.sh`
  - 验证结果：脚本可执行（chmod +x）；默认脚本不依赖外网/密钥；真实验证脚本缺失环境变量时给出提示

## Task 4: 最小 API Key 保护

- **目标**：为 Java 和 Go HTTP API 增加可选 API Key 保护，默认关闭。
- **层级/模块**：入口层 / 配置 / 安全 / 测试
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`
  - `openscout-agent-server/src/main/resources/application.yml`
  - Java Web filter/interceptor 相关类与测试。
  - `openscout-repo-collector/internal/api/router.go` 或 middleware 文件与测试。
- **依赖**：无
- **风险标记**：安全 / 兼容性
- **实现要点**：
  - Header 默认 `X-OpenScout-Api-Key`。
  - 配置 disabled 时行为与当前一致。
  - enabled 时缺失/错误 key 返回 401，错误体不输出 key。
  - `/health` 可按配置豁免，业务 API 默认保护。
- **验收标准**：
  - Java 和 Go 均有 disabled/invalid/valid 覆盖。
  - 既有默认测试不因默认 disabled 退化。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  cd openscout-repo-collector && go test ./...
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`OpenScoutProperties.java`（Security 内部类）、`application.yml`（security 配置）、`ApiKeyFilter.java`、`SecurityConfig.java`、`router.go`（apiKeyMiddleware）、`main.go`（OPSCOUT_COLLECTOR_API_KEY）、`ApiKeyFilterTest.java`（8 tests）、`api_key_test.go`（6 tests）
  - 验证结果：Java 132 tests、Go tests 通过；disabled/invalid/valid/health exempt/无泄漏 全覆盖

## Task 5: Java 入站配额

- **目标**：限制 ask/run 入口被误刷，降低 GitHub/LLM 成本放大风险。
- **层级/模块**：入口层 / 配置 / 安全 / 测试
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`
  - `openscout-agent-server/src/main/resources/application.yml`
  - Java quota/filter/service 类与测试。
- **依赖**：Task 4 可共享 key/IP 识别逻辑。
- **风险标记**：并发 / 外部接口 / 配置
- **实现要点**：
  - 进程内固定窗口或等价轻量算法。
  - quota key 优先 API Key，其次 remote address。
  - 超限返回 429 和可解释错误，不泄漏内部状态。
  - README 标注非分布式限流。
- **验收标准**：
  - 窗口内/超限/刷新/disabled/不同 key 隔离均有测试。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`OpenScoutProperties.java`（Quota 内部类）、`application.yml`（quota 配置）、`RateLimitService.java`、`QuotaFilter.java`、`SecurityConfig.java`（注册 QuotaFilter）、`RateLimitServiceTest.java`（5 tests）、`QuotaFilterTest.java`（7 tests）
  - 验证结果：Java 144 tests 通过；窗口内/超限/刷新/不同 key 隔离/disabled 全覆盖

## Task 6: 配置治理与文档同步

- **目标**：补齐安全配置示例、模式矩阵和 production-hardening 边界说明。
- **层级/模块**：配置 / 文档
- **涉及文件**：
  - `.env.example`
  - `README.md`
  - `项目实施进度.md`
  - `code_copilot/changes/openscout-production-hardening/log.md`
- **依赖**：Task 1-5
- **风险标记**：敏感配置 / 文档口径
- **实现要点**：
  - `.env.example` 只使用占位符，不包含真实密钥。
  - README 明确 mock/real、LLM enabled/disabled、persistence、security、quota 默认值。
  - 明确 API Key 和进程内 quota 不是完整生产安全体系。
- **验收标准**：
  - 文档可指导新环境启动和默认验证。
  - 不夸大生产 SLA、线上准确率或完整鉴权能力。
- **验证命令**：
  ```bash
  rg -n "ghp_[A-Za-z0-9]{12,}|sk-[A-Za-z0-9]{12,}|DEEPSEEK_API_KEY=.*[A-Za-z0-9]{8,}|GITHUB_TOKEN=.*[A-Za-z0-9]{8,}" README.md .env.example scripts code_copilot/changes/openscout-production-hardening
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：`.env.example`（完整配置示例）、`README.md`（Production Hardening 章节）、`项目实施进度.md`（apply 状态更新）、`log.md`（执行日志）
  - 验证结果：敏感值扫描通过（无真实密钥）；配置矩阵清晰；边界说明明确

## 变更摘要

> `/apply` 已完成。

- **总文件数**：24
- **新增文件**：12
  - `.github/workflows/ci.yml`
  - `openscout-agent-server/src/main/resources/db/migration/V1__init_schema.sql`
  - `openscout-agent-server/src/main/java/com/openscout/config/ApiKeyFilter.java`
  - `openscout-agent-server/src/main/java/com/openscout/config/SecurityConfig.java`
  - `openscout-agent-server/src/main/java/com/openscout/config/QuotaFilter.java`
  - `openscout-agent-server/src/main/java/com/openscout/config/RateLimitService.java`
  - `openscout-agent-server/src/test/java/com/openscout/config/ApiKeyFilterTest.java`
  - `openscout-agent-server/src/test/java/com/openscout/config/RateLimitServiceTest.java`
  - `openscout-agent-server/src/test/java/com/openscout/config/QuotaFilterTest.java`
  - `openscout-repo-collector/internal/api/api_key_test.go`
  - `scripts/verify-local.sh`
  - `scripts/demo-mock.sh`
  - `scripts/demo-real-optional.sh`
- **修改文件**：8
  - `openscout-agent-server/pom.xml`（Flyway 依赖）
  - `openscout-agent-server/src/main/resources/application.yml`（flyway + security + quota 配置）
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`（Security + Quota 内部类）
  - `openscout-repo-collector/cmd/server/main.go`（OPSCOUT_COLLECTOR_API_KEY）
  - `openscout-repo-collector/internal/api/router.go`（apiKeyMiddleware）
  - `.env.example`（完整配置示例）
  - `README.md`（Production Hardening 章节）
  - `项目实施进度.md`（apply 状态同步）
  - `code_copilot/changes/openscout-production-hardening/log.md`（执行日志）
  - `code_copilot/changes/openscout-production-hardening/tasks.md`（完成记录）
- **删除文件**：0
- **Spec-Plan 偏差记录**：
  - `repo_info.description VARCHAR(1000)` 截断风险未纳入本阶段修复，与 spec 一致。
  - 实际测试总数 144（Java）超出 spec 估计的 124+new，因为新增了 20 个 filter/quota tests。
- **未完成项**：无（全部 7 个 Task 已完成）
- **遗留风险**：
  - API Key 保护是最小访问门，不等价于完整生产鉴权。
  - 进程内 quota 重启清空，多实例不共享。
  - Docker Compose config 在 WSL 2 环境可校验但无法完整启动验证。
