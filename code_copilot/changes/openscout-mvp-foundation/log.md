# 执行日志 - OpenScout MVP 基础骨架与核心推荐闭环

## 基本信息

- change：`openscout-mvp-foundation`
- status：apply
- created：2026-05-29
- last_updated：2026-05-29

## Research 记录

- 初始仓库只有 `OpenScout Agent 项目方案.md`，没有应用源码、构建文件、配置文件或测试文件。
- 方案已覆盖项目定位、技术栈、系统架构、MVP 功能、Go Collector 接口、Java Tool、评分规则、数据表草案、目录结构、一周计划和现实约束。
- 本次 propose 采纳改进方向：第一阶段收敛为可运行 MVP 闭环；Release/目录结构、多项目对比、完整学习计划落库延后；GitHub 限流、Trace 脱敏、评分 evidence 和数据表约束需要前置设计。
- 早期 `git status` 曾无法执行，原因是当时 `.git` 目录为空且只读；后续复查已恢复为有效 Git 仓库。

## 用户确认

- Java 版本：Java 17。
- Java 持久化：MyBatis-Plus。
- 模型提供商：DeepSeek V4 Pro。
- Git 管理要求：初始化 Git，但 `OpenScout Agent 项目方案.md` 不加入 Git。
- 第一阶段 Agent 策略：先 mock Agent，再接 Spring AI。
- Go module path：`github.com/LiPeicheng/openscout-repo-collector`。

## 执行记录

| 时间 | 动作 | 文件 | 结果 |
|---|---|---|---|
| 2026-05-29 | 创建 SpecAI 工作区 | `code_copilot/`、`AGENTS.md`、`CLAUDE.md` | 已完成 |
| 2026-05-29 | 创建 propose | `code_copilot/changes/openscout-mvp-foundation/` | 已完成 |
| 2026-05-29 | 同步用户确认选型 | `spec.md`、`tasks.md`、`README.md`、`rules/` | 已完成 |
| 2026-05-29 | 同步 Agent 与 Go module 决策 | `spec.md`、`tasks.md`、`README.md`、`rules/` | 已完成 |
| 2026-05-29 | 创建 Java Agent Server | `openscout-agent-server/` | 已完成，`mvn test` 通过 |
| 2026-05-29 | 创建 Go Repo Collector | `openscout-repo-collector/` | 已完成代码创建，本机缺少 Go，未编译 |
| 2026-05-29 | 创建本地依赖与 DDL | `deploy/docker-compose.yml`、`deploy/init.sql` | 已完成，Compose config 通过 |
| 2026-05-29 | 创建项目说明和演示文档 | `README.md`、`docs/`、`.env.example` | 已完成 |
| 2026-05-29 | 同步 apply 状态 | `spec.md`、`tasks.md`、`test-spec.md`、`log.md`、`project-context.md` | 已完成 |

## 决策记录

- change id 使用 `openscout-mvp-foundation`。
- 已按用户确认进入 `/apply`，实现第一阶段 MVP 骨架。
- 第一阶段以 mock 端到端链路为主，真实 GitHub API 作为可选增强，避免限流影响演示。
- 规则评分作为最终分数来源，LLM 只负责解释。
- Trace 第一阶段记录请求级和工具摘要，step-level trace 可后续拆分。
- Java Trace 第一阶段采用内存实现，`deploy/init.sql` 先预留 `agent_trace` 表，后续再接 MyBatis-Plus Mapper。
- Go Collector 第一阶段采用进程内 TTL 缓存，Redis adapter 延后；Docker Compose 仍提供 Redis 依赖用于后续增强。

## 验证记录

- 已创建 `code_copilot/README.md`、`agents/`、`rules/`、`knowledge/index.md`、`changes/templates/`。
- 已创建根级 `AGENTS.md` 和 `CLAUDE.md`，均指向 `code_copilot/`。
- 已创建 `spec.md`、`tasks.md`、`test-spec.md`、`log.md`。
- 已创建 `.gitignore`，排除 `/OpenScout Agent 项目方案.md`。
- 早期尝试初始化 Git 时发现当时 `.git` 是只读空目录：普通命令可见但无法改权限；提权命令不可见该目录；`git init` 失败于 `.git/hooks/: Read-only file system`，因此当时暂不能完成标准 Git 初始化。
- 后续复查时 `.git` 已成为有效 Git 目录，`git status` 可正常执行；默认分支已设置为 `main`；`git check-ignore` 确认 `OpenScout Agent 项目方案.md` 被 `.gitignore` 忽略。
- `cd openscout-agent-server && mvn test` 通过；当前编译 18 个 Java source，2 个测试执行，0 失败，0 错误。
- 首次 `mvn test` 曾因 `RestTemplateBuilder#connectTimeout(Duration)` 编译不兼容失败；已移除未使用的 `RestTemplateBuilder` bean，改为只提供 `RestClient.Builder`。
- 后续补充 Java 出站 HTTP 超时和 Agent 失败响应：Collector 不可用时返回带 `traceId` 的 502 JSON，而不是默认错误页。
- `docker compose -f deploy/docker-compose.yml config` 通过，MySQL/Redis 服务配置可解析。
- `cd openscout-repo-collector && go version` 失败：`go: command not found`。
- `cd openscout-repo-collector && gofmt -w cmd internal` 失败：`gofmt: command not found`。
- Go 侧 `go test ./...`、`go run ./cmd/server`、curl 接口验证未执行，原因是本机缺少 Go 工具链且未启动长驻服务。

## 遗留问题

- Git 已可用；后续如需同步 GitHub，还需要创建远端仓库并配置 `origin`。
- 需要安装 Go 后补跑 `gofmt -w cmd internal`、`go test ./...`、`go run ./cmd/server`。
- Redis adapter、Spring AI Tool Calling、MyBatis-Plus Mapper 持久化和真实 GitHub API 完整验证建议拆后续 change。
