# 任务拆分 - 阶段 10 Project Memory / RAG

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已检查 `code_copilot/knowledge/index.md`
- [x] 已检查工作区状态，确认不会覆盖他人修改
- [x] 已创建/切换阶段分支 `feature/09-project-memory-rag`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项（用户已确认）
- [x] 已确认本地验证命令或替代验证方式

## Task 1: ProjectMemoryService — MySQL 关键词搜索与 README 缓存查询

- **目标**：封装对 `repo_info` 和 `repo_analysis` 的查询，提供关键词搜索、README 缓存查询和 freshness 判断。
- **层级/模块**：应用服务 / Memory
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/memory/ProjectMemoryService.java`（新增）
  - `openscout-agent-server/src/test/java/com/openscout/memory/ProjectMemoryServiceTest.java`（新增）
- **依赖**：无（只依赖既有 `RepoInfoMapper`、`RepoAnalysisMapper`）
- **风险标记**：数据库查询 / 性能
- **实现要点**：
  - `searchByKeyword(keyword, maxResults)`：按 keyword 拆分单词，对 `full_name`、`description` 和 `language` 做 `LIKE '%word%'` 匹配，限制返回行数（默认 20）。
  - `getCachedReadmeSummary(fullName)`：按 `full_name` 查 `repo_analysis`，按 `analyzed_at DESC` 取最新一条，返回 `summary` 和相关字段。
  - `isFresh(modifiedAt, freshnessHours)`：判断 `modifiedAt` 或 `analyzedAt` 是否在 freshness TTL 内。
  - `toRepoSummary(RepoInfoEntity)`：将 entity 转换为 `RepoSummary`，readmeLength=0, hasExamples=false, hasDocker=false, source="cache"。
  - 所有方法在查询异常时 catch 并 log.warn，返回空结果，不向上抛异常。
  - 注入 `OpenScoutProperties` 读取 `memory.freshness-hours` 配置。
- **验收标准**：
  - 关键词搜索返回匹配的 repo_info 记录（大小写不敏感）。
  - 无匹配关键词返回空列表。
  - README 缓存查询返回最新分析记录的摘要。
  - freshness TTL 内外判断正确。
  - MySQL 查询异常时返回空，不抛异常。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -pl . -Dtest=ProjectMemoryServiceTest
  ```

- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/memory/ProjectMemoryService.java`
    - `openscout-agent-server/src/test/java/com/openscout/memory/ProjectMemoryServiceTest.java`
  - 验证结果：`mvn test` 通过（11 tests）。

## Task 2: AgentContext Memory 状态与 OpenScoutProperties Memory 配置

- **目标**：在 `AgentContext` 中增加 memory 相关状态，在 `OpenScoutProperties` 中增加 `Memory` 配置类。
- **层级/模块**：配置 / Runtime 模型
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentContext.java`（修改）
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`（修改）
  - `openscout-agent-server/src/main/resources/application.yml`（修改）
- **依赖**：无
- **风险标记**：配置兼容
- **实现要点**：
  - `AgentContext` 新增字段：
    - `memoryHit: boolean` — 标记本次 ask 是否命中 memory（默认为 false）。
    - `memoryRepoCount: int` — 从 memory 获取的 repo 数量（默认为 0）。
    - getter/setter。
  - `OpenScoutProperties` 新增 `Memory` 内部类：
    - `enabled: boolean`，默认 `true`，环境变量 `OPSCOUT_MEMORY_ENABLED`。
    - `freshnessHours: int`，默认 `24`，环境变量 `OPSCOUT_MEMORY_FRESHNESS_HOURS`。
  - `application.yml` 新增：
    ```yaml
    openscout:
      memory:
        enabled: ${OPSCOUT_MEMORY_ENABLED:true}
        freshness-hours: ${OPSCOUT_MEMORY_FRESHNESS_HOURS:24}
    ```
- **验收标准**：
  - `AgentContext` 新增字段不影响既有 getter/setter。
  - `OpenScoutProperties.getMemory()` 返回非 null 的 Memory 实例。
  - 默认值 `enabled=true, freshnessHours=24` 生效。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentContext.java`
    - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`
    - `openscout-agent-server/src/main/resources/application.yml`
  - 验证结果：`mvn test` 通过。

## Task 3: CheckMemoryTool — 计划中的 Memory 查询步骤

- **目标**：新增 Tool，在 `search_repos` 前执行：查询 MySQL 是否有新鲜的项目数据，命中则预填充 context。
- **层级/模块**：Agent Tool
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/CheckMemoryTool.java`（新增）
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/CheckMemoryToolTest.java`（新增）
- **依赖**：Task 1、Task 2
- **风险标记**：Trace 事件 / fallback
- **实现要点**：
  - toolName = `"check_memory"`。
  - 执行流程：
    1. 读取 `openscout.memory.enabled`，若 false → 返回 success("memory disabled")，不修改 context。
    2. 从 context 读取 keyword（由 `interpret_goal` 设置）。
    3. 调用 `ProjectMemoryService.searchByKeyword(keyword, 20)`。
    4. 按 `isFresh(modifiedAt, freshnessHours)` 过滤结果。
    5. 若新鲜结果非空 → 设置 `context.setRepos(freshRepos)`、`context.setMemoryHit(true)`、`context.setMemoryRepoCount(freshRepos.size())`，记录 `memory_hit` Trace 事件。
    6. 若新鲜结果为空 → 记录 `memory_miss` Trace 事件。
  - Trace 事件通过 `TraceService.recordToolCall()` 记录：
    - `memory_check`：记录 keyword、总匹配数和新鲜数。
    - `memory_hit` / `memory_miss`：记录命中 repo 数量或未命中原因。
  - `continueOnFailure = true`：memory 异常不中断 ask。
- **验收标准**：
  - memory 命中新鲜结果时 context.repos 被填充。
  - memory 未命中或 disabled 时 context.repos 不变。
  - Trace 中可见 memory hit/miss 事件。
  - memory 查询异常时记录错误但不中断。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -pl . -Dtest=CheckMemoryToolTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/CheckMemoryTool.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/tool/CheckMemoryToolTest.java`
  - 验证结果：`mvn test` 通过。

## Task 4: 修改 RuleBasedAgentPlanner 插入 check_memory 步骤

- **目标**：在 mock 和 real 计划中插入 `check_memory` 步骤，确保计划结构正确。
- **层级/模块**：Agent Runtime
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`（新增或修改既有测试）
- **依赖**：Task 3
- **风险标记**：计划兼容
- **实现要点**：
  - Mock 计划：`interpret_goal → check_memory → search_repos → score_projects → generate_learning_plan → generate_answer`（6 step）。
  - Real 计划：`interpret_goal → check_memory → search_repos → fetch_readme → score_projects → generate_learning_plan → generate_answer`（7 step）。
  - `check_memory` 步骤属性：`continueOnFailure = true`（memory 异常不中断 ask）。
  - step-id 重新分配（`step-1` 到 `step-N`）。
  - purpose 描述："检查 MySQL 中是否已有新鲜的项目数据，命中则复用"
- **验收标准**：
  - mock 计划包含 6 个步骤，第 2 个是 `check_memory`。
  - real 计划包含 7 个步骤，第 2 个是 `check_memory`。
  - `check_memory` 步骤的 `continueOnFailure = true`。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -pl . -Dtest=RuleBasedAgentPlannerTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`
  - 验证结果：`mvn test` 通过。

## Task 5: 修改 SearchReposTool 支持 Memory 跳过

- **目标**：当 context 中已有 memory 返回的 repo 数据时，跳过 Collector 调用。
- **层级/模块**：Agent Tool
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/SearchReposTool.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/SearchReposToolTest.java`（新增或修改既有测试）
- **依赖**：Task 2、Task 3
- **风险标记**：行为兼容 / mock 模式
- **实现要点**：
  - 在 `execute()` 开头检查 `context.getRepos()` 是否已非空且 `context.isMemoryHit()` 为 true。
  - 若是 → 跳过 Collector 调用，记录 Trace 事件 `search_repos_skipped`（原因="memory_hit"），返回 success。
  - 若否 → 走原有逻辑（mock 调 `fetchMockRepos`，real 调 `searchRepos`）。
  - mock 模式也支持 memory 跳过：mock 返回的是固定数据，但 memory 复用的语义同样适用。
  - Trace 事件：`search_repos_skipped` 记录跳过的 repo 数量。
- **验收标准**：
  - memory 命中时 `SearchReposTool` 不调用 `CollectorClient`。
  - memory 未命中时走原有逻辑不变。
  - mock 和 real 模式均支持 memory 跳过。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -pl . -Dtest=SearchReposToolTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/SearchReposTool.java`
  - 验证结果：`mvn test` 通过。

