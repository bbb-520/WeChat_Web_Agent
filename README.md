# 微信 iLink 多模态 AI 机器人 — 项目文档

> **更新日期:** 2026-07-28 | **作者:** bbb | **分支:** main | **架构:** 多模块 + 双循环 Agent（ReAct）
> **模块:** `summer-common` · `summer-aigc` · `summer-bot` · `summer-bootstrap`

---

## 一、项目概述

### 1.1 简介

基于 **Spring Boot 3.2 + Java 21** 的微信 iLink 多模态 AI 机器人。项目已重构为 **4 个 Maven 模块**，并通过 `wechat-ilink-sdk` 接入微信客户端。

重构后的核心是一个 **双循环 ReAct Agent 引擎**（`AgentLoop`）：

- **THINK**：调用大模型（qwen-plus）+ 全部工具清单（`@Tool` 函数），由模型自主决策调用哪些工具；
- **ACT**：执行工具并把结果回灌给模型，进入下一轮思考；
- 循环最多 **10 轮 / 120 秒** 安全阀后终止。

能力覆盖：**AI 多轮对话（三级记忆）、文生图 / 图片识别 / 图片编辑、语音合成（12 音色）、文件识别（Tika+AI+RAG）、天气查询（高德）、成语接龙、路线导航 / 路况、内存监控、定时提醒、全局异常拦截** 等。

> 历史说明：此前使用 `IntentClassifier`（qwen-turbo）+ `AgentRouter` 的「一次性派发」模型，已于 2026-07-28 重构为上述双循环 + 工具调用模型，原 `IntentClassifier` / `AgentRouter` / `CommandAgent` 等类已移除，相关设计见 `docs/superpowers/`。

### 1.2 技术栈

| 技术 | 说明 |
|------|------|
| Java 21 | 虚拟线程、Record、Switch 表达式、Pattern Matching |
| Spring Boot 3.2.10 | IoC/DI、Actuator、@ConfigurationProperties |
| Spring AI Alibaba DashScope | ChatClient、`@Tool` 函数调用、MessageWindowChatMemory |
| MyBatis-Plus 3.5.7 | ORM、LambdaQueryWrapper、BaseMapper |
| Flyway | 数据库版本化迁移（取代原 `schema.sql` always-init） |
| MySQL 8.x | 9+ 张表、utf8mb4 |
| AI 模型 | qwen-plus（对话/文档）、qwen-vl-plus（多模态）、wan2.5-t2i-preview（文生图）、cosyvoice-v1（TTS）、paraformer-v2（ASR） |
| 高德开放平台 | 天气 API + 路线规划 API + 地理编码 API + 实时路况 API |
| wechat-ilink-sdk 2.3.3 | 微信消息收发、图片/文件 CDN 下载 |
| Apache Tika 2.9.4 | MIME 检测 + 文本提取 |
| Gson | JSON 序列化/反序列化 |
| OkHttp 4.x | HTTP 连接池 |
| Micrometer + Actuator | /actuator/metrics, /actuator/health |

### 1.3 多模块架构

依赖方向单向、无环：`summer-bootstrap → summer-bot → summer-aigc → summer-common`。

```
┌──────────────────────────────────────────────────────────────────┐
│  summer-bootstrap  (启动 + 装配)                                    │
│  SummerApplication · application.yml · config/{ai,bot,security}.yml │
└───────────────────────────────┬──────────────────────────────────┘
                                 │ 依赖
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│  summer-bot  (传输适配器)                                          │
│  ILinkBotAdapter · SlashInterceptor · RetrySender                  │
└───────────────────────────────┬──────────────────────────────────┘
                                 │ 依赖
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│  summer-aigc  (Agent 核心引擎)                                      │
│  AgentLoop · ToolRegistry · @Tool×11 · RAG · entity/mapper/service │
│  port: BotInboundPort / BotMessage / MessageSender                 │
└───────────────────────────────┬──────────────────────────────────┘
                                 │ 依赖
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│  summer-common  (零依赖工具包)                                      │
│  exception/ · enums/ · constant/ · prompts/                        │
└──────────────────────────────────────────────────────────────────┘
```

### 1.4 双循环 Agent 引擎（核心流程）

