# 微信 iLink 多模态 AI 机器人 — 项目文档

> **更新日期:** 2026-07-24
> **作者:** bbb
> **分支:** main

---

## 一、项目概述

### 1.1 项目简介

本项目是一个基于 **Spring Boot 3.2 + Java 21** 的微信 iLink 多模态 AI 机器人。
通过接入微信 iLink SDK，实现在微信客户端内与 AI 进行多轮对话、图片生成/编辑、语音合成（TTS）、文件内容识别分析、天气查询、成语接龙游戏等功能。

### 1.2 架构概览

项目采用 **Route Workflow Agent 架构**：消息到达后先经 AgentRouter 成语接龙拦截，再由 **IntentClassifier**（v2.1：LLM 默认 + 严格正则短路 + 黑名单校验三层策略）进行意图分类，**AgentRouter**（O(1) Map 查找）直接路由到对应 Agent 执行。未处理异常由 **GlobalExceptionHandler** 统一捕获格式化。

```
微信消息 → AgentContext 构建
              │
              ▼
       AgentRouter.route()
              │
              ├─ 成语接龙拦截（游戏中非命令文本 → IdiomGameService）
              ▼
       IntentClassifier (LLM默认 + 严格正则短路 + 黑名单校验)
              │
              ▼
       AgentRouter O(1) Map 路由
              │
              ├─ try-catch → GlobalExceptionHandler 统一错误格式
              │
    ┌─────────┼──────────┬──────────┬──────────┬──────────┬──────────┐
    ▼         ▼          ▼          ▼          ▼          ▼          ▼
ChatAgent CommandAgent WeatherAgent ImageGen  FileAgent  VoiceGen  IdiomGame
(qwen+)   (/命令+cy)   (高德v2)   Agent      (Tika+AI) Agent    Service
    │         │           │       (wan2.5)      │       (cosyvoice)  │
    │         │      ┌────┴────┐     │    FileRecogService   │   O(1)词典
    │         │      │地理编码   │ ImageRecogAgent │         │  游戏会话
    │         │      │TTL缓存   │ (qwen-vl+)      │         │  积分DB
    │         │      │NPE安全   │     │     RAG切片+Embedding │
    └─────────┴──────┴──────────┴─────┴──────────┴──────────┴──────────┘
```

### 1.3 技术栈

