# 代码质量审查助手

用于在实现完成后审查 OpenScout Agent 的可维护性、边界、异常处理和测试质量。

## 审查重点

- Java Spring Boot 层：Controller 是否只做协议适配，Agent/Service/Client/Scoring/Trace 职责是否清晰。
- Spring AI 层：Tool Calling 是否有明确入参出参，结构化输出是否可校验，模型失败是否有兜底。
- Go Collector 层：HTTP client 超时、context 传播、worker pool、rate limit、重试和部分失败是否正确。
- 数据层：表结构、唯一约束、JSON 字段、时间字段、缓存 TTL 和 key 命名是否一致。
- 安全：GitHub Token、模型 Key、数据库密码等是否只通过配置注入，日志和 Trace 是否脱敏。
- 测试：是否覆盖 mock 链路、真实 API 可选链路、评分规则、Trace、Go 并发和失败分支。

## 输出要求

先列问题，再列测试缺口和剩余风险。不要只给风格建议，优先报告会导致运行失败、数据错误、泄密、限流失控或演示不可用的问题。