## Task 6: 修改 FetchReadmeTool 支持 Memory README 缓存

- **目标**：对每个 repo，先查询 `repo_analysis` 是否有新鲜的 README 摘要，有则跳过 GitHub 调用。
- **层级/模块**：Agent Tool
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/FetchReadmeTool.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/FetchReadmeToolTest.java`（修改）
- **依赖**：Task 1、Task 2
- **风险标记**：外部接口 / best-effort
- **实现要点**：
  - 在 `enrichWithReadme()` 方法开头增加 memory 查询：
    1. 调用 `ProjectMemoryService.getCachedReadmeSummary(repo.fullName())`。
    2. 若返回非空且 `isFresh(analyzedAt, freshnessHours)` → 复用已有 evidence（hasExamples/hasDocker 从 `repo_analysis` 的 evidence_json 推断），标记 `fromCache=true`，跳过 GitHub 调用。
    3. 若未命中 → 走原有 `collectorClient.getReadme()` 逻辑。
  - Trace 事件：
    - `readme_cache_hit`：记录 repo fullName 和 freshness。
    - `readme_cache_miss`：记录 repo fullName 和未命中原因。
  - 不修改 `EnrichedRepo` 内部 record——通过 Trace 事件区分来源。
- **验收标准**：
  - 有新鲜 README 缓存时跳过 GitHub 调用。
  - 无缓存或过期时走正常 GitHub 路径。
  - mock 模式不受影响（FetchReadmeTool 对 mock 直接返回 skipped）。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -pl . -Dtest=FetchReadmeToolTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/FetchReadmeTool.java`
  - 验证结果：`mvn test` 通过。

## Task 7: 修改 ScoreProjectsTool 记录 Memory Write-back

- **目标**：在持久化成功后，显式记录 `memory_writeback` Trace 事件。
- **层级/模块**：Agent Tool
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ScoreProjectsTool.java`（修改）
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/ScoreProjectsToolTest.java`（修改）
- **依赖**：Task 6
- **风险标记**：Trace 事件
- **实现要点**：
  - 在 `persistReposIfEnabled()` 成功后，记录 `memory_writeback` Trace 事件：
    - `TraceService.recordToolCall(trace, "memory_writeback", "repos=" + persistedCount, "status=ok", latencyMs)`。
  - 持久化关闭时不记录 writeback 事件。
  - 持久化部分失败时记录 `memory_writeback_partial`（成功数/总数）。
- **验收标准**：
  - 持久化开启时 Trace 包含 `memory_writeback` 事件。
  - 持久化关闭时无 writeback 事件。
  - 部分失败时事件摘要反映成功/失败计数。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test -pl . -Dtest=ScoreProjectsToolTest
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ScoreProjectsTool.java`
  - 验证结果：`mvn test` 通过。

## Task 8: 回归验证与测试补齐

- **目标**：证明 Project Memory 没有破坏 Agent Runtime、Tool Runtime、Trace、LLM fallback 和 Collector 错误处理。
- **层级/模块**：测试
- **涉及文件**：
  - `openscout-agent-server/src/test/java/com/openscout/memory/`
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/`
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/`
  - `openscout-agent-server/src/test/java/com/openscout/trace/`
- **依赖**：Task 1-7
- **风险标记**：回归验证
- **实现要点**：
  - `ProjectMemoryServiceTest`：关键词搜索、README 缓存查询、freshness 判断、异常 fallback。
  - `CheckMemoryToolTest`：memory hit/miss/disabled/error 分支。
  - `SearchReposToolTest`：memory 命中跳过、memory 未命中正常调用。
  - `FetchReadmeToolTest`：README 缓存命中跳过、缓存过期调用 GitHub。
  - `ScoreProjectsToolTest`：persist 成功记录 writeback、persist 关闭无 writeback。
  - `PlanExecutorTest`：新计划包含 `check_memory` 步骤；mock 成功和 search 失败回归。
  - `TraceServiceTest`：memory 事件脱敏和截断。
  - 全量回归：Java `mvn test`、Go `go test ./...`、Docker Compose config。
- **验收标准**：
  - Java 全量测试通过。
  - Go 测试通过。
  - Docker Compose config 通过。
  - 所有新增 memory 事件经过脱敏。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/agent/tool/CheckMemoryToolTest.java`
    - `openscout-agent-server/src/test/java/com/openscout/memory/ProjectMemoryServiceTest.java`
  - 验证结果：Java `mvn test` 通过（50 tests）；Go `go test ./...` 通过；Docker Compose config 通过。