| 技术 | 版本 / 说明 |
|------|-----------|
| **Java** | 21 |
| **Spring Boot** | 3.2.10 |
| **Spring AI** | Alibaba DashScope Starter 1.0.0.1 |
| **AI 模型平台** | 阿里云 DashScope（通义千问 / 万象 / CosyVoice / Paraformer） |
| **意图分类** | qwen-turbo（v2.1：LLM 默认 + 严格正则短路 + 黑名单 + 图片上下文校验） |
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
| **意图分类** | IntentClassifier | qwen-turbo + 严格正则 | v2.1：LLM 默认 + 黑名单 + IMAGE_EDIT 上下文校验 |
| **AI 多轮对话** | ChatAgent | qwen-plus | 滑动窗口记忆 + RAG 增强 |
| **文生图** | ImageGenAgent | wan2.5-t2i-preview | 文本描述 → AI 生成图片，支持参考图迭代 |
| **图片识别&编辑** | ImageRecognitionAgent | qwen-vl-plus + wan2.5 | 上传图识别 → 编辑指令 → 多模态理解 → 重绘 |
| **语音合成** | VoiceGenAgent | cosyvoice-v1 | 文本 → TTS → WAV 音频，12 种音色 |
| **天气查询** | WeatherAgent v2.1 | 高德 API | 地理编码回退 + TTL 缓存 + DB 持久化 + NPE 安全 |
| **文件识别** | FileAgent | Tika + qwen-plus | 文本提取 → AI 分析 → RAG 切片 + Embedding |
| **命令系统** | CommandAgent | / 前缀路由 | /cy 成语接龙等命令 |
| **成语接龙** | IdiomGameService | 内置 ~590 成语 | O(1) 词典查找 + 独立会话 + 积分 DB + 超时清理 |
| **全局异常拦截** | GlobalExceptionHandler | — | 统一错误格式 + 追踪 ID + 异常分类 |
| **会话持久化** | — | MySQL | 全部消息/会话/音色/天气/文件/游戏记录入库 |

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
├── tools/                            # ★ Agent 智能体层
│   ├── Intent.java                   # 意图枚举（8 种）
│   ├── Agent.java                    # Agent 接口
│   ├── AgentContext.java             # Agent 调用上下文
│   ├── IntentClassifier.java         # v2.1：LLM 默认 + 严格正则短路 + 黑名单 + 校验
│   ├── AgentRouter.java              # O(1) Map 路由器 + 成语接龙拦截 + 异常捕获
│   ├── BotMetrics.java               # Micrometer 指标注册
│   ├── TextTool.java                 # 文本工具类（截断/TTS提取/音色剥离）
│   ├── chat/                         # 对话智能体
│   │   ├── ChatAgent.java            # 通用对话 Agent（qwen-plus + RAG + VOICE TTS）
│   │   └── ChatService.java          # AI 对话 + 多模态 + 文档分析（带重试）
│   ├── command/                      # 命令智能体
│   │   ├── CommandAgent.java         # 命令分发 Agent
│   │   ├── Command.java              # 命令函数式接口
│   │   └── CommandRegistry.java      # 命令注册表（LinkedHashMap 前缀匹配 + /cy 子命令）
│   ├── voice/                        # 语音智能体
│   │   ├── VoiceGenAgent.java        # TTS + 音色切换 Agent
│   │   ├── TTSEngine.java            # DashScope CosyVoice TTS API 封装
│   │   ├── TimbreSession.java        # 用户音色会话管理器
│   │   └── AudioTranscoder.java      # PCM → WAV 转码 + 时长计算
│   ├── weather/                      # 天气智能体
│   │   └── WeatherAgent.java         # 高德天气 API Agent（v2.1：NPE 安全 + 地理编码 + 缓存 + DB）
│   ├── image/                        # 图片智能体
│   │   ├── ImageGenAgent.java        # 文生图 Agent（wan2.5, 支持迭代）
│   │   ├── ImageRecognitionAgent.java # 图片识别 + 完整编辑管线（v2）
│   │   ├── ImageGenService.java      # DashScope 文生图 API 封装（长轮询）
│   │   ├── ImageCacheManager.java    # 待编辑图片缓存（会话隔离 + 锁）
│   │   └── ImageContextManager.java  # 生成图片上下文（迭代编辑参考图）
│   ├── file/                         # 文件智能体
│   │   ├── FileAgent.java            # 文件处理 Agent（Tika + AI + RAG）
│   │   └── FileRecognitionService.java # MIME检测 + 内容提取 + AI分析 + RAG切片 + FileRecord持久化
│   └── idiom/                        # ★ 成语接龙（v2.1 新增）
│       ├── IdiomDictionary.java      # ~590 成语 + O(1) 首字索引 + 随机查找
│       ├── GameSession.java          # 独立游戏会话状态
│       └── IdiomGameService.java     # 游戏逻辑 + 会话管理 + 积分 + 超时清理
├── entity/                           # 数据库实体
│   ├── Conversation.java / Message.java / FileRecord.java
│   ├── ImageRecord.java / TimbreChange.java / WeatherQuery.java
│   ├── IdiomGameRecord.java / DocumentChunk.java / UserMemory.java / VoiceResult.java
├── enums/  RouteContext.java / Timbre.java
├── exception/
│   ├── BotException / AIServiceException / ConfigurationException / ...
│   └── GlobalExceptionHandler.java   # ★ 全局异常拦截器（v2.1 新增）
├── mapper/   9 个 MyBatis-Plus Mapper（含 IdiomGameRecordMapper）
├── rag/      VectorStoreService / RAGRetrievalService / RAGContextAugmenter / EmbeddingService / DocumentChunkingService
├── service/
│   ├── ILinkBotService.java          # 微信 iLink 适配器（核心入口 + MessageSender 实现）
│   ├── MessageSender.java            # 消息发送抽象接口
│   ├── ChatPersistenceService.java   # 持久化门面
│   ├── IIdiomGameRecordService.java  # 成语接龙积分服务接口
│   └── impl/                          # 9 个 ServiceImpl
└── resources/
    ├── application.yml               # 主配置
    ├── schema.sql                    # 建表脚本（v2.1：message_id 默认值 + idiom_game_record 表）
    ├── config/  ai.yml / bot.yml / security.yml
    └── prompts/  system.txt / file-system.txt
