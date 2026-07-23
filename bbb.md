# 微信 iLink 多模态 AI 机器人 — 项目文档

> **更新日期:** 2026-07-23
> **作者:** bbb
> **分支:** master

---

## 一、项目概述

### 1.1 项目简介

本项目是一个基于 **Spring Boot 3.2 + Java 21** 的微信 iLink 多模态 AI 机器人。
通过接入微信 iLink SDK，实现在微信客户端内与 AI 进行多轮对话、图片生成/编辑、语音合成（TTS）、文件内容识别分析、天气查询等功能。

### 1.2 架构概览

项目采用 **Route Workflow Agent 架构**：消息到达后由 **IntentClassifier**（qwen-turbo LLM + 正则快速路径双层策略）进行意图分类，**AgentRouter**（O(1) Map 查找）直接路由到对应 Agent 执行。每个 Agent 封装独立的功能域和专属模型。

```
微信消息 → AgentContext 构建 → IntentClassifier (qwen-turbo + 正则) → Intent 分类
                                                                           │
                                            ┌──────────────────────────────┘
                                            ▼
                                     AgentRouter (O(1) Map 查找)
                                            │
        ┌───────────────┬───────────────────┼───────────────────┬──────────────┐
        ▼               ▼                   ▼                   ▼              ▼
   ChatAgent      CommandAgent         WeatherAgent         ImageGen       FileAgent
   (qwen-plus)    (/命令处理)          (高德API v2)         Agent          (Tika+AI)
        │               │                   │              (wan2.5)           │
        │               │              ┌────┴────┐             │              │
        │               │              │ 地理编码  │    ImageRecogAgent   FileRecogService
        │               │              │ TTL缓存  │    (qwen-vl+)         │
        │               │              │ DB持久化 │             │        RAG切片
   VoiceGenAgent        │              └─────────┘    图片编辑管线     + Embedding
   (cosyvoice)                                                                  │
                                                         发送结果 ←─────────────┘
```

### 1.3 技术栈

| 技术 | 版本 / 说明 |
|------|-----------|
| **Java** | 21 |
| **Spring Boot** | 3.2.10 |
| **Spring AI** | Alibaba DashScope Starter 1.0.0.1 |
| **AI 模型平台** | 阿里云 DashScope（通义千问 / 万象 / CosyVoice / Paraformer） |
| **意图分类** | qwen-turbo LLM + 正则快速路径（双层策略） |
| **ORM** | MyBatis-Plus 3.5.7 |
| **数据库** | MySQL 8.x（数据库名 `wxbot_db`） |
| **微信 SDK** | wechat-ilink-sdk 2.3.3 |
| **文件解析** | Apache Tika 2.9.4 |
| **HTTP 客户端** | OkHttp 4.x + Java 内置 HttpClient |
| **DashScope SDK** | dashscope-sdk-java 2.16.4 |
| **可观测性** | Spring Boot Actuator + Micrometer |
| **构建工具** | Maven |

### 1.4 核心功能一览

| 功能 | Agent | 模型/API | 说明 |
|------|-------|----------|------|
| **意图分类** | IntentClassifier | qwen-turbo + 正则 | 双层策略：快速正则 → LLM 回退 |
| **AI 多轮对话** | ChatAgent | qwen-plus | 滑动窗口记忆 + RAG 增强 |
| **文生图** | ImageGenAgent | wan2.5-t2i-preview | 文本描述 → AI 生成图片，支持参考图迭代 |
| **图片识别&编辑** | ImageRecognitionAgent | qwen-vl-plus + wan2.5 | 上传图识别 → 编辑指令 → 多模态理解 → 重绘 |
| **语音合成** | VoiceGenAgent | cosyvoice-v1 | 文本 → TTS → WAV 音频，12 种音色 |
| **天气查询** | WeatherAgent v2 | 高德 API | 地理编码回退 + TTL 缓存 + DB 持久化 |
| **文件识别** | FileAgent | Tika + qwen-plus | 文本提取 → AI 分析 → RAG 切片 + Embedding |
| **命令系统** | CommandAgent | / 前缀路由 | 8 个内置命令 |
| **会话持久化** | — | MySQL | 全部消息/会话/音色/天气/文件入库 |

---

## 二、项目结构

