# AGENTS.md

本仓库使用 `code_copilot/` 作为 AI 协作与 Spec 驱动开发的权威入口。

## 语言

生成 AGENTS.md、README.md、贡献者指南、开发规范、`code_copilot/` 文档和 change 文档时，默认使用简体中文；只有用户明确要求英文或其他语言时才切换。

## 工作规则

涉及业务逻辑、架构边界、数据契约、外部 API、缓存、限流、并发、Agent Trace、模型调用或多文件联动的变更前，必须先读取：

- `code_copilot/README.md`
- `code_copilot/rules/project-context.md`
- `code_copilot/rules/coding-style.md`
- `code_copilot/rules/domain-rules.md`
- `code_copilot/rules/security.md`
- 相关 `code_copilot/changes/<change-id>/`

没有用户确认时，不要从 `/propose` 自动进入 `/apply`。
