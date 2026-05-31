# 测试计划 - 阶段 10 Project Memory / RAG

## 1. 测试目标

验证阶段 10 在现有 Agent Runtime / Tool Runtime 之上建立 Project Memory 层后，`/api/agent/ask` 的响应、Trace、错误处理、LLM fallback、学习计划和 Collector 错误语义保持兼容，且 memory hit/miss/freshness/write-back 在 Trace 中可见。

## 2. 测试范围

- P0：
  - `ProjectMemoryService` 关键词搜索、README 缓存查询、freshness 判断、DB 异常 fallback。
  - `CheckMemoryTool` memory hit/miss/disabled/error 分支和 Trace 事件。
  - `SearchReposTool` memory 命中跳过 Collector 调用、memory 未命中正常调用。
  - `FetchReadmeTool` README 缓存命中跳过 GitHub、缓存过期正常调用。
  - `ScoreProjectsTool` 持久化成功后 memory_writeback Trace 事件。
  - `RuleBasedAgentPlanner` 新计划包含 `check_memory` 步骤，mock/real step 数量正确。
  - `PlanExecutor` 回归：mock 成功、Collector 不可用、real README 部分失败。
  - Trace 中 memory 事件可见且摘要脱敏。
- P1：
  - `openscout.memory.enabled=false` 时 memory 检查静默跳过。
  - `openscout.persistence.enabled=false` 时 memory 查询返回空（没有被查询的数据）。
  - freshness TTL 边界测试（刚好在 TTL 内/外）。
- 不覆盖：
  - 向量检索、FULLTEXT 索引、embedding。
  - Evidence-aware ReAct、Reflection Verifier、SSE/Streaming。
  - 生产鉴权、多用户隔离。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| MemoryService 关键词搜索有匹配 | 单元 | keyword="react", repo_info 有匹配记录 | 返回匹配的 RepoSummary 列表 | P0 |
| MemoryService 关键词搜索无匹配 | 单元 | keyword="xyznotexist" | 返回空列表 | P0 |
| MemoryService 空 keyword | 单元 | keyword="" | 返回空列表 | P0 |
| MemoryService README 缓存查询有记录 | 单元 | fullName="facebook/react", repo_analysis 有记录 | 返回最新分析的 summary | P0 |
| MemoryService README 缓存查询无记录 | 单元 | fullName="nonexist/repo" | 返回 empty | P0 |
| MemoryService freshness 判断（新鲜） | 单元 | modifiedAt = 1 小时前, freshnessHours=24 | isFresh=true | P0 |
| MemoryService freshness 判断（过期） | 单元 | modifiedAt = 25 小时前, freshnessHours=24 | isFresh=false | P0 |
| MemoryService DB 异常 fallback | 单元 | Mapper 抛异常 | 返回空列表，不抛异常 | P0 |
| CheckMemoryTool 命中新鲜结果 | 单元 | keyword 有匹配 + 新鲜 | context.repos 被填充, memoryHit=true, Trace memory_hit | P0 |
| CheckMemoryTool 未命中 | 单元 | keyword 无匹配 | context.repos 不变, Trace memory_miss | P0 |
| CheckMemoryTool 结果全部过期 | 单元 | keyword 有匹配但全部过期 | 视为 miss, context.repos 不变, Trace memory_miss | P0 |
| CheckMemoryTool memory disabled | 单元 | openscout.memory.enabled=false | 跳过, context 不变, Trace memory_check status=disabled | P0 |
| CheckMemoryTool memory 异常 | 单元 | MemoryService 抛异常 | 记录错误不中断, continueOnFailure=true | P0 |
| SearchReposTool memory 命中跳过 | 单元 | context.repos 非空 + memoryHit=true | 不调用 CollectorClient, 返回 success | P0 |
| SearchReposTool memory 未命中 | 单元 | context.repos 为空 | 正常调用 CollectorClient | P0 |
| SearchReposTool mock 模式 memory 跳过 | 单元 | mockAgent=true, memory 命中 | 不调用 fetchMockRepos | P1 |
| FetchReadmeTool README 缓存命中 | 单元 | repo_analysis 有新鲜 summary | 跳过 GitHub 调用, 记录 readme_cache_hit | P0 |
| FetchReadmeTool README 缓存过期 | 单元 | repo_analysis 有记录但过期 | 走 GitHub 调用, 记录 readme_cache_miss | P1 |
| FetchReadmeTool README 无缓存 | 单元 | repo_analysis 无记录 | 走 GitHub 调用 | P0 |
| FetchReadmeTool mock 模式不变 | 单元 | mockAgent=true | 直接返回 skipped, 不查 memory | P0 |
| ScoreProjectsTool 持久化成功记录 writeback | 单元 | persistence.enabled=true | Trace 包含 memory_writeback 事件 | P0 |
| ScoreProjectsTool 持久化关闭无 writeback | 单元 | persistence.enabled=false | Trace 不含 memory_writeback | P1 |
| Planner mock 计划含 check_memory | 单元 | mockAgent=true | plan 有 6 step, step-2 是 check_memory | P0 |
| Planner real 计划含 check_memory | 单元 | mockAgent=false | plan 有 7 step, step-2 是 check_memory | P0 |
| PlanExecutor mock 回归 | 集成式单元 | mockAgent=true, memory disabled | ask 结果兼容 | P0 |
| PlanExecutor search 失败 | 集成式单元 | Collector 不可用 | Trace 含 step failed, 异常向上 | P0 |
| Trace memory 事件脱敏 | 单元 | 摘要含敏感字段 | 脱敏和截断生效 | P0 |

## 4. 验证命令

```bash
cd openscout-agent-server && mvn test
cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
docker compose -f deploy/docker-compose.yml config
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|---|
| 2026-05-31 | `cd openscout-agent-server && mvn test` | 通过 | 50 tests |
| 2026-05-31 | `cd openscout-repo-collector && go test ./...` | 通过 | 使用项目本地 Go 1.26.3 |
| 2026-05-31 | `docker compose -f deploy/docker-compose.yml config` | 通过 | Compose 配置可解析 |