```
src/main/java/log/demo/linkDemo/
├── ILinkApplication.java            # Spring Boot 启动入口
├── config/                           # 配置类
│   ├── AiConfig.java                 # ChatClient 装配 + intentChatClient (qwen-turbo)
│   ├── BotProperties.java            # bot.* 配置属性绑定
│   ├── HttpClientConfig.java         # OkHttpClient Bean + DashScope SDK 超时注入
│   └── VoiceProperties.java          # TTS 配置属性绑定
├── agent/                            # ★ Agent 智能体层
│   ├── Intent.java                   # 意图枚举（8 种）
│   ├── Agent.java                    # Agent 接口
│   ├── AgentContext.java             # Agent 调用上下文
│   ├── IntentClassifier.java         # LLM + 正则双层意图分类器（qwen-turbo）
│   ├── AgentRouter.java              # O(1) Map 路由器（Intent → Agent）
│   ├── BotMetrics.java               # Micrometer 指标注册
│   ├── TextTool.java                 # 文本工具类（截断/TTS提取/音色剥离）
│   ├── chat/                         # 对话智能体
│   │   ├── ChatAgent.java            # 通用对话 Agent（qwen-plus + RAG + VOICE TTS）
│   │   └── ChatService.java          # AI 对话 + 多模态 + 文档分析（带重试）
│   ├── command/                      # 命令智能体
│   │   ├── CommandAgent.java         # 命令分发 Agent
│   │   ├── Command.java              # 命令函数式接口
│   │   └── CommandRegistry.java      # 命令注册表（LinkedHashMap 前缀匹配）
│   ├── voice/                        # 语音智能体
│   │   ├── VoiceGenAgent.java        # TTS + 音色切换 Agent
│   │   ├── TTSEngine.java            # DashScope CosyVoice TTS API 封装
│   │   ├── TimbreSession.java        # 用户音色会话管理器
│   │   └── AudioTranscoder.java      # PCM → WAV 转码 + 时长计算
│   ├── weather/                      # 天气智能体
│   │   └── WeatherAgent.java         # 高德天气 API Agent (v2: 地理编码 + 缓存 + DB)
│   ├── image/                        # 图片智能体
│   │   ├── ImageGenAgent.java        # 文生图 Agent（wan2.5, 支持迭代）
│   │   ├── ImageRecognitionAgent.java # 图片识别 + 完整编辑管线（v2）
│   │   ├── ImageGenService.java      # DashScope 文生图 API 封装（长轮询）
│   │   ├── ImageCacheManager.java    # 待编辑图片缓存（会话隔离 + 锁）
│   │   └── ImageContextManager.java  # 生成图片上下文（迭代编辑参考图）
│   └── file/                         # 文件智能体
│       ├── FileAgent.java            # 文件处理 Agent（Tika + AI + RAG）
│       └── FileRecognitionService.java # MIME检测 + 内容提取 + AI分析 + RAG切片 + FileRecord持久化
├── entity/                           # 数据库实体
│   ├── Conversation.java / Message.java / FileRecord.java
│   ├── ImageRecord.java / TimbreChange.java / WeatherQuery.java
│   ├── DocumentChunk.java / UserMemory.java / VoiceResult.java
├── enums/  RouteContext.java / Timbre.java
├── exception/  BotException / AIServiceException / ConfigurationException / ...
├── mapper/   8 个 MyBatis-Plus Mapper
├── rag/      VectorStoreService / RAGRetrievalService / RAGContextAugmenter / EmbeddingService / DocumentChunkingService
├── service/
│   ├── ILinkBotService.java          # 微信 iLink 适配器（核心入口 + MessageSender 实现）
│   ├── MessageSender.java            # 消息发送抽象接口
│   ├── ChatPersistenceService.java   # 持久化门面（8 个 Service 聚合）
│   └── impl/                          # 8 个 ServiceImpl
└── resources/
    ├── application.yml               # 主配置
    ├── schema.sql                    # 建表脚本
    ├── config/  ai.yml / bot.yml / security.yml
    └── prompts/  system.txt / file-system.txt
```

---

## 三、功能详解（示例 → 调用链 → 生命周期）

### 3.1 意图分类（IntentClassifier）

**双层策略：** 正则快速路径（零延迟）→ qwen-turbo LLM 回退（~200ms）。

#### 示例

| 用户输入 | 匹配路径 | 分类结果 |
|----------|----------|----------|
| `/draw 一只猫` | 前缀 `/` → 快速路径 | `COMMAND` |
| `画一只猫` | IMAGE_GEN_RE 正则 | `IMAGE_GEN` |
| `改成黑白风格` | IMAGE_EDIT_RE 正则 | `IMAGE_EDIT` |
| `帮我看看外面要不要带伞` | 无正则命中 → LLM | `WEATHER` |
| `你好` | 无正则命中 → LLM | `CHAT` |

#### 完整调用链

```
ILinkBotService.handleMessage()                     [service/ILinkBotService.java:107]
  └─ AgentRouter.route(ctx)                        [agent/AgentRouter.java:64]
       └─ IntentClassifier.classify(ctx)            [agent/IntentClassifier.java:84]
            ├─ 快速路径检查:
            │   ├─ ctx.text().startsWith("/")  → COMMAND       [:86]
            │   ├─ ctx.hasFile()               → FILE          [:87]
            │   └─ ctx.hasImage()              → IMAGE_EDIT    [:88]
            ├─ 正则快速路径: regexClassify(text)                [:94]
            │   ├─ IMAGE_GEN_RE.find()    → IMAGE_GEN          [:113]
            │   ├─ IMAGE_EDIT_RE.find()   → IMAGE_EDIT         [:114]
            │   ├─ TTS_RE.find()          → TTS                [:115]
            │   ├─ VOICE_SWITCH_RE.find() → VOICE_SWITCH       [:116]
            │   └─ WEATHER_RE.find()      → WEATHER            [:118]
            └─ LLM 回退: llmClassify(text)                     [:102]
                 └─ intentChatClient (qwen-turbo)
                      .prompt().system(CLASSIFY_PROMPT).user(text).call()
                      失败 → 自动降级 CHAT
```

#### 关键正则（v2.0 优化版）

```java
// 画一张 / 生成一张 → IMAGE_GEN（前缀强信号，优先匹配）
IMAGE_GEN_RE  = "(画|生成|绘制|做图|画图|画一张|生成一张|做一张|来一张)(一?[张个幅]?.{0,20}|$)"

// 仅保留双字编辑动作词（移除 改/换/变/加 单字防误判）
IMAGE_EDIT_RE = "(修改|改成|换成|编辑|调整|替换|添加|删除|去掉|去除|增加|加上|加个|换个|重绘|重新生成).{1,15}"

// 天气关键词（弱信号，最后匹配，防止"画一张天气图"误判）
WEATHER_RE    = "(天气|温度|气温|热不热|冷不冷|会不会下雨|有没有雨|多少度|几度|刮风|雾霾|空气质量|天气预报)"
```

#### 生命周期

```
[构造] IntentClassifier(@Qualifier intentChatClient)
   └─ intentChatClient 注入（qwen-turbo, temperature=0, maxToken=20, 无记忆）

[@PostConstruct] init()
   └─ log 初始化信息

[运行时] classify(ctx) — 每次消息到达调用一次，~200ms（LLM 路径）或 <1ms（正则路径）

[销毁] 无特殊清理（ChatClient 由 Spring 管理）
```

---

### 3.2 AI 多轮对话（ChatAgent + ChatService）

#### 示例

```
用户: 你好，请介绍一下你自己
Bot:  你好！我是基于通义千问的 AI 助手...

用户: 刚才说到哪里了？
Bot:  （有记忆）我们刚才在讨论我的功能...
```

#### 完整调用链

