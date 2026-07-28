# 微信 iLink 多模态 AI 机器人 — 项目文档

> **更新日期:** 2026-07-29 | **作者:** bbb | **分支:** main | **文件数:** 85+ Java 源文件 + 13 张 DB 表

---

## 一、项目概述

### 1.1 简介

基于 **Spring Boot 3.2 + Java 21** 的微信 iLink 多模态 AI 机器人。通过 `wechat-ilink-sdk` 接入微信客户端，支持 **AI 对话（三级记忆）、图片生成/编辑、语音合成（12 音色）、文件识别（Tika+AI+RAG）、天气查询（高德 API）、成语接龙（O(1) 词典）、路线导航（4 种出行方式）、内存监控（JVM 指标+趋势）、定时提醒（调度+回调）、历史记录查询（DB 会话检索）、全局异常拦截** 等 15 项功能。

## Features

### Agent Engine
- **Dual-loop Agent** — ReAct (Reasoning + Acting) orchestrator with think-act cycles
- **Suspend/Resume** — Agent can pause mid-task waiting for user confirmation, survive service restarts
- **Session State Persistence** — Full conversation context saved to DB on suspend, restored on next message
- **Multi-tool Chain** — Tools can be chained naturally: query -> outline -> confirm -> generate document

### Document Generation
- **Outline Generation** (`createOutline`) — AI generates structured outlines for Word/PPT/Excel, persists to DB
- **User Confirmation Flow** — Outline presented to user for review; Agent waits for confirmation or modifications
- **Document Rendering** (`generateDocument`) — Apache POI renders confirmed outlines into .docx/.pptx/.xlsx files
- **Idempotency** — Same outline+type+version returns cached result, no duplicate generation

### 1.2 技术栈

| 技术 | 说明 |
|------|------|
| Java 21 | 虚拟线程、Record、Switch 表达式、Pattern Matching |
| Spring Boot 3.2.10 | IoC/DI、Actuator、@ConfigurationProperties |
| Spring AI (DashScope) | ChatClient、ChatMemory（滑动窗口 20 条）、MessageChatMemoryAdvisor |
| MyBatis-Plus 3.5.7 | ORM、LambdaQueryWrapper、BaseMapper |
| MySQL 8.x | 11 张表、utf8mb4 |
| AI 模型 | qwen-turbo（意图分类）、qwen-plus（对话/文档）、qwen-vl-plus（多模态）、wan2.5-t2i-preview（文生图）、cosyvoice-v1（TTS） |
| 高德开放平台 | 天气 API + 路线规划 API + 地理编码 API + 实时路况 API |
| wechat-ilink-sdk 2.3.3 | 微信消息收发、图片/文件 CDN 下载 |
| Apache Tika 2.9.4 | MIME 检测 + 文本提取 |
| Gson | JSON 序列化/反序列化 |
| OkHttp 4.x | HTTP 连接池 |
| Micrometer + Actuator | /actuator/metrics, /actuator/health |

### 1.3 整体架构

```
微信消息到达
      │
      ▼
ILinkBotService.handleMessage(WeixinMessage)
  ├─ VoiceItem → ASR 提取文本 → RouteContext.VOICE
  ├─ TextItem  → RouteContext.TEXT
  ├─ ImageItem → CDN 下载 → imageBytes
  └─ FileItem  → 大小校验 → CDN 下载 → fileBytes
      │
      ▼
AgentRouter.route(ctx)                           ← 核心路由
  ├─ 成语接龙拦截: isUserInGame + 非/文本 → IdiomGameService
  │
  ├─ IntentClassifier.classify(ctx)              ← 意图分类 (v2.3)
  │   ├─ 确定性规则: /→COMMAND | 图片→IMAGE_EDIT | 文件→FILE (零延迟)
  │   └─ LLM: qwen-turbo 单次 JSON 调用 → CHAT/IMAGE_GEN/WEATHER/TTS/... (~150ms)
  │
  └─ agentMap.get(intent).execute(ctx)           ← O(1) Map 路由
      └─ try-catch → GlobalExceptionHandler
      │
  ┌───┼──────────┬──────────┬──────────┬──────────┬──────────┐
  ▼   ▼          ▼          ▼          ▼          ▼          ▼
Chat  Command  Weather  Image Gen/ Voice Gen File    Idiom Game
Agent Agent    Agent    Edit Agent Agent    Agent   Service
  │
  ├─ ChatMemory (20条滑动窗口)                   ← 短期记忆
  ├─ message 表 DB 历史注入 prompt               ← 会话记忆
  └─ user_memory 表检索                          ← 长期记忆
```

