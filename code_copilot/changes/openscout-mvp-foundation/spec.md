# OpenScout MVP 基础骨架与核心推荐闭环
> status: done
> created: 2026-05-29
> complexity: 复杂

## 1. 背景与目标

当前仓库只有 `OpenScout Agent 项目方案.md`，尚未创建应用源码。方案目标是构建一个面向开发者和求职者的 AI 开源项目情报分析与学习路径助手：用户输入学习目标后，Java Spring AI Agent 调用 Go Repo Collector 获取 GitHub 项目数据，再由规则评分和 LLM 总结生成项目推荐、分析报告、学习计划，并记录 Agent Trace。

本 change 的目标是把方案收敛为第一阶段可执行 MVP：先创建可运行的 monorepo 骨架，跑通 Java 调 Go、mock 数据、基础评分、基础 Trace 和可选真实 GitHub API，不把所有规划功能一次性实现。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 初始项目落地。
- 调用方向：HTTP 入站接口、Java 出站 HTTP 调 Go、Go 出站 HTTP 调 GitHub REST API、Redis/MySQL 本地依赖。
- 是否涉及高风险项：是。
- 高风险类型：外部接口、限流、重试、并发、缓存、数据库 schema、敏感配置、模型输出可信度。

### 1.2 范围裁剪

本次包含：

- 创建 monorepo 应用骨架：`openscout-agent-server/`、`openscout-repo-collector/`、`deploy/`、`docs/`。
- Java 服务可启动，并提供第一阶段接口 `POST /api/agent/ask`。
- Go 服务可启动，并提供 mock repo 接口和基础 Collector 接口骨架。
- Docker Compose 启动 MySQL 和 Redis。
- Java 通过 HTTP 调用 Go mock 接口，返回项目推荐结果。
- 实现基础规则评分，LLM 解释可先以 mock/可选 provider 方式接入。
- 实现基础 Agent Trace 记录结构，至少能记录用户问题、工具调用摘要、评分结果、耗时和异常。
- 真实 GitHub API 做成可选模式，支持 `GITHUB_TOKEN` 配置、超时、基础限流和缓存。
- README 说明本地启动、mock 模式、真实 API 限流约束。

本次不包含：

- 复杂前端页面。
- MCP。
- 完整用户系统、权限系统、多租户。
- 完整多项目对比体验。
- 完整学习任务管理 UI。
- 复杂定时任务、后台调度平台。
- 生产级监控告警和部署方案。

后续可能拆分：

- `openscout-github-real-api`：补齐真实 GitHub Release、目录树、ETag/If-None-Match。
- `openscout-learning-plan`：学习目标与 7 天学习任务完整落库和查询。
- `openscout-project-compare`：多项目对比和推荐顺序。
- `openscout-trace-query`：Trace 查询和可视化。

## 2. Research Findings

### 2.1 相关入口与链路

- 方案文档：`OpenScout Agent 项目方案.md`。
- 项目定位：方案第 1 节说明自然语言目标到 Spring AI Agent、Go Collector、GitHub 数据、规则评分、LLM 总结、学习计划和 Trace 的链路。
- 技术栈：方案第 2 节计划 Java Spring Boot + Spring AI、Go Gin、MySQL、Redis、Docker Compose。
- MVP 功能：方案第 4 节列出项目推荐、单项目分析、多项目对比、学习计划、Agent Trace。
- Go Collector 接口：方案第 5 节已有 search/profile/readme/batch-profile 草案。
- Java Tool 设计：方案第 6 节已有 RepoSearchTool、RepoProfileTool、ReadmeFetchTool、BatchRepoProfileTool、ProjectScoreTool、LearningPlanTool。
- 评分规则：方案第 7 节明确规则评分为分数来源，LLM 负责解释。
- 数据表：方案第 8 节已有 `repo_info`、`repo_analysis`、`learning_goal`、`learning_task`、`agent_trace` 草案。
- 目录结构：方案第 9 节已有 monorepo 目录规划。
- 一周计划：方案第 10 节按 Day 1 到 Day 7 拆分。
- 现实约束：方案第 11 节明确 GitHub API 访问频率限制、mock 数据、rate limit、Token 和 ETag。

### 2.2 现有实现摘要

propose 阶段没有应用源码、构建文件、配置文件或测试文件；apply 阶段已创建 Java Agent Server、Go Repo Collector、部署脚本和项目文档。当前实现事实以源码、`tasks.md` 和 `log.md` 为准。

### 2.3 发现的问题

- 方案中提到 Release 和目录结构采集，但 Go Collector 接口和 Java Tool 列表未完整覆盖，需要在后续任务中补齐或明确延后。
- 一周计划覆盖范围偏大，若全部作为第一阶段实现，容易导致每块都不可演示或不可测试。
- 数据表设计只有字段名，缺少字段类型、唯一约束、索引、JSON 字段和更新时间。
- Agent Trace 目前只有总表思路，缺少 step-level trace、脱敏策略和大字段控制。
- GitHub API 约束需要工程化处理：Token、限流响应、超时、重试、缓存、mock fallback。
- 模型提供商已确认使用 DeepSeek V4 Pro，但 Spring AI 依赖版本、模型名、base URL 和 tool calling 兼容方式仍需在实现时确认。