```
AgentRouter.route(ctx) → intent=CHAT → ChatAgent        [agent/AgentRouter.java:67]
  └─ ChatAgent.execute(ctx)                              [agent/chat/ChatAgent.java]
       ├─ ImageCacheManager.removeSilently(userId)       # 清除待编辑图片缓存
       ├─ RAGRetrievalService.retrieve(text)             # 检索相关文档
       ├─ 有相关文档:
       │   └─ ChatService.chatWithRAG(userId, text, docs)
       │        └─ RAGContextAugmenter.buildAugmentedUserMessage()
       │        └─ ChatClient.prompt().system(ragPrompt).user(augmented).call()
       └─ 无相关文档:
           └─ ChatService.chat(userId, text)
                └─ ChatClient.prompt().system(chatSystemPrompt).user(text)
                     .advisors(MessageChatMemoryAdvisor, CONVERSATION_ID=userId)
                     .call().content()
       └─ 若 RouteContext=VOICE → VoiceGenAgent TTS 合成 → sendFile(wav)
       └─ ctx.sender().sendText(userId, reply)
```

#### ChatService 模型体系

| 方法 | 模型 | 记忆策略 | 用途 |
|------|------|----------|------|
| `chat()` | qwen-plus | userId 隔离 | 多轮文本对话 |
| `chatWithRAG()` | qwen-plus | UUID 隔离 | RAG 增强对话 |
| `analyzeImage()` | qwen-vl-plus | UUID 隔离 | 多模态图片分析 |
| `describeImageEdit()` | qwen-vl-plus | UUID 隔离 | 图片编辑 prompt 生成 |
| `analyzeDocument()` | qwen-plus | UUID 隔离 + 重试2次 | 文档 AI 分析 |

#### 生命周期

```
[构造] ChatAgent(ChatService, RAGRetrievalService, ImageCacheManager, VoiceGenAgent)

[运行时] execute(ctx)
   └─ 同步执行（文本对话为同步，语音后处理异步）

[销毁] 无特殊清理（ChatClient/ChatMemory 由 AiConfig Bean 生命周期管理）

ChatService:
  [@PostConstruct] initSystemPrompt() — 从 classpath:prompts/system.txt 加载
  [运行时] 无状态，线程安全（ChatClient 内部管理连接池）
  [clearHistory] 手动清除用户会话记忆
```

---

### 3.3 文生图（ImageGenAgent + ImageGenService）

#### 示例

```
用户: 画一只坐在月亮上的黑猫
Bot:  正在生成图片...
      [异步] → 文生图 → wan2.5-t2i-preview → CDN URL → 下载 → 发送图片

用户: 给猫加上一顶巫师帽
Bot:  正在生成图片（基于上一张迭代）...
      [异步] → 获取上一张图 CDN URL 作为 refImage → 生成新图 → 发送
```

#### 完整调用链

```
AgentRouter.route(ctx) → intent=IMAGE_GEN → ImageGenAgent      [agent/AgentRouter.java:67]
  └─ ImageGenAgent.execute(ctx)                                 [agent/image/ImageGenAgent.java:53]
       └─ executor.submit(() -> doImageGen(ctx))                # 虚拟线程异步

doImageGen(ctx):                                                [:60]
  1. imageContextManager.getLastRefImage(userId)                # 获取上一张图 CDN URL
  2. ctx.sender().sendText("正在生成图片...")
  3. refUrl != null ?
       imageGenService.generateImageUrl(prompt, refUrl)         # 带参考图生成
         └─ 失败 → 自动回退 generateImageUrl(prompt, null)     [:78]
     : imageGenService.generateImageUrl(prompt)                 # 纯文本生成
         └─ ImageSynthesis.asyncCall(param) → taskId            [agent/image/ImageGenService.java:57]
         └─ 长轮询: for(i=0; i<90; i++) sleep(2s)               [:62-79]
              ├─ fetch(taskId) → "SUCCEEDED" → 提取 CDN URL
              ├─ fetch(taskId) → "FAILED"    → log error → null
              └─ 90次超时(180s) → log error → null
  4. imageGenService.downloadImage(url)                         # Java HttpClient GET
  5. imageContextManager.save(userId, url, bytes)               # 保存上下文供迭代
  6. ctx.sender().sendImage(userId, bytes, "ai-gen.png", prompt)
```

#### ImageGenService 核心参数

```java
model:  wan2.5-t2i-preview
n:      1                     // 每次生成 1 张
size:   1024*1024             // 方形
refImage: 上一张图 CDN URL（可选，实现迭代一致性）
长轮询:   90次 × 2s = 180s 超时
```

#### 生命周期

```
[构造] ImageGenAgent(ImageGenService, ImageContextManager, ChatService)
   └─ executor = Executors.newVirtualThreadPerTaskExecutor()   # 虚拟线程池

[@PostConstruct] 无特殊初始化（ImageGenService 通过 @Value 注入 apiKey/model）

[运行时] execute(ctx)
   └─ executor.submit(...) → 立即返回 true（不阻塞消息循环）
   └─ doImageGen 在虚拟线程中异步执行

[@PreDestroy] shutdown()                                        [:102]
   └─ executor.shutdown() → awaitTermination(30s) → shutdownNow()

ImageGenService: 无状态，每个 generateImageUrl 调用独立提交任务 + 长轮询
```

---

### 3.4 图片识别 & 编辑（ImageRecognitionAgent）

#### 示例

```
用户: [上传图片 photo.jpg]
Bot:  【图片描述】
      这是一张夕阳下的海滩照片，前景有棕榈树，天空呈橙红色...
      💡 你可以对我说："改成黑白风格"、"把背景换成蓝天"、"添加一只猫" 等进行编辑

用户: 改成黑白风格
Bot:  正在分析编辑需求："改成黑白风格"...
      正在生成编辑后的图片...
      [发送编辑后图片: edited-image.png]
```

#### 完整调用链