### 1.4 核心功能一览

| # | 功能 | 组件 | 触发方式 |
|---|------|------|---------|
| 1 | 意图分类 | IntentClassifier v2.3 | 每条消息自动触发 |
| 2 | AI 多轮对话（三级记忆） | ChatAgent + ChatService | 自然语言 / VOICE 上下文 |
| 3 | 命令系统 | CommandAgent + CommandRegistry | `/` 前缀（17 条命令） |
| 4 | 文生图 | ImageGenAgent + ImageGenService | 自然语言 / `/draw` |
| 5 | 图片识别 & 编辑 | ImageRecognitionAgent | 上传图片 / 编辑指令 |
| 6 | 语音合成 | VoiceGenAgent + TTSEngine | 自然语言 / `/tts` |
| 7 | 音色切换 | VoiceGenAgent + TimbreSession | 自然语言 / `/voice` |
| 8 | 天气查询 | WeatherAgent v2.1 | 自然语言 / `/weather` |
| 9 | 文件识别 | FileAgent + FileRecognitionService | 上传文件 |
| 10 | 成语接龙 | IdiomGameService | `/cy start/stop/ls` |
| 11 | 历史记录查询 | HistoryService | `/history [id/all]` |
| 12 | 内存监控 | MemoryMonitorTools | `/memory [diff/simple/history]` |
| 13 | 路线导航 | NavigationTools | `/nav 从A到B [方式]` |
| 14 | 路况查询 | NavigationTools | `/traffic 道路名` |
| 15 | 定时提醒 | ReminderTools | `/remind 时间 内容` |
| — | 全局异常拦截 | GlobalExceptionHandler | Agent 未捕获异常 |

---

## 二、项目结构

```
src/main/java/log/demo/linkDemo/
├── ILinkApplication.java                    # @SpringBootApplication 入口
│
├── config/                                   # 配置层
│   ├── AiConfig.java                         # ChatClient + intentChatClient + ChatMemory(20条)
│   ├── BotProperties.java                    # @ConfigurationProperties("bot")
│   ├── HttpClientConfig.java                 # OkHttp Bean + DashScope SDK 超时三层注入
│   └── VoiceProperties.java                 # TTS 配置
│
├── entity/                                   # 数据库实体 (11 个)
│   ├── Conversation.java                     # 会话: id/userId/status/messageCount/title
│   ├── Message.java                          # 消息核心表: 30+ 字段
│   ├── WeatherQuery.java                     # 天气查询记录
│   ├── IdiomGameRecord.java                  # 成语接龙积分
│   ├── FileRecord.java                       # 文件识别记录
│   ├── ImageRecord.java                      # 图片上下文
│   ├── TimbreChange.java                     # 音色切换审计
│   ├── DocumentChunk.java                    # RAG 切片 + embedding JSON
│   ├── UserMemory.java                       # 用户长期记忆 + embedding JSON
│   └── VoiceResult.java                      # TTS 结果 (非 DB record)
│
├── enums/                                    # 枚举
│   ├── RouteContext.java                     # TEXT / VOICE
│   └── Timbre.java                          # 12 种音色定义 + 匹配逻辑
│
├── exception/                                # 异常体系
│   ├── BotException.java                     # 基类
│   ├── AIServiceException.java               # AI 调用 (operation: chat/analyzeImage/...)
│   ├── ImageGenerationException.java         # 图片生成 (stage: generate/download/...)
│   ├── VoiceSynthesisException.java          # 语音合成
│   ├── FileRecognitionException.java         # 文件识别 (stage: detect/extract/analyze)
│   ├── ConfigurationException.java           # 配置错误 (configKey)
│   └── GlobalExceptionHandler.java           # ★ 全局异常拦截器
│
├── mapper/                                   # MyBatis-Plus Mapper (9 个)
├── rag/                                      # RAG 引擎
│   ├── DocumentChunkingService.java          # 智能切片 (段落/句子边界)
│   ├── EmbeddingService.java                 # DashScope text-embedding API
│   ├── VectorStoreService.java               # 内存余弦相似度 TopK
│   ├── RAGRetrievalService.java              # 检索入口 + 相关性判断
│   └── RAGContextAugmenter.java              # RAG prompt 构建
│
├── service/                                  # 业务服务层
│   ├── ILinkBotService.java                  # ★ 核心入口 + MessageSender 实现
│   ├── MessageSender.java                    # 发送抽象 (sendText/sendImage/sendFile)
│   ├── ChatPersistenceService.java           # 持久化门面 (8 Service 聚合)
│   ├── IConversationService.java             # 含 getRecentByUser/getAllByUser/getByConvId
│   ├── IMessageService.java                  # 含 getMessages/countMessages
│   └── impl/                                 # 9 个 ServiceImpl
│
└── tools/                                    # ★ 智能体工具层 (核心)
    ├── Agent.java                            # Agent 接口
    ├── AgentContext.java                     # 调用上下文
    ├── AgentRouter.java                      # O(1) 路由 + 成语拦截 + 异常捕获
    ├── Intent.java                           # 8 种意图枚举
    ├── IntentClassifier.java                 # v2.3: 确定性规则 + 单次 LLM JSON 分类
    ├── BotMetrics.java                       # Micrometer 指标
    ├── TextTool.java                         # 文本工具
    ├── chat/    ChatAgent.java + ChatService.java
    ├── command/ CommandAgent.java + Command.java + CommandRegistry.java (17条)
    ├── voice/   VoiceGenAgent.java + TTSEngine.java + TimbreSession.java + AudioTranscoder.java
    ├── weather/ WeatherAgent.java (v2.1: 高德 API + NPE 安全 + TTL + DB)
    ├── image/   ImageGenAgent.java + ImageRecognitionAgent.java + ImageGenService.java
    │            + ImageCacheManager.java + ImageContextManager.java
    ├── file/    FileAgent.java + FileRecognitionService.java
    ├── idiom/   IdiomDictionary.java (~590成语 O(1)) + GameSession.java + IdiomGameService.java
    ├── history/ HistoryService.java (DB 会话+消息查询, 1900字截断)
    ├── memoryMonitor/ MemoryMonitorTools.java (JVM 指标+历史趋势+告警)
    ├── navigation/ NavigationTools.java (高德 API + 6 内嵌模型)
    └── reminder/ ReminderTools.java (解析+存储+ScheduledExecutor+回调)

src/main/resources/
├── application.yml                           # 主配置
├── schema.sql                                # 11 张表 DDL
├── config/  ai.yml / bot.yml / security.yml
└── prompts/  system.txt / file-system.txt
```