```

---

## 三、功能详解

### 3.1 意图分类（IntentClassifier v2.1）

**三层策略：** 确定性规则 → 严格正则（带校验）→ qwen-turbo LLM 默认路径。

v2.1 核心改进：默认将所有请求交由 LLM 进行意图判断，仅在识别出明确的、简单的意图时才短路到快速执行路径。正则命中后增加黑名单校验和上下文图片检查，避免误判。

#### 示例

| 用户输入 | 匹配路径 | 分类结果 |
|----------|----------|----------|
| `/draw 一只猫` | 前缀 `/` → 确定性规则 | `COMMAND` |
| `画一只猫` | IMAGE_GEN_RE 严格匹配（必须含图片名词） | `IMAGE_GEN` |
| `把图片改成黑白风格` | IMAGE_EDIT_RE 匹配 + 上下文校验通过 | `IMAGE_EDIT` |
| `改成黑白风格`（无上下文图片） | IMAGE_EDIT_RE 不匹配 → LLM | 由 LLM 判断 |
| `生成完整的 ILinkBotService 示例代码` | IMAGE_GEN 不匹配 / 黑名单拦截 → LLM | `CHAT` |
| `修改代码` | IMAGE_EDIT 黑名单拦截 → LLM | `CHAT` |
| `画流程图` | IMAGE_GEN 不匹配（无图片名词） → LLM | `CHAT` |
| `帮我看看外面要不要带伞` | 无正则命中 → LLM | `WEATHER` |

#### 完整调用链

```
ILinkBotService.handleMessage()                     [service/ILinkBotService.java:107]
  └─ AgentRouter.route(ctx)                        [tools/AgentRouter.java:75]
       ├─ 成语接龙拦截（游戏中非命令文本）            [:77-86]
       └─ IntentClassifier.classify(ctx)            [tools/IntentClassifier.java:168]
            ├─ 层1 确定性规则:
            │   ├─ ctx.text().startsWith("/")  → COMMAND       [:170]
            │   ├─ ctx.hasFile()               → FILE          [:171]
            │   └─ ctx.hasImage()              → IMAGE_EDIT    [:172]
            ├─ 层2 严格正则 + 校验: strictRegexClassify(ctx)   [:199]
            │   ├─ IMAGE_GEN_RE.find() → 黑名单检查 → IMAGE_GEN [:203-209]
            │   ├─ IMAGE_EDIT_RE.find() → 黑名单检查
            │   │   → 上下文图片校验(imageCacheManager.containsKey)
            │   │   → IMAGE_EDIT                               [:213-226]
            │   ├─ TTS_RE.find()          → TTS                [:230]
            │   ├─ VOICE_SWITCH_RE.find() → VOICE_SWITCH       [:233]
            │   └─ WEATHER_RE.find()      → WEATHER            [:236]
            └─ 层3 LLM 默认路径: llmClassify(text)             [:186]
                 └─ intentChatClient (qwen-turbo)
                      .prompt().system(CLASSIFY_PROMPT).user(text).call()
                      失败 → 自动降级 CHAT
```

#### 关键正则（v2.1 严格化）

```java
// 必须含"量词 + 图片名词"：生成一张图/画个头像/做张海报
IMAGE_GEN_RE  = "(?:画|生成|绘制|做|来|帮我画|...)(?:一?[张个幅]|一下)"
                + "(?:图|图片|照片|插画|头像|壁纸|logo|图标|海报|...)"

// 必须含明确的图片指代词：这张图/那张照片/图片改成...
IMAGE_EDIT_RE = "(?:把|将|给)?(?:这张图|那张图|图片|照片|图)(?:.{0,10})"
                + "(?:修改|改成|换成|编辑|调整|替换|P一下|去水印|...)"

// 排除绘画前缀的天气正则："画一张天气图"不会被误判为 WEATHER
WEATHER_RE    = "^(?!.*(画|生成|绘制|...)).*(?:天气预报|天气|温度|...)"

// TTS/VOICE_SWITCH: 添加 ^ 起始锚点，仅匹配明确语序
```

#### 黑名单（v2.1 新增）

| 黑名单 | 拦截场景 | 示例 |
|--------|---------|------|
| IMAGE_GEN_BLACKLIST (5 patterns) | 代码/文档/流程/架构 | "生成ILinkBotService示例代码"、"画流程图" |
| IMAGE_EDIT_BLACKLIST (2 patterns) | 代码/配置/文档修改 | "修改代码"、"编辑文档"、"调整参数" |

#### 生命周期

```
[构造] IntentClassifier(@Qualifier intentChatClient, ImageCacheManager)
   └─ intentChatClient 注入（qwen-turbo, 无记忆）

[@PostConstruct] init()
   └─ log v2.1 初始化信息 + 黑名单数量

[运行时] classify(ctx) — 每消息一次，<1ms（确定性规则）/ LLM ~200ms / 严格正则 <1ms

[销毁] 无特殊清理
```

---

### 3.2 成语接龙（IdiomGameService）★ 新增

使用 `/cy start` 等命令显式触发，避免干扰正常聊天。已用成语加入 Set 记录，O(1) 词典查找，独立会话管理，超时自动结束，积分持久化。

#### 示例

```
用户: /cy start
Bot:  🎯 成语接龙开始！
     当前成语：「虎虎生威」
     请说出一个以「威」开头的成语

用户: 威风凛凛
Bot:  ✅ 接龙成功！威风凛凛 → 凛然正气
     轮到你了！请说出以「气」开头的成语
     📊 当前积分：1 | 已接 2 轮

用户: 气壮山河
Bot:  ✅ 接龙成功！气壮山河 → 河清海晏
     ...

用户: /cy stop
Bot:  🛑 游戏已结束
     📊 最终积分：5 | 🔄 总轮数：10
     输入 /cy ls 查看历史积分

用户: /cy ls
Bot:  📋 最近成语接龙积分
     1. 07-24 15:30 | 积分：5 | 轮数：10 | 🛑 主动结束
     2. 07-23 10:00 | 积分：3 | 轮数：6  | ⏰ 超时
```

#### 完整调用链

```
═══════════ 游戏开始 ═══════════

用户发送: /cy start
  → AgentRouter: text.startsWith("/") → 正常流程
    → IntentClassifier → COMMAND
      → CommandAgent → CommandRegistry.execute()
        → "/cy " 前缀匹配 → handleIdiomGame()
          → sub="start" → idiomGameService.startGame(userId, null)
            → dictionary.randomIdiom() → "虎虎生威"
            → new GameSession(userId, "虎虎生威") → sessions.put()