```
═══════════════ 第一阶段：图片上传识别 ═══════════════

AgentRouter.route(ctx) → ctx.hasImage()=true
  └─ IntentClassifier.classify(ctx) → IMAGE_EDIT               [:87]
       └─ AgentRouter → ImageRecognitionAgent

ImageRecognitionAgent.execute(ctx)                               [agent/image/ImageRecognitionAgent.java:62]
  └─ ctx.hasImage()=true → handleImageUpload(ctx)               [:67]

handleImageUpload(ctx):                                          [:76]
  1. ctx.sender().sendText("正在识别图片...")                   # 隐式，在ChatService内
  2. chatService.analyzeImage(ctx.imageBytes())                  [:82]
       └─ ChatClient (qwen-vl-plus, 多模态)
            .prompt().system(...).user(u->u.text("请详细描述...").media(imageMedia))
            .call().content()
  3. imageCacheManager.put(userId, ctx.imageBytes())             [:83]
       └─ 生成 sessionId → 复合键 userId:sessionId → 缓存
       └─ 容量保护: 超出 maxPendingImages → 淘汰最旧
  4. ctx.sender().sendText("【图片描述】\n" + desc + "\n\n💡 ...")

═══════════════ 第二阶段：编辑指令执行 ═══════════════

用户发送: "改成黑白风格"
  └─ IntentClassifier.classify(ctx)
       ├─ ctx.hasImage()=false（文本消息）
       └─ IMAGE_EDIT_RE.find("改成黑白风格") → IMAGE_EDIT

ImageRecognitionAgent.execute(ctx)                               [:62]
  ├─ ctx.hasImage()=false
  └─ ctx.hasText() && imageCacheManager.containsKey(userId) → true
       └─ executor.submit(() -> doImageEdit(ctx))               [:70]

doImageEdit(ctx):                                                [:92]（异步虚拟线程）
  1. imageCacheManager.lock(userId)                             [:97]  # 串行化同用户编辑
  2. imageCacheManager.peek(userId)                             [:99]   # peek不删除，失败可重试
  3. ctx.sender().sendText("正在分析编辑需求...")
  4. chatService.describeImageEdit(originalBytes, instruction)  [:105]
       └─ ChatClient (qwen-vl-plus, 多模态)
            .prompt().system(imageEditSystemPrompt)
            .user(u->u.text("修改要求："+instruction).media(imageMedia))
            .call().content()
       → 返回编辑后画面的详细文本描述（作为文生图 prompt）
  5. imageGenService.generateImageUrl(editPrompt)               [:110]  # wan2.5 文生图
  6. imageGenService.downloadImage(imageUrl)                    [:117]
  7. imageContextManager.save(userId, url, bytes)               [:123]   # 保存供迭代
  8. imageCacheManager.removeSilently(userId)                   [:126]   # 清除待编辑缓存
  9. ctx.sender().sendImage(userId, bytes, "edited-image.png")  [:129]
  10. finally: imageCacheManager.unlock(userId)                 [:137]
```

#### 核心设计要点

| 设计点 | 说明 |
|--------|------|
| **peek 不删除** | 编辑失败时保留原图，用户可重新发送编辑指令 |
| **编辑锁** | `lock(userId)/unlock(userId)` 串行化同一用户的并发编辑 |
| **多模态 + 重绘** | qwen-vl-plus 理解原图+指令 → 描述文本 → wan2.5 重新生成 |
| **会话隔离** | ImageCacheManager 用 `userId:sessionId` 复合键，多图并发不覆盖 |

#### 生命周期

```
[构造] ImageRecognitionAgent(ChatService, ImageCacheManager, ImageGenService, ImageContextManager)
   └─ executor = Executors.newVirtualThreadPerTaskExecutor()

[运行时] execute(ctx) — 双分支
   ├─ 有图片 → handleImageUpload (同步识别)
   └─ 有文本 + 缓存命中 → executor.submit(doImageEdit) (异步编辑)

[@PreDestroy] shutdown()                                        [:146]
   └─ executor.shutdown() → awaitTermination(30s) → shutdownNow()
```

---

### 3.5 天气查询（WeatherAgent v2）

#### 示例

```
用户: 北京天气
Bot:  正在查询「北京」天气...
      【北京北京市 实时天气报告】
      🌡 当前温度 25°C，晴。
      💧 相对湿度 45%，北风 3 级。
      🕐 数据发布时间：2026-07-23 14:00:00。

用户: 深圳明天天气
Bot:  正在查询「深圳」天气...
      【深圳市明天天气】（2026-07-24）
      🌡 温度 26°C ~ 33°C。
      ☀ 白天晴，🌙 夜间多云。
      💧 湿度 60%，🌬 东南风 3 级。

用户: 三亚天气
Bot:  [内置38城市无"三亚"]
      → 调用高德地理编码API → "三亚" → adcode=460200
      → 缓存 adcode（下次直接命中）
      → 调用高德天气API → 返回实时天气报告
```

#### 完整调用链

```
AgentRouter.route(ctx) → intent=WEATHER → WeatherAgent          [agent/AgentRouter.java:67]
  └─ WeatherAgent.execute(ctx)                                  [agent/weather/WeatherAgent.java:143]

execute(ctx):
  1. extractCity(ctx.text())                                    [:144]
       ├─ CITY_WEATHER_PATTERN 正则匹配 "城市名 + 天气关键词"     [:257-266]
       ├─ 遍历 38 个内置城市（LinkedHashMap 保证长名优先）       [:269-271]
       └─ 回退模糊匹配 "XX天气"                                  [:274-277]
  2. ctx.sender().sendText("正在查询「" + city + "」天气...")
  3. generateReport(userId, text)                               [:146]
       ├─ 含"明天/明日" → buildForecastReport(userId, city, 1)  [:159]
       ├─ 含"后天"     → buildForecastReport(userId, city, 2)  [:162]
       ├─ 含"预报/未来" → buildMultiDayReport(userId, city)     [:165]
       └─ 默认         → buildNowReport(userId, city)           [:168]

buildNowReport(userId, city):                                   [:172]
  1. fetchNow(city) — 带缓存                                    [:174]
       ├─ CacheEntry cached = responseCache.get("now:"+city)    [:287]
       ├─ 缓存命中且未过期 → (WeatherNow) cached.data           [:289-290]
       └─ 缓存未命中:
           ├─ callApi(city, "base")                             [:295]
           │    └─ resolveAdcode(city)                           [:326]
           │         ├─ 内置 38 城市 adcode → 直接返回           [:329-330]
           │         ├─ geocodeCache 命中 → 返回                 [:333-335]
           │         └─ geocodeCity(city)                        [:338]
           │              └─ GET https://restapi.amap.com/v3/geocode/geo
           │                   ?address=city&key=xxx             [:352-355]
           │              → 提取 adcode → 写入 geocodeCache      [:373-375]
           └─ GET https://restapi.amap.com/v3/weather/weatherInfo
                ?city=adcode&key=xxx&extensions=base             [:314-320]
       └─ new CacheEntry(now, 5min) → responseCache.put          [:312]
  2. 格式化报告 → saveQuery(userId, ..., "SUCCESS", elapsed)    [:184-186]

buildForecastReport / buildMultiDayReport: 同上，extensions="all"
```

