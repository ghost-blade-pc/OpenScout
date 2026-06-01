# 测试计划 - 阶段 12 Reflection Verifier

## 1. 测试目标

验证阶段 12 在现有 Agent Runtime / Tool Runtime / Evidence ReAct 之上加入 Reflection Verifier 后，能够在最终回答之后执行分数完整性、证据声明和学习计划可行性三项自检，并记录到 Trace。Verifier 任何失败（包括异常）不阻断 `/api/agent/ask` 主流程。

## 2. 测试范围

- P0：
  - 分数完整性检查：分数匹配、分数篡改、分数格式无法识别、回答不含分数。
  - 证据声明检查：evidence 支持声称、evidence 缺失时 warning、回答无声称。
  - 学习计划检查：仓库名匹配、仓库名幻觉、targetStack 一致性。
  - `VerifyAnswerTool`：disabled 跳过、各项聚合正确、单项异常不阻断、Trace 记录。
  - Planner 插入 `verify_answer`：mock / real 计划 step 顺序正确。
  - PlanExecutor 回归：成功链路兼容、Verifier 失败不阻断 ask。
- P1：
  - 多项问题同时检出。
  - 无 learningPlan 时跳过学习计划检查。
  - 无 recommendations 时跳过分数/证据检查。
- 不覆盖：
  - LLM 语义检查（第一版 `llm-enabled=false`）。
  - 自动修正被检出的问题。
  - SSE / Streaming。
  - 向量检索、FULLTEXT、embedding。
  - 生产鉴权、多用户隔离、前端展示。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| 分数一致 | 单元 | 回答含 `评分 75`，totalScore=75 | `scoreIntegrityOk=true`，无 issue | P0 |
| 分数篡改 | 单元 | 回答含 `评分 80`，totalScore=75 | `SCORE_TAMPERING` issue | P0 |
| 分数格式无法识别 | 单元 | 回答不含分数数字 | `SCORE_FORMAT_UNRECOGNIZED` warning | P0 |
| 多仓库分数混合检测 | 单元 | 2 个推荐，回答中各提分数，一处篡改 | 一个 `SCORE_TAMPERING`，一个 OK | P0 |
| evidence 支持声称 | 单元 | evidence 含 `docs:README 5000+`，回答称"文档完善" | `evidenceClaimsOk=true` | P0 |
| evidence 缺失声称 | 单元 | evidence 无 `docs:`，回答称"文档非常完善" | `EVIDENCE_UNVERIFIED` warning | P0 |
| answered 无能力声称 | 单元 | 回答只列分数无描述 | `evidenceClaimsOk=true` | P0 |
| 学习计划仓库名匹配 | 单元 | 任务 projectName 在推荐列表中 | `learningPlanOk=true` | P0 |
| 学习计划仓库名幻觉 | 单元 | 任务 projectName 不在推荐列表中 | `PLAN_HALLUCINATION` issue | P0 |
| 学习计划为空 | 单元 | learningPlan=null | 跳过检查，不抛异常 | P0 |
| Verifier disabled | 单元 | `verifier.enabled=false` | `ToolResult.success("verifier_disabled")` | P0 |
| ScoreChecker 抛异常不阻断 | 单元 | ScoreIntegrityChecker 抛 RuntimeException | 其他检查继续，Verifier 返回 success | P0 |
| EvidenceChecker 抛异常不阻断 | 单元 | EvidenceClaimsChecker 抛 RuntimeException | 其他检查继续，Verifier 返回 success | P0 |
| PlanChecker 抛异常不阻断 | 单元 | LearningPlanChecker 抛 RuntimeException | 其他检查继续，Verifier 返回 success | P0 |
| Planner mock 计划 | 单元 | mockAgent=true | 最终 step 为 `generate_answer` 后 `verify_answer` | P0 |
| Planner real 计划 | 单元 | mockAgent=false | 最终 step 为 `generate_answer` 后 `verify_answer` | P0 |
| PlanExecutor mock 回归 | 集成式单元 | mockAgent=true | ask 响应兼容，Trace 含 verify_answer | P0 |
| PlanExecutor Verifier 异常不阻断 | 集成式单元 | Verifier 内部抛异常 | ask 正常返回，Trace 含 verify 失败记录 | P0 |
| Trace 脱敏 | 单元 | answer 含疑似 token/key | Trace 摘要脱敏 | P0 |

## 4. 验证命令

```bash
cd openscout-agent-server && mvn test
cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...
docker compose -f deploy/docker-compose.yml config
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | 98 tests（含 28 个新增 Verifier 测试） |
| 2026-06-01 | `cd openscout-repo-collector && source ../scripts/use-local-tools.sh && go test ./...` | 通过 | 使用项目本地 Go 1.26.3 |
| 2026-06-01 | `docker compose -f deploy/docker-compose.yml config` | 跳过 | Docker 未在当前 WSL 环境可用 |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | review round 1-7 fixes 后复跑，98 tests |
| 2026-06-01 | `cd openscout-agent-server && mvn test` | 通过 | achieve 前最终验证，98 tests |