---

## 三、功能详解 & 测试案例

### 3.1 消息入口与路由

**实现：** `ILinkBotService` 接收微信回调 → 按消息类型分发 → 构建 `AgentContext` → `AgentRouter.route()`

```
handleMessage(WeixinMessage)
  ├─ VoiceItem  → ASR → RouteContext.VOICE (不回显, 直接路由)
  ├─ TextItem   → 去重 → RouteContext.TEXT
  ├─ ImageItem  → CDN下载(重试2次) → imageBytes
  └─ FileItem   → 大小校验(>maxSizeMb拒绝) → CDN下载 → fileBytes+fileName
```

**AgentRouter.route():**
1. 成语接龙拦截：`isUserInGame && !text.startsWith("/")` → `IdiomGameService.handleInput()`
2. 意图分类：`IntentClassifier.classify(ctx)` → `Intent`
3. O(1) Map 查找：`agentMap.get(intent)`
4. 执行 + 异常捕获：`try { agent.execute(ctx) } catch → GlobalExceptionHandler`

**测试案例：** 用户发送文本 `"你好"` → ILinkBotService 构建 AgentContext(routeContext=TEXT) → AgentRouter 路由 → IntentClassifier 返回 CHAT → ChatAgent 回复

---

### 3.2 意图分类 (IntentClassifier v2.3)

**实现：** 确定性规则（零延迟）→ 单次 qwen-turbo JSON 调用（~150ms）→ 降级 CHAT

```
classify(ctx)
  ├─ / 开头      → COMMAND       (零延迟)
  ├─ hasFile()   → FILE          (零延迟)
  ├─ hasImage()  → IMAGE_EDIT    (零延迟)
  ├─ text 为空   → CHAT          (零延迟)
  └─ llmClassify(text)
       └─ intentChatClient(qwen-turbo)
            .system("分类用户消息，仅输出JSON: {\"intent\":\"类型\"}...")
            .user(text).call().content()
            ├─ parseJson() → {"intent":"CHAT"} → Intent.CHAT
            ├─ JSON失败 → 同响应提取关键词 → Intent
            └─ 全部失败 → CHAT
```