#### WeatherAgent v2 核心优化

| 优化项 | v1 (旧) | v2 (新) |
|--------|---------|---------|
| **城市覆盖** | 仅 38 个内置 adcode | 38 内置 + 高德地理编码 API 动态解析任意城市 |
| **API 缓存** | 无 | 实时天气 5min TTL / 预报 30min TTL |
| **DB 持久化** | 无 | 每次查询写入 `weather_query` 表 |
| **城市提取** | 多段正则，顺序敏感 | 统一 CITY_WEATHER_PATTERN + 长名优先遍历 |
| **管理接口** | 无 | evictCache(city) / evictAllCache() |

#### 生命周期

```
[构造] WeatherAgent(BotProperties, IWeatherQueryService)

[@PostConstruct] init()                                         [:135]
   └─ log apiKey(脱敏) / baseUrl / builtinCities count

[运行时]
   ├─ execute(ctx) → 同步调用，返回 String 报告
   ├─ generateReport(userId, text) → 供 CommandRegistry 直接调用
   └─ fetchNow/fetchForecast → 缓存优先 → API 回退

[销毁] 无特殊清理（ConcurrentHashMap 随 GC 回收，WeatherQuery 走 MyBatis-Plus）
```

---

### 3.6 语音合成（VoiceGenAgent + TTSEngine）

#### 示例

```
用户: 用语音朗读你好世界
Bot:  正在生成语音："你好世界"...
      [发送 WAV 音频文件]

用户: 换萝莉音
Bot:  音色已切换：龙小夏（活泼元气少女音）

用户: 再读一遍你好世界
Bot:  [用龙小夏音色朗读] → 发送 WAV
```

#### 完整调用链

```
═══════════ TTS 流程 ═══════════

AgentRouter.route(ctx) → intent=TTS → VoiceGenAgent
  └─ VoiceGenAgent.execute(ctx)
       1. TextTool.extractTtsText(text)  # 剥离"用语音/朗读/播报"触发词
       2. TimbreSession.getCurrentVoiceId(userId)  # 获取用户当前音色
       3. TTSEngine.synthesize(text, voiceId)
            └─ SpeechSynthesisParam.builder()
                 .model("cosyvoice-v1").voice(voiceId).text(text)
            └─ SpeechSynthesizer.call(param)
            └─ AudioTranscoder.pcmToWav(pcmData, 16000, 16, 1)
            └─ BotMetrics.recordTts(...)
       4. ctx.sender().sendFile(userId, wavBytes, "tts.wav", text)

═══════════ 音色切换流程 ═══════════

AgentRouter.route(ctx) → intent=VOICE_SWITCH → VoiceGenAgent
  └─ VoiceGenAgent.execute(ctx)
       1. Timbre.matchKeyword(text)  # 匹配"萝莉/御姐/男声/..."
       2. TimbreSession.switchTo(userId, timbre)
       3. ChatPersistenceService.saveTimbreChange(...)
       4. 若有剩余文本 → 继续 TTS 流程
```

#### 生命周期

```
[构造] VoiceGenAgent(TTSEngine, TimbreSession, ChatPersistenceService, BotProperties)

[运行时] execute(ctx) — 同步 TTS 合成，约 1-3s
   └─ TimbreSession 维护用户→音色映射，2h 无活动自动清除

[销毁]
   └─ TimbreSession: 定时清理线程 shutdown
```

---

### 3.7 文件识别（FileAgent + FileRecognitionService）

#### 示例

```
用户: [上传 Day3.docx]
Bot:  收到文件「Day3.docx」（45.2 KB），正在分析…
      【文件分析】Day3.docx（Word 文档 (.docx)，8.2 KB）

      [AI 分析结果...]
      
      [后台异步: 切片 → Embedding → 向量索引 → 后续对话可 RAG 检索]
```

#### 完整调用链

```
AgentRouter.route(ctx) → ctx.hasFile()=true
  └─ IntentClassifier.classify(ctx) → FILE                       [:87]
       └─ AgentRouter → FileAgent

FileAgent.execute(ctx)                                           [agent/file/FileAgent.java:33]
  └─ executor.submit(() -> doFileRecognition(ctx))               [:34]

doFileRecognition(ctx):                                          [:38]
  └─ fileRecognitionService.recognize(userId, fileBytes, fileName) [:42]

FileRecognitionService.recognize():                              [:67]
  1. detectType(fileBytes, fileName)                             [:73]
       └─ Tika.detect(InputStream, fileName)
          ├─ 魔数优先（PDF → application/pdf）
          └─ 后缀兜底（.docx → Word .docx）
  2. 内容提取:                                                    [:78-91]
       ├─ image/* → chatService.analyzeImage(bytes)              [:80]
       └─ 其他   → extractTextWithTika(bytes)                    [:90]
            └─ Tika.parseToString(InputStream)
  3. AI 分析: analyzeWithAI(userId, ...)                         [:100]
       └─ truncateForContext(text, 8000chars)                    [:250-253]
       └─ chatService.analyzeDocument(userId, fileName, type, text, prompt)
            └─ 截断 8000 字符 → 重试2次(2s/4s退避)               [:123-170]
  4. 持久化 FileRecord → 获取 fileRecordId                        [:115-116]
       └─ saveFileRecord(userId, fileName, size, mime, ...)      [:282-301]
            └─ FileRecord 实体 → fileRecordService.save(rec) → rec.getId()
  5. 异步 RAG: ragExecutor.submit(                               [:120-121]
       () -> chunkAndEmbed(fileRecordId, userId, ...))
       └─ DocumentChunkingService.chunk(text)                    [:137]
            └─ 智能切片（按段落/句子边界）
       └─ 存 DB: documentChunkService.save(chunk)                [:145]
            └─ chunk.setFileRecordId(fileRecordId) ← 关联文件记录
       └─ Embedding: embeddingService.embedBatch(texts)          [:152]
            └─ DashScope text-embedding API
       └─ 更新 embedding 字段: documentChunkService.updateById   [:162]
       └─ 写入内存索引: vectorStoreService.storeDocumentChunk    [:169]
  6. 返回 formatResponse(fileName, mimeType, aiResult, ...)     [:123]
```

