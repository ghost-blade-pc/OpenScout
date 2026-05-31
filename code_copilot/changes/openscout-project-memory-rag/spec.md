# 阶段 10 Project Memory / RAG
> status: done
> created: 2026-05-31
> complexity: 中等

## 1. 背景与目标

阶段 9 已将 Agent Runtime 的能力标准化为 Tool Runtime，`PlanExecutor` 通过 `ToolExecutor` 统一执行 6 个固定 Tool。当前每次 `/api/agent/ask` 请求都会完整走一遍 `search_repos → fetch_readme → score_projects` 链路，即使同一 repo 在之前的请求中已经采集并持久化过。

阶段 4 已落地的 `repo_info` 和 `repo_analysis` 表实际上就是项目记忆的原始存储——`repo_info` 保存了 GitHub 项目元数据，`repo_analysis` 保存了评分证据和摘要。阶段 10 的目标是在这些既有持久化能力之上，建立显式的 **Project Memory** 层：查询时先检查 MySQL 中是否已有新鲜的项目数据，命中则复用，未命中或过期则走外部 API 并回写记忆。

完成后可观察到：

- **同 keyword 搜索复用**：相同或相似关键词的搜索，可从 MySQL `repo_info` 中复用已采集的项目元数据，减少 GitHub API 调用。
- **同 repo README/分析复用**：`repo_analysis` 中已有的评分证据和 README 摘要，可在 freshness TTL 内跳过 GitHub README 获取。
- **Trace 可见**：Agent Trace 中可看到 `memory_check`、`memory_hit`、`memory_miss`、`memory_writeback` 事件。
- **MySQL 关键词检索**：不依赖向量库，使用 MySQL `LIKE` 实现基本关键词匹配即可跑通。
- **API 兼容**：`/api/agent/ask` 响应字段和错误语义保持兼容。

### 1.1 业务边界

- 所属上下文：OpenScout Agent 的 Agent Runtime → Tool Runtime → Project Memory → MySQL 持久化 → Trace。
- 调用方向：HTTP → AgentService → PlanExecutor → ToolExecutor → CheckMemoryTool / SearchReposTool / FetchReadmeTool → ProjectMemoryService → MySQL (repo_info / repo_analysis)。
- 是否涉及高风险项：否（不涉及外部 API 变更，不新增鉴权需求）。
- 高风险类型：数据库查询性能（LIKE 全表扫描）、缓存新鲜度边界、Trace 事件兼容。

### 1.2 范围裁剪

- 本次包含：
  - 新增 `ProjectMemoryService`，封装对 `repo_info` 和 `repo_analysis` 的只读查询（关键词搜索、按 fullName 查 README 缓存、freshness 判断）。
  - 新增 `CheckMemoryTool`，在 `search_repos` 前执行，查询 MySQL 是否已有新鲜项目数据。
  - 修改 `RuleBasedAgentPlanner`，在 mock 和 real 计划中插入 `check_memory` 步骤。
  - 修改 `SearchReposTool`，当 context 中已有 memory 返回的 repo 数据时跳过 Collector 调用。
  - 修改 `FetchReadmeTool`，对每个 repo 先查询 `repo_analysis` 中是否有新鲜 README 摘要，有则跳过 GitHub 调用。
  - 修改 `ScoreProjectsTool`，在持久化成功后记录 `memory_writeback` Trace 事件。
  - 新增 `memory_check`、`memory_hit`、`memory_miss`、`memory_writeback` Trace 事件约定。
  - 新增 `openscout.memory.enabled`（默认 true）和 `openscout.memory.freshness-hours`（默认 24）配置。
  - 补充 `AgentContext` 的 memory 相关状态字段。
  - 单元测试覆盖 MemoryService、CheckMemoryTool、SearchReposTool/FetchReadmeTool 的 memory 分支、freshness 边界和 Trace 事件。
- 本次不包含：
  - 向量数据库接入（如 Milvus、PgVector、Redis Vector）。
  - 全文索引（MySQL FULLTEXT）或 Elasticsearch。
  - README 全文语义检索或 embedding 生成。
  - 跨用户记忆共享和隔离（当前 MVP 单用户）。
  - Evidence-aware ReAct 追加循环。
  - Reflection Verifier。
  - SSE/Streaming、前端事件展示。
  - Spring AI `@Tool` 或 MCP 协议。
  - 新增数据库表或改动 `deploy/init.sql`。
  - 缓存预热、缓存失效策略或异步刷新。