```
用户消息到达 ILinkBotAdapter.onMessage(BotMessage)
      │
      ▼
SlashInterceptor.intercept(msg, sender)        ← /help /status /cancel 在此拦截
  ├─ 命中 → 直接回复，不进入 AgentLoop
  └─ 未命中 → AgentLoop.orchestrate(msg, sender)
                  │
                  ▼
        while (round < 10 && !timeout 120s):
          ┌─────────────────────────────────────────────┐
          │ THINK:                                       │
          │   ChatService.chatWithTools(messages,        │
          │       ToolRegistry.getCallbacks())           │
          │     → ThinkResult(finalAnswer?, toolCalls?)  │
          │                                               │
          │ if 有最终答案且无工具调用 → 回复用户，EXIT     │
          │                                               │
          │ if 有工具调用 → ACT:                          │
          │   for each toolCall:                          │
          │     ArgumentResolver.resolve(参数, msg)       │
          │     ToolRegistry.execute(name, args)         │
          │       → ActResult(success,data,errorMessage) │
          │     结果以 ToolResponseMessage 回灌 ChatMemory│
          │   continue（下一轮带着工具结果再思考）         │
          └─────────────────────────────────────────────┘
        finally: chatMemory.clear(userId)  ← 每轮会话结束清空
```

**三层记忆（ChatService 组装增强系统提示）：**
- 短期：`MessageWindowChatMemory`（per-user 滑动窗口，会话级，结束后清空）
- 会话：`message` 表（DB 历史注入 prompt）
- 长期：`user_memory` 表（DB 长期记忆检索）

### 1.5 工具（@Tool）一览

所有工具方法均返回 `ActResult`（`success` / `data` / `errorMessage`），失败时把 `errorMessage` 回灌给模型实现自我纠正。

| 工具名 | 类 | 能力 | 底层实现 |
|--------|----|------|---------|
| `weather_query` | WeatherTool | 查询城市今/明日天气 | 高德天气 API |
| `image_generate` | ImageGenTool | 文生图 | wan2.5-t2i-preview |
| `image_recognize` | ImageRecognitionTool | 图片识别描述 | qwen-vl-plus |
| `image_edit` | ImageRecognitionTool | 按文字编辑图片 | wan2.5 + qwen-vl-plus |
| `tts_synthesize` | TtsTool | 文字转语音 | cosyvoice-v1 |
| `voice_switch` | TtsTool | 切换 12 种音色 | TimbreSession |
| `file_analyze` | FileTool | 文件内容分析提取 | Tika + qwen-plus + RAG |
| `idiom_game` | IdiomGameTool | 成语接龙 | IdiomDictionary（O(1)）|
| `reminder_set` | ReminderTool | 定时提醒 | ScheduledExecutor |
| `navigation` | NavigationTool | 路线规划 / 路况 | 高德 API |
| `memory_status` | MemoryStatusTool | JVM 运行时监控 | java.lang.management |

> 不是工具：纯对话 / RAG 召回走 `ChatService.chat` 兜底；`/help` `/status` `/cancel` 走 `SlashInterceptor` 预拦截。

---

## 二、项目结构

```
summer-dev/                        ← 父 POM (<packaging>pom>, <dependencyManagement>, <modules>)
│
├── pom.xml                        ← 父工程，管理版本与模块声明
│
├── summer-common/                 ← 零依赖工具包（无 Spring）
│   └── log/summer/common/
│       ├── exception/             ← BotException 及 5 个子类（异常体系）
│       ├── enums/                 ← Timbre（12 音色）、RouteContext（TEXT/VOICE）
│       ├── constant/              ← Prompts（提示词键常量）
│       └── resources/prompts/     ← system.txt、file-system.txt
│
├── summer-aigc/                   ← Agent 核心引擎
│   └── log/summer/aigc/
│       ├── port/                  ← BotInboundPort、BotMessage、MessageSender 接口
│       ├── loop/                  ← AgentLoop、ThinkResult、ActResult、ArgumentResolver
│       ├── tool/                  ← ToolRegistry + 11 个 @Tool 实现
│       ├── rag/                   ← RAG 检索服务
│       ├── entity/                ← 10 个 MyBatis-Plus 实体
│       ├── mapper/                ← 9 个 Mapper 接口
│       ├── service/               ← ChatService、ChatPersistenceService + 接口/实现
│       ├── config/                ← AiConfig、BotProperties、VoiceProperties、
│       │                          │   HttpClientConfig、GlobalExceptionHandler
│       └── resources/db/migration/← Flyway V1__initial_schema.sql
│
├── summer-bot/                    ← 传输适配器层
│   └── log/summer/bot/
│       ├── ILinkBotAdapter.java   ← 实现 BotInboundPort + MessageSender（微信 ILink）
│       ├── SlashInterceptor.java  ← / 命令预拦截
│       └── RetrySender.java       ← 带重试的发送器
│
├── summer-bootstrap/              ← 启动 + 装配
│   └── log/summer/bootstrap/
│       └── SummerApplication.java ← @SpringBootApplication + @MapperScan + @ComponentScan
│   └── resources/
│       ├── application.yml        ← 主配置（datasource / mybatis-plus / actuator / flyway）
│       └── config/                ← ai.yml、bot.yml、security.yml(gitignored)
│
└── docs/superpowers/              ← 重构设计文档（specs / plans）
```