═══════════ 游戏接龙（AgentRouter 拦截） ═══════════

用户发送: 威风凛凛
  → AgentRouter.route()
    → idiomGameService.isUserInGame(userId) → true
    → text 不以 "/" 开头 → idiomGameService.handleInput()
      → dictionary.isValid("威风凛凛") → true
      → session.usedIdioms().contains("威风凛凛") → false
      → "威风凛凛".charAt(0) == "威" → true ✓
      → session.recordSuccess("威风凛凛")  // score++, rounds++
      → dictionary.findChain('凛', usedIdioms) → Optional["凛然正气"]
      → session.recordAiMove("凛然正气")
      → 返回 "✅ 接龙成功！..."

═══════════ AI 无法接龙（用户获胜） ═══════════

用户发送: xxx
  → handleInput() → 校验通过 → recordSuccess()
  → dictionary.findChain(lastChar, usedIdioms) → Optional.empty()
  → session.addBonus(2)  // +2 奖励分
  → saveRecord(userId, score, rounds, "USER_WIN")  // 写入 DB
  → sessions.remove(userId)
  → 返回 "🎉 恭喜！我已无法接龙..."

═══════════ 超时自动结束 ═══════════

cleanupExecutor (每2min):
  → 遍历 sessions
  → isExpired(s) → Duration.between(lastActivity, now).toMinutes() >= 5
  → saveRecord() → sessions.remove()
```

#### 核心设计

| 设计点 | 说明 |
|--------|------|
| **O(1) 词典查找** | `Map<Character, List<String>>` 首字索引 + `Set<String>` 全量，均为 O(1) |
| **独立会话** | `ConcurrentHashMap<String, GameSession>`，userId 隔离，互不干扰 |
| **超时清理** | `ScheduledExecutorService` 每 2 分钟扫描，5 分钟无操作自动结束 |
| **AgentRouter 拦截** | 游戏中非 `/` 开头文本直接路由到游戏，不走 LLM 分类 |
| **积分持久化** | `idiom_game_record` 表，支持历史查询最近 3 局 |
| **AI 接龙** | Fisher-Yates 随机抽样，从未使用的候选成语中选择 |

#### 命令一览

| 命令 | 行为 |
|------|------|
| `/cy start` | 随机起始成语 |
| `/cy start 龙飞凤舞` | 指定起始成语 |
| `/cy stop` | 结束并保存积分 |
| `/cy ls` | 最近三局积分 |
| `/cy help` | 规则帮助 |
| `{四字成语}`（游戏中） | 自动识别为接龙输入 |

#### 生命周期

```
[构造] IdiomGameService(IdiomDictionary, IIdiomGameRecordService)
   └─ cleanupExecutor = newSingleThreadScheduledExecutor()

[@PostConstruct] init()
   └─ scheduleAtFixedRate(cleanupInactive, 2min, 2min)

[运行时]
   ├─ startGame(userId, startIdiom) → new GameSession
   ├─ handleInput(userId, text) → 校验 + AI 接龙 + 响应
   ├─ endGame(userId, reason) → saveRecord + remove session
   ├─ getHistory(userId) → DB 查询最近 3 局
   └─ isUserInGame(userId) → 超时检查 + 会话查找

[@PreDestroy] destroy()
   └─ 保存所有活跃会话 → sessions.clear() → executor.shutdown()
```

---

### 3.3 AI 多轮对话（ChatAgent + ChatService）

#### 示例

```
用户: 你好，请介绍一下你自己
Bot:  你好！我是基于通义千问的 AI 助手...

用户: 刚才说到哪里了？
Bot:  （有记忆）我们刚才在讨论我的功能...
```

#### 完整调用链

```
AgentRouter.route(ctx) → intent=CHAT → ChatAgent        [tools/chat/ChatAgent.java]
  └─ ChatAgent.execute(ctx)
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

---

### 3.4 文生图（ImageGenAgent + ImageGenService）

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
AgentRouter.route(ctx) → intent=IMAGE_GEN → ImageGenAgent      [tools/image/ImageGenAgent.java:53]
  └─ ImageGenAgent.execute(ctx)
       └─ executor.submit(() -> doImageGen(ctx))                # 虚拟线程异步

doImageGen(ctx):
  1. imageContextManager.getLastRefImage(userId)                # 获取上一张图 CDN URL
  2. ctx.sender().sendText("正在生成图片...")
  3. refUrl != null ?
       imageGenService.generateImageUrl(prompt, refUrl)
         └─ 失败 → 自动回退 generateImageUrl(prompt, null)
     : imageGenService.generateImageUrl(prompt)
         └─ ImageSynthesis.asyncCall(param) → taskId
         └─ 长轮询: for(i=0; i<90; i++) sleep(2s)
              ├─ fetch(taskId) → "SUCCEEDED" → 提取 CDN URL
              ├─ fetch(taskId) → "FAILED"    → null
              └─ 90次超时(180s) → null
  4. imageGenService.downloadImage(url)                         # Java HttpClient GET
  5. imageContextManager.save(userId, url, bytes)
  6. ctx.sender().sendImage(userId, bytes, "ai-gen.png", prompt)