**8 种意图：** COMMAND / CHAT / IMAGE_GEN / IMAGE_EDIT / TTS / VOICE_SWITCH / WEATHER / FILE

**测试案例：**

| 输入 | 预期分类 | 原因 |
|------|---------|------|
| `/draw 猫` | COMMAND | `/` 开头确定性规则 |
| `画一只猫` | IMAGE_GEN | qwen-turbo 识别为图片生成 |
| `今天天气怎么样` | WEATHER | qwen-turbo 识别为天气查询 |
| `帮我写一段Java代码` | CHAT | qwen-turbo 识别为通用对话 |
| `用语音朗读` | TTS | qwen-turbo 识别为语音合成 |
| LLM 超时 | CHAT | 异常降级 |

---

### 3.3 AI 多轮对话 + 三级记忆 (ChatAgent)

**实现：** 三级记忆体系 — ChatMemory(20条滑动窗口) + message 表 DB 历史注入 + user_memory 长期记忆检索

```
ChatAgent.execute(ctx)
  ├─ buildAugmentedMessage(userId, text)
  │   ├─ loadDbHistory(userId)
  │   │   └─ getActiveConversation → getMessages → 最近 6 条
  │   │   └─ 格式化为 "[对话历史]\n用户：...\n助手：...\n"
  │   └─ loadLongTermMemory(userId)
  │       └─ userMemoryService.lambdaQuery()
  │           .eq(userId).orderByDesc(importance).last("LIMIT 5")
  │       └─ 格式化为 "[用户已知信息]\n- ...\n"
  │
  ├─ LLM 调用
  │   ├─ RAG 检索 → hasRelevant → chatWithRAG(augmentedText)
  │   └─ else → chat(userId, augmentedText)
  │       └─ ChatClient(qwen-plus)
  │           .system(chatSystemPrompt)
  │           .advisors(MessageChatMemoryAdvisor, userId)  ← 20条窗口
  │           .call()
  │
  ├─ 异步 → saveLongTermMemory()  (长度>30 的回复保存到 user_memory)
  │
  └─ VOICE → ttsAndSend() (仅语音) | TEXT → sendText() (仅文本)
```

| 记忆层 | 存储 | 容量 | 生命周期 |
|--------|------|------|---------|
| 短期 | ChatMemory (内存) | 20条 | 重启丢失 |
| 会话 | message 表 (DB) | 永久 | 跨重启恢复 |
| 长期 | user_memory 表 (DB) | 永久 | 跨会话检索 |

**测试案例：**
1. 用户 `"我叫小明，喜欢咖啡"` → Bot 回复 → user_memory 异步保存 "我叫小明"、"喜欢咖啡"
2. 第二天用户 `"推荐饮料"` → loadLongTermMemory 检索到 "喜欢咖啡" → Bot `"小明，推荐拿铁！"`

---

### 3.4 命令系统 (CommandAgent + CommandRegistry)

**实现：** `LinkedHashMap<String, Command>` 前缀匹配，注册顺序=优先级

**17 条命令：**

| 命令 | 处理器 | 功能 |
|------|--------|------|
| `/help` | handleHelp | 帮助列表 |
| `/status` | handleStatus | 连接状态+音色 |
| `/clear` | handleClear | 清除记忆+缓存+关闭会话 |
| `/draw <描述>` | handleDraw | 文生图（异步虚拟线程） |
| `/tts <文本>` | handleTts | 文字转语音 |
| `/weather <城市>` | handleWeather | 天气查询 |
| `/voice list` | handleVoice | 12 种音色列表 |
| `/voice <编号>` | handleVoice | 切换音色 |
| `/cancel` | handleCancel | 取消图片编辑 |
| `/cy start [成语]` | handleIdiomGame | 成语接龙 |
| `/cy stop` | handleIdiomGame | 结束接龙 |
| `/cy ls` | handleIdiomGame | 接龙积分 |
| `/memory [diff/simple/history]` | handleMemory | 内存监控 |
| `/nav 从<A>到<B> [方式]` | handleNav | 路线规划 |
| `/traffic <道路>` | handleTraffic | 实时路况 |
| `/remind <时间> <内容>` | handleRemind | 定时提醒 |
| `/history [id/all]` | handleHistory | 历史会话查询 |

**测试案例：**
- `/help` → 返回完整命令列表
- `/voice 3` → 切换到第 3 种音色 → 返回 `✅ 已切换到音色：龙小秋`
- `/clear` → ChatService.clearHistory + ImageCacheManager.removeSilently + closeConversation

