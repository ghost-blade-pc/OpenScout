---
alwaysApply: true
---
# 编码规范

项目既有风格优先；当前无源码阶段先采用以下约定，待真实代码生成后按实际风格回填。

## 1. 命名

- Java package 建议使用 `com.openscout`，最终以首次源码创建为准。
- Java 类名使用清晰职责后缀，例如 `Controller`、`Service`、`Client`、`Tool`、`Advisor`、`Repository`。
- Go package 使用短小英文名，避免 `common`、`util` 滥用；按 `api/service/github/worker/cache/limiter` 分层。
- 数据表、Redis key、配置键必须在 Spec 中记录，不能散落硬编码。
- 禁止拼音命名和无意义缩写。

## 2. 分层与依赖

- Java Controller 只处理 HTTP 协议、参数校验、响应转换和调用应用服务。
- Java Agent/Tool 层负责 Tool Calling 编排，不直接拼接外部 API 细节。
- Java scoring/learning/trace 逻辑应独立成服务，便于单测。
- Go api 层只做路由和协议转换，GitHub 调用放在 `internal/github` 或等价 adapter 中。
- Go worker pool、rate limiter、cache 和 retry 需要独立封装，避免写进 handler。

## 3. 异常处理

- 外部 API 失败必须返回可解释错误，区分超时、限流、鉴权失败、not found 和解析失败。
- 批量采集必须允许部分失败，不能因为单个 repo 失败导致整体不可用。
- 模型调用失败时应有可演示兜底，例如返回规则评分和提示模型不可用。
- 禁止空 `catch`、吞异常或只打印日志后返回成功。

## 4. 日志与 Trace

- Trace 记录入参和返回摘要，不记录完整 GitHub Token、模型 Key、完整 README 原文或大对象。
- 工具调用日志应包含 trace id、tool name、耗时、状态、错误摘要。
- Go Collector 应记录请求目标、状态码、耗时、是否命中缓存、是否触发限流或重试。

## 5. 配置与依赖

- GitHub Token、模型 API Key、数据库密码必须来自环境变量或配置文件占位符。
- 新增依赖必须说明用途；MVP 阶段避免引入非必要框架。
- HTTP 超时、重试次数、worker 数、rate limit、缓存 TTL 必须可配置。

## 6. 变更范围

- 第一阶段优先可运行：mock 链路、服务启动、端到端调用、基础 Trace。
- 不在第一阶段做复杂前端、MCP、多租户、权限系统、复杂任务调度。
- 每个 task 应能独立验证，跨模块任务必须写清依赖。