```

---

### 3.5 图片识别 & 编辑（ImageRecognitionAgent）

#### 示例

```
用户: [上传图片 photo.jpg]
Bot:  【图片描述】
      这是一张夕阳下的海滩照片，前景有棕榈树...
      💡 你可以对我说："改成黑白风格"、"把背景换成蓝天" 等进行编辑

用户: 把图片改成黑白风格
Bot:  正在分析编辑需求："把图片改成黑白风格"...
      正在生成编辑后的图片...
      [发送编辑后图片]
```

#### 完整调用链

```
═══════════════ 第一阶段：图片上传识别 ═══════════════

AgentRouter.route(ctx) → ctx.hasImage()=true
  → IntentClassifier.classify(ctx) → IMAGE_EDIT（确定性规则）
    → ImageRecognitionAgent

handleImageUpload(ctx):
  1. chatService.analyzeImage(ctx.imageBytes())
       └─ ChatClient (qwen-vl-plus).prompt()....media(imageMedia).call()
  2. imageCacheManager.put(userId, ctx.imageBytes())
       └─ 生成 sessionId → 复合键 userId:sessionId → 缓存
  3. ctx.sender().sendText("【图片描述】\n" + desc + "\n\n💡 ...")

═══════════════ 第二阶段：编辑指令执行 ═══════════════

用户发送: "把图片改成黑白风格"
  → IntentClassifier.classify(ctx)
       ├─ IMAGE_EDIT_RE.find() → true
       ├─ 黑名单检查 → 通过（无代码/文档关键词）
       └─ IMAGE_EDIT 上下文校验 → imageCacheManager.containsKey(userId) → true
         → 返回 IMAGE_EDIT

ImageRecognitionAgent.execute(ctx):
  → executor.submit(() -> doImageEdit(ctx))

doImageEdit(ctx):（异步虚拟线程）
  1. imageCacheManager.lock(userId)        # 串行化
  2. imageCacheManager.peek(userId)        # peek 不删除
  3. chatService.describeImageEdit(originalBytes, instruction)
       └─ qwen-vl-plus: image + instruction → 编辑后详细描述
  4. imageGenService.generateImageUrl(editPrompt)  # wan2.5 文生图
  5. imageGenService.downloadImage(imageUrl)
  6. imageContextManager.save(userId, url, bytes)
  7. imageCacheManager.removeSilently(userId)
  8. ctx.sender().sendImage(userId, bytes, "edited-image.png")
  9. finally: imageCacheManager.unlock(userId)
```

---

### 3.6 天气查询（WeatherAgent v2.1）

#### 示例

```
用户: 北京天气
Bot:  正在查询「北京」天气...
      【北京北京市 实时天气报告】
      🌡 当前温度 25°C，晴。💧 相对湿度 45%，北风 3 级。

用户: 三亚天气
Bot:  [内置38城市无"三亚"]
      → 调用高德地理编码API → "三亚" → adcode=460200
      → 缓存 adcode → 调用天气API → 返回报告
```

#### v2.1 NPE 安全修复

```java
// 之前：l.get("temperature").getAsDouble()  → key缺失或value为null → NPE
// 现在：safeDouble(l, "temperature", 0.0)   → 安全返回默认值

private static String safeString(JsonObject obj, String key, String defaultValue) {
    try { var el = obj.get(key); if (el == null || el.isJsonNull()) return defaultValue;
          return el.getAsString(); }
    catch (Exception e) { return defaultValue; }
}
```

| 修复点 | 之前 | 之后 |
|--------|------|------|
| `fetchNow()` 8 字段 | 直接 `get().getAsXxx()` | `safeString()` / `safeDouble()` |
| `fetchForecast()` 8 字段 | 直接 `get().getAsXxx()` | `safeString()` / `safeDouble()` |
| API status/info 字段 | 直接 `get().getAsString()` | `safeString()` |
| 地理编码 adcode 提取 | 链式 getAsXxx | `safeString()` |
| `messageId` 传 null | `null` → MySQL 约束错误 | `""` + schema `DEFAULT ''` |
| `forecasts.days.length` | 未检查 null | `forecasts.days == null` 检查 |

#### WeatherAgent v2 核心优化

| 优化项 | v2.1 (新) |
|--------|---------|
| **城市覆盖** | 38 内置 + 高德地理编码 API 动态解析 |
| **API 缓存** | 实时天气 5min TTL / 预报 30min TTL |
| **DB 持久化** | 每次查询写入 `weather_query` 表 |
| **NPE 安全** | 全部 JSON 字段安全访问，容错默认值 |
| **管理接口** | evictCache(city) / evictAllCache() |

---

### 3.7 语音合成（VoiceGenAgent + TTSEngine）

略（与上一版本一致，详见 v1 文档）

---

### 3.8 文件识别（FileAgent + FileRecognitionService）

略（与上一版本一致，详见 v1 文档）

---

### 3.9 命令系统（CommandAgent + CommandRegistry）

#### 示例

```
用户: /help
Bot:  命令列表：
      /draw <描述> /weather <城市> /tts <文本> /voice <编号>
      /cy start /cy stop /cy ls ...

