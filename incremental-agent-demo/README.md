# Incremental Agent Demo

这个 Demo 使用 Spring WebFlux + SSE，包含写作 Agent 和资料 Agent 两个逻辑角色。用户上传一篇已经写好的完整参考文档和待写资料后，前端立即启动第一轮。写作 Agent 每章使用一个全新的 Session，自主决定下一章和需要读取的上下文；需要资料时调用资料 Agent重新定位相关文档和字符位置，再由写作 Agent分段读取具体原文。每轮只保存一章，然后等待用户审核或继续对话。

## 核心约束

- 一个任务保留基础 `sessionId`，每轮实际创建 `writing-{taskId}-chapter-{序号}-{随机ID}`，不把所有章节堆进同一个模型上下文。
- 资料 Agent 使用 AgentScope Harness 官方 `SubagentDeclaration + agent_spawn`，工作区模式为 `ISOLATED`，并显式设置 `persistSession=false`。每章先把相关资料问题合并到一次全新的子 Agent 查询中，由它使用多组关键词逐项定位；只有定位不足、资料冲突或出现新的独立问题时才追加新的子 Agent，不跨章节复用 Session，也不使用 `agent_send` 续接。资料 Agent只负责搜索定位，写作 Agent根据其返回的位置读取 `sources/` 原文。
- Java 只提供目录、关键词搜索、分段读取和保存工具。写作 Agent 自己决定本章、检索问题和要读取的历史章节。
- 写作 Agent 的逐章工作流程定义在 AgentScope 工作区 skill：`.incremental-agent/agent-workspace/skills/incremental-writing/SKILL.md`。
- 主 Agent 系统提示词由 `demo.writing-system-prompt` 配置提供。当前本地 YAML 使用多行文本；部署到 Nacos 后可用同名配置管理具体提示词。
- 暂不使用向量数据库；原始资料通过关键词搜索和字符 offset 分页读取，单次最多返回 12000 字符。
- `document-summary.md` 是首次生成的资料导航概览，不能作为正文事实证据；每章保存前必须重新让资料 Agent定位资料，并由写作 Agent读取相关 `sources/` 原文。
- 临时计划保存为 `working-plan.json`，可以随用户意见和写作进展覆盖更新，不是预生成的固定任务队列。
- 两层跨章记忆分别是固定大小的 `memory/document-state.md` 和按章保存的 `memory/chapters/*.md`。下一章只读取自己判断相关的章节记忆。
- 完整参考文档只用于分析写法，不作为新正文的事实来源。
- 参考文档、资料和准确正文保存在每个任务独立的 workspace 中。
- 每轮最多成功调用一次 `save_content`，并且只暂存一章正文。正文、章节记忆、滚动状态和临时计划组成持久化 `ChapterStage`；只有完整 Stage 经幂等 `commitChapter` 发布后，章节才会出现在 `outputs/index.json`。
- 没有 `PLAN_NEXT`、`EXECUTE`、`DONE` 等业务动作枚举。

## 运行

前端使用 Vue 3 + TypeScript + Vite。首次运行或修改 `frontend/` 中的页面代码后，先在 Demo 目录执行：

```powershell
cd frontend
npm install
npm run build
cd ..
```

`npm run build` 会把产物写入 Spring Boot 的 `src/main/resources/static/`，服务端仍由 Maven 启动。随后在父目录执行：

```powershell
$env:DASHSCOPE_API_KEY = "你的 API Key"
.\mvnw.cmd -f incremental-agent-demo\pom.xml spring-boot:run
```

服务端口为 `9091`。

## 创建任务

前端支持上传一个完整参考文档和多个目标背景资料。支持 `docx`、`pdf`、`md`、`txt`，每个文件最大 10MB；扫描版 PDF 需要先完成 OCR。

```powershell
curl.exe -X POST http://localhost:9091/api/tasks `
  -F "userId=demo-user" `
  -F "referenceDocument=@C:\docs\完整参考文档.docx" `
  -F "sourceFiles=@C:\docs\项目背景.pdf" `
  -F "sourceFiles=@C:\docs\约束条件.md"
```

