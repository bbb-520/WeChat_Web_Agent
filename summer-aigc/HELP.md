# summer-aigc — 帮助文档

> 模块定位：**Agent 核心引擎**。定义传输端口接口、实现双循环 `AgentLoop` 编排器、所有 `@Tool` 工具、RAG 检索、实体/Mapper/Service 与配置。是整个项目的大脑，不关心消息来自微信还是飞书。

## 1. 职责

- 定义端口接口：`BotInboundPort`（入站）、`MessageSender`（出站）、`BotMessage`（归一化消息）
- 双循环 Agent 编排（`loop/`）
- 11 个 `@Tool` 能力实现（`tool/`）
- 三级记忆与 RAG（`service/` + `rag/`）
- 持久化（MyBatis-Plus 实体/Mapper/Service）
- 配置与全局异常（`config/`）
- Flyway 数据库迁移脚本

## 2. 依赖

- **依赖**：`summer-common`（仅此一个内部模块）
- **被依赖**：`summer-bot`、`summer-bootstrap`
- **外部**：Spring Boot Web、Spring AI Alibaba DashScope、dashscope-sdk、MyBatis-Plus、Tika、Micrometer、Gson、MySQL(运行时)、Lombok(provided)

## 3. 包结构

```
summer-aigc/
└── log/summer/aigc/
    ├── port/                  # 传输无关接口
    │   ├── BotInboundPort.java    # void onMessage(BotMessage)
    │   ├── BotMessage.java        # record(userId,text,imageBytes,fileBytes,fileName,context)
    │   └── MessageSender.java     # sendText / sendImage / sendFile
    ├── loop/                  # 双循环核心
    │   ├── AgentLoop.java         # 编排器（while 迭代 + 安全阀 + ChatMemory）
    │   ├── ThinkResult.java       # 解析 LLM 响应：finalAnswer + toolCalls
    │   ├── ActResult.java         # 工具结果：success/data/errorMessage
    │   └── ArgumentResolver.java   # ${message.image/file/fileName} 占位符替换
    ├── tool/                  # ToolRegistry + 11 个 @Tool
    │   ├── ToolRegistry.java       # 扫描 @Tool Bean → ToolCallback 列表 + execute()
    │   ├── BotMetrics.java         # 运行时指标（消息/错误计数、uptime）
    │   ├── TextTool.java           # 文本工具
    │   ├── Stats.java              # BotMetrics 统计载体
    │   ├── weather/WeatherTool.java
    │   ├── image/{ImageGenTool,ImageRecognitionTool,ImageGenService,ImageContextManager,ImageCacheManager}.java
    │   ├── voice/{TtsTool,TTSEngine,TimbreSession,AudioTranscoder}.java
    │   ├── file/{FileTool,FileRecognitionService}.java
    │   ├── idiom/{IdiomGameTool,IdiomDictionary,GameSession}.java
    │   ├── navigation/NavigationTool.java
    │   ├── reminder/ReminderTool.java
    │   └── memory/MemoryStatusTool.java
    ├── rag/                   # RAG 检索
    │   ├── DocumentChunkingService / EmbeddingService /
    │   └── VectorStoreService / RAGRetrievalService / RAGContextAugmenter
    ├── entity/                # 10 个 MyBatis-Plus 实体
    ├── mapper/                # 9 个 Mapper 接口
    ├── service/               # ChatService、ChatPersistenceService + 接口/impl
    ├── config/                # AiConfig、BotProperties、VoiceProperties、
    │                         #   HttpClientConfig、GlobalExceptionHandler
    └── resources/db/migration/V1__initial_schema.sql   # Flyway 基线
```

## 4. 关键机制

### 4.1 AgentLoop（双循环）

`AgentLoop.orchestrate(BotMessage, MessageSender)`：
1. 把用户消息加入 per-user `ChatMemory`；
2. `while (round<10 && !timeout 120s)`：
   - **THINK**：`ChatService.chatWithTools(messages, ToolRegistry.getCallbacks())` → `ThinkResult`
   - 有最终答案且无工具调用 → 回复并退出
   - 有工具调用 → **ACT**：`ArgumentResolver.resolve()` 后 `ToolRegistry.execute()`，结果以 `ToolResponseMessage` 回灌记忆，`continue`