用户: /cy start 龙飞凤舞
Bot:  🎯 成语接龙开始！当前成语：「龙飞凤舞」
      请说出一个以「舞」开头的成语
```

#### 命令注册表（v2.1 更新）

| 前缀 | 处理器 | 说明 |
|------|--------|------|
| `/draw ` | handleDraw | 文生图 |
| `/weather ` | handleWeather | 天气查询 |
| `/tts ` | handleTts | 语音合成 |
| `/voice ` | handleVoice | 音色切换 |
| `/help` | handleHelp | 帮助（含 /cy 命令） |
| `/status` | handleStatus | 连接状态 |
| `/clear` | handleClear | 清除记忆 |
| `/cancel` | handleCancel | 取消图片编辑 |
| `/cy ` | handleIdiomGame | **成语接龙**（start/stop/ls/help 子命令） |

---

### 3.10 全局异常拦截器（GlobalExceptionHandler）★ 新增

未处理异常统一捕获格式化，防止裸堆栈暴露给用户。按异常类型分类，生成用户友好的中文提示 + 追踪 ID。

#### 错误响应格式

```
【操作失败】
──────────────
错误类型：图片生成失败
详情：AI 服务暂时不可用，请稍后重试
时间：2026-07-24 15:30:00
追踪ID：err-a1b2c3d4
──────────────
如需帮助，请输入 /help 查看可用命令
```

#### 异常分类

| 异常类型 | 用户提示 | 日志级别 |
|----------|---------|---------|
| `AIServiceException(chat)` | AI 对话失败 | WARN |
| `AIServiceException(analyzeImage)` | 图片识别失败 | WARN |
| `ImageGenerationException(generate)` | 图片生成失败 | WARN |
| `VoiceSynthesisException` | 语音生成失败 | WARN |
| `FileRecognitionException` | 文件处理失败 | WARN |
| `ConfigurationException` | 配置错误，请联系管理员 | ERROR |
| 未知异常 | 系统内部异常，请稍后重试 | ERROR（含完整堆栈） |

#### 调用链

```
AgentRouter.route(ctx)                                    [tools/AgentRouter.java:75]
  └─ agent.execute(ctx)                                   [:100]
       └─ try { ... }
          catch (Exception e) {
              exceptionHandler.handle(userId, sender, agent.name(), e)
                → classify(e) → ErrorInfo(type, detail, severity)
                → buildErrorMessage(info, operation, trackingId)
                → sender.sendText(userId, formattedMessage)
          }
```

---

## 四、配置体系（v2.1 更新）

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

# ── 超时 ──
spring.ai.dashscope.connect-timeout: 30000
spring.ai.dashscope.read-timeout:    300000

# ── 缓存 ──
bot.cache.pending-image-ttl-minutes:  5
bot.cache.max-pending-images:         50
bot.cache.cleanup-interval-minutes:   2
bot.cache.image-context-ttl-minutes:  30
bot.cache.max-image-context-entries:  200

# ── 天气 ──
bot.weather.api-key:  <key>
bot.weather.base-url: https://restapi.amap.com/v3/weather/weatherInfo

# ── 成语接龙（硬编码，无外部配置） ──
# 超时: 5min  |  清理间隔: 2min  |  词典: ~590 条内置
```

---

## 五、数据库设计（v2.1 更新）

### 5.1 表汇总

| 表名 | 实体 | 主要用途 | v2.1 变更 |
|------|------|---------|---------|
| `conversation` | Conversation | 会话生命周期管理 | — |
| `message` | Message | 所有消息记录（核心表） | — |
| `file_record` | FileRecord | 文件上传→提取→分析全链路 | — |
| `image_context` | ImageRecord | 图片 CDN URL、描述、编辑指令 | — |
| `timbre_change` | TimbreChange | 音色切换审计日志 | — |
| `weather_query` | WeatherQuery | 天气查询记录 | **message_id DEFAULT '' + NPE 安全** |
| `idiom_game_record` | IdiomGameRecord | 成语接龙游戏积分 | **★ 新增** |
| `document_chunks` | DocumentChunk | RAG 文档切片 + embedding | — |
| `user_memory` | UserMemory | 用户长期记忆/偏好存储 | — |

### 5.2 新增表

```sql
-- 成语接龙游戏记录表
CREATE TABLE idiom_game_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    score INT NOT NULL DEFAULT 0,
    rounds INT NOT NULL DEFAULT 0,
    end_reason VARCHAR(20) NOT NULL DEFAULT 'USER_STOP' COMMENT 'USER_WIN/USER_STOP/TIMEOUT',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_user_created (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

### 5.3 weather_query 变更

```sql
-- v2.0 (旧)
message_id VARCHAR(64) NOT NULL,
-- → WeatherAgent.saveQuery() 传 null → MySQL 约束错误

