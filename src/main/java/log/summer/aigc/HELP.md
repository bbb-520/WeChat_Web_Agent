# Summer Bot — 消息处理流程详解

> 本文档详细说明每种消息类型从接收到回复的完整调用链路。
> 涉及组件：`ILinkBotAdapter` → `SlashInterceptor` → `AgentLoop` → `ThinkLoop` → `ActLoop` → `ToolRegistry` → `@Tool`

---

## 架构速查

```
ILinkBotAdapter.handleMessage(WeixinMessage)
  │
  ├─ TextItem   → BotMessage(text)           → SlashInterceptor → AgentLoop
  ├─ VoiceItem  → ASR → BotMessage(text)     → SlashInterceptor → AgentLoop
  ├─ ImageItem  → CDN → BotMessage(image)    → SlashInterceptor → AgentLoop
  └─ FileItem   → CDN → BotMessage(file)     → SlashInterceptor → AgentLoop

AgentLoop.orchestrate(msg, sender)
  while (round < 10):
    ThinkLoop.think(userId, chatMemory)       ← 内循环：LLM 思考
      if finalAnswer → sendText → exit
      if toolCalls → ActLoop.execute()        ← 外循环：工具执行
```

---

## 一、文本类消息（输入: TextItem）

### 1. 普通文本对话

```
用户发送: "你好"
     │
     ▼
ILinkBotAdapter.handleMessage()
  → item.getText_item() != null
  → text = "你好"
  → BotMessage(userId, "你好", null, null, null, RouteContext.TEXT)
     │
     ▼
SlashInterceptor.intercept()
  → "你好" 不以 / 开头 → 返回 false (不拦截)
     │
     ▼
AgentLoop.orchestrate(BotMessage, sender)
  → UserContextHolder.setUserId(userId)
  → chatMemory.add(userId, UserMessage("你好"))
     │
     ▼
┌─ ThinkLoop.think(userId, chatMemory) ─────────────────────┐
│ 1. messages = chatMemory.get(userId) → [UserMessage("你好")] │
│ 2. tools = toolRegistry.getCallbacks() → 14 个 ToolCallback │
│ 3. chatService.chatWithTools(messages, tools)               │
│    → system.txt + messages + 14 tool definitions            │
│    → DashScope (qwen-plus) API                              │
│    → LLM 返回: "你好呀！有什么可以帮你的吗？"                  │
│    → 无 tool calls                                          │
│ 4. ThinkResult(finalAnswer="你好呀！...", toolCalls=[])     │
└────────────────────────────────────────────────────────────┘
     │
     ▼
AgentLoop 判断: hasFinalAnswer=true, hasToolCalls=false
  → sender.sendText(userId, "你好呀！有什么可以帮你的吗？")
  → finalAnswerSent = true → break
     │
     ▼
finally: chatMemory.clear(userId) + UserContextHolder.clear()
```

**关键点**: 纯文本对话不触发任何工具，LLM 直接生成回复文字。

---

### 2. 文本生图（Text → Image）

```
用户发送: "画一只柴犬"
     │
     ▼
ILinkBotAdapter → BotMessage(userId, "画一只柴犬", null, null, null, TEXT)
SlashInterceptor → 不拦截
AgentLoop
  → chatMemory.add(userId, UserMessage("画一只柴犬"))
     │
     ▼
┌─ ThinkLoop.think() ────────────────────────────────────────┐
│ LLM 分析: 用户要求画图                                       │
│ LLM 从工具清单匹配: draw(prompt="一只可爱的柴犬")             │
│ → ChatResponse 包含 toolCall: {name:"draw", args:{prompt:...}} │
│ → ThinkResult(toolCalls=[draw])                            │
└────────────────────────────────────────────────────────────┘
     │
     ▼
┌─ ActLoop.execute(draw, {prompt:"一只可爱的柴犬"}, msg, ...) ─┐
│ 1. argumentResolver.resolve({prompt:"..."}, msg)              │
│    → prompt 无占位符 → 原样返回 {"prompt":"一只可爱的柴犬"}    │
│                                                               │
│ 2. toolRegistry.execute("draw", {"prompt":"一只可爱的柴犬"})   │
│    → 查找 callbacks["draw"] → ImageGenTool.draw()             │
│    → ImageGenService.generateImageUrl("一只可爱的柴犬")        │
│      → DashScope wan2.5-t2i-preview API                       │
│      → 返回 CDN URL                                           │
│    → ImageGenService.downloadImage(url) → byte[]              │
│    → ImageContextManager.save(userId, url, bytes)             │
│    → messageSender.sendImage(userId, bytes, "ai-gen.png", ...)│
│      → ILinkBotAdapter → ILinkClient.sendImage() → 微信用户    │
│    → return ActResult.success("图片已发送")                    │
│                                                               │
│ 3. appendToMemory: ToolResponseMessage("draw", "图片已发送")   │
└───────────────────────────────────────────────────────────────┘
     │
     ▼
┌─ ThinkLoop.think() (第2轮) ───────────────────────────────┐
│ messages: [UserMessage("画一只柴犬"), ToolResponse("图片已发送")] │
│ LLM: "已经为你画了一只柴犬！"                                  │
│ → ThinkResult(finalAnswer="已经为你...", toolCalls=[])      │
└────────────────────────────────────────────────────────────┘
     │
     ▼
AgentLoop → sender.sendText("已经为你画了一只柴犬！") → exit
finally: chatMemory.clear() + UserContextHolder.clear()
```

