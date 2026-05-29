# CLAUDE.md

本仓库使用 `code_copilot/` 作为 Claude Code 与其他 AI 工具协作的权威入口。

## 语言

仓库文档、开发规范、Spec、Tasks、Review、测试计划和执行日志默认使用简体中文；用户明确要求其他语言时再切换。

## 工作规则

处理业务逻辑、架构边界、数据契约、外部 API、缓存、限流、并发、Agent Trace、模型调用或多文件联动变更前，必须先读取：

- `code_copilot/README.md`
- `code_copilot/rules/project-context.md`
- `code_copilot/rules/coding-style.md`
- `code_copilot/rules/domain-rules.md`
- `code_copilot/rules/security.md`
- 相关 `code_copilot/changes/<change-id>/`

`code_copilot/changes/<change-id>/spec.md` 和 `tasks.md` 是实现合同。没有用户确认时，不要从 `/propose` 自动进入 `/apply`。