-- v2.1 (新)
message_id VARCHAR(64) NOT NULL DEFAULT '',
-- → WeatherAgent.saveQuery() 传 "" → 正常写入
```

---

## 六、消息处理全局流程图

```
微信消息到达
      │
      ▼
ILinkBotService.handleMessage(WeixinMessage)
      │
      ├─ VoiceMsg → 识别文本 → AgentContext(VOICE) ────┐
      ├─ TextMsg  → 去重检查 → AgentContext(TEXT) ──────┤
      ├─ ImageMsg → CDN下载  → AgentContext(imageBytes) ┤
      └─ FileMsg  → 大小检查 → AgentContext(fileBytes) ─┘
                                          │
                                          ▼
                                   AgentRouter.route(ctx)
                                          │
                              ┌───────────┴───────────┐
                              │ 成语接龙拦截             │
                              │ isUserInGame + 非/文本  │
                              │ → IdiomGameService      │
                              └───────────┬───────────┘
                                          │ (非游戏/命令字符)
                                          ▼
                               IntentClassifier.classify(ctx)
                                          │
                          ┌───────────────┼───────────────┐
                          │ 层1 确定性规则  │ 层2 严格正则    │ 层3 LLM 默认
                          │ /→COMMAND     │ +黑名单+校验    │ qwen-turbo
                          │ file→FILE     │ IMAGE_GEN      │ 失败→CHAT
                          │ image→IMG_EDIT│ IMAGE_EDIT     │
                          │               │ TTS/VOICE/WEATHER│
                          └───────────────┴───────────────┘
                                          │
                                          ▼
                              AgentRouter O(1) Map 查找
                              try { agent.execute(ctx) }
                              catch → GlobalExceptionHandler
                                          │
    ┌────────┬───────────┬───────────┬────┴──────┬──────────┬──────────┐
    ▼        ▼           ▼           ▼           ▼          ▼          ▼
 COMMAND   CHAT      IMAGE_GEN   IMAGE_EDIT    TTS       WEATHER    FILE
    │        │           │           │           │          │          │
Command  ChatAgent  ImageGen  ImageRecog  VoiceGen   WeatherAgent FileAgent
 Agent   (qwen+)    Agent     Agent       Agent     (高德v2.1)  (Tika+AI)
    │        │      (wan2.5) (qwen-vl+) (cosyvoice)     │          │
    │        │           │      │            │     safeString/  FileRecogSvc
Command  ChatService ImageGen ChatService  TTSEngine safeDouble    │
Registry  ├─chat()  Service  ├─analyzeImage            │     ├─Tika提取
(/命令     ├─RAG    ├─asyncCall├─describeEdit           │     ├─AI(重试)
 +/cy)    ├─analyze│─长轮询 │       │                  │     ├─FileRecord
    │     └─doc    │        │   ImageGenService         │     └─RAG+Embed
    │              │        │   (wan2.5)                │
    │        ImageContext  ImageCacheManager       weather_query
    │         Manager      (会话隔离+锁)           (message_id='')
    │              │                                 │
    └──────────────┴─────────────────────────────────┘
                             │
                   ctx.sender() 回传结果
                (sendText / sendImage / sendFile)
                             │
                      ChatPersistenceService
                  (消息/会话/音色/图片/文件/天气/游戏记录 入库)
