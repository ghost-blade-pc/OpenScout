# 执行日志 - 阶段 11 Evidence ReAct

## 变更信息

- **Change ID**: `openscout-evidence-react`
- **分支**: `feature/10-evidence-react`
- **创建时间**: 2026-06-01
- **状态**: done

## 前置检查

- [x] 已读取 `项目实施进度.md`
- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/project-context.md`
- [x] 已读取 `code_copilot/rules/coding-style.md`
- [x] 已读取 `code_copilot/rules/domain-rules.md`
- [x] 已读取 `code_copilot/rules/security.md`
- [x] 已读取 `code_copilot/knowledge/index.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已检查工作区状态
- [x] 已从 `main` 创建并切换到 `feature/10-evidence-react`

## Research 记录

- `项目实施进度.md` 显示阶段 10 Project Memory / RAG 已完成并合并回 `main`，下一步是阶段 11 `feature/10-evidence-react`。
- `RuleBasedAgentPlanner.java` 当前 mock 计划为 6 step，real 计划为 7 step；阶段 11 需要在 `score_projects` 后插入 `evidence_react`。
- `PlanExecutor.java` 已通过 `ToolExecutor` 执行固定 `PlanStep`，新增 `AgentTool` 可以复用既有 step started/finished/observation Trace。
- `AgentContext.java` 当前持有 `repos`、`recommendations`、`learningPlan`、`answer` 和 memory 状态，适合承载补查后的 repo 与重新评分结果。
- `FetchReadmeTool.java` 已有 README enrichment、Top 5 限制、404/限流/普通异常 best-effort 处理，可作为 ReAct 补查逻辑的复用来源。
- `ScoreProjectsTool.java` 当前封装评分、排序和持久化回写；若 ReAct 后重评分需要回写 memory，建议抽取可复用评分/持久化服务，避免重复逻辑。
- `ProjectScoreService.java` 的 evidence 为字符串列表，阶段 11 需要新增轻量 gap 模型，不依赖 LLM 判断。
- `TraceService.java` 已统一脱敏和截断，ReAct 新事件继续复用 `TraceToolCall`，不需要新增 DDL。
- `OpenScoutProperties.java` / `application.yml` 已有配置分组模式，可新增 `openscout.react.*`。

## 执行记录

| 时间 | 阶段 | 操作 | 结果 | 备注 |
|---|---|---|---|---|
| 2026-06-01 | propose | 检查 Git 状态 | 通过 | 当前 `main` clean |
| 2026-06-01 | propose | 创建分支 `feature/10-evidence-react` | 通过 | 基于 `main` |
| 2026-06-01 | propose | 创建 `code_copilot/changes/openscout-evidence-react/` | 通过 | spec/tasks/test-spec/log |
| 2026-06-01 | propose | 同步 `项目实施进度.md` | 通过 | 记录阶段 11 当前状态 |
| 2026-06-01 | apply | 用户确认关键决策 | 通过 | max-rounds=1, max-follow-up-repos=3, README-only |
| 2026-06-01 | apply | Task 1: Evidence Gap 模型与检测器 | 已完成 | 新增 react gap 模型和测试 |
| 2026-06-01 | apply | Task 2: ReAct 配置与 Planner | 已完成 | 新增 openscout.react.*，插入 evidence_react |
| 2026-06-01 | apply | Task 3: README 补查能力复用 | 已完成 | 抽取 ReadmeEvidenceEnricher |
| 2026-06-01 | apply | Task 4: EvidenceReActTool | 已完成 | 有限补查、重评分、Trace |
| 2026-06-01 | apply | Task 5: 主链路回归 | 已完成 | PlanExecutor/AgentService 回归通过 |
| 2026-06-01 | apply | Task 6: 文档与阶段同步 | 已完成 | README、进度、knowledge、change 文档同步 |
| 2026-06-01 | review | Spec 合规和代码质量 review | 已完成 | 发现 2 个 deferred |
| 2026-06-01 | fix | 处理 review deferred | 已完成 | README 失败同请求去重；空 README 返回 `empty_readme` |
| 2026-06-01 | archive | achieve 沉淀与整理 | 已完成 | status=done，deferred 已关闭 |
| 2026-06-01 | review | Review fix 后复查 | 已完成 | 新增 1 个非阻塞 deferred：`fetch_readme` rate limit 停止语义 |
| 2026-06-01 | archive | achieve 复查沉淀 | 已完成 | 记录 deferred、边界和下一步 |
| 2026-06-01 | fix | 处理 rate limit deferred | 已完成 | `FetchReadmeTool` 遇限流停止；ReAct 复用 retryAfter observation |

## 决策记录

- 第一版 Evidence ReAct 作为固定 `evidence_react` Tool 插入计划，不引入动态 Tool 选择、MCP 或 Spring AI `@Tool`。
- ReAct 位置放在 `score_projects` 后、`generate_learning_plan` 前，保证补查后的 recommendations 会影响学习计划和最终回答。
- 默认配置确认：`enabled=true`、`max-rounds=1`、`max-follow-up-repos=3`，防止 GitHub API 配额和延迟失控。
- ReAct 首版确认只补 README evidence；release、目录结构、issues、向量检索和 verifier 留到后续阶段。
- Trace 继续复用 `TraceToolCall`，只保存摘要、状态和错误原因，不保存完整 README 或敏感配置。

## 验证记录