**关键点**: 图片由 `ImageGenTool` 内部直接通过 `MessageSender` 发送，不经过 AgentLoop 的文件下发通道。

---

### 3. 文本生音（Text → Voice）

```
用户发送: "朗读你好世界"
     │
     ▼
ILinkBotAdapter → BotMessage(userId, "朗读你好世界", null, null, null, TEXT)
AgentLoop → ThinkLoop
     │
     ▼
LLM 决策: tts(text="朗读你好世界")
     │
     ▼
┌─ ActLoop.execute(tts, {text:"朗读你好世界"}, ...) ──────────┐
│ 1. argumentResolver → 无占位符 → 原样                         │
│                                                               │
│ 2. toolRegistry.execute("tts", {text:"朗读你好世界"})          │
│    → TtsTool.tts("朗读你好世界")                               │
│    → extractTtsText("朗读你好世界") → "你好世界"               │
│    → timbreSession.getCurrentVoiceId(userId) → voiceId        │
│    → ttsEngine.synthesize("你好世界", voiceId)                 │
│      → DashScope cosyvoice-v1 API                             │
│      → VoiceResult(wavBytes, durationMs)                      │
│    → messageSender.sendFile(userId, wavBytes, "tts-xxx.wav")  │
│      → ILinkBotAdapter → ILinkClient.sendFile() → 微信用户     │
│    → return ActResult.success("语音已发送")                    │
│                                                               │
│ 3. appendToMemory: ToolResponseMessage("tts", "语音已发送")    │
└───────────────────────────────────────────────────────────────┘
     │
     ▼
ThinkLoop (第2轮) → LLM: "已为你朗读！"
AgentLoop → sender.sendText("已为你朗读！") → exit
```

**关键点**: 音频由 `TtsTool` 内部直接通过 `MessageSender.sendFile()` 发送。

---

### 4. 文本生文件（Text → Document）

文档生成使用**三步流程 + 挂起/恢复**机制：

