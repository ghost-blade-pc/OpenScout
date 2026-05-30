# code_copilot - OpenScout Agent SpecAI 工作台

本目录是 `OpenScout Agent` 的 AI 协作工作台，用于管理项目级规则、需求规格、任务拆分、测试计划、执行日志和可复用知识。当前仓库已创建 Java Agent Server、Go Repo Collector、Docker Compose、README 和演示文档；后续变更必须继续把真实代码路径和验证证据回填到 change 文档。

## 项目状态

- 上下文模式：`Initial Agreement`
- 应用名：`OpenScout Agent`
- 技术栈：计划使用 Java 17、Spring Boot 3.x、Spring AI、MyBatis-Plus、Go 1.22+、Gin、MySQL、Redis、Docker Compose。
- 构建工具：计划 Java 使用 Maven；Go 使用 go modules。
- 根包名/命名空间：Java 建议使用 `com.openscout`；Go module 使用 `github.com/LiPeicheng/openscout-repo-collector`。
- 测试框架：Java 使用 JUnit 5/Surefire；Go 使用标准 `testing`。

## 目录说明

```text
code_copilot/
  agents/              AI 角色提示词：协作主流程、Spec 审查、代码质量审查
  rules/               项目长期规则：工程上下文、编码规范、业务约束、安全红线
  knowledge/           可复用业务知识、技术约定和历史踩坑索引
  changes/templates/   Spec、任务拆分、测试计划、执行日志模板
  changes/<name>/      单个需求、缺陷、重构或分析任务的工作目录
```

## 使用方式

涉及业务逻辑、架构边界、数据契约、外部 API、缓存、限流、并发、Agent Trace、模型调用或多文件联动的变更前，先读取：

```text
code_copilot/agents/copilot-prompt.md
code_copilot/rules/*.md
code_copilot/knowledge/index.md
```

每个需求、bug 或重构使用独立 change 目录：

```text
code_copilot/changes/<change-name>/spec.md
code_copilot/changes/<change-name>/tasks.md
code_copilot/changes/<change-name>/test-spec.md
code_copilot/changes/<change-name>/log.md
```

## 基本原则

- 先 Spec，后代码；先 Research，后设计；先任务拆分，后执行。
- 当前阶段没有真实源码时，所有实现细节必须标记为计划或 TODO，不得编造类名、接口、表或配置键。
- 第一版目标是跑通可演示 MVP 闭环，不把项目做成大平台。
- 先 mock，再接真实 GitHub API；先单工具调用，再多工具编排；先规则评分，再 LLM 解释。
- GitHub Token、模型 Key、数据库密码等敏感值只能使用环境变量或占位符。

## 当前项目关键词

- 核心业务域：开源项目检索、项目画像、规则评分、学习路径、Agent Trace。
- 入口与集成点：计划 HTTP API、Spring AI Tool Calling、Go Collector REST API、GitHub REST API。
- 中间件与外部依赖：计划 MySQL、Redis、GitHub API、DeepSeek V4 Pro 模型服务。
- 高风险关键词：外部 API、限流、重试、并发、缓存一致性、敏感配置、模型输出可信度。