---

### 3.5 文生图 (ImageGenAgent)

**实现：** DashScope wan2.5-t2i-preview 异步调用 + 180s 长轮询 + 参考图迭代

**调用链：**

```
ImageGenAgent.execute(ctx)
  └─ executor.submit(doImageGen)          ← 虚拟线程异步
      ├─ imageContextManager.getLastRefImage(userId)    → CDN URL
      ├─ imageGenService.generateImageUrl(prompt, refUrl)
      │   └─ ImageSynthesis.asyncCall(param) → taskId
      │   └─ for(i=0; i<90; i++) sleep(2s)  ← 180s 长轮询
      │       ├─ fetch(taskId) → "SUCCEEDED" → CDN URL
      │       └─ "FAILED" / 超时 → null
      ├─ refUrl 失败 → 回退 generateImageUrl(prompt, null)
      ├─ imageGenService.downloadImage(url)              → byte[]
      ├─ imageContextManager.save(userId, url, bytes)     ← 供下次迭代
      └─ sender.sendImage(userId, bytes, "ai-gen.png", prompt)
```

**测试案例：**
1. `"画一只柴犬"` → 无参考图 → wan2.5 生成 → 发送图片 → 缓存 URL
2. `"给柴犬戴上帽子"` → getLastRefImage 获取上次 URL → wan2.5(refImage) 迭代生成 → 发送
3. 参考图过期 → refImage 生成失败 → 自动回退纯文本生成

---

### 3.6 图片识别 & 编辑 (ImageRecognitionAgent)

**实现：** qwen-vl-plus 多模态理解 + wan2.5 重绘；双阶段：上传识别 → 编辑指令执行

**阶段 1 — 上传识别：**

```
ctx.hasImage()=true → IMAGE_EDIT → handleImageUpload(ctx)
  ├─ chatService.analyzeImage(bytes)      ← qwen-vl-plus
  │   └─ prompt("请详细描述这张图片...").media(image).call()
  ├─ imageCacheManager.put(userId, bytes)  ← 会话隔离缓存
  └─ sender.sendText("【图片描述】\n" + desc + "\n💡 你可以编辑...")
```

**阶段 2 — 编辑：**

```
"把图片改成黑白风格" → IMAGE_EDIT → doImageEdit(ctx)
  ├─ imageCacheManager.lock(userId)            ← 串行化
  ├─ imageCacheManager.peek(userId)            ← peek 不删除
  ├─ chatService.describeImageEdit(原图, 指令)  ← qwen-vl-plus
  │   └─ 原图+指令 → 文生图详细画面描述
  ├─ imageGenService.generateImageUrl(editPrompt) ← wan2.5
  ├─ download → imageContextManager.save()
  ├─ imageCacheManager.removeSilently()        ← 清除待编辑
  └─ sender.sendImage() → finally: unlock()
```

**测试案例：**
1. 上传海滩照片 → `"【图片描述】夕阳海滩，棕榈树..."` + 编辑提示
2. `"把图片改成黑白风格"` → lock → describeImageEdit → wan2.5 重绘 → 发送黑白照片 → unlock
3. 无图片时说 `"改图"` → IMAGE_EDIT_RE 不匹配 → LLM 分类为 CHAT（不会误判）

---

### 3.7 语音合成 (VoiceGenAgent)

**实现：** DashScope cosyvoice-v1 + PCM→WAV 转码 + TimbreSession 用户音色管理

**调用链：**

```
══════ TTS ══════
"用语音朗读你好世界" → TTS → VoiceGenAgent.execute(ctx)
  ├─ TextTool.extractTtsText(text)         → "你好世界"
  ├─ TimbreSession.getCurrentVoiceId(userId) → "longxiaochun"
  ├─ TTSEngine.synthesize(text, voiceId)
  │   └─ SpeechSynthesizer.call(cosyvoice-v1) → PCM byte[]
  ├─ AudioTranscoder.pcmToWav(pcm, 16000, 16, 1) → WAV byte[]
  └─ sender.sendFile(userId, wav, "tts.wav", text)

══════ 音色切换 ══════
"换萝莉音" → VOICE_SWITCH → VoiceGenAgent
  ├─ Timbre.matchKeyword("萝莉") → LONG_XIAO_XIA
  ├─ TimbreSession.switchTo(userId, timbre)
  └─ saveTimbreChange(...) → timbre_change 表
```