3. `finally`：清空该用户 `ChatMemory`。

安全阀：达到 10 轮或 120s 超时，回复「我暂时无法完成这个任务，请稍后再试。」

### 4.2 ToolRegistry + @Tool

- `@PostConstruct` 扫描容器中所有带 `@Tool` 方法的 Bean，用 `MethodToolCallbackProvider` 生成 `ToolCallback` 列表，并建 `name → callback` 索引。
- `AgentLoop` 调 `execute(name, args)`：参数序列化 JSON → 调用 → 结果反序列化为 `ActResult`（非 `ActResult` 则包裹为 `success`）。
- 所有工具方法**必须**返回 `ActResult`，失败时通过 `errorMessage` 让模型自我纠正。

### 4.3 工具清单（name → 类）

| name | 类 | 能力 |
|------|----|------|
| `weather_query` | WeatherTool | 城市今/明日天气 |
| `image_generate` | ImageGenTool | 文生图 |
| `image_recognize` | ImageRecognitionTool | 图片识别 |
| `image_edit` | ImageRecognitionTool | 图片编辑 |
| `tts_synthesize` | TtsTool | 文字转语音 |
| `voice_switch` | TtsTool | 切换音色 |
| `file_analyze` | FileTool | 文件分析 |
| `idiom_game` | IdiomGameTool | 成语接龙 |
| `reminder_set` | ReminderTool | 定时提醒 |
| `navigation` | NavigationTool | 路线/路况 |
| `memory_status` | MemoryStatusTool | JVM 监控 |

### 4.4 三级记忆与 RAG

`ChatService` 组装增强系统提示（DB 历史 + 长期记忆），提供 `chatWithTools` / `chat` / `chatWithRAG` 三种调用。RAG 链路：`DocumentChunkingService` → `EmbeddingService` → `VectorStoreService`（余弦 TopK）→ `RAGRetrievalService` → `RAGContextAugmenter`。

## 5. 构建

```bash
mvn -pl summer-common,summer-aigc install   # 先装 common
mvn -pl summer-aigc compile                  # 仅编译
```

## 6. 扩展指引：新增一个 @Tool

1. 在 `tool/<domain>/` 下新建 `@Component` 类；
2. 编写方法并标注：
   ```java
   @Tool(name = "my_tool", description = "一句话描述何时调用")
   public ActResult myTool(@ToolParam(description="参数说明") String arg) {
       try {
           // ... 业务逻辑
           return ActResult.success(resultText);
       } catch (Exception e) {
           return ActResult.failure("失败原因：" + e.getMessage());
       }
   }
   ```
3. 若需用户上传的图片/文件字节，参数用占位符字符串 `"${message.image}"` / `"${message.file}"` / `"${message.fileName}"`，由 `ArgumentResolver` 在执行前替换；
4. 需要给用户发图片/语音时，注入 `MessageSender` 直接发送，并返回 `ActResult.success("已发送")`（不要把原始字节放进 LLM 上下文）；
5. 无需改 `ToolRegistry` —— 它自动扫描；重启后 `/status` 旁的逻辑会统计到新工具。

## 7. 注意事项

- `GlobalExceptionHandler` 在本模块 `config/`（依赖 `MessageSender` 端口），**不是** `summer-common`。
- Flyway 基线 `V1__initial_schema.sql` 取代旧的 `schema.sql`；`application.yml` 中 `spring.sql.init.mode` 已移除。
- `AgentLoop` 每轮结束清空 `ChatMemory`；多轮会话的持久化历史依赖 `message` 表，由 `ChatPersistenceService` 写入。

## 8. 相关文档

- [上层 README](../README.md)
- [summer-bot/HELP.md](../summer-bot/HELP.md)
- 设计： [specs](../docs/superpowers/specs/2026-07-28-multi-module-agent-refactor-design.md)
