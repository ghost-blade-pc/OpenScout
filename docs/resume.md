# 简历与面试材料

## 项目描述

OpenScout Agent 是一个基于 Java Spring Boot、Spring AI 规划能力和 Go 并发采集服务构建的开源项目情报分析助手。用户输入学习目标后，系统检索候选 GitHub 项目，结合项目元数据、README 和规则评分生成推荐理由，并记录 Agent Trace 用于复盘。

## 当前 MVP 亮点

- 使用 Java 17 + Spring Boot 构建 Agent Server，第一阶段通过 mock Agent 跑通端到端链路，后续可替换为 Spring AI Tool Calling。
- 使用 Go + Gin 构建 Repo Collector，提供 mock、search、profile、readme、batch-profile 接口，并预留 GitHub API 真实模式。
- 规则评分覆盖活跃度、文档完整度、技术匹配度、学习友好度和简历价值，评分结果带 evidence，避免完全依赖 LLM 主观判断。
- Agent Trace 记录用户问题、工具调用摘要、评分结果、最终回答、耗时和异常，强调 Agent 工程化可观测性。
- 通过 Docker Compose 提供 MySQL 和 Redis 本地依赖，SQL 预留项目画像、分析结果、学习任务和 Trace 表。

## 面试讲解重点

- 为什么 Java 负责编排，Go 负责采集：Java 生态适合 Spring AI 和业务编排，Go 适合并发 HTTP 采集、限流和 worker pool。
- 为什么第一阶段 mock Agent：先保证端到端可运行，再接真实模型，降低模型和网络依赖对演示的影响。
- 为什么规则评分优先：规则可解释、可测试、可复盘，LLM 负责自然语言总结。
- 如何处理 GitHub API 限流：Token 可选配置、请求超时、rate limiter、有限重试、mock fallback 和缓存。