### 2.4 风险初判

- 外部依赖风险：GitHub API 限流、网络失败、认证失败、API 结构变化。
- 并发风险：Go batch worker pool 如果不限制并发，会触发限流或请求堆积。
- 缓存风险：缓存 key、TTL 和失败缓存策略不清晰会导致脏数据或重复请求。
- 数据库风险：表结构未定，后续频繁调整可能影响任务推进。
- 模型风险：模型不可用或 provider 不支持工具调用时，Agent 演示会失败。
- Trace 风险：记录完整 README、prompt、token 或大响应会导致泄密和存储膨胀。

## 3. 功能点

- [x] 功能 1：本地环境启动。Docker Compose 配置已创建并通过 `docker compose config` 校验；Java 服务可编译测试；Go 服务代码已创建，后续阶段已补齐本地 Go 工具链并通过单测和 mock 服务启动验证。
- [x] 功能 2：mock 端到端链路。Java `POST /api/agent/ask` 调 Go mock repo 接口，返回推荐项目、基础评分和 traceId。
- [x] 功能 3：Go Collector 基础接口。提供 search/profile/readme/batch-profile 的 HTTP 合约和 mock/真实 API 可切换实现。
- [x] 功能 4：基础评分。按活跃度、文档完整度、技术匹配度、学习友好度、简历价值生成分数和 evidence，并已有 Java 单测。
- [x] 功能 5：基础 Trace。记录一次请求的用户问题、工具调用摘要、评分结果、最终回答、耗时和异常，并已有 Java 单测。
- [x] 功能 6：配置与文档。README 说明 mock 模式、真实 GitHub API 模式、限流约束、环境变量和验证命令。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| 数据库 | `repo_info` | 项目基础元数据，`full_name` 建议唯一索引 | 新表，无旧数据 | 删除表或清空本地数据 |
| 数据库 | `repo_analysis` | 分析摘要、评分、score_breakdown/evidence | 新表，无旧数据 | 删除表或清空本地数据 |
| 数据库 | `learning_goal` | 用户学习目标 | 新表，无旧数据 | 删除表或清空本地数据 |
| 数据库 | `learning_task` | 学习计划任务，第一阶段可延后完整功能 | 新表，无旧数据 | 删除表或清空本地数据 |
| 数据库 | `agent_trace` | 请求级 Trace 和工具调用摘要 | 新表，无旧数据 | 删除表或清空本地数据 |
| 缓存 | Go 进程内缓存 | repo/profile/readme 缓存，TTL 当前为 10 分钟 | 新内存对象 | 重启服务清空 |
| 配置 | `GITHUB_TOKEN` | 可选，真实 API 模式提高额度 | 不配置时使用公开 API 或 mock | 删除环境变量 |
| 配置 | `OPSCOUT_COLLECTOR_BASE_URL` | Java 调 Go 服务地址 | 本地默认值 | 回滚配置 |
| 配置 | DeepSeek API Key | 可选，未配置时使用 mock summary | 不配置仍可演示规则结果 | 删除环境变量 |

数据库字段类型和索引已落到 `deploy/init.sql`；Redis key 级缓存后续在 Redis adapter change 中补齐。

## 5. 接口与消息契约

### 5.1 Java 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | `goal/question`、可选 `mode` | 推荐项目、评分、解释、traceId | MVP 暂不鉴权 | 新接口 |

### 5.2 Go 入站接口

| Path/Name | Method | Request | Response | 说明 |
|---|---|---|---|---|
| `/api/repos/mock` | GET | 无或 keyword | 写死项目 JSON | 第一阶段端到端验收 |
| `/api/repos/search` | GET | `keyword`、`limit` | repo list | 支持 mock/真实 |
| `/api/repos/{owner}/{repo}/profile` | GET | path vars | repo profile | 支持缓存 |
| `/api/repos/{owner}/{repo}/readme` | GET | path vars | readme 摘要/内容 | 需要长度控制 |
| `/api/repos/batch-profile` | POST | repo fullName list | 成功项 + 失败项 | worker pool，部分失败 |

### 5.3 出站调用

| 目标服务 | Path/Method | Request | Response | 超时/重试 | 失败处理 |
|---|---|---|---|---|---|
| Go Collector | 本地 REST API | Java Tool DTO | repo/search/profile/readme | Java client timeout 可配置 | 返回工具失败摘要，Trace 记录 |
| GitHub REST API | search/repos、repos、contents/readme、releases | Go HTTP client | GitHub JSON | Go timeout、rate limit、有限重试 | 限流不盲目重试，可回退 mock/cache |
| DeepSeek V4 Pro | Spring AI 或兼容 OpenAI 协议的 Chat API | prompt + tool context | summary/plan | timeout 可配置 | 返回规则评分和模型不可用提示 |