---

## 三、核心引擎详解

### 3.1 消息入口与传输层

**实现：** `ILinkBotAdapter` 实现 `BotInboundPort.onMessage(BotMessage)`，接收 ILink 回调后按消息类型构建 `BotMessage`，再经 `SlashInterceptor` 预拦截，未命中则进入 `AgentLoop`。

```
ILinkBotAdapter.onMessage(msg)
  ├─ VoiceItem → ASR 文本 → BotMessage(context=VOICE)
  ├─ TextItem  → 文本 → BotMessage(context=TEXT) → SlashInterceptor 预拦截
  ├─ ImageItem → CDN 下载(重试) → imageBytes → BotMessage
  └─ FileItem  → 大小校验(>maxSizeMb 拒绝) → CDN 下载 → fileBytes+fileName
        │
        ▼  (未命中 slash)
  AgentLoop.orchestrate(botMsg, this /* MessageSender */)
```

`MessageSender` 三个方法由 `ILinkBotAdapter` 实现：`sendText`（直接发）、`sendImage`/`sendFile`（经 `RetrySender` 指数退避重试，1s→2s→4s，最多 3 次）。

### 3.2 Slash 命令（预拦截）

`SlashInterceptor` 在消息进入 `AgentLoop` 之前处理，**消费后不再转发**：

| 命令 | 行为 |
|------|------|
| `/help` | 列出可用命令与功能说明 |
| `/status` | 返回 Bot 运行时指标（来自 `BotMetrics`） |
| `/cancel` | 清空当前用户 `ChatMemory`，结束会话 |
| 未知 `/xxx` | 「未知命令，输入 /help 查看可用命令」 |

> 其余能力（画图、天气、语音、文件、成语、导航、提醒、监控）均**不再以 `/` 命令形式存在**，而是由用户自然语言触发，模型通过函数调用选择对应 `@Tool`。

### 3.3 双循环 AgentLoop

见 §1.4 流程图。`AgentLoop` 负责：维护 `while` 迭代、安全阀（10 轮 / 120s）、`ChatMemory` 增删、把工具结果以 `ToolResponseMessage` 回灌、循环结束 `finally` 清空记忆、未捕获异常转交 `GlobalExceptionHandler`。

`ArgumentResolver` 在执行前替换参数占位符：`${message.image}`、`${message.file}`、`${message.fileName}`（取自当前 `BotMessage` 的二进制数据）。若引用了占位符但消息无对应资源 → 抛出 `PlaceholderResolutionException` → 转为 `ActResult.failure` → 模型自我纠正。

### 3.4 工具注册与执行（ToolRegistry）

`ToolRegistry` 在 `@PostConstruct` 扫描 Spring 容器中带 `@Tool` 注解方法的 Bean，用 `MethodToolCallbackProvider` 生成 `List<ToolCallback>`，并以 `name → callback` 建索引。`AgentLoop` 通过 `execute(name, args)` 调用，返回统一 `ActResult`。

### 3.5 三级记忆与 RAG

`ChatService` 负责组装增强系统提示（DB 历史 + 长期记忆）并提供三类调用：
- `chatWithTools(messages, callbacks)` — 思考轮（带工具清单）
- `chat(messages)` — 兜底纯对话
- `chatWithRAG(messages, docs)` — RAG 命中时的对话

RAG 服务（`rag/` 包）：`DocumentChunkingService`（切片）、`EmbeddingService`（DashScope embedding）、`VectorStoreService`（内存余弦相似度 TopK）、`RAGRetrievalService`（检索入口）、`RAGContextAugmenter`（prompt 构建）。

### 3.6 全局异常拦截

`GlobalExceptionHandler`（位于 `summer-aigc/config`，因为它依赖 `MessageSender` 端口）按异常类型分类，构建带时间戳+追踪 ID 的中文友好响应。`summer-common/exception/` 仅放异常类，**不含** Handler。

---

## 四、配置体系

配置归属按模块划分，运行时由 `summer-bootstrap/application.yml` 的 `spring.config.import` 聚合。