- 后续可能拆分：
  - `openscout-evidence-react`：当 memory miss 或 evidence gap 时，动态追加有限 Tool 调用。
  - `openscout-agent-evaluation`：评测 memory hit rate、API 调用节省量等指标。
  - 向量检索增强：在 MySQL 关键词基础上接入 embedding + 向量相似度排序。

## 2. Research Findings

### 2.1 相关入口与链路

- **HTTP/API**：`AgentController.java` 提供 `POST /api/agent/ask`，请求进入 `AgentService → PlanExecutor → ToolExecutor`。
- **Planner**：`RuleBasedAgentPlanner.java` 当前生成固定 step 序列（mock: 5 step / real: 6 step），需要插入 `check_memory` 步骤。
- **Tool 执行**：`ToolExecutor.java` 统一执行 Tool 并记录 `agent_tool_started/finished/failed`。
- **搜索 Tool**：`SearchReposTool.java` 当前直接调用 `CollectorClient.fetchMockRepos()` 或 `searchRepos()`，需要在调用前检查 context 是否已有 memory 数据。
- **README Tool**：`FetchReadmeTool.java` 当前对 Top 5 repo 逐一调用 `CollectorClient.getReadme()`，需要增加 memory 查询前置步骤。
- **评分 Tool**：`ScoreProjectsTool.java` 当前已通过 `RepoPersistenceService.upsertRepoInfo()` 和 `RepoAnalysisPersistenceService.saveAnalysis()` 持久化数据，这些就是 memory 的 write-back。
- **持久化**：`RepoInfoEntity` 映射 `repo_info` 表，`RepoAnalysisEntity` 映射 `repo_analysis` 表，两表已有 `modified_at`/`analyzed_at` 时间字段可用于 freshness 判断。
- **配置**：`OpenScoutProperties.java` 已有 `Persistence` 内部类，需新增 `Memory` 内部类。
- **Trace**：`TraceService.java` 已有 `recordToolCall()` 方法，memory 事件可复用此方法。

### 2.2 现有数据与 freshness 基础

- `repo_info.modified_at`：记录 repo 元数据最后更新时间，可用于判断是否需要重新采集 stars/forks/language 等。
- `repo_analysis.analyzed_at`：记录分析时间，可用于判断评分证据是否需要重新生成。
- 阶段 4 的 `RepoPersistenceService.upsertRepoInfo()` 已实现按 `full_name` 幂等 upsert。
- 阶段 4 的 `RepoAnalysisPersistenceService.saveAnalysis()` 每次追加新记录，不覆盖历史分析。

### 2.3 发现的问题

- 目前 `repo_info` 和 `repo_analysis` 只写入不查询复用——持久化数据是"死"的，每次 ask 仍然重新调用 GitHub API。
- 没有 freshness 判断机制：无法区分"昨天采集的 react 项目数据"和"从未采集过"。
- `SearchReposTool` 每次都调用 Collector，无法跳过。
- `FetchReadmeTool` 对已分析过的 repo 仍重复获取 README。
- 缺少 MySQL 关键词搜索能力：`repo_info` 表目前只能按 `full_name` 精确查找。

### 2.4 风险初判

- **数据库查询性能**：`LIKE '%keyword%'` 在 `repo_info` 表上可能导致全表扫描。MVP 阶段数据量小（< 10000 行），可接受；后续数据量大时再考虑 FULLTEXT 索引或向量检索。
- **Memory 开关兼容**：当 `openscout.memory.enabled=false` 或 `openscout.persistence.enabled=false`（MySQL 不可用）时，memory 检查必须静默 fallback 到直接调用 API，不阻塞 ask 主流程。
- **Freshness 边界**：`openscout.memory.freshness-hours` 是全局阈值，不对不同维度（stars 变化快，README 变化慢）做差异化 TTL。MVP 可接受。
- **Plan 兼容**：插入 `check_memory` 步骤会改变 step-id 序列和 step 数量，但不影响 `/api/agent/ask` 响应字段。
- **Trace 事件**：memory 事件继续复用 `TraceToolCall`，不新增 DDL。

## 3. 功能点