**测试案例：**
1. `"用语音朗读你好世界"` → extractTtsText="你好世界" → TTS 合成 → 发送 WAV 文件
2. `"换萝莉音"` → matchKeyword → 切换到龙小夏 → `✅ 已切换`
3. 再发 `"用语音朗读测试"` → 用龙小夏音色朗读

---

### 3.8 天气查询 (WeatherAgent v2.1)

**实现：** 高德天气 API + 38 内置城市 adcode + 地理编码 API 动态解析 + TTL 缓存 + NPE 安全

**调用链：**

```
"北京天气" → WEATHER → WeatherAgent.execute(ctx)
  ├─ extractCity(text) → "北京"
  │   ├─ CITY_WEATHER_PATTERN 正则
  │   ├─ LinkedHashMap 遍历 38 内置城市 (长名优先)
  │   └─ 回退模糊匹配
  │
  ├─ generateReport(userId, text)
  │   ├─ "明天" → buildForecastReport(dayOffset=1)
  │   ├─ "预报" → buildMultiDayReport()
  │   └─ 默认 → buildNowReport()
  │       ├─ fetchNow(city)
  │       │   ├─ TTL 缓存检查 (5min)
  │       │   ├─ resolveAdcode(city)
  │       │   │   ├─ 38内置 → adcode
  │       │   │   ├─ geocodeCache 命中
  │       │   │   └─ 高德地理编码 API → adcode (缓存)
  │       │   └─ GET /v3/weather/weatherInfo → JSON
  │       │       └─ safeString()/safeDouble() ← 防 NPE
  │       └─ saveQuery() → weather_query 表
  └─ sender.sendText(report)
```

**NPE 安全：** `safeString(obj, key, default)` / `safeDouble(obj, key, default)` 封装所有 JSON 字段访问

**测试案例：**
1. `"北京天气"` → extractCity="北京" → 内置 adcode "110000" → 天气 API → 报告
2. `"三亚天气"` → 非 38 内置 → 地理编码 API → adcode="460200" → 缓存 → 天气 API
3. `"深圳明天天气"` → extractCity="深圳" + "明天" → buildForecastReport(dayOffset=1)
4. API 返回异常 JSON → safeString 返回默认值 → 不 NPE

---

### 3.9 文件识别 (FileAgent)

**实现：** Tika MIME 检测 + 文本提取(截断 8K) + qwen-plus AI 分析(重试 2 次) + RAG 切片+Embedding

**调用链：**

```
ctx.hasFile() → FILE → FileAgent.execute(ctx)
  └─ executor.submit(doFileRecognition)
      └─ FileRecognitionService.recognize(userId, bytes, fileName)
          ├─ Tika.detect() → MIME 类型
          ├─ 图片? → analyzeImage() (qwen-vl-plus)
          └─ 文档? → Tika.parseToString() → 截断 8K
          ├─ chatService.analyzeDocument() (重试2次, 2s/4s 指数退避)
          ├─ FileRecord 持久化 → fileRecordId
          ├─ 异步 RAG:
          │   ├─ DocumentChunkingService.chunk(text)
          │   ├─ EmbeddingService.embedBatch(texts)
          │   └─ VectorStoreService.storeDocumentChunk(chunks)
          └─ sender.sendText(formattedReport)
```

**测试案例：**
1. 上传 `Day3.docx` → Tika 检测为 Word → 提取文本 → AI 分析 → 回复分析结果 → 后台 RAG 切片
2. 上传 `photo.jpg` → Tika 检测为图片 → qwen-vl-plus 多模态分析
3. 上传 `big.pdf` (25MB > 20MB) → 大小校验拒绝

---

### 3.10 成语接龙 (IdiomGameService)

**实现：** O(1) 词典 (Map<Character, List<String>> + Set<String>) + 独立会话 (ConcurrentHashMap) + 超时清理 (ScheduledExecutorService) + 积分 DB

**调用链：**