```
用户发送: "生成一个Q2业绩报告的PPT"
     │
     ▼
AgentLoop → ThinkLoop
  → LLM: createOutline(type="PPT", title="Q2业绩报告",
       outlineJson={"title":"...", "sections":[...]})
     │
     ▼
┌─ ActLoop.execute(createOutline, {type, title, outlineJson}) ─┐
│ CreateOutlineTool.createOutline("PPT", "Q2业绩报告", json)     │
│   → 校验 type ∈ {WORD, PPT, EXCEL}                            │
│   → 校验 title != null, outlineJson 合法 JSON                 │
│   → UserContextHolder.getUserId() → userId                    │
│   → 若无活跃会话 → ChatPersistenceService.newConversation()    │
│   → DocumentOutline 写入 DB (status=DRAFT)                    │
│   → return ActResult.suspend(data, "等待确认大纲内容")         │
│                                                               │
│ AgentLoop 检测到 result.suspend() = true:                     │
│   → sender.sendText(大纲文本)                                  │
│   → sender.sendText("等待确认大纲...")                         │
│   → sessionStateManager.suspend(userId, convId, ...)          │
│     → ChatMemory 消息快照序列化到 DB                            │
│   → suspended = true → break → exit (不清除 ChatMemory)       │
└───────────────────────────────────────────────────────────────┘
     │
     ▼
用户看到大纲，回复: "确认，第三点改成团队建设"
     │
     ▼
AgentLoop.orchestrate()
  → sessionStateManager.getSuspendedByUser(userId)
  → 检测到挂起会话! → resumeOrchestrate()
     │
     ▼
┌─ resumeOrchestrate() ───────────────────────────────────────┐
│ 1. chatMemory.clear(userId)                                  │
│ 2. sessionStateManager.restoreMessages(suspendedConv, memory) │
│    → 从 DB 反序列化之前的消息快照 → 回填 ChatMemory             │
│ 3. chatMemory.add(userId, UserMessage("确认，第三点..."))     │
│ 4. sessionStateManager.clearSuspend(convId)                  │
│ 5. 进入标准 Think-Act 循环...                                 │
└──────────────────────────────────────────────────────────────┘
     │
     ▼
ThinkLoop (第1轮): LLM 看到历史消息 + "确认，第三点改成团队建设"
  → LLM: confirmOutline(outlineId=123, modifiedOutlineData=修改后JSON)
     │
     ▼
ActLoop → ConfirmOutlineTool.confirmOutline(123, 修改后JSON)
  → DB: DRAFT → MODIFIED
  → return ActResult.success("大纲已修改并确认")
     │
     ▼
ThinkLoop (第2轮): LLM 看到 "大纲已修改并确认"
  → LLM: generateDocument(type="PPT", outlineId=123)
     │
     ▼
┌─ ActLoop.execute(generateDocument, {type:"PPT", outlineId:123}) ─┐
│ GenerateDocumentTool.generateDocument("PPT", 123)                 │
│   → outlineService.getById(123) → 获取大纲                        │
│   → 状态检查: status ∈ {CONFIRMED, MODIFIED} ✓                    │
│   → 幂等检查: outlineId + type + version → 无重复                  │
│   → documentGenerator.generatePpt(title, outlineJson)             │
│     → Apache POI XMLSlideShow                                     │
│     → 标题页 + 每章节一页幻灯片                                     │
│     → ByteArrayOutputStream → byte[]                              │
│   → DocumentRecord 写入 DB (status=GENERATED, idempotencyKey)     │
│   → return ActResult.success({                                      │
│       fileName:"Q2业绩报告.pptx",                                  │
│       fileBytes: byte[],                                          │
│       ...                                                         │
│     })                                                            │
│                                                                    │
│ ActLoop.deliverFileIfPresent():                                   │
│   → dataMap.get("fileBytes") → base64 String / byte[]             │
│   → dataMap.get("fileName") → "Q2业绩报告.pptx"                    │
│   → resolveFileBytes(base64) → byte[]                             │
│   → sender.sendFile(userId, bytes, "Q2业绩报告.pptx")             │
└────────────────────────────────────────────────────────────────────┘
     │
     ▼
ThinkLoop (第3轮) → LLM: "PPT已生成并发送，请查收！"
AgentLoop → sender.sendText("PPT已生成并发送，请查收！") → exit
```

**关键点**:
- 文档走三步流程: `createOutline` → `confirmOutline` → `generateDocument`
- 第一步后 AgentLoop **挂起**，等待用户确认
- 用户回复后从 DB **恢复**对话上下文继续
- 文件由 `ActLoop.deliverFileIfPresent()` 从 `ActResult.data()` Map 中检测并下发
- 支持幂等: 相同 outlineId+type+version 不重复生成

---

## 二、语音类消息（输入: VoiceItem）

> 语音消息由微信 ASR 引擎自动识别为文本，后续处理与文本消息完全相同。
> 区别仅在于 `BotMessage.context = RouteContext.VOICE`（目前未对处理逻辑产生影响）。

### 5. 语音识别 → 普通对话

```
用户发送语音: "今天天气怎么样"
     │
     ▼
ILinkBotAdapter.handleMessage()
  → item.getVoice_item() != null
  → text = item.getVoice_item().getText() → "今天天气怎么样" (微信ASR)
  → handleVoiceMessage(userId, item)
     │
     ▼
BotMessage(userId, "今天天气怎么样", null, null, null, RouteContext.VOICE)
  → SlashInterceptor → 不拦截
  → AgentLoop → 等同于 普通文本对话 (流程1)
```

### 6. 语音生图（Voice → Image）

