# 知识索引

本文件只做路由索引，不承载大段知识。新增知识必须来自真实代码、配置、测试、运行结果或已确认方案。

## 当前事实来源

- `OpenScout Agent 项目方案.md`：项目定位、技术栈、MVP 功能、Go Collector 接口草案、Java Tool 设计、评分规则、数据表草案、目录结构、一周开发计划。
- `code_copilot/changes/openscout-mvp-foundation/`：第一阶段 MVP 骨架与核心推荐闭环，已在阶段 3 mock e2e 验证后归档为 `done`。
- `code_copilot/changes/openscout-mock-e2e-demo/`：阶段 3 Java 调 Go mock 端到端验证记录，已归档为 `done`；包含 Go `/api/repos/mock`、Java `/api/agent/ask`、Trace 查询、Collector 不可用 502 场景、Spring AI 模型 provider 默认关闭配置。

## 已沉淀知识

- 阶段 3 mock e2e 的已验证闭环：Java `/api/agent/ask` 调 Go `/api/repos/mock`，返回 3 个 mock 推荐项目、规则评分和 `traceId`；随后 Java `/api/agent/traces/{traceId}` 可查到 `repo_search_mock` 工具调用摘要。
- 阶段 3 失败分支已验证：停止 Go Collector 后调用 Java `/api/agent/ask` 返回 HTTP 502，并保留失败 `traceId` 和 `Connection refused` 错误摘要。
- Spring AI 1.1.6 mock 模式约定：默认通过 `SPRING_AI_MODEL_* = none` 关闭模型 provider，避免无 Key 时 OpenAI 自动配置阻塞 Java 启动；真实 DeepSeek chat 接入时再显式设置 `SPRING_AI_MODEL_CHAT=openai` 和 `DEEPSEEK_API_KEY`。
- 当前阶段结论边界：结果只证明本地 mock HTTP 链路、规则评分和内存 Trace 可演示；不证明真实 GitHub API、真实模型调用、Redis adapter 或 MyBatis Trace 持久化完成。

## 待沉淀主题

- TODO: Spring AI ChatClient、Tool Calling、Advisor、结构化输出与 DeepSeek V4 Pro 的实际版本和项目用法。
- TODO: GitHub REST API 限流、ETag、README、Release、目录树接口的实际封装策略。
- TODO: Go Collector worker pool、rate limiter、retry、cache 的实现约定。
- TODO: OpenScout 项目评分公式和 evidence JSON 结构。
- TODO: Agent Trace 字段、脱敏策略和查询方式。

## 索引规则

- 有源码后，知识条目必须附真实路径、类名、方法名、配置键或测试命令。
- 不确定内容写 TODO，不把推测写成事实。
- 已完成 change 归档时，再把可复用经验追加到本索引。