- [x] 功能 1：新增 `ProjectMemoryService`，封装 MySQL 关键词搜索和 README 缓存查询。
- [x] 功能 2：新增 `CheckMemoryTool`，在计划中先于 `search_repos` 执行 memory 查询。
- [x] 功能 3：修改 `RuleBasedAgentPlanner`，在 mock 和 real 计划中插入 `check_memory` 步骤。
- [x] 功能 4：修改 `SearchReposTool`，在 memory 命中时跳过 Collector 调用。
- [x] 功能 5：修改 `FetchReadmeTool`，对每个 repo 先查 memory 再决定是否调用 GitHub。
- [x] 功能 6：修改 `ScoreProjectsTool`，持久化后记录 `memory_writeback` Trace 事件。
- [x] 功能 7：新增 `openscout.memory.*` 配置和 `AgentContext` memory 状态字段。
- [x] 功能 8：补充 ProjectMemoryService、CheckMemoryTool、各 Tool memory 分支和 freshness 边界的单元测试。
- [x] 功能 9：同步 README、项目实施进度和 change 文档。

## 4. 数据与配置变更

| 类型 | 对象 | 变更内容 | 兼容性 | 回滚/补偿 |
|---|---|---|---|---|
| Java Service | `com.openscout.memory.ProjectMemoryService` | 新增 memory 查询服务 | 新增内部服务，不影响 API | 关闭 `openscout.memory.enabled` 即可禁用 |
| Java Tool | `CheckMemoryTool` | 新增 Tool，插入计划中 | 新增 Tool，API 兼容 | 移除计划中的 step 即可 |
| Java Model | `AgentContext` | 新增 `memoryRepos` 和 `memoryHit` 字段 | 内部状态扩展 | 回退字段即可 |
| Config | `openscout.memory.enabled` / `freshness-hours` | 新增配置键 | 新增配置，默认值不破坏现有行为 | 设置 `enabled=false` 可完全关闭 |
| DB | 无 | 不改 `deploy/init.sql`，复用既有 `repo_info`/`repo_analysis` 表 | 无迁移风险 | 无 |
| Trace | `TraceToolCall` 事件约定 | 新增 `memory_check`、`memory_hit`、`memory_miss`、`memory_writeback` 事件 | 不改 DDL，继续写入 `tool_calls_json` | 删除新增事件记录即可 |

## 5. 接口与消息契约

### 5.1 入站接口

| Path/Name | Method | Request | Response | 鉴权/权限 | 兼容性 |
|---|---|---|---|---|---|
| `/api/agent/ask` | POST | 复用 `AgentAskRequest` | 保留既有字段；内部 Memory 不新增响应字段 | 不新增鉴权 | 必须向后兼容 |
| `/api/agent/traces/{traceId}` | GET | path `traceId` | `toolCalls` 可看到 memory 事件和命中/未命中摘要 | 不新增鉴权 | 兼容追加事件 |

### 5.2 出站调用（Memory 层为内部 MySQL 查询，不新增外部调用）

| 目标 | 操作 | 说明 |
|---|---|---|
| MySQL `repo_info` | `SELECT ... WHERE full_name LIKE ? OR description LIKE ? OR language = ?` | 关键词搜索，限制返回行数 |
| MySQL `repo_analysis` | `SELECT ... WHERE full_name = ? ORDER BY analyzed_at DESC LIMIT 1` | 查最新分析记录 |
| Go Collector | 现有调用不变；memory 命中时跳过 | 减少 GitHub API 调用 |

## 6. 风险与关注点

- **MySQL LIKE 性能**：当前数据量极小，无风险。后续若 `repo_info` 超过 1 万行，需评估 FULLTEXT 索引或分关键词拆表。本次在 `spec.md` 中记录此约束，不做额外索引。
- **Memory 不可用不阻塞主流程**：当 `persistence.enabled=false` 或 MySQL 查询异常时，`ProjectMemoryService` 返回空结果，`CheckMemoryTool` 记录 `memory_check`（status=disabled/error）后继续，后续 Tool 走正常 API 路径。
- **Freshness 一致性**：同一 keyword 的搜索结果可能因 GitHub 新项目出现而变化，memory 复用会丢失新项目。MVP 阶段以 freshness TTL 为界限：TTL 内相信缓存，超过 TTL 重新搜索。
- **README 摘要长度**：`repo_analysis.summary` 字段是 TEXT 类型（最大 2000 字符的截断字符串），不是完整 README。FetchReadmeTool 从 memory 得到的 README 摘要可能比实时获取的更简略——这是有意的取舍。
- **不做向量检索**：第一版只用 `LIKE` 关键词匹配，语义相似度不在范围内。需在文档中明确记录此约束。