```
用户发送语音: "画一只小猫"
     │
     ▼
VoiceItem → ASR text: "画一只小猫"
  → BotMessage(userId, "画一只小猫", null, null, null, VOICE)
  → AgentLoop → ThinkLoop → draw(prompt="一只小猫") → ActLoop → 图片发送
  （与流程2 文本生图完全相同，仅入口途径不同）
```

### 7. 语音生音（Voice → TTS）

```
用户发送语音: "用语音朗读你好"
     │
     ▼
VoiceItem → ASR text: "用语音朗读你好"
  → BotMessage(userId, "用语音朗读你好", null, null, null, VOICE)
  → AgentLoop → ThinkLoop → tts(text="用语音朗读你好") → ActLoop → 音频发送
  （与流程3 文本生音完全相同，仅入口途径不同）
```

### 8. 语音生文件（Voice → Document）

```
用户发送语音: "生成一个关于Q2的PPT"
     │
     ▼
VoiceItem → ASR text: "生成一个关于Q2的PPT"
  → BotMessage(userId, "生成一个关于Q2的PPT", null, null, null, VOICE)
  → AgentLoop → 三步文档流程（与流程4 文本生文件完全相同）
```

---

## 三、图片类消息（输入: ImageItem）

### 9. 识别图片（Image Recognition）

```
用户发送一张图片
     │
     ▼
ILinkBotAdapter.handleMessage()
  → item.getImage_item() != null
  → handleImageMessage(userId, item)
     │
     ▼
1. client.downloadImageFromMessageItem(item) → byte[] imageBytes
   → CDN 下载，失败则 sendText("图片下载失败") 并返回

2. BotMessage(userId, null, imageBytes, null, null, RouteContext.TEXT)
   → SlashInterceptor → 不拦截
     │
     ▼
AgentLoop
  → chatMemory.add(userId, UserMessage("[用户发送了一张图片]"))
     │
     ▼
┌─ ThinkLoop.think() ────────────────────────────────────────┐
│ messages: ["[用户发送了一张图片]"]                           │
│ LLM 分析: 用户发了图片，需要识别                              │
│ LLM 从工具清单匹配: image_recognize(image="${message.image}") │
│ → ThinkResult(toolCalls=[image_recognize])                  │
└────────────────────────────────────────────────────────────┘
     │
     ▼
┌─ ActLoop.execute(image_recognize, {image:"${message.image}"}, msg, ...) ─┐
│ 1. argumentResolver.resolve({image:"${message.image}"}, msg)             │
│    → 检测到 "${message.image}" → msg.imageBytes() → byte[]              │
│    → resolvedArgs = {image: byte[]}                                       │
│                                                                           │
│ 2. toolRegistry.execute("image_recognize", {image: byte[]})               │
│    → ImageRecognitionTool.recognizeImage(imageBytes)                      │
│    → chatService.analyzeImage(imageBytes)                                 │
│      → ByteArrayResource → Media(MimeType("image/jpeg"))                 │
│      → ChatClient 多模态调用 qwen-vl-plus                                  │
│      → "请详细描述这张图片的内容..."                                       │
│      → LLM 返回: "这张图片显示了一只可爱的柴犬..."                          │
│    → return ActResult.success("这张图片显示了一只可爱的柴犬...")              │
│                                                                           │
│ 3. appendToMemory: ToolResponseMessage("image_recognize", "这张图片显示...") │
└───────────────────────────────────────────────────────────────────────────┘
     │
     ▼
ThinkLoop (第2轮): LLM 看到识别结果 → "这张图片显示了一只可爱的柴犬..."
AgentLoop → sender.sendText("这张图片显示了一只可爱的柴犬...") → exit
```

**关键点**:
- 图片在 `ILinkBotAdapter` 层通过 CDN 下载为 `byte[]`，存入 `BotMessage.imageBytes`
- LLM 使用占位符 `"${message.image}"` 引用图片数据
- `ArgumentResolver` 在执行前将占位符替换为实际 `byte[]`
- `ImageRecognitionTool` 调用 `qwen-vl-plus` 多模态模型识别

---

### 10. 修改图片（Image Editing）

> **注意**: 当前架构中图片编辑存在天然限制。微信将图片和文字作为 **两条独立消息** 下发，而 `AgentLoop` 每次只处理一条 `BotMessage`。