响应中的 `task.id` 是后续调用使用的任务 ID。

原有的 `application/json` 创建接口继续保留，适合已经完成文本提取的调用方。

## 对话任务列表

```http
GET http://localhost:9091/api/tasks
```

前端左侧显示所有历史写作任务。页面不会自动恢复并运行上一次任务；用户点击任务后才切换到对应的 AgentScope Session，也可以随时点击“新建”上传另一组文档。选中任务后，通过底部对话框发送任意消息；没有背景资料的旧任务可以查看，但不能继续运行。

## 运行一轮

```http
POST http://localhost:9091/api/tasks/{taskId}/rounds
```

请求体是当前用户消息：

```json
{"message": "上一章内容可以，下一章重点说明实施进度"}
```

创建任务后，页面立即发起第一轮。写作 Agent 会在本章独立 Session 中读取当前计划和两层记忆，自主调用资料 Agent定位原文，再根据返回位置读取具体资料并只保存一章。每章保存后，前端提供 2 分钟审核时间：用户发送消息会取消自动继续，并将消息作为下一章要求；2 分钟内没有消息，页面会自动发送“继续”。如果本轮没有保存新章节，倒计时不会启动。
接口使用 `text/event-stream` 返回 AgentScope 官方 AG-UI 事件：`RUN_STARTED`、`TEXT_MESSAGE_*`、`REASONING_MESSAGE_*`、`TOOL_CALL_*`、`TOOL_CALL_RESULT` 与终态 `RUN_FINISHED` / `RUN_ERROR`。业务层保留 `CUSTOM chapter.saved`，表示 `ChapterStage` 已通过幂等提交发布了正文、章节记忆、滚动状态和临时计划；事件包含 `stageId`，并在 `RUN_FINISHED` 前发出。

完整 Stage 不会由 `RoundRunner` 自动提交。写作 Agent 必须调用 `commit_chapter(stage_id)`，该工具配置为 `ASK`：前端会先收到携带 interrupt 的 `RUN_FINISHED`，Stage 进入 `AWAITING_REVIEW`，输出目录保持不变。审核通过时，调用当前业务入口（不是 starter 自动暴露的 `/agui/run`）：

```http
POST /api/tasks/{taskId}/rounds/{runId}/resume
Content-Type: application/json

{"decisions":[{"toolCallId":"...","approved":true}]}
```

恢复请求不携带用户消息；Runtime 使用保存的 thread、pending tool call 与 AG-UI resume 从原暂停点继续。当前仅开放通过审核；拒绝审核需先实现业务 review feedback 的保存和读取，再以 `approved=false` 恢复。

前端直接消费 AG-UI 事件，并把文本、推理、工具调用与工具结果整理为可折叠的“模型工作过程”。资料子 Agent 默认以 `CUSTOM subagent.*` 事件出现，避免污染父 Run 的文本与生命周期。

SSE 首帧发出后的运行异常会转换为 `RUN_ERROR`，而不是再交给 HTTP 异常处理器修改已经提交的响应；`RUN_ERROR` 与 `RUN_FINISHED` 不会同时发送。

```powershell
curl.exe -N -X POST http://localhost:9091/api/tasks/{taskId}/rounds `
  -H "Content-Type: application/json" `
  -d '{"message":"继续"}'
```

## 查看结果

```http
GET http://localhost:9091/api/tasks/{taskId}
GET http://localhost:9091/api/tasks/{taskId}/content
```

## 测试

```powershell
.\mvnw.cmd -f incremental-agent-demo\pom.xml test
```

测试使用 Stub Agent，不调用真实模型，包含真实 DOCX、PDF 生成与 multipart 上传解析，并验证原文搜索与分段读取、每章 Session 隔离、临时计划、滚动文档状态、独立章节记忆，以及连续三轮分别为“保存第一章、保存第二章、不再保存”。