### 5.4 MQ/Event

第一阶段不涉及 MQ/Event。

## 6. 风险与关注点

- GitHub 限流：真实 API 模式必须支持 Token，未配置时 README 说明额度限制；Go 服务记录限流状态，不盲目重试。
- 并发采集：batch-profile 必须用 worker pool 控制并发，默认并发数保守，单 repo 失败不影响整体结果。
- 重试策略：只对短暂网络错误或 5xx 做有限重试；403/429 需根据响应头或错误类型处理。
- 缓存策略：第一阶段 Go 进程内缓存必须有 TTL，失败响应不缓存；Redis adapter 后续补齐时再定义 key 命名和 TTL 配置。
- 模型输出：评分由规则生成，LLM 只解释，不允许模型改写分数。
- Trace 脱敏：Trace 只保存摘要，不保存 token、完整 prompt、完整 README 或大对象。
- 第一阶段范围：多项目对比、完整学习计划落库和 Release/目录树分析可延后，避免 MVP 失焦。

## 7. 测试策略

- 测试范围：服务启动、mock 链路、Java 调 Go、评分规则、Trace 写入、Go handler、Go worker pool 部分失败。
- P0 必测：
  - Docker Compose 能启动 MySQL/Redis。
  - Go `/api/repos/mock` 返回稳定 JSON。
  - Java `/api/agent/ask` 能调用 Go mock 并返回推荐项目和 traceId。
  - 评分规则对固定输入输出稳定。
  - Trace 不包含敏感配置字段。
- P1 建议：
  - Go batch-profile 部分失败不影响整体。
  - GitHub Token 未配置时真实 API 模式给出清晰提示。
  - Redis 缓存命中和 TTL 行为。
  - 模型未配置时 fallback 到规则结果。
- 不测试项及原因：
  - 复杂前端：不在本 change 范围。
  - 生产部署：当前只做本地演示。
  - 完整多项目对比：后续 change。
- 优先验证命令：`cd openscout-agent-server && mvn test`、`docker compose -f deploy/docker-compose.yml config`、`cd openscout-repo-collector && go test ./...`、curl 调用接口。
- 是否需要独立 `test-spec.md`：是。

## 8. 待澄清

以下问题不阻塞第一阶段骨架创建，但会影响 `/apply` 中具体选型：

- [x] 模型 provider 选择：DeepSeek V4 Pro。
- [x] Java 版本优先使用 17 还是 21？已确认 Java 17。
- [x] Java 持久化使用 MyBatis-Plus 还是 Spring Data JPA？已确认 MyBatis-Plus。
- [x] 第一阶段是否必须接真实 Spring AI Tool Calling，还是允许先 mock Agent 后再补 Spring AI？已确认第一阶段先 mock Agent，再接 Spring AI。
- [x] Go module path 使用什么？已确认使用 `github.com/LiPeicheng/openscout-repo-collector`。

上述选型已确认。Git 后续复查已可用，当前分支为 `main`；`OpenScout Agent 项目方案.md` 已通过 `.gitignore` 排除。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| 第一阶段范围 | 可运行 MVP 闭环 | 一次性实现全部方案功能 | 降低失焦风险，保证可演示 | 多项目对比和完整学习计划延后 |
| 数据采集职责 | Go Collector 独立服务 | Java 直接调 GitHub | 体现 Go 并发、限流、缓存能力 | 增加跨服务 HTTP 合约 |
| 评分来源 | 规则评分为准，LLM 解释 | LLM 直接打分 | 可解释、可测试、面试更好讲 | 需要维护规则和 evidence |
| GitHub API | mock 优先，真实可选 | 默认真实 API | 避免限流影响演示 | README 需说明模式切换 |
| Trace | 请求级 + 工具摘要起步 | 完整 step 表立即实现 | MVP 先可用，避免复杂化 | 后续可拆 step-level trace |
| Java 版本 | Java 17 | Java 21 | 用户已确认，生态兼容性好 | Maven 编译和 Docker 镜像按 17 配置 |
| Java 持久化 | MyBatis-Plus | Spring Data JPA | 用户已确认 | 表结构和 Mapper 按 MyBatis-Plus 设计 |
| 模型提供商 | DeepSeek V4 Pro | OpenAI、DashScope、Ollama | 用户已确认 | 需确认 Spring AI 接入方式和配置键 |
| 第一阶段 Agent | mock Agent | 直接接 Spring AI Tool Calling | 用户已确认，先保证端到端链路可运行 | Spring AI 集成作为后续增强 |
| Go module path | `github.com/LiPeicheng/openscout-repo-collector` | 本地模块名 | 用户准备同步到 GitHub | `go.mod` 按 GitHub 路径生成 |

## 10. 确认记录

- 确认时间：2026-05-29。
- 确认人：用户。
- 确认范围：OpenScout MVP 基础骨架与核心推荐闭环。