```

---

## 七、类依赖关系总图（v2.1）

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

HttpClientConfig
  ├── okHttpClient Bean ──→ ImageGenService.downloadImage()
  └── @PostConstruct configureDashScopeSdkTimeouts()
        └── Constants.connectionConfigurations ← 替换

ILinkBotService (核心入口，实现 MessageSender)
  └── AgentRouter
        ├── IdiomGameService ──→ idiom game intercept [:77-86]
        ├── IntentClassifier ──→ intentChatClient (qwen-turbo)
        │                        + ImageCacheManager (上下文校验)
        ├── GlobalExceptionHandler ──→ 统一异常捕获 [:101-105]
        └── agentMap: EnumMap<Intent, Agent>
              ├── CHAT → ChatAgent
              │           ├── ChatService ──→ ChatClient (qwen-plus)
              │           ├── RAGRetrievalService ──→ VectorStoreService
              │           ├── VoiceGenAgent (VOICE上下文时TTS)
              │           └── ImageCacheManager
              ├── COMMAND → CommandAgent
              │               └── CommandRegistry (10个命令, LinkedHashMap)
              │                    ├── handleDraw → ImageGenService
              │                    ├── handleWeather → WeatherAgent
              │                    ├── handleIdiomGame → IdiomGameService
              │                    └── ...
              ├── IMAGE_GEN → ImageGenAgent
              │                 ├── ImageGenService ──→ DashScope wan2.5
              │                 └── ImageContextManager
              ├── IMAGE_EDIT → ImageRecognitionAgent
              │                  ├── ChatService ──→ qwen-vl-plus
              │                  ├── ImageGenService ──→ wan2.5
              │                  ├── ImageCacheManager ──→ 会话隔离+锁
              │                  └── ImageContextManager
              ├── TTS → VoiceGenAgent
              │          ├── TTSEngine ──→ DashScope cosyvoice-v1
              │          └── TimbreSession (12音色, 2h TTL)
              ├── WEATHER → WeatherAgent (v2.1)
              │               ├── 38内置adcode + 高德地理编码API
              │               ├── 内存TTL缓存 + safeString/safeDouble NPE安全
              │               └── IWeatherQueryService → weather_query表
              └── FILE → FileAgent
                           └── FileRecognitionService
                                 ├── Tika + ChatService + DocumentChunkingService
                                 └── EmbeddingService + VectorStoreService

── IdiomGameService ★新增
     ├── IdiomDictionary (Map<Character, List<String>> O(1)索引, ~590成语)
     ├── ConcurrentHashMap<String, GameSession> (userId隔离)
     ├── IIdiomGameRecordService → idiom_game_record表
     └── ScheduledExecutorService (2min清理超时会话)

── GlobalExceptionHandler ★新增
     └── classify(Exception) → ErrorInfo(type, detail, severity)
         → buildErrorMessage() → sender.sendText()

── ChatPersistenceService (持久化门面)
     ├── IConversationService / IMessageService / ITimbreChangeService
     ├── IImageRecordService / IFileRecordService / IWeatherQueryService
     ├── IIdiomGameRecordService / IDocumentChunkService / IUserMemoryService
     └── 9 个 ServiceImpl → 9 个 Mapper (MyBatis-Plus BaseMapper)

── BotMetrics (Micrometer指标)
     ├── TTS 成功/失败 + 耗时分布
     ├── 图片生成 成功/失败 + 耗时分布
     ├── 消息处理总数
     └── 待编辑图片缓存实时大小 (Gauge)
```

---

## 八、架构设计亮点

### 8.1 意图分类 v2.1：LLM 默认 + 正则短路 + 黑名单

原有双层策略（正则优先 → LLM 回退）中正则命中率高但误判严重（"生成代码" → IMAGE_GEN，"修改配置" → IMAGE_EDIT）。v2.1 三层策略：
1. **确定性规则**：`/` 命令、文件/图片消息（零延迟，100% 可靠）
2. **严格正则短路**：仅匹配含明确图片名词+量词的生成请求、含图片指代词的编辑请求，且必须通过黑名单 + 上下文图片校验
3. **LLM 默认路径**：其余所有请求交由 qwen-turbo 分类（约 200ms）

### 8.2 成语接龙 O(1) + 独立会话

`Map<Character, List<String>>` 首字索引实现 O(1) 词典查找与接龙候选。`ConcurrentHashMap<String, GameSession>` 确保多用户独立，`ScheduledExecutorService` 每 2 分钟清理超时会话（5 分钟无操作）。AgentRouter 最高优先级拦截游戏中非命令文本，确保零额外延迟。

### 8.3 天气 NPE 安全

`safeString()` / `safeDouble()` 封装所有高德 API 返回字段访问，key 缺失或 value 为 null 时返回默认值而非抛 NPE。`message_id` 由 `null` 改为 `""` 并配合 schema `DEFAULT ''` 修复 MySQL 约束错误。

### 8.4 全局异常拦截

`AgentRouter.route()` 中 `agent.execute(ctx)` 包裹 `try-catch`，`GlobalExceptionHandler.classify()` 按异常类型（AIServiceException → 操作类型 / ImageGenerationException → stage / 未知 → 通用提示）格式化统一错误响应，含时间戳和追踪 ID。

### 8.5 图片编辑管线完整性

完整 "多模态理解 + 文生图重绘" 管线：`peek 缓存 → qwen-vl-plus 理解 → wan2.5 生成 → 发送 → 清除缓存`，带 `lock/unlock` 串行化和上下文图片校验（无图片不回 IMAGE_EDIT）。

### 8.6 DashScope SDK 超时注入

`HttpClientConfig.configureDashScopeSdkTimeouts()` 在 `@PostConstruct` 阶段三层注入（系统属性 + 全局 Config 替换 + int 字段），确保所有 DashScope API 调用统一使用 300s read timeout。

### 8.7 文档分析可靠性

`ChatService.analyzeDocument()` 截断 8K 字符 + 指数退避重试 2 次（2s/4s），`FileRecognitionService` Tika 提取层也截断 8K，双层保护防止 token 爆炸。

### 8.8 正则快速路径优化

移除 IMAGE_EDIT_RE 中单字词根（`改/换/变/加`），防止"改变/变化/加油"误判。IMAGE_GEN_RE 要求"量词+图片名词"组合（`画一张图`、`生成个头像`），拒绝孤立动词。IMAGE_EDIT_RE 要求明确图片指代词（`这张图`、`那个照片`）或上下文图片存在。
