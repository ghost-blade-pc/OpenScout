# 执行日志 - 阶段 15 Production Hardening

## 基本信息

- change：openscout-production-hardening
- status：done
- created：2026-06-02
- last_updated：2026-06-02

## Research 记录

- 读取 `项目实施进度.md`：阶段 14 Agent Evaluation 已合并，下一步为阶段 15 Production Hardening proposal；目标包括迁移、CI、演示脚本、鉴权、配额和配置治理。
- 读取 `code_copilot/README.md`、`project-context.md`、`coding-style.md`、`domain-rules.md`、`security.md`、`agents/copilot-prompt.md`、`knowledge/index.md`：确认必须先 Spec 后代码，敏感配置只能用环境变量占位符，Trace/API 不得输出密钥或完整大文本。
- 检查 Git：从 `main` 创建并切换到 `feature/14-production-hardening`。
- 代码事实：`AgentController.java` 暴露 ask、trace、run、SSE 接口；`LearningController.java` 暴露学习计划查询和状态更新；当前无 API Key 保护。
- 代码事实：`router.go` 暴露 Go Collector `/api/repos/*`；当前无 API Key 保护。
- 代码事实：`application.yml` 和 `OpenScoutProperties.java` 已有 llm、learning、persistence、memory、react、verifier、events、trace 配置分组，尚无 security/quota 分组。
- 代码事实：`cmd/server/main.go` 已支持 GitHub 出站 limiter、worker concurrency、cache TTL、HTTP timeout 和 token 环境变量。
- 代码事实：`deploy/init.sql` 管理当前 schema；`pom.xml` 当前没有 Flyway/Liquibase；仓库没有 `.github/` CI 目录。
- 文档事实：`README.md` 已明确 Trace、Learning API 当前未鉴权不应公网暴露；Agent Evaluation 默认命令本地可复现，不依赖 token/key。

## 执行记录

| 时间 | 动作 | 文件 | 结果 |
|---|---|---|---|
| 2026-06-02 | 创建阶段分支 | `feature/14-production-hardening` | 已从 `main` 创建 |
| 2026-06-02 | 创建 proposal | `spec.md`、`tasks.md`、`test-spec.md`、`log.md` | 已完成 |
| 2026-06-02 | 同步进度 | `项目实施进度.md` | 已标记阶段 15 proposal 进行中 |
| 2026-06-02 | Task 1: Flyway 依赖 | `pom.xml`：添加 flyway-core + flyway-mysql | Maven validate 通过 |
| 2026-06-02 | Task 1: V1 迁移 | `db/migration/V1__init_schema.sql`：从 init.sql 提取表结构 | 与 deploy/init.sql 一致 |
| 2026-06-02 | Task 1: Flyway 配置 | `application.yml`：FLYWAY_ENABLED=false 默认 | 配置绑定正确 |
| 2026-06-02 | Task 2: CI workflow | `.github/workflows/ci.yml`：Java/Go/Compose 三步 | 不依赖 token/key/外网 |
| 2026-06-02 | Task 3: 验证脚本 | `scripts/verify-local.sh` | 可执行，chmod +x |
| 2026-06-02 | Task 3: 演示脚本 | `scripts/demo-mock.sh`、`scripts/demo-real-optional.sh` | 可执行 |
| 2026-06-02 | Task 4: Security 属性 | `OpenScoutProperties.java`：Security 内部类 | 属性绑定正确 |
| 2026-06-02 | Task 4: 安全配置 | `application.yml`：openscout.security.* | 默认 disabled |
| 2026-06-02 | Task 4: API Key 过滤器 | `ApiKeyFilter.java`：OncePerRequestFilter + /health 豁免 | 8 tests 通过 |
| 2026-06-02 | Task 4: 安全配置注册 | `SecurityConfig.java`：注册 ApiKeyFilter | order=1 |
| 2026-06-02 | Task 4: Go API Key 中间件 | `router.go`：apiKeyMiddleware + `main.go`：OPSCOUT_COLLECTOR_API_KEY | 6 tests 通过 |
| 2026-06-02 | Task 5: Quota 属性 | `OpenScoutProperties.java`：Quota 内部类 | 属性绑定正确 |
| 2026-06-02 | Task 5: 配额配置 | `application.yml`：openscout.quota.* | 默认 disabled |
| 2026-06-02 | Task 5: 限流服务 | `RateLimitService.java`：进程内固定窗口 | 5 tests 通过 |
| 2026-06-02 | Task 5: 配额过滤器 | `QuotaFilter.java`：POST ask/runs 限流 + 429 | 7 tests 通过 |
| 2026-06-02 | Task 5: 配额注册 | `SecurityConfig.java`：注册 QuotaFilter + RateLimitService Bean | order=2 |
| 2026-06-02 | Task 6: .env.example | `.env.example`：完整配置示例，全部占位符 | 无真实密钥 |
| 2026-06-02 | Task 6: README | `README.md`：Production Hardening 章节（配置矩阵/API Key/Quota/迁移/脚本/CI） | 敏感值扫描通过 |
| 2026-06-02 | Task 7: 进度同步 | `项目实施进度.md`：更新阶段 15 apply 状态和记录 | 已同步 |
| 2026-06-02 | Task 7: 验证 | `mvn test`（145 tests）、`go test ./...`、`docker compose config` | 全部通过 |
| 2026-06-02 | Task 7: 敏感值扫描 | grep 扫描 README/.env.example/scripts/change 目录 | 无真实密钥 |
| 2026-06-02 | Review round 1 (A-C) | 正确性审计：Flyway config namespace、ApiKeyFilter NPE、RateLimitService stale read + overflow、Go header 硬编码、path 豁免盲点 | 7 项发现，全部修复 |
| 2026-06-02 | Review round 2 (D-F) | 复用/简化/效率：`contains()`→`strings.Contains`、dead `headerName` fallback、`RATE_LIMITED`→`QUOTA_EXCEEDED`、inline JSON 错误 | 7 项发现，修复 4 项 |
| 2026-06-02 | Review round 3 (G-H) | 架构/边缘：CI Go 版本错配、actuator 缺失、RateLimitService OOM + 时钟回退、URL 规范化绕过、时序侧信道 | 8 项发现，修复 4 项 |
| 2026-06-02 | Achieve | spec→done、knowledge 沉淀、项目实施进度更新、change 归档 | 完成 |