## 7. 测试策略

- 单元测试：
  - `ProjectMemoryService`：关键词搜索返回匹配的 repo_info 记录；空 keyword 返回空；无匹配返回空；freshness 判断正确（在 TTL 内/外）。
  - `CheckMemoryTool`：memory 命中时 context.repos 被填充、memoryHit 标记为 true、Trace 记录 `memory_hit`；memory 未命中时 context 不变、Trace 记录 `memory_miss`；memory disabled 时静默跳过。
  - `SearchReposTool`：memory 命中时跳过 Collector 调用，直接返回 success；memory 未命中时走正常路径。
  - `FetchReadmeTool`：memory 有新鲜 README 缓存时跳过 GitHub 调用；无缓存或过期时走正常路径。
  - `ScoreProjectsTool`：持久化成功后 Trace 包含 `memory_writeback` 事件；持久化关闭时无 writeback 事件。
  - `PlanExecutor`：新计划包含 `check_memory` 步骤；mock 和 real 模式 step 数量正确。
- 回归测试：
  - `AgentServiceTest` 验证 `/api/agent/ask` 响应兼容。
  - `PlanExecutorTest` 验证 mock 成功、search 失败、real README 部分失败继续执行。
  - 现有 Tool 测试不受影响。
- 验证命令：
  - `cd openscout-agent-server && mvn test`
  - `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...`
  - `docker compose -f deploy/docker-compose.yml config`

## 8. 待澄清

- [x] `/apply` 前确认：第一版 Memory 只做 MySQL LIKE 关键词检索，不做 FULLTEXT 索引或向量检索。
- [x] `/apply` 前确认：Memory 事件继续复用 `TraceToolCall`，不新增 Trace DDL。
- [x] `/apply` 前确认：`check_memory` 作为新的固定 Tool 插入计划，不引入动态 Tool 选择。

## 9. 技术决策

| 决策点 | 选择 | 备选 | 理由 | 影响 |
|---|---|---|---|---|
| Memory 实现方式 | 新增 `CheckMemoryTool` 插入计划 | 在 ToolExecutor 层做拦截 | 显式化 memory 为计划步骤，Trace 可见，遵循现有 Tool 模式 | 增加一个 Tool 和一个计划步骤 |
| 关键词搜索 | MySQL `LIKE '%keyword%'` | FULLTEXT 索引 / Elasticsearch | MVP 数据量小，避免索引维护复杂度 | 数据量大时需迁移 |
| Freshness TTL | 全局单一 `freshness-hours` 配置 | 按字段（stars/README）差异化 TTL | 简化配置和判断逻辑 | 对 stars 变化敏感度不够 |
| Memory 写入 | 复用 `ScoreProjectsTool` 已有的持久化逻辑 | 独立 `WriteMemoryTool` | 减少 Tool 数量和步骤，持久化已在评分 Tool 中 | 写入和评分耦合，但阶段 4 已是如此 |
| README 缓存粒度 | 按 `full_name` 取最新 `repo_analysis` 记录 | 独立 README 缓存表 | 复用既有表结构，避免 DDL | summary 字段非完整 README |
| fallback 策略 | Memory 异常/disabled 时静默跳过，走正常 API 路径 | 中断 ask 返回错误 | 保证主流程可用性，Memory 是优化而非必需 | Memory 故障对用户透明 |

## 10. 确认记录

- 确认时间：2026-05-31。
- 确认人：用户。
- 确认范围：第一版 Memory 只做 MySQL LIKE 关键词检索；继续复用 `TraceToolCall`；`check_memory` 作为固定 Tool 插入计划；不做 FULLTEXT/向量/ReAct/Verifier/SSE/MCP。
- `/apply` 记录：2026-05-31，已开始实现 Project Memory 层。
- `/apply` 完成时间：2026-05-31。所有 Task 完成，50 tests 通过，Go/Docker 回归通过。