#### FileRecord 持久化（v2 修复）

```
之前: FileRecord 从未保存到 DB，chunk.setFileRecordId(null) + schema NOT NULL → SQL 异常
现在:
  1. schema.sql: file_record_id BIGINT DEFAULT NULL（宽松约束）
  2. recognize() 中同步保存 FileRecord → 获取 ID → 传给异步 RAG
  3. chunkAndEmbed() 中 chunk.setFileRecordId(fileRecordId) ← 正确关联
```

#### 生命周期

```
[构造] FileAgent(FileRecognitionService)
   └─ executor = Executors.newVirtualThreadPerTaskExecutor()

[运行时] execute(ctx) → executor.submit(...) → 异步处理
   └─ FileRecognitionService.recognize() 同步提取+分析（主线程）
   └─ RAG 切片+Embedding 异步（vThread，不阻塞响应）

[销毁] FileAgent.shutdown() — executor 关闭
   FileRecognitionService: 无状态，Tika 线程安全
   └─ ragExecutor 未显式关闭（虚拟线程，JVM 退出时自动清理）
```

---

### 3.8 命令系统（CommandAgent + CommandRegistry）

#### 示例

```
用户: /help
Bot:  【可用命令】
      /draw <描述>     - AI 生成图片
      /weather <城市>  - 查询天气
      /tts <文本>      - 语音合成
      /voice <编号>    - 切换音色(1-12)
      /voice list      - 查看音色列表
      /status          - 查看连接状态
      /clear           - 清除对话历史
      /cancel          - 取消待编辑图片

用户: /voice list
Bot:  【可用音色(共12种)】
      1. 龙小春 - 温柔知性女声
      2. 龙小夏 - 活泼元气少女音
      ...
```

#### 完整调用链

```
AgentRouter.route(ctx) → ctx.text()="/draw 一只猫"
  └─ IntentClassifier.classify(ctx) → "/" 开头 → COMMAND         [:86]
       └─ AgentRouter → CommandAgent

CommandAgent.execute(ctx)                                        [agent/command/CommandAgent.java]
  └─ CommandRegistry.execute(userId, cmd, sender, isRunning)

CommandRegistry.execute():                                       [agent/command/CommandRegistry.java]
  └─ LinkedHashMap 前缀遍历（注册顺序 = 匹配优先级）
       ├─ cmd.startsWith("/draw ")    → handleDraw(...)
       │    └─ ImageGenService.generateImageUrl(prompt)
       │    └─ download + sendImage
       ├─ cmd.startsWith("/weather ") → handleWeather(...)
       │    └─ WeatherAgent.generateReport(userId, city + "天气")
       ├─ cmd.startsWith("/tts ")     → handleTts(...)
       │    └─ VoiceGenAgent.synthesizeWithDefaultVoice(text)
       ├─ cmd.startsWith("/voice list") → handleVoiceList(...)
       ├─ cmd.startsWith("/voice ")   → handleVoiceSwitch(...)
       ├─ cmd.startsWith("/help")     → 返回帮助文本
       ├─ cmd.startsWith("/status")   → 连接状态 + 音色状态
       ├─ cmd.startsWith("/clear")    → ChatService.clearHistory + ImageCacheManager.removeSilently
       │                               + ImageContextManager.clear + closeConversation
       └─ cmd.startsWith("/cancel")   → ImageCacheManager.removeSilently
```

#### 生命周期

```
[构造] CommandAgent(CommandRegistry)

CommandRegistry:
  [构造] 9 个 Command → LinkedHashMap（按注册顺序前缀匹配）
  依赖: ChatService, ImageCacheManager, ImageContextManager,
        ImageGenService, VoiceGenAgent, WeatherAgent, ChatPersistenceService

[运行时] execute(userId, cmd, sender, isRunning)
   └─ 同步执行（轻量命令 <100ms，/draw 异步虚拟线程）

[销毁] 无特殊清理
```

---

## 四、配置体系（v2 更新）

### 4.1 配置分层

| 文件 | 命名空间 | 内容 |
|------|---------|------|
| `application.yml` | `server` `spring.datasource` `mybatis-plus` `management` | 基础 + 数据源 + ORM + Actuator |
| `config/ai.yml` | `spring.ai.dashscope` | DashScope 模型、提示词路径、超时 |
| `config/bot.yml` | `bot.*` | 图片编辑提示词、缓存参数、文件限制、天气 API |
| `config/security.yml` | `spring.ai.dashscope.api-key` | API 密钥（gitignore） |
| `prompts/system.txt` | 外部文件 | AI 对话系统提示词 |
| `prompts/file-system.txt` | 外部文件 | 文件分析系统提示词 |

### 4.2 关键配置项

