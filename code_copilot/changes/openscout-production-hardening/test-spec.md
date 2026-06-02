# 测试计划 - 阶段 15 Production Hardening

## 1. 测试目标

验证阶段 15 的工程化基线不会破坏既有 OpenScout Agent 行为，并确保迁移、CI、脚本、API Key、quota 和配置治理都有可重复证据。默认测试必须 mock-first、本地可复现，不依赖真实 GitHub Token、DeepSeek Key、外网或生产环境。

## 2. 测试范围

- P0：
  - Java 全量测试仍通过。
  - Agent Evaluation 默认命令仍通过并生成报告。
  - Go 全量测试仍通过。
  - Docker Compose config 仍通过。
  - Java API Key disabled/enabled/invalid/valid。
  - Go API Key disabled/enabled/invalid/valid。
  - Java ask/run quota disabled/allowed/limited/window reset。
  - migration V1 与现有 schema 兼容。
  - `.env.example`、README、脚本不包含真实密钥。
- P1：
  - `scripts/verify-local.sh` 覆盖默认验证命令。
  - mock demo 脚本能明确启动/调用/输出 trace 或说明受端口环境限制。
  - optional real 脚本缺少 token/key 时给出 skip 提示。
- 不覆盖：
  - 完整用户体系、OAuth/RBAC、多租户。
  - 分布式 quota、Redis 配额、跨实例 SSE。
  - 线上压测、生产 SLA、真实 GitHub/LLM enabled 必过验证。

## 3. 测试场景

| 场景 | 类型 | 输入 | 预期 | 优先级 |
|---|---|---|---|---|
| Java API Key 默认关闭 | 单元/Controller | 无 header | 既有 API 行为不变 | P0 |
| Java API Key 开启但缺失 | 单元/Controller | 无 header | 返回 401，错误体不含 key | P0 |
| Java API Key 开启且错误 | 单元/Controller | 错误 header | 返回 401，错误体不含 key | P0 |
| Java API Key 开启且正确 | 单元/Controller | 正确 header | 请求继续执行 | P0 |
| Go API Key 默认关闭 | Go test | 无 header | `/api/repos/mock` 可访问 | P0 |
| Go API Key 开启但错误 | Go test | 错误 header | 返回 401 | P0 |
| Go API Key 开启且正确 | Go test | 正确 header | 请求继续执行 | P0 |
| Java quota disabled | 单元 | 多次 ask/run | 不限流 | P0 |
| Java quota 超限 | 单元 | 超过窗口阈值 | 返回 429 或服务层 rejected | P0 |
| quota 窗口刷新 | 单元 | 等待/模拟窗口推进 | 新窗口重新允许 | P0 |
| migration 基线 | 集成/单元 | V1 schema | 与 Entity/Mapper 兼容 | P0 |
| CI 默认命令 | 命令 | Java/Go/Compose/Evaluation | 全部通过或记录环境阻塞 | P0 |
| 本地 verify 脚本 | 脚本 | `scripts/verify-local.sh` | 执行默认验证集 | P1 |
| optional real 脚本缺密钥 | 脚本 | 无 token/key | 清晰 skip，不当作默认失败 | P1 |
| 敏感值扫描 | 文档检查 | README/.env.example/log | 不出现真实 token/key | P0 |

## 4. 验证命令

```bash
cd openscout-agent-server && mvn test
cd openscout-agent-server && mvn test -Dtest=AgentEvaluationCommandTest
cd openscout-repo-collector && go test ./...
docker compose -f deploy/docker-compose.yml config
scripts/verify-local.sh
rg -n "ghp_[A-Za-z0-9]{12,}|sk-[A-Za-z0-9]{12,}|DEEPSEEK_API_KEY=.*[A-Za-z0-9]{8,}|GITHUB_TOKEN=.*[A-Za-z0-9]{8,}" README.md .env.example scripts code_copilot/changes/openscout-production-hardening
```

## 5. 执行记录

| 时间 | 命令 | 结果 | 备注 |
|---|---|---|---|
| 2026-06-02 | proposal 阶段未执行测试 | 未执行 | 本轮只创建 proposal，不进入 apply |