```
══════ 开始 ══════
/cy start → COMMAND → handleIdiomGame()
  → startGame(userId, null)
    ├─ dictionary.randomIdiom() → "虎虎生威"
    └─ new GameSession(userId, "虎虎生威") → sessions.put()

══════ 接龙 (AgentRouter 拦截) ══════
"威风凛凛" (游戏中非/文本)
  → AgentRouter.isUserInGame=true → handleInput()
    ├─ dictionary.isValid("威风凛凛") → O(1) Set.contains ✓
    ├─ 首字'威'=='威' ✓  |  未重复 ✓
    ├─ session.recordSuccess() → score++
    ├─ dictionary.findChain('凛', used)
    │   └─ Map.get('凛') → O(1) → Fisher-Yates 随机 → "凛然正气"
    └─ session.recordAiMove("凛然正气")

══════ AI 无法接龙 ══════
findChain(lastChar, used) → Optional.empty()
  → addBonus(2) → saveRecord("USER_WIN") → remove session

══════ 超时 ══════
cleanupExecutor(每2min) → isExpired(5min) → saveRecord("TIMEOUT") → remove
```

**测试案例：**
1. `/cy start` → 随机成语 → 用户接龙 → AI 接龙 → 循环
2. `/cy start 龙飞凤舞` → 指定起始
3. 接龙 `"威风凛凛"` → 成功 → `"凛然正气"` → 积分+1
4. 重复说 `"威风凛凛"` → `"已经用过了，换一个吧！"`
5. 说非四字 `"你好"` → `"请输入一个四字成语"`
6. `/cy stop` → 保存积分 → idiom_game_record 表
7. `/cy ls` → 最近 3 局积分
8. 5 分钟无操作 → 自动结束 → saveRecord("TIMEOUT")

---

### 3.11 历史记录查询 (HistoryService)

**实现：** 基于 conversation + message 表查询，1900 字智能截断适配微信限制

**调用链：**

```
/history → handleHistory()
  ├─ arg="" → recentHistory(userId)
  │   └─ getRecentByUser(userId, 3) → 格式化摘要
  │
  ├─ arg="all" → allHistory(userId)
  │   └─ getAllByUser(userId) → 1900 字截断
  │
  └─ arg="15" → conversationDetail(userId, 15)
      └─ getByConvId(15) → getMessages(15)
          └─ 逐条 USER/BOT 格式化 → 1900 字截断
```

**测试案例：**
1. `/history` → 返回最近 3 个会话摘要（编号、状态、消息数、标题）
2. `/history 15` → 返回 #15 会话的完整对话（👤/🤖 逐条显示）
3. `/history all` → 全部会话列表（自动截断）
4. 无历史 → `"暂无历史会话记录，发送一条消息开始对话吧！"`
5. 查询他人会话 → `"未找到会话 #XX，或该会话不属于你"`

---

### 3.12 内存监控 (MemoryMonitorTools)

**实现：** `java.lang.management` API 采集 JVM/OS 指标 + Unicode 块字符进度条 + 4 级告警 + 历史趋势

**调用链：**

```
/memory → handleMemory()
  ├─ collectSnapshot()
  │   ├─ MemoryMXBean → heap used/max/committed + nonHeap
  │   ├─ OperatingSystemMXBean → totalPhysical/freePhysical/Swap/CPU
  │   ├─ ThreadMXBean → threadCount
  │   └─ GarbageCollectorMXBean → gcCount/gcTimeMs
  ├─ recordSnapshot() → ConcurrentLinkedDeque (max 20)
  ├─ generateReport() → 多行图形化报告
  ├─ generateSimpleReport() → 单行简版
  ├─ generateReport(prev, curr) → 对比报告
  └─ generateHistoryReport() → 最近 10 次表格 + 趋势箭头
```

**测试案例：**
1. `/memory` → 完整报告：堆内存进度条 + 系统内存 + CPU/GC/线程 + 告警
2. `/memory diff` → 与上次快照对比 → 堆增长+GC频率+线程变化
3. `/memory simple` → `🖥 堆：[████▓░░░] 72.5% | CPU：12.5% | 线程：156`
4. `/memory history` → 最近 10 次采样表格 + `📈 堆内存增长 +120.5 MB`

---

### 3.13 路线导航 (NavigationTools)

**实现：** 高德 API 地理编码 + 4 种出行方式路线规划 + 实时路况查询 + 6 内嵌模型

**调用链：**

```
/nav 从北京西站到天安门 步行 → handleNav()
  ├─ 解析 "从(.+?)到(.+)" → origin/dest
  ├─ 后缀 "步行" → TravelMode.WALKING
  └─ Thread.startVirtualThread()
      ├─ NavigationTools.planRoute(apiKey, origin, dest, WALKING)
      │   ├─ geocode("北京西站") → "116.322,39.895"
      │   ├─ geocode("天安门") → "116.397,39.909"
      │   └─ GET /v3/direction/walking → JSON → RouteResult
      └─ buildTextReport(result, mode, origin, dest)
          ├─ distanceFormatted() / durationFormatted()
          └─ 前 6 步导航指令
```

