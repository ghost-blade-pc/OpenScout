---
alwaysApply: true
---
# 安全红线

## 1. 配置安全

- 禁止在代码、测试、脚本、文档示例中硬编码 GitHub Token、模型 API Key、数据库密码、Redis 密码或生产连接串。
- 配置示例只能使用 `${GITHUB_TOKEN}`、`${OPENAI_API_KEY}`、`${DB_PASSWORD}` 等占位符。
- 日志、异常、Trace、测试快照不得包含完整 token、key、cookie、authorization header 或生产数据。

## 2. 接口安全

- MVP 可不实现完整登录鉴权，但公开 API 必须避免暴露内部异常堆栈和敏感配置。
- 管理类、调试类、Trace 查询类接口不得默认公网暴露；如需提供，必须在 Spec 中说明访问控制。
- 用户输入的 repo full name、keyword、limit 等参数必须校验格式和范围。

## 3. 外部调用安全

- GitHub API 调用必须设置 User-Agent、timeout 和错误处理。
- 不可信 README、Release 文本进入 LLM prompt 前应控制长度，避免超大输入和 prompt 注入风险。
- 对外请求失败不得把内部异常、堆栈或敏感参数直接透出给最终用户。

## 4. 数据与 Trace 安全

- Trace 保存工具入参和返回摘要，不保存完整 README、完整模型 prompt、完整模型响应中的敏感字段。
- 数据库和 Redis 中存储的第三方数据应有来源和更新时间字段，便于过期判断。
- 批量采集、缓存刷新和补偿任务必须有范围控制，避免误刷大量 GitHub API 请求。
