# 知识索引

本文件只做路由索引，不承载大段知识。新增知识必须来自真实代码、配置、测试、运行结果或已确认方案。

## 当前事实来源

- `OpenScout Agent 项目方案.md`：项目定位、技术栈、MVP 功能、Go Collector 接口草案、Java Tool 设计、评分规则、数据表草案、目录结构、一周开发计划。
- `code_copilot/changes/openscout-mvp-foundation/`：第一阶段 MVP 骨架与核心推荐闭环，已在阶段 3 mock e2e 验证后归档为 `done`。
- `code_copilot/changes/openscout-mock-e2e-demo/`：阶段 3 Java 调 Go mock 端到端验证记录，已归档为 `done`；包含 Go `/api/repos/mock`、Java `/api/agent/ask`、Trace 查询、Collector 不可用 502 场景、Spring AI 模型 provider 默认关闭配置。
- `code_copilot/changes/openscout-mybatis-persistence/`：阶段 4 MyBatis-Plus 持久化，已归档为 `done`；包含 agent_trace/repo_info/repo_analysis Entity/Mapper/Service、持久化开关、内存+MySQL 双查、幂等 upsert、脱敏和 MySQL 集成验证。

## 已沉淀知识

- 阶段 3 mock e2e 的已验证闭环：Java `/api/agent/ask` 调 Go `/api/repos/mock`，返回 3 个 mock 推荐项目、规则评分和 `traceId`；随后 Java `/api/agent/traces/{traceId}` 可查到 `repo_search_mock` 工具调用摘要。
- 阶段 3 失败分支已验证：停止 Go Collector 后调用 Java `/api/agent/ask` 返回 HTTP 502，并保留失败 `traceId` 和 `Connection refused` 错误摘要。
- Spring AI 1.1.6 mock 模式约定：默认通过 `SPRING_AI_MODEL_* = none` 关闭模型 provider，避免无 Key 时 OpenAI 自动配置阻塞 Java 启动；真实 DeepSeek chat 接入时再显式设置 `SPRING_AI_MODEL_CHAT=openai` 和 `DEEPSEEK_API_KEY`。
- 当前阶段结论边界：结果只证明本地 mock HTTP 链路、规则评分和内存 Trace 可演示；不证明真实 GitHub API、真实模型调用、Redis adapter 或 MyBatis Trace 持久化完成。

## 阶段 4 持久化知识（2026-05-30 集成验证通过）

### MyBatis-Plus 持久化架构

- **包结构约定**：Entity + Mapper 按表分包子包 `persistence/{trace,repo,analysis}/`，Service 类桥接 domain 对象与 entity。
- **Mapper 扫描**：`@MapperScan("com.openscout.persistence")` 在 `OpenScoutAgentApplication` 上，Mapper 接口继承 `BaseMapper<T>` 并用 `@Mapper` 标注。
- **JSON 序列化**：统一使用 Spring Boot 内置 `ObjectMapper`，不手写拼接 JSON。`topics_json` → `List<String>`，`score_breakdown_json` → `Map<String,Integer>`，`evidence_json` → `List<String>`，`tool_calls_json` → `List<TraceToolCall>`。
- **score_summary JSON 包装**：AgentTrace 中的 `scoreSummary` 是纯文本字符串（如 `"repo1=85, repo2=70"`），写入 MySQL JSON 列时必须包装为 `{"summary":"..."}` 对象，读取时解包还原。

### 持久化开关模式

- 配置键：`OPSCOUT_PERSISTENCE_ENABLED` (env) → `openscout.persistence.enabled` (YAML)，默认 `false`。
- `OpenScoutProperties.Persistence` 内部类持有 `boolean enabled = false`。
- 调用方（TraceService、MockAgentService）在调用持久化方法前检查 `properties.getPersistence().isEnabled()`。
- 持久化写入失败时 catch 异常并 `log.warn`，不影响 ask 主流程。`persistIfEnabled(Runnable)` 封装此模式，位于 `TraceService` 中。
- **目的**：保证未启动 MySQL 时阶段 3 mock 演示不退化。

### Trace 内存+MySQL 双查

- `TraceService.find(traceId)` → 先查 `ConcurrentHashMap`，命中直接返回。
- 内存未命中且持久化启用 → 查 MySQL（`AgentTraceMapper.selectOne`），命中后回填内存缓存。
- `start()` → 创建 AgentTrace 放入内存 → `persistIfEnabled(() -> insertTrace)`。
- `complete()`/`fail()` → 更新内存字段 → `persistIfEnabled(() -> updateTrace)`。
- `recordToolCall()` 只操作内存；tool_calls_json 在 `complete()`/`fail()` 时一次性落库。
- `AgentTrace` 新增 `id` 字段（Long），用于 MyBatis-Plus `updateById`。

### repo_info 幂等 upsert

- 以 `full_name` 为业务键，`deploy/init.sql` 已有 `UNIQUE KEY uk_repo_full_name`。
- 实现：先 `selectOne` 按 `full_name` 查，存在则 `updateById`，不存在则 `insert`。不使用 MySQL `ON DUPLICATE KEY UPDATE`（避免 MyBatis-Plus 与原生 SQL 混用）。
- 重复 mock ask 不会因唯一索引冲突导致 500。

### Trace 脱敏规则

- 正则：`(?i)(token|api[-_]?key|authorization|password|secret)\s*[:=]\s*[^\s,;]+` → `$1=<redacted>`。
- 长度截断：`openscout.trace.max-summary-length`（默认 800），超出加 `...<truncated>`。
- 脱敏在 `sanitize()` 中统一执行，所有写入 DB 和 API 返回的字段均经过脱敏。

### MySQL 集成验证命令集

```bash
# 启动 MySQL 并初始化
docker compose -f deploy/docker-compose.yml up -d mysql
docker exec -i openscout-mysql mysql -uopenscout -popenscout openscout < deploy/init.sql

# 启动 Java（持久化启用）
cd openscout-agent-server && OPSCOUT_PERSISTENCE_ENABLED=true mvn spring-boot:run

# 验证查询
docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select trace_id,status,latency_ms from agent_trace order by id desc limit 5;"
docker exec openscout-mysql mysql -uopenscout -popenscout openscout \
  -e "select full_name,total_score from repo_analysis order by id desc limit 5;"
```

### 已知约束

- Trace 只保存摘要和脱敏字段，不保存完整 README、完整 prompt、模型 Key 或 GitHub Token。
- `/api/agent/traces/{traceId}` 仅用于本地排障，未做鉴权，不应公网暴露。
- 持久化默认关闭时 `mvn test` 无需 MySQL。
- `repo_analysis` 每次 ask 追加新记录，不覆盖历史分析。

## 待沉淀主题

- TODO: Spring AI ChatClient、Tool Calling、Advisor、结构化输出与 DeepSeek V4 Pro 的实际版本和项目用法。
- TODO: GitHub REST API 限流、ETag、README、Release、目录树接口的实际封装策略。
- TODO: Go Collector worker pool、rate limiter、retry、cache 的实现约定。
- TODO: OpenScout 项目评分公式和 evidence JSON 结构。
- [x] Agent Trace 字段、脱敏策略和查询方式 → 已沉淀到阶段 4 知识。

## 索引规则

- 有源码后，知识条目必须附真实路径、类名、方法名、配置键或测试命令。
- 不确定内容写 TODO，不把推测写成事实。
- 已完成 change 归档时，再把可复用经验追加到本索引。