```
用户发送: [图片] + 文字 "改成黑白风格"
     │
     ▼
ILinkBotAdapter.handleMessage() 遍历 item_list:
  ├─ ImageItem: 下载图片 → BotMessage(userId, null, imageBytes, null, null, TEXT)
  │   → agentLoop.orchestrate()
  │   → ThinkLoop: 用户发了图片 → image_recognize → 识别描述
  │   → sender.sendText("这是一张彩色照片...")
  │   → finally: chatMemory.clear()  ← 记忆被清除
  │
  └─ TextItem: BotMessage(userId, "改成黑白风格", null, null, null, TEXT)
      → agentLoop.orchestrate()
      → ThinkLoop: 只有文字 "改成黑白风格"，无图片上下文
      → LLM 无法调用 image_edit（缺少 image 参数）
      → LLM 回复: "请发送一张图片，我来帮你修改"
```

**现状**: 图片编辑需要用户在**同一轮**中提供图片和编辑指令。当微信将它们拆分为两条消息时，第二条消息丢失了图片上下文。

**替代路径**（当 LLM 能够在第一条消息中同时调用 image_recognize 和 image_edit 时可用）:

```
ImageItem → BotMessage(userId, null, imageBytes, ...)
  → AgentLoop
  → ThinkLoop:
      LLM: image_edit(image="${message.image}", editPrompt="推断或询问编辑意图")
  → ActLoop:
      ImageRecognitionTool.editImage(imageBytes, editPrompt)
        → chatService.describeImageEdit(imageBytes, editPrompt)
          → qwen-vl-plus: 原图 + 编辑指令 → 生成新图片描述
        → imageGenService.generateImageUrl(生成描述)
        → imageGenService.downloadImage(url) → 新图片 byte[]
        → messageSender.sendImage(userId, 新图片)
        → return ActResult.success("图片已发送")
```

---

## 四、文件类消息（输入: FileItem）

用户发送文件（PDF/Word/Excel 等）时：

```
ILinkBotAdapter.handleMessage()
  → item.getFile_item() != null
  → handleFileMessage(userId, item)
     │
     ▼
1. 大小校验: fileLen > maxSizeMb → 拒绝并提示
2. client.downloadFileFromMessageItem(item) → byte[]
3. sender.sendText("收到文件「xxx」，正在分析…")
4. BotMessage(userId, null, null, fileBytes, fileName, RouteContext.TEXT)
     │
     ▼
AgentLoop
  → chatMemory.add(userId, UserMessage("[用户发送了文件: xxx.pdf]"))
     │
     ▼
ThinkLoop: LLM 看到文件 → file_analyze(fileBytes="${message.file}", fileName="${message.fileName}")
     │
     ▼
ActLoop:
  → argumentResolver.resolve: "${message.file}" → msg.fileBytes()
                              "${message.fileName}" → msg.fileName()
  → FileTool.analyzeFile(fileBytes, fileName)
    → FileRecognitionService.recognize("default", fileBytes, fileName)
    → Tika 提取文本 → qwen-plus 分析 → 返回摘要
  → appendToMemory: 分析结果
     │
     ▼
ThinkLoop (第2轮): LLM 基于分析结果回复用户
```

---

## 五、关键组件职责速查

| 组件 | 职责 | 输入 | 输出 |
|------|------|------|------|
| `ILinkBotAdapter` | 微信消息接收、CDN 下载、协议转换 | `WeixinMessage` | `BotMessage` |
| `SlashInterceptor` | / 命令预拦截 | `BotMessage` | `boolean` (是否消费) |
| `AgentLoop` | 编排器：记忆管理、安全阀、挂起/恢复 | `BotMessage` + `MessageSender` | 用户可见的回复 |
| `ThinkLoop` | 内循环：LLM 思考与决策 | `chatMemory` | `ThinkResult(finalAnswer?, toolCalls?)` |
| `ActLoop` | 外循环：工具执行与文件下发 | `ToolCall` + `BotMessage` | `ActResult` + 文件/图片/音频下发 |
| `ToolRegistry` | 工具注册与执行 | 工具名 + 参数 | `ActResult` |
| `ArgumentResolver` | 占位符 → 实际数据 | `Map` + `BotMessage` | `Map` (已解析) |
| `UserContextHolder` | ThreadLocal 用户上下文 | — | `userId`, `conversationId` |
| `SessionStateManager` | 挂起/恢复状态管理 | `userId` + `conversationId` | DB 读写 |
