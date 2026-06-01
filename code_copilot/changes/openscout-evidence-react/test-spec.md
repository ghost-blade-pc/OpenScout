# 测试计划 - 阶段 11 Evidence ReAct

## 1. 测试目标

验证阶段 11 在现有 Agent Runtime / Tool Runtime / Project Memory 之上加入 Evidence ReAct 后，能够基于评分 evidence 识别缺口、执行受限 README 补查、记录 follow-up observation、重新评分，并在 GitHub 404、限流、部分失败、mock 模式和配置关闭时保持 `/api/agent/ask` 主流程兼容。

## 2. 测试范围

- P0：
  - Evidence gap 检测：README 缺失、文档 evidence 不足、无 gap。
  - Planner 插入 `evidence_react`：mock / real 计划 step 顺序正确。
  - `EvidenceReActTool`：enabled 成功补查、disabled 跳过、mock 跳过、无推荐跳过、无 actionable gap 停止。
  - 外部 API 异常：README 404、RateLimit、普通异常和部分失败。
  - 循环控制：`max-rounds`、`max-follow-up-repos` 生效。
  - Trace：gap、follow-up action、observation、rescore、stop reason 可见且脱敏。
  - PlanExecutor 回归：成功链路、search fatal 失败、real README 部分失败。
- P1：
  - 持久化开启时补查后评分结果写回 memory。
  - memory 命中但 evidence 不完整时触发补查。
  - README 长度和 examples 推断保持与既有评分规则一致。
- 不覆盖：
  - Reflection Verifier。
  - SSE / Streaming。
  - Spring AI `@Tool`、MCP、公开 Tool API。
  - 向量检索、FULLTEXT、embedding。
  - 生产鉴权、多用户隔离、前端展示。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| GapDetector 识别 README 缺失 | 单元 | repo.readmeLength=0, score 无 docs evidence | 返回 `MISSING_README` gap | P0 |
| GapDetector 忽略证据充分项目 | 单元 | README >= 2000 且有 docs/learning evidence | 不返回 gap | P0 |
| GapDetector Top N 限制 | 单元 | 5 个推荐, max=3 | 最多返回 3 个 gap | P0 |
| Planner mock 计划插入 ReAct | 单元 | mockAgent=true | `score_projects` 后是 `evidence_react` | P0 |
| Planner real 计划插入 ReAct | 单元 | mockAgent=false | `fetch_readme -> score_projects -> evidence_react` 顺序正确 | P0 |
| ReAct disabled | 单元 | `openscout.react.enabled=false` | 不补查, Trace stop reason=disabled | P0 |
| ReAct mock 模式跳过 | 单元 | mode=MOCK | 不调用 CollectorClient, Trace stop reason=mode_not_real | P0 |
| ReAct 无推荐跳过 | 单元 | recommendations 为空 | Trace stop reason=no_recommendations | P0 |
| ReAct 无 gap 停止 | 单元 | evidence 充分 | Trace stop reason=no_actionable_gap | P0 |
| ReAct 成功补 README | 单元 | Top repo README 缺失, Collector 返回 README | 更新 RepoSummary, 重新评分, Trace observation success | P0 |
| ReAct README 404 | 单元 | Collector 抛 GitHubApiException 404 | 记录 skipped/not_found observation, 主流程继续 | P0 |
| ReAct RateLimit | 单元 | Collector 抛 RateLimitException | 记录 retryAfter, 停止后续补查, 主流程继续 | P0 |
| ReAct 普通异常 | 单元 | Collector 抛 RuntimeException | 记录 failed observation, 继续其他 repo | P0 |
| ReAct 部分失败 | 单元 | 3 个 repo 中 1 成功 2 失败 | 成功项重评分, 失败项保留原推荐 | P0 |
| max-follow-up-repos 生效 | 单元 | 5 个 gap, max=2 | 只补查 2 个 repo | P0 |
| max-rounds 生效 | 单元 | max-rounds=1 且仍有 gap | Trace stop reason=max_rounds_reached | P0 |
| Trace 脱敏 | 单元 | error 含 token/api-key | Trace 摘要脱敏 | P0 |
| PlanExecutor mock 回归 | 集成式单元 | mockAgent=true | ask 响应兼容, Trace 包含 evidence_react skipped | P0 |
| PlanExecutor real 部分失败 | 集成式单元 | real mode, README 部分失败 | 仍生成 recommendations/learningPlan/answer | P0 |
| 持久化回写 | 单元/集成式单元 | persistence.enabled=true, 补查成功 | repo_analysis 保存新 evidence | P1 |

## 4. 验证命令

```bash
cd openscout-agent-server && mvn test
cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
docker compose -f deploy/docker-compose.yml config
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | 66 tests |
| 2026-06-01 | `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...` | 通过 | 使用项目本地 Go 1.26.3 |
| 2026-06-01 | `docker compose -f deploy/docker-compose.yml config` | 通过 | Compose 配置可解析 |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | review 期间复跑，66 tests |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | review fix 后复跑，68 tests |
| 2026-06-01 | `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...` | 通过 | review fix 后复跑，使用项目本地 Go 1.26.3 |
| 2026-06-01 | `docker compose -f deploy/docker-compose.yml config` | 通过 | review fix 后复跑，Compose 配置可解析 |
| 2026-06-01 | `cd openscout-agent-server && mvn test -Dtest=FetchReadmeToolTest,EvidenceReActToolTest,PlanExecutorTest` | 通过 | rate limit fix targeted，14 tests |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | rate limit fix 后复跑，70 tests |
| 2026-06-01 | `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...` | 通过 | rate limit fix 后复跑，使用项目本地 Go 1.26.3 |
| 2026-06-01 | `docker compose -f deploy/docker-compose.yml config` | 通过 | rate limit fix 后复跑，Compose 配置可解析 |

## 6. Review Fix 测试补充

- [x] README 失败去重：`PlanExecutorTest.shouldContinueWhenReadmeFetchFailsInRealMode` 验证 `fetch_readme` 已返回 `not_found` 后，`evidence_react` 不再重复调用同一 repo README。
- [x] ReAct 上下文跳过：`EvidenceReActToolTest.shouldSkipRepoWhenReadmeAlreadyFailedInSameAsk` 验证已记录本轮 README 失败时只写 `skipped_previous_<status>` observation。
- [x] 空 README：`ReadmeEvidenceEnricherTest.shouldSkipEmptyReadme` 验证 `length=0` / 空正文返回 `empty_readme` observation，而不是 `fetched`。

## 7. Review 复查 Fix

- [x] `fetch_readme` rate limit 停止语义：`FetchReadmeToolTest.shouldStopReadmeFetchWhenRateLimitedAndKeepRemainingRepos` 覆盖遇到 `ReadmeEnrichmentResult.rateLimited()` 后停止后续 README 调用、保留剩余 repo 和 `retryAfterSeconds`。
- [x] ReAct 复用 rate limit observation：`EvidenceReActToolTest.shouldStopWhenReadmeAlreadyRateLimitedInSameAsk` 覆盖 ReAct 不重复调用 README，输出 `rate_limited`、`retryAfterSeconds` 并停止补查。
