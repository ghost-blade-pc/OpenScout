---
alwaysApply: true
---
# 工程上下文

本文件描述 OpenScout Agent 的当前事实和架构约定。当前仓库已创建第一阶段 MVP 骨架，应用代码事实以本文件和源码为准；原始方案文档 `OpenScout Agent 项目方案.md` 不纳入 Git。

## 1. 应用概况

- 应用名：`OpenScout Agent`
- 项目名：`OpenScout Agent：AI 开源项目情报分析与学习路径助手`
- 上下文模式：`Initial Agreement`
- 技术栈：Java 17、Spring Boot 3.3.x、Spring AI 1.1.x、MyBatis-Plus、Go 1.22+、Gin、MySQL、Redis、Docker Compose。
- 构建工具：Maven + go modules。
- Java 根包名：`com.openscout`。
- Go module：`github.com/LiPeicheng/openscout-repo-collector`。
- 测试框架：Java 使用 JUnit 5/Surefire；Go 计划使用标准 `testing`。

## 2. 模块与目录职责

计划模块：

- `openscout-agent-server/`：Java Spring Boot 服务，负责 Agent 对话接口、mock Agent 编排、评分规则、Trace 和后续 Spring AI/持久化接入。
- `openscout-repo-collector/`：Go Gin 服务，负责 mock repo、GitHub 搜索、项目画像、README、批量并发采集、进程内 TTL 缓存、限流和超时；Go module 固定为 `github.com/LiPeicheng/openscout-repo-collector`。
- `deploy/`：Docker Compose、MySQL/Redis 本地环境、初始化 SQL。
- `docs/`：架构、接口契约、Demo Case、简历和面试说明。
- `code_copilot/`：SpecAI 工作区，不属于应用运行时代码。

## 3. 架构边界

- Java 服务负责 Agent 编排、业务规则评分、学习路径生成和 Trace 持久化。
- Go 服务负责外部 GitHub 数据采集，不承载 Agent 对话和模型逻辑。
- MySQL 存储项目元数据、分析结果、学习目标、学习任务和 Trace。
- Redis 当前由 Docker Compose 提供本地依赖；Go Collector 第一阶段使用进程内 TTL 缓存，Redis adapter 后续补齐。
- LLM 负责解释、总结和生成自然语言，不负责不可解释的最终评分。

## 4. 入口与集成点

- Java 入站 HTTP：`POST /api/agent/ask`、`GET /api/agent/traces/{traceId}`。
- Go 入站 HTTP：`GET /api/repos/mock`、`GET /api/repos/search`、`GET /api/repos/{owner}/{repo}/profile`、`GET /api/repos/{owner}/{repo}/readme`、`POST /api/repos/batch-profile`。
- Java 出站 HTTP：调用 Go Collector REST API。
- Go 出站 HTTP：调用 GitHub REST API。
- Spring AI：第一阶段使用 mock Agent 跑通链路；`application.yml` 已预留 DeepSeek 兼容 OpenAI 协议的配置，后续再接 Tool Calling。

## 5. 中间件与外部依赖

- MySQL：计划存储 `repo_info`、`repo_analysis`、`learning_goal`、`learning_task`、`agent_trace`。
- Redis：当前只作为本地依赖预留；GitHub 响应、项目画像、README 摘要第一阶段使用 Go 进程内 TTL 缓存。
- GitHub REST API：搜索项目、读取 repo metadata、README、Release、目录结构。
- 模型服务：DeepSeek V4 Pro，配置键为 `DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL`、`DEEPSEEK_MODEL`；当前不强制真实模型可用。
- Docker Compose：`deploy/docker-compose.yml` 启动 MySQL 和 Redis。

## 6. 当前核心业务域

- 开源项目检索：根据用户自然语言目标转换为 GitHub 搜索关键词，返回候选项目。
- 项目画像：采集 stars、forks、language、topics、license、open issues、updated/pushed 时间、README、Release 和目录摘要。
- 规则评分：用活跃度、文档完整度、技术匹配度、学习友好度、简历价值计算可解释分数。
- 学习路径：根据用户目标和项目画像生成 7 天学习任务。
- Agent Trace：记录用户问题、工具调用、入参摘要、返回摘要、评分证据、耗时、异常和最终回答。

## 7. 构建、运行与测试

- Java 构建/测试命令：`cd openscout-agent-server && mvn test`。
- Java 本地运行命令：`cd openscout-agent-server && mvn spring-boot:run`。
- Go 测试命令：`cd openscout-repo-collector && go test ./...`。
- Go 本地运行命令：`cd openscout-repo-collector && go run ./cmd/server`。
- Docker 配置校验命令：`docker compose -f deploy/docker-compose.yml config`。
- 需要的本地依赖：JDK 17、Maven、Go、Docker、Docker Compose、GitHub Token 可选、DeepSeek API Key 可选。
- 已知测试限制：当前机器没有 `go`/`gofmt`，Go 编译、格式化和单测需在安装 Go 后验证；真实 GitHub API 受限流影响，MVP 必须支持 mock 模式。

## 8. 待代码创建后回填

- Java 启动类：`openscout-agent-server/src/main/java/com/openscout/OpenScoutAgentApplication.java`。
- Java 配置：`openscout-agent-server/src/main/resources/application.yml`、`config/OpenScoutProperties.java`。
- Java 核心入口：`controller/AgentController.java`、`agent/MockAgentService.java`、`client/CollectorClient.java`、`scoring/ProjectScoreService.java`、`trace/TraceService.java`。
- Go 启动入口：`openscout-repo-collector/cmd/server/main.go`。
- Go router/service/client/worker：`internal/api/router.go`、`internal/service/repo_service.go`、`internal/github/client.go`、`internal/worker/pool.go`。
- 数据库 DDL：`deploy/init.sql`。
- 典型测试类：`ProjectScoreServiceTest`、`TraceServiceTest`；Go 侧有 `repo_service_test.go`、`memory_test.go`，但当前机器未安装 Go，尚未执行。