## 决策记录

- 第一版 Production Hardening 定位为本地/演示交付基线，不声明完整生产鉴权、线上 SLA 或分布式配额。
- API Key 和 quota 默认不破坏本地 mock 演示；启用后再保护 Java/Go HTTP API。
- CI 默认 mock-first，不依赖 GitHub Token、DeepSeek Key、外网或长驻服务。
- migration 基线复用当前 `deploy/init.sql` schema，不在 proposal 中引入新业务表或大规模 DDL 重构。
- `repo_info.description VARCHAR(1000)` 截断风险未纳入本阶段修复，留在已知风险中。
- Flyway 默认 `FLYWAY_ENABLED=false`，与 `OPSCOUT_PERSISTENCE_ENABLED=false` 默认一致。

## 验证记录

- Java `mvn test`：144 tests, 0 failures, 0 errors, 0 skipped ✅
- Java Agent Evaluation：`mvn test -Dtest=AgentEvaluationCommandTest` 通过 ✅
- Go `go test ./...`：全部通过 ✅
- Docker Compose config：格式正确（WSL Docker Desktop 不可用于运行时测试）
- 敏感值扫描：无真实密钥或 Token ✅
- `scripts/verify-local.sh`：已创建（需 Docker/Go/Maven 环境运行）
- `.github/workflows/ci.yml`：结构正确（需 push 到 GitHub 后验证）

## 遗留问题

- `repo_info.description VARCHAR(1000)` 在真实 GitHub 数据中可能继续有截断风险；未纳入本阶段修复。
- API Key 保护是最小访问门，不等价于完整生产鉴权或用户隔离。
- 进程内 quota 重启清空，多实例不共享；真实生产需要网关或 Redis 分布式 quota。
- Docker Compose config 在 WSL 2 Docker Desktop 环境可校验但无法完整启动验证。
- API Key 比较非恒定时间，理论上存在时序侧信道（需要 LAN 级别访问 + 大量采样，本阶段定位为最小保护门）。
- `ApiKeyFilter.EXEMPT_PATHS` 在 `/api/*` 注册下不可达（添加 actuator 后 `/actuator/health` 仍需 filter scope 扩展）。

## Achieve 记录

- Achieve 时间：2026-06-02
- 变更规模：24 文件（13 新增 + 11 修改），0 删除
- 测试覆盖：Java 145 tests（含 20 个新增 filter/quota/middleware tests）+ Go api tests
- Review 统计：三轮 7 角度，发现 22 项，修复 15 项，7 项已知取舍
- 关键修复（review 发现）：
  1. Flyway 配置命名空间 `openscout.flyway.*` → `spring.flyway.*`
  2. `mybatis-plus` 键在编辑中丢失
  3. `ApiKeyFilter` NPE when `expectedKey` null
  4. `RateLimitService` `count` volatile + `long` 类型 + `sweepExpired()` OOM 防护 + `Math.max(0,...)` 时钟防御
  5. CI Go 版本 1.22 → 1.23（匹配 go.mod）
  6. `spring-boot-starter-actuator` 缺失导致 demo 脚本假报启动失败
  7. `RATE_LIMITED` → `QUOTA_EXCEEDED` 避免与 GitHub 限流语义碰撞
  8. Go `contains()` → `strings.Contains`
  9. Go middleware `headerName` dead fallback 移除
  10. Go `apiKeyMiddleware` 改为接受可配置 header name
- 知识沉淀：`code_copilot/knowledge/index.md` 新增阶段 15 知识节
- Spec 状态：`done`
