# 任务拆分 - OpenScout MyBatis-Plus 持久化

## 前置条件

- [x] 已读取 `code_copilot/README.md`
- [x] 已读取 `code_copilot/rules/*.md`
- [x] 已读取 `code_copilot/agents/copilot-prompt.md`
- [x] 已读取 `code_copilot/knowledge/index.md`
- [x] 已读取 `项目实施进度.md`
- [x] 已检查工作区状态，当前分支为 `feature/03-mybatis-persistence`
- [x] 已确认当前 change 的 `spec.md`
- [x] 已确认 `spec.md` 中无阻塞待澄清项
- [x] 已确认本地验证命令或替代验证方式

## Task 1: 建立 MyBatis-Plus 持久化基础设施

- **目标**：为 Java 服务增加清晰的数据访问边界，避免 Mapper 逻辑散落到 Agent 编排层。
- **层级/模块**：基础设施 / 配置
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/OpenScoutAgentApplication.java`：必要时增加 Mapper 扫描。
  - `openscout-agent-server/src/main/java/com/openscout/persistence/`：新增 Entity、Mapper、Repository/Service。
  - `openscout-agent-server/src/main/resources/application.yml`：新增持久化开关配置。
  - `deploy/init.sql`：确认既有表结构是否满足本阶段，原则上不做破坏性 schema 变更。
- **依赖**：现有 `mybatis-plus-spring-boot3-starter` 和 `mysql-connector-j`。
- **风险标记**：数据库 / 配置。
- **实现要点**：
  - 新增 `openscout.persistence.enabled`，建议默认 `false`。
  - Mapper 与 Entity 字段必须严格对齐 `deploy/init.sql`。
  - JSON 字段写入统一通过 Jackson 序列化，避免拼接非法 JSON。
- **验收标准**：
  - 默认配置下 `mvn test` 不需要 MySQL 也能通过。
  - 启用持久化时 Mapper 能正常加载。
- **验证命令**：
  ```bash
  cd openscout-agent-server && mvn test
  docker compose -f deploy/docker-compose.yml config
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
  - 验证结果：

## Task 2: 实现 agent_trace 持久化

- **目标**：让 Trace 从当前 JVM 内存扩展到 MySQL，可支持 Java 重启后按 `traceId` 查询。
- **层级/模块**：Java Trace / 基础设施
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/trace/TraceService.java`：接入持久化端口，保留脱敏和内存缓存。
  - `openscout-agent-server/src/main/java/com/openscout/trace/AgentTrace.java`：必要时补充从持久化实体恢复的构造或工厂。
  - `openscout-agent-server/src/main/java/com/openscout/trace/TraceToolCall.java`：作为 tool_calls_json 的序列化结构。
  - `openscout-agent-server/src/main/java/com/openscout/persistence/trace/`：新增 `AgentTraceEntity`、Mapper、Repository。
- **依赖**：Task 1。
- **风险标记**：Trace 脱敏 / JSON / 数据库。
- **实现要点**：
  - `start` 创建 Trace 后可插入 RUNNING 记录；`complete` 和 `fail` 更新最终状态。
  - `find` 优先查内存，未命中再查 MySQL 并恢复为 `AgentTrace`。
  - `tool_calls_json` 保存摘要数组；`score_summary` 保存合法 JSON 对象。
  - 持久化前复用 `sanitize`，禁止保存敏感值和超长大字段。
- **验收标准**：
  - 启用持久化后 `/api/agent/ask` 成功时 `agent_trace` 有 SUCCESS 记录。
  - Collector 不可用时 `agent_trace` 有 FAILED 记录和错误摘要。
  - Java 重启后 `/api/agent/traces/{traceId}` 能从 MySQL 查回 Trace。
- **验证命令**：
  ```bash
  docker compose -f deploy/docker-compose.yml up -d mysql
  cd openscout-agent-server && OPSCOUT_PERSISTENCE_ENABLED=true mvn test -Dtest=*Trace*
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
  - 验证结果：

## Task 3: 实现 repo_info 幂等保存

- **目标**：将 mock 推荐链路中的项目基础元数据保存到 `repo_info`，为后续真实 GitHub API 和报告分析提供基础数据。
- **层级/模块**：Java 应用服务 / 基础设施
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/client/RepoSummary.java`：作为映射来源，不修改 HTTP 契约。
  - `openscout-agent-server/src/main/java/com/openscout/agent/MockAgentService.java`：在推荐成功路径调用持久化服务。
  - `openscout-agent-server/src/main/java/com/openscout/persistence/repo/`：新增 `RepoInfoEntity`、Mapper、Repository。
  - `deploy/init.sql`：使用 `repo_info.full_name` 唯一索引。
- **依赖**：Task 1。
- **风险标记**：数据库唯一约束 / JSON。
- **实现要点**：
  - 以 `full_name` 作为幂等键，重复 ask 不应导致唯一索引异常。
  - `topics_json` 保存 topic 数组 JSON。
  - 对 nullable 字段保持与 DDL 一致，不伪造 owner/repo 之外的数据。
- **验收标准**：
  - 调用 `/api/agent/ask` 后，推荐项目写入 `repo_info`。
  - 重复调用同一 mock 问题不会因为 `full_name` 冲突导致 ask 失败。
- **验证命令**：
  ```bash
  docker compose -f deploy/docker-compose.yml up -d mysql
  cd openscout-agent-server && OPSCOUT_PERSISTENCE_ENABLED=true mvn test -Dtest=*Repo*
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
  - 验证结果：