```bash
$ cd openscout-agent-server && mvn test
Tests run: 66, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

$ cd openscout-agent-server && mvn test
Tests run: 68, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

$ cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
ok  github.com/LiPeicheng/openscout-repo-collector/internal/cache
ok  github.com/LiPeicheng/openscout-repo-collector/internal/service

$ docker compose -f deploy/docker-compose.yml config
valid config output

$ cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
ok  github.com/LiPeicheng/openscout-repo-collector/internal/cache
ok  github.com/LiPeicheng/openscout-repo-collector/internal/service

$ docker compose -f deploy/docker-compose.yml config
valid config output

$ cd openscout-agent-server && mvn test -Dtest=FetchReadmeToolTest,EvidenceReActToolTest,PlanExecutorTest
Tests run: 14, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

$ cd openscout-agent-server && mvn test
Tests run: 70, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

$ cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
ok  github.com/LiPeicheng/openscout-repo-collector/internal/cache
ok  github.com/LiPeicheng/openscout-repo-collector/internal/service

$ docker compose -f deploy/docker-compose.yml config
valid config output
```

## 遗留问题

- 当前无阻塞待澄清项。
- 已知边界：第一版只补 README evidence；默认最大一轮、最多 3 个 repo；`evidence_react` 限流时停止后续补查并保留 Trace observation。
- 后续阶段：Reflection Verifier、SSE/Streaming、Agent Evaluation、向量检索、FULLTEXT、release/tree/issues 补查、生产鉴权。

## Review 发现与 Deferred

1. **同一请求内 README 失败可能被重复补查**
   - 位置：`FetchReadmeTool` 失败后只保留原 `RepoSummary`；`EvidenceGapDetector` 继续把 `readmeLength=0` 判为 gap；`EvidenceReActTool` 再次调用 `ReadmeEvidenceEnricher.enrichFromGitHub()`。
   - 影响：404、403/429、普通 Collector 异常可能在同一次 ask 内重复打到 Go Collector / GitHub，不利于限流控制。
   - 处理状态：已修复。`FetchReadmeTool` 记录本轮 README 失败状态到 `AgentContext`；`EvidenceReActTool` 遇到已失败 repo 时记录 `skipped_previous_<status>` observation，不再调用 `ReadmeEvidenceEnricher`。

2. **空 README 会被计为补查成功**
   - 位置：`ReadmeEvidenceEnricher.enrichFromGitHub()` 对 `readmeLength=0` 仍返回 `ReadmeEnrichmentResult.fetched()`。
   - 影响：Trace observation 和 fetched 计数会显示成功，但实际未补到 README evidence。
   - 处理状态：已修复。`ReadmeEvidenceEnricher` 仅当 `readmeLength > 0` 才返回 fetched，空 README 返回 `empty_readme`。

## Review Fix 记录

- 根因 1：`fetch_readme` 失败只影响本 Tool 的聚合计数，未把 repo 粒度失败状态传给后续 `evidence_react`。
- 修复 1：`AgentContext` 新增请求内 README failure map；`FetchReadmeTool` 写入失败状态，成功或有效 cache 命中时清理；`EvidenceReActTool` 读取后跳过重复补查并保留 Trace observation。
- 根因 2：`ReadmeEvidenceEnricher` 把 Collector 返回的空 README 当作成功 enrichment。
- 修复 2：`readmeLength <= 0` 返回 `empty_readme`，不更新 repo evidence，不增加 fetched 计数。
- 验证：`cd openscout-agent-server && mvn test` 通过，68 tests；新增覆盖 `shouldSkipEmptyReadme`、`shouldSkipRepoWhenReadmeAlreadyFailedInSameAsk`，并在 PlanExecutor real 失败回归中断言 README 只调用 1 次。

## Review 复查 Fix

1. **`fetch_readme` rate limit 停止语义仍需补齐**
   - 位置：`FetchReadmeTool.execute()` 对 `ReadmeEnrichmentResult.rateLimited()` 没有特殊处理，仍按 skipped 继续后续 repo；`AgentContext` 当前只保存 failure status，不保存 `retryAfterSeconds`。
   - 影响：真实 GitHub 限流时可能继续调用后续 README；后续 `evidence_react` 只能看到 `skipped_previous_rate_limited`，无法带上 retryAfter，也可能以 `max_rounds_reached` 作为最终 stop reason。
   - 处理状态：已修复。`AgentContext` 改为保存 `ReadmeFailureObservation(status, retryAfterSeconds)`；`FetchReadmeTool` 遇 `rateLimited` 立即停止后续 README，并保留未处理 repo；`EvidenceReActTool` 复用 observation 输出 `rate_limited`、`retryAfterSeconds` 并停止补查。
   - 验证：`FetchReadmeToolTest.shouldStopReadmeFetchWhenRateLimitedAndKeepRemainingRepos`、`EvidenceReActToolTest.shouldStopWhenReadmeAlreadyRateLimitedInSameAsk` 覆盖该行为；Java 全量 `mvn test` 通过，70 tests。

## Achieve 摘要

- 本阶段核心价值：在固定 Agent Runtime / Tool Runtime 中加入受控 Evidence ReAct，让评分后的证据缺口、补查动作、观察结果、重评分和停止原因全部可追踪。
- 可写入项目说明的已验证事实：本地实现新增 README-only Evidence ReAct；默认最大 1 轮、每轮最多 3 个 repo；Trace 追加 gap/follow-up/observation/rescore/stop 事件；同请求 README 失败不会重复补查，空 README 记录为 `empty_readme`；`fetch_readme` 遇 rate limit 会停止后续 README 并把 retryAfter 传给 ReAct observation；Java 70 tests、Go tests、Docker compose config 均通过。
- 不应夸大的说法：不能说已具备完整 ReAct 推理、自主 Tool 规划、release/tree/issues 多源补证据、SSE 事件流或 hallucination verifier。
- 下一步建议：合并阶段 11 后进入阶段 12 `openscout-reflection-verifier`。