| 文件 | 归属模块 | 命名空间 | 关键项 |
|------|---------|---------|--------|
| `application.yml` | summer-bootstrap | server / spring.datasource / mybatis-plus / management / flyway | 端口 8080、MySQL `wxbot_db`、`spring.config.import` |
| `config/ai.yml` | summer-bootstrap(resources) | spring.ai.dashscope | qwen-plus / wan2.5 / cosyvoice-v1 / paraformer-v2，超时 300s |
| `config/bot.yml` | summer-bootstrap(resources) | bot.* | 图片编辑 prompt、缓存 TTL、文件上限 20MB、天气 base-url |
| `config/security.yml` | summer-bootstrap(resources) | spring.ai.dashscope.api-key | DashScope API Key（**gitignored**） |
| `prompts/system.txt` | summer-common(resources) | 外部文件 | 对话系统提示词 |
| `prompts/file-system.txt` | summer-common(resources) | 外部文件 | 文档分析系统提示词 |

```yaml
# application.yml 关键片段
spring:
  config:
    import:
      - optional:classpath:config/security.yml
      - classpath:config/ai.yml
      - classpath:config/bot.yml
  flyway:
    enabled: true
    locations: classpath:db/migration

# ai.yml 关键片段
spring.ai.dashscope.chat.options.model:  qwen-plus
spring.ai.dashscope.image.options.model: wan2.5-t2i-preview
spring.ai.dashscope.voice.tts.model:     cosyvoice-v1

# bot.yml 关键片段
bot.cache.pending-image-ttl-minutes: 5
bot.file.max-size-mb:                 20
bot.weather.base-url: https://restapi.amap.com/v3/weather/weatherInfo
```

> 注意：`ai.yml` / `bot.yml` 中仍有少量注释沿用了旧类名（`IntentClassifier`、`ImageGenAgent` 等），仅为注释，不影响运行；如需可后续清理。

---

## 五、数据库设计

持久化框架 MyBatis-Plus；迁移由 **Flyway** 管理，基线脚本 `summer-aigc/src/main/resources/db/migration/V1__initial_schema.sql`。

| 表名 | 实体 | 关键字段 | 用途 |
|------|------|---------|------|
| `conversation` | Conversation | userId/status/messageCount/title | 会话生命周期 |
| `message` | Message | conversationId/messageType/textContent/intentType/... | 全部消息记录 |
| `weather_query` | WeatherQuery | userId/city/queryType/reportText/apiElapsedMs | 天气查询日志 |
| `idiom_game_record` | IdiomGameRecord | userId/score/rounds/endReason | 成语接龙积分 |
| `file_record` | FileRecord | userId/fileName/fileSize/mimeType/extractedText/aiAnalysis | 文件识别 |
| `image_context` | ImageRecord | userId/imageUrl/description/editInstruction | 图片上下文 |
| `timbre_change` | TimbreChange | userId/oldVoiceId/newVoiceId/changeSource | 音色变更 |
| `document_chunks` | DocumentChunk | fileRecordId/chunkText/embedding(JSON) | RAG 向量切片 |
| `user_memory` | UserMemory | userId/memoryType/content/embedding(JSON)/importance | 长期记忆 |

---

## 六、构建与运行

要求：JDK 21、Maven 3.9+、MySQL 8.x（库名 `wxbot_db`）、可用的 DashScope API Key（写入 `summer-bootstrap/src/main/resources/config/security.yml`）。

```bash
# 1. 安装全部模块到本地仓库
mvn clean install

# 2. 运行（仅构建并启动 bootstrap 模块，依赖已 install）
mvn -pl summer-bootstrap spring-boot:run

# 3. 或先打包再运行
mvn -pl summer-bootstrap package
java -jar summer-bootstrap/target/summer-bootstrap-0.0.1-SNAPSHOT.jar
```

启动后微信扫码登录，发送消息即可对话；访问 `http://localhost:8080/actuator/health` 与 `/actuator/metrics` 查看健康与指标。

---

## 七、子模块帮助文档

每个子模块下均附有 `HELP.md`，说明该模块职责、包结构、关键类、依赖与构建、扩展方式：

- [summer-common/HELP.md](summer-common/HELP.md) — 零依赖工具包
- [summer-aigc/HELP.md](summer-aigc/HELP.md) — Agent 核心引擎
- [summer-bot/HELP.md](summer-bot/HELP.md) — 传输适配器
- [summer-bootstrap/HELP.md](summer-bootstrap/HELP.md) — 启动与装配

重构设计文档：
- [specs](docs/superpowers/specs/2026-07-28-multi-module-agent-refactor-design.md)
- [plans](docs/superpowers/plans/2026-07-28-multi-module-agent-refactor.md)