```yaml
# ── 模型绑定 ──
spring.ai.dashscope.chat.options.model:        qwen-plus
spring.ai.dashscope.image.options.model:       wan2.5-t2i-preview
spring.ai.dashscope.voice.tts.model:           cosyvoice-v1
spring.ai.dashscope.voice.tts.voice:           longxiaochun

# ── 超时 ──
spring.ai.dashscope.connect-timeout: 30000    # 连接超时 30s
spring.ai.dashscope.read-timeout:    300000   # 读取超时 5min
# ⚠️ 以上属性仅配置 Spring AI 层，DashScope SDK 内建 OkHttpClient
#    的超时由 HttpClientConfig.configureDashScopeSdkTimeouts() 强制注入

# ── 系统提示词 ──
spring.ai.system-prompt-path: classpath:prompts/system.txt

# ── 图片编辑 ──
bot.intent.image-edit-system-prompt: <编辑描述提示词>

# ── 缓存 ──
bot.cache.pending-image-ttl-minutes:  5
bot.cache.max-pending-images:         50
bot.cache.cleanup-interval-minutes:   2
bot.cache.image-context-ttl-minutes:  30
bot.cache.max-image-context-entries:  200

# ── 文件 ──
bot.file.max-size-mb:  20

# ── 天气 (高德开放平台) ──
bot.weather.api-key:  <key>
bot.weather.base-url: https://restapi.amap.com/v3/weather/weatherInfo
```

### 4.3 DashScope SDK 超时注入（v2 新增）

```java
// HttpClientConfig.java — @PostConstruct
// DashScope SDK 从 Constants.connectionConfigurations + 环境变量读取超时，
// Spring 的 spring.ai.dashscope.read-timeout 不会传递到 SDK 层，
// 必须在 @PostConstruct 中三层注入:

1. System.setProperty("DASHSCOPE_READ_TIMEOUT", "300")   → 环境变量路径
2. Constants.connectionConfigurations = builder()          → 全局配置替换（主方案）
        .readTimeout(Duration.ofSeconds(300)).build()
3. Constants.CONNECT_TIMEOUT = 30                          → 旧版 int 字段
```

---

## 五、数据库设计（v2 更新）

### 5.1 表汇总

| 表名 | 实体 | 主要用途 | v2 变更 |
|------|------|---------|---------|
| `conversation` | Conversation | 会话生命周期管理 | — |
| `message` | Message | 所有消息记录（核心表） | — |
| `file_record` | FileRecord | 文件上传→提取→分析全链路追踪 | — |
| `image_context` | ImageRecord | 图片 CDN URL、描述、编辑指令 | — |
| `timbre_change` | TimbreChange | 音色切换审计日志 | — |
| `weather_query` | WeatherQuery | 天气查询记录 | **WeatherAgent v2 开始写入** |
| `document_chunks` | DocumentChunk | RAG 文档切片 + embedding 向量 | `file_record_id` NOT NULL→NULL |
| `user_memory` | UserMemory | 用户长期记忆/偏好存储 | — |

### 5.2 document_chunks 约束变更

```sql
-- v1 (旧)
file_record_id BIGINT NOT NULL,
FOREIGN KEY (file_record_id) REFERENCES file_record(id) ON DELETE CASCADE

-- v2 (新)
file_record_id BIGINT DEFAULT NULL,
FOREIGN KEY (file_record_id) REFERENCES file_record(id) ON DELETE SET NULL
```

---

## 六、消息处理全局流程图

```
微信消息到达
      │
      ▼
ILinkBotService.handleMessage(WeixinMessage)          [service/ILinkBotService.java:107]
      │
      ├─ VoiceMsg → 识别文本 → AgentContext(VOICE) ────┐
      ├─ TextMsg  → 去重检查 → AgentContext(TEXT) ──────┤
      ├─ ImageMsg → CDN下载  → AgentContext(imageBytes) ┤
      └─ FileMsg  → 大小检查 → AgentContext(fileBytes) ─┘
                                          │
                                          ▼
                                   AgentRouter.route(ctx)            [:64]
                                          │
                                          ▼
                               IntentClassifier.classify(ctx)       [:84]
                                          │
                          ┌───────────────┼───────────────┐
                          │ 快速路径      │ 正则快速路径    │ LLM 回退
                          │ /→COMMAND     │ 前缀信号优先    │ qwen-turbo
                          │ file→FILE     │ IMAGE_GEN      │ 失败→CHAT
                          │ image→IMG_EDIT│ IMAGE_EDIT     │
                          │               │ TTS            │
                          │               │ VOICE_SWITCH   │
                          │               │ WEATHER(最后)  │
                          └───────────────┴───────────────┘
                                          │
                                          ▼
                              AgentRouter O(1) Map 查找              [:67]
                                          │
    ┌────────┬───────────┬───────────┬────┴──────┬──────────┬──────────┐
    ▼        ▼           ▼           ▼           ▼          ▼          ▼
 COMMAND   CHAT      IMAGE_GEN   IMAGE_EDIT    TTS       WEATHER    FILE
    │        │           │           │           │          │          │
Command  ChatAgent  ImageGen  ImageRecog  VoiceGen   WeatherAgent FileAgent
 Agent   (qwen+)    Agent     Agent       Agent     (高德API v2) (Tika+AI)
    │        │      (wan2.5) (qwen-vl+) (cosyvoice)     │          │
    │        │           │      │            │          │    FileRecogService
Command  ChatService ImageGen ChatService  TTSEngine   │     ├─ Tika提取
Registry  ├─chat()  Service  ├─analyzeImage TimbreSession│    ├─ AI分析(重试)
(前缀)    ├─RAG     ├─asyncCall ├─describeEdit            │     ├─ FileRecord持久化
    │     ├─analyzeImage├─长轮询 │       │               │     └─ RAG切片+Embedding
    │     ├─describeEdit│        │   ImageGenService      │
    │     └─analyzeDoc │         │   (wan2.5)             │
    │                  │         │       │                │
    │            ImageContext  ImageCacheManager           │
    │             Manager      (会话隔离+锁)              │
    │                  │                                 │
    └──────────────────┴─────────────────────────────────┘
                             │
                   ctx.sender() 回传结果
                (sendText / sendImage / sendFile)
                             │
                      ChatPersistenceService
                      (消息/会话/音色/图片/文件/天气 入库)
```

---

## 七、类依赖关系总图（v2）