**内嵌模型：** TravelMode(enum) + RouteResult + RouteStep + TrafficCondition(CongestionLevel 5级+emoji) + TransitSegment + TransitPlan

**测试案例：**
1. `/nav 从北京西站到天安门` → 驾车路线（默认）：距离、耗时、导航步骤
2. `/nav 从北京西站到天安门 步行` → 步行路线
3. `/nav 从北京西站到天安门 公交` → 公交/地铁换乘方案
4. `/traffic 中关村南大街` → 实时路况 🟡缓行(25km/h)

---

### 3.14 定时提醒 (ReminderTools)

**实现：** 时间正则解析（6 种格式）+ ConcurrentHashMap 内存存储 + ScheduledExecutorService 1s 检查 + MessageSender 回调

**调用链：**

```
/remind 30分钟后 开会 → handleRemind()
  ├─ parseTime("30分钟后 开会")
  │   └─ MINUTES_PAT.matcher() → 30分钟 → ParsedTime(now+30min, "开会")
  ├─ createReminder(userId, triggerTime, "开会")
  │   ├─ ensureStarted() → ScheduledExecutorService(1s)
  │   └─ reminders.computeIfAbsent(userId).add(task)
  └─ "✅ 提醒已设置！..."

══════ 触发 ══════
checkDueReminders() (每1秒)
  ├─ task.isDue() → sender.sendText("⏰ 提醒时间到！\n──── 开会 ────")
  └─ tasks.remove(task)
```

**支持格式：** `X分钟后/小时后/秒后` | `HH:mm` | `明天 HH:mm` | `M月d日 HH:mm`

**测试案例：**
1. `/remind 30分钟后 开会` → `✅ 提醒已设置` → 30分钟后 → `⏰ 提醒时间到！`
2. `/remind 明天 08:00 起床` → 明天 8:00 触发
3. `/remind list` → 所有进行中提醒
4. `/remind cancel 2` → 取消 ID=2 的提醒
5. `/remind cancel all` → 取消全部

---

### 3.15 全局异常拦截 (GlobalExceptionHandler)

**实现：** AgentRouter try-catch 统一捕获 → 按异常类型分类 → 构建友好中文响应

```
AgentRouter.route(ctx)
  └─ try { agent.execute(ctx) }
     catch (Exception e) {
       exceptionHandler.handle(userId, sender, agent.name(), e)
         ├─ classify(e) → BotException? 子类? 未知?
         │   → ErrorInfo(type, detail, severity)
         ├─ buildErrorMessage → 含时间戳+追踪ID
         └─ sender.sendText(formattedError)
     }
```

**测试案例：**
1. AI 服务超时 → `【AI 对话失败】错误类型：AI 对话失败 详情：... 追踪ID：err-xxxx`
2. 图片生成 API 异常 → `【图片生成失败】错误类型：图片生成失败 追踪ID：err-xxxx`
3. 未知 NPE → `【系统错误】系统内部异常，请稍后重试或联系管理员`

---

## 四、配置体系

| 文件 | 命名空间 | 关键项 |
|------|---------|--------|
| `application.yml` | server/spring.datasource/mybatis-plus/management | 端口 8080, MySQL wxbot_db, 自动建表 |
| `config/ai.yml` | spring.ai.dashscope | qwen-plus/qwen-turbo/qwen-vl-plus/wan2.5/cosyvoice-v1, 超时 300s |
| `config/bot.yml` | bot.* | 图片编辑 prompt, 缓存 TTL, 文件大小上限 20MB, 天气 API Key |
| `config/security.yml` | spring.ai.dashscope.api-key | DashScope API Key (gitignore) |
| `prompts/system.txt` | 外部文件 | AI 对话系统提示词 |
| `prompts/file-system.txt` | 外部文件 | 文档分析系统提示词 |

```yaml
# 关键配置
spring.ai.dashscope.chat.options.model:        qwen-plus
spring.ai.dashscope.image.options.model:       wan2.5-t2i-preview
spring.ai.dashscope.voice.tts.model:           cosyvoice-v1
bot.cache.pending-image-ttl-minutes:  5
bot.cache.max-pending-images:         50
bot.file.max-size-mb:                 20
bot.weather.base-url: https://restapi.amap.com/v3/weather/weatherInfo
```

---

## 五、数据库设计

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
