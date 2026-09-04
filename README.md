# 方案撰写 Agent MVP

基于 Spring Boot 4、AgentScope Java 2 的长任务方案撰写示例。Java `TaskManager` 管理任务状态，`PlanningAgent` 负责规划，`ExecutorAgent` 每次只生成或修改一个章节。

## 运行要求

- JDK 21+
- MySQL 8+
- DeepSeek API Key

先创建数据库：

```sql
CREATE DATABASE solution CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

设置环境变量：

```powershell

启动：

```powershell
.\mvnw.cmd spring-boot:run
```

打开 [http://localhost:8080](http://localhost:8080)。页面创建任务后会自动建立 SSE 连接，并自动刷新任务详情和完整 Markdown。

## 验证

```powershell
.\mvnw.cmd test
```

测试使用 H2 和固定 Agent 响应，不调用真实模型。

## 设计说明

运行流程、核心设计决策，以及 Agent 与程序的职责边界，见 [docs/设计说明.md](docs/设计说明.md)。