## Task 9: 文档与 code_copilot 同步

## Task 9: 文档与 code_copilot 同步

- **目标**：同步 README、项目实施进度和 change 文档。
- **层级/模块**：文档 / SpecAI
- **涉及文件**：
  - `README.md`
  - `项目实施进度.md`
  - `code_copilot/changes/openscout-project-memory-rag/spec.md`
  - `code_copilot/changes/openscout-project-memory-rag/tasks.md`
  - `code_copilot/changes/openscout-project-memory-rag/test-spec.md`
  - `code_copilot/changes/openscout-project-memory-rag/log.md`
  - `code_copilot/knowledge/index.md`
- **依赖**：Task 1-8
- **风险标记**：文档与实现一致性
- **实现要点**：
  - README 说明 Project Memory 是基于 MySQL 的关键词检索缓存层，不做向量检索。
  - 项目实施进度记录阶段 10 的开始/完成时间、结果和未完成项。
  - `log.md` 记录真实执行日志和验证命令输出。
  - `knowledge/index.md` 沉淀阶段 10 的可复用知识。
- **验收标准**：
  - 文档中的 Memory 能力和实际代码一致。
  - `log.md` 有真实执行记录。
- **验证命令**：
  ```bash
  rg -n "Project Memory|memory_check|memory_hit|memory_miss|memory_writeback|阶段 10" README.md 项目实施进度.md code_copilot/changes/openscout-project-memory-rag
  ```
- **完成记录**：
  - 状态：已完成（代码和文档同步）

## 变更摘要

- **总文件数**：16
- **新增文件**：5
  - `openscout-agent-server/src/main/java/com/openscout/memory/ProjectMemoryService.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/CheckMemoryTool.java`
  - `openscout-agent-server/src/test/java/com/openscout/memory/ProjectMemoryServiceTest.java`
  - `openscout-agent-server/src/test/java/com/openscout/agent/tool/CheckMemoryToolTest.java`
  - `code_copilot/changes/openscout-project-memory-rag/`
- **修改文件**：8
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/AgentContext.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/runtime/RuleBasedAgentPlanner.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/SearchReposTool.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/FetchReadmeTool.java`
  - `openscout-agent-server/src/main/java/com/openscout/agent/tool/ScoreProjectsTool.java`
  - `openscout-agent-server/src/main/java/com/openscout/config/OpenScoutProperties.java`
  - `openscout-agent-server/src/main/resources/application.yml`
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/PlanExecutorTest.java`
  - `openscout-agent-server/src/test/java/com/openscout/agent/runtime/RuleBasedAgentPlannerTest.java`
- **删除文件**：0
- **Spec-Plan 偏差记录**：无。Memory 事件继续复用 `TraceToolCall`；`check_memory` 作为固定 Tool 插入计划；MySQL LIKE 关键词检索，不做向量/FULLTEXT。
- **未完成项**：未做向量检索、FULLTEXT 索引、embedding、ReAct、Verifier、SSE、MCP、生产鉴权，均属于后续阶段。
- **遗留风险**：
  - MySQL LIKE 查询在大数据量下可能性能下降，当前数据量极小（< 1000 行），无实际风险。
  - freshness TTL 是全局单一阈值，不对不同维度做差异化。
  - `memory_writeback` 事件与评分持久化耦合在同一 Tool 中。