## Task 4: 实现 repo_analysis 评分结果保存

- **目标**：将规则评分、score breakdown 和 evidence 保存到 `repo_analysis`，保持评分可解释。
- **层级/模块**：Java scoring / 基础设施
- **涉及文件**：
  - `openscout-agent-server/src/main/java/com/openscout/scoring/ProjectScore.java`：作为评分映射来源。
  - `openscout-agent-server/src/main/java/com/openscout/agent/ProjectRecommendation.java`：提供推荐理由和分数。
  - `openscout-agent-server/src/main/java/com/openscout/persistence/analysis/`：新增 `RepoAnalysisEntity`、Mapper、Repository。
- **依赖**：Task 1、Task 3。
- **风险标记**：评分 evidence / JSON / 数据膨胀。
- **实现要点**：
  - `score_breakdown_json` 保存各评分维度。
  - `evidence_json` 保存 evidence 数组。
  - `summary` 和 `learning_value` 只保存摘要，不保存 README 原文或完整模型响应。
- **验收标准**：
  - 调用 `/api/agent/ask` 后，至少为每个推荐项目生成一条 `repo_analysis` 记录或更新最新记录。
  - 数据中可追踪 totalScore 和 evidence。
- **验证命令**：
  ```bash
  docker compose -f deploy/docker-compose.yml up -d mysql
  cd openscout-agent-server && OPSCOUT_PERSISTENCE_ENABLED=true mvn test -Dtest=*Persistence*
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
  - 验证结果：

## Task 5: 更新接口文档、README 和进度记录

- **目标**：让运行方式和结论边界可复现，避免把阶段 4 误解成真实 GitHub API 或模型接入完成。
- **层级/模块**：文档 / code_copilot
- **涉及文件**：
  - `README.md`：新增持久化启用方式和 MySQL 验证命令。
  - `docs/api-contract.md`：说明 Trace 查询启用持久化后的查询来源。
  - `项目实施进度.md`：更新阶段 4 当前状态和 propose 记录。
  - `code_copilot/changes/openscout-mybatis-persistence/`：同步 spec、tasks、test-spec、log。
- **依赖**：Task 1-4。
- **风险标记**：文档准确性。
- **实现要点**：
  - 明确阶段 4 不覆盖真实 GitHub API、Redis adapter、Spring AI DeepSeek。
  - 明确持久化默认是否启用和如何启用。
  - 记录实际命令、状态码、traceId 和数据库验证摘要，不粘贴大响应。
- **验收标准**：
  - 文档中包含可复跑命令。
  - `项目实施进度.md` 与 change 状态一致。
- **验证命令**：
  ```bash
  rg -n "feature/03-mybatis-persistence|openscout-mybatis-persistence|OPSCOUT_PERSISTENCE_ENABLED|agent_trace" README.md docs 项目实施进度.md code_copilot/changes/openscout-mybatis-persistence
  ```
- **完成记录**：
  - 状态：已完成
  - 实际改动文件：
  - 验证结果：

## 变更摘要

> `/apply` 完成后填写。

- **总文件数**：15（8 新增 + 7 修改）
- **新增文件**：`persistence/trace/AgentTraceEntity.java`、`persistence/trace/AgentTraceMapper.java`、`persistence/trace/TracePersistenceService.java`、`persistence/repo/RepoInfoEntity.java`、`persistence/repo/RepoInfoMapper.java`、`persistence/repo/RepoPersistenceService.java`、`persistence/analysis/RepoAnalysisEntity.java`、`persistence/analysis/RepoAnalysisMapper.java`、`persistence/analysis/RepoAnalysisPersistenceService.java`
- **修改文件**：`OpenScoutAgentApplication.java`、`config/OpenScoutProperties.java`、`application.yml`、`trace/TraceService.java`、`trace/AgentTrace.java`、`agent/MockAgentService.java`、`README.md`、`项目实施进度.md`、`spec.md`、`tasks.md`、`log.md`、`test-spec.md`、`trace/TraceServiceTest.java`
- **删除文件**：无
- **Spec-Plan 偏差记录**：无显著偏差。Persist 相关 service 按 spec 建在 persistence 子包中，JSON 序列化统一使用 Spring Boot 内置 ObjectMapper。
- **未完成项**：MySQL 集成验证（curl + SQL 查询）需启动 Docker MySQL 并启用 `OPSCOUT_PERSISTENCE_ENABLED=true`。
- **遗留风险**：持久化写入失败时 catch 异常并 log.warn，不影响 ask 主流程，但可能丢失部分持久化数据。