```
ILinkApplication (入口)
  └── AiConfig
        ├── ChatClient ──→ ChatService
        ├── ChatMemory (滑动窗口, 20条)
        ├── MessageChatMemoryAdvisor
        ├── intentChatClient (qwen-turbo) ──→ IntentClassifier
        ├── BotProperties ──→ ImageCacheManager, ImageContextManager,
        │                      FileRecognitionService, WeatherAgent
        └── VoiceProperties ──→ TTSEngine

HttpClientConfig (v2 新增 DashScope SDK 超时注入)
  ├── okHttpClient Bean ──→ ImageGenService.downloadImage()
  └── @PostConstruct configureDashScopeSdkTimeouts()
        └── Constants.connectionConfigurations ← 替换
        └── System.setProperty(DASHSCOPE_*_TIMEOUT)

ILinkBotService (核心入口，实现 MessageSender)
  └── AgentRouter
        ├── IntentClassifier ──→ intentChatClient (qwen-turbo)
        └── agentMap: EnumMap<Intent, Agent>
              ├── CHAT → ChatAgent
              │           ├── ChatService ──→ ChatClient (qwen-plus)
              │           │   ├── chat() / chatWithRAG()
              │           │   ├── analyzeImage() ──→ qwen-vl-plus
              │           │   ├── describeImageEdit() ──→ qwen-vl-plus
              │           │   └── analyzeDocument() ──→ 重试2次 + 截断8K
              │           ├── RAGRetrievalService ──→ VectorStoreService
              │           ├── VoiceGenAgent (VOICE上下文时TTS)
              │           └── ImageCacheManager
              ├── COMMAND → CommandAgent
              │               └── CommandRegistry (9个命令, LinkedHashMap前缀匹配)
              ├── IMAGE_GEN → ImageGenAgent
              │                 ├── ImageGenService ──→ DashScope wan2.5
              │                 │   └── asyncCall + 180s长轮询
              │                 ├── ImageContextManager ──→ TTL缓存
              │                 └── @PreDestroy shutdown()
              ├── IMAGE_EDIT → ImageRecognitionAgent
              │                  ├── ChatService ──→ qwen-vl-plus
              │                  ├── ImageGenService ──→ wan2.5
              │                  ├── ImageCacheManager ──→ 会话隔离+锁
              │                  ├── ImageContextManager
              │                  └── @PreDestroy shutdown() (v2新增)
              ├── TTS → VoiceGenAgent
              │          ├── TTSEngine ──→ DashScope cosyvoice-v1
              │          └── TimbreSession (12音色, 2h TTL)
              ├── WEATHER → WeatherAgent (v2)
              │               ├── 38内置adcode + 高德地理编码API回退
              │               ├── 内存TTL缓存 (now 5min / forecast 30min)
              │               ├── IWeatherQueryService → MySQL weather_query表
              │               └── Java HttpClient → 高德API
              └── FILE → FileAgent
                           └── FileRecognitionService
                                 ├── Tika (MIME检测 + 文本提取)
                                 ├── ChatService ──→ qwen-plus (重试2次)
                                 ├── IFileRecordService → MySQL file_record表
                                 ├── DocumentChunkingService → 智能切片
                                 ├── EmbeddingService → DashScope embedding
                                 ├── VectorStoreService → 内存余弦索引
                                 └── IDocumentChunkService → MySQL document_chunks

── ChatPersistenceService (持久化门面)
     ├── IConversationService / IMessageService / ITimbreChangeService
     ├── IImageRecordService / IFileRecordService / IWeatherQueryService
     ├── IDocumentChunkService / IUserMemoryService
     └── 8 个 ServiceImpl → 8 个 Mapper (MyBatis-Plus BaseMapper)

── BotMetrics (Micrometer指标)
     ├── TTS 成功/失败 + 耗时分布
     ├── 图片生成 成功/失败 + 耗时分布
     ├── 消息处理总数
     └── 待编辑图片缓存实时大小 (Gauge)
```

---

## 八、架构设计亮点（v2 新增）

### 8.1 DashScope SDK 超时注入

Spring AI 的 `spring.ai.dashscope.read-timeout` 无法传导到 DashScope SDK 内建的 OkHttpClient，导致长耗时调用（文档分析、图片生成）实际使用 ~30s 默认超时。`HttpClientConfig.configureDashScopeSdkTimeouts()` 在 `@PostConstruct` 阶段三层注入（系统属性 + 全局 Config 替换 + int 字段），确保所有 DashScope API 调用统一使用 300s read timeout。

### 8.2 图片编辑管线完整性

v1 中 `ImageRecognitionAgent.handleImageEdit()` 是空壳（`return false`），用户上传图片后无法执行编辑。v2 实现了完整的 "多模态理解 + 文生图重绘" 管线：`peek 缓存 → qwen-vl-plus 理解 → wan2.5 生成 → 发送 → 清除缓存`，带 `lock/unlock` 串行化和 `@PreDestroy` 生命周期管理。

### 8.3 天气服务鲁棒性

v2 WeatherAgent 通过高德地理编码 API 动态解析任意城市 → adcode，打破 38 城市限制。TTL 缓存减少重复 API 调用（now 5min / forecast 30min）。每次查询同步写入 `weather_query` 表记录原始请求/响应/耗时。

### 8.4 文档分析可靠性

`ChatService.analyzeDocument()` 内部截断 8K 字符 + 指数退避重试 2 次（2s/4s），`FileRecognitionService` Tika 提取层也截断 8K，双层保护防止 token 爆炸/non-deterministic timeout。

### 8.5 FileRecord 持久化完整性

v1 中 `FileRecord` 从未保存到 DB，`chunk.setFileRecordId(null)` + `NOT NULL` 约束导致 SQL 异常。v2：schema 放宽为 `DEFAULT NULL` + `recognize()` 同步保存 FileRecord → 获取 ID → 传给异步 RAG 切片正确关联。

### 8.6 正则快速路径优化

移除 IMAGE_EDIT_RE 中 4 个高频单字（`改/换/变/加`），消除常见词误判（改变/变化/加油/更加 → 不再路由到 IMAGE_EDIT）。前缀强信号（IMAGE_GEN/IMAGE_EDIT/TTS）优先于关键词信号（WEATHER），防止"画一张天气图"误判。
