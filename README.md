# Summer Bot — 微信 AI 机器人

> **技术栈:** Spring Boot 3.2 + Java 21 + Spring AI 1.0.0 + DashScope + MySQL
> **架构:** 双循环 Agent（内循环思考 + 外循环执行）

---

## 一、架构概览

### 双循环 Agent 引擎

```
用户消息
  │
  ▼
┌──────────────────────────────────────────┐
│           AgentLoop (编排器)              │
│  • ChatMemory 生命周期                    │
│  • 安全阀 (10轮 / 300s)                  │
│  • 挂起 / 恢复 (文档确认流程)             │
└────┬──────────────────────┬──────────────┘
     │                      │
┌────▼──────┐        ┌──────▼─────┐
│ ThinkLoop │  tool  │  ActLoop   │
│ (内循环)   │ ────→  │  (外循环)   │
│           │ ←────  │            │
│ • LLM思考 │ result │ • 工具执行  │
│ • 决策    │        │ • 参数解析  │
│ • 规划    │        │ • 文件下发  │
└───────────┘        └────────────┘
```

- **内循环（ThinkLoop）**：纯 LLM 交互。接收对话上下文 + 工具清单 → 决定下一步（调工具 or 回复用户）
- **外循环（ActLoop）**：纯工具执行。接收工具名 + 参数 → 解析占位符 → 执行 → 回灌结果 + 文件下发
- **编排器（AgentLoop）**：协调内外循环，管理 ChatMemory 生命周期和会话挂起/恢复

### 核心流程

```
用户: "画一只猫"
  │
  ├─ 内循环 ThinkLoop.think()
  │   → LLM: "调用 draw(prompt='一只猫')"
  │   → ThinkResult(toolCalls=[draw])
  │
  ├─ 外循环 ActLoop.execute(draw, {prompt: '一只猫'})
  │   → ImageGenService 生成图片
  │   → MessageSender 发送图片给用户
  │   → 结果回灌 ChatMemory
  │
  ├─ 内循环 ThinkLoop.think()
  │   → LLM: "图片已生成！"
  │   → ThinkResult(finalAnswer="图片已生成！")
  │
  └─ AgentLoop 发送最终答案 → 用户收到图片 + 文字
```

---

## 二、能力清单

| 能力 | 工具名 | 参数 | 触发示例 |
|------|--------|------|---------|
| 🎨 画图 | `draw` | `prompt` | "画一只猫" |
| 🖼️ 图片识别 | `image_recognize` | `image` | 发图片 |
| ✏️ 图片编辑 | `image_edit` | `image`, `editPrompt` | 发图片+"改成黑白" |
| 🔊 文字转语音 | `tts` | `text` | "朗读你好" |
| 🎤 切换音色 | `voice_switch` | `voice` | "换萝莉音" |
| ☀️ 查天气 | `weather_query` | `city`, `day` | "北京天气" |
| 📄 文件分析 | `file_analyze` | `fileBytes`, `fileName` | 发文件 |
| 📝 文档生成 | `createOutline`→`confirmOutline`→`generateDocument` | 见文档生成流程 | "生成PPT" |
| 🗺️ 导航/路况 | `navigation` | `destination` | "从A到B" |
| 🎮 成语接龙 | `idiom_game` | `input` | "/cy start" |
| ⏰ 定时提醒 | `reminder_set` | `time`, `message` | "30分钟后提醒" |
| 💾 运行状态 | `memory_status` | — | "/memory" |

---

## 三、项目结构

```
src/main/java/log/summer/
├── SummerApplication.java              ← 启动入口
├── bot/
│   ├── ILinkBotAdapter.java           ← 微信适配器
│   ├── SlashInterceptor.java          ← / 命令拦截
│   └── RetrySender.java               ← 发送重试
├── aigc/
│   ├── port/                          ← MessageSender, BotMessage, BotInboundPort
│   ├── loop/                          ← AgentLoop, ThinkLoop, ActLoop, ThinkResult, ActResult
│   ├── context/                       ← UserContextHolder (ThreadLocal 用户上下文)
│   ├── tool/
│   │   ├── ToolRegistry.java          ← @Tool 扫描 + 执行
│   │   ├── image/                     ← ImageGenTool (draw + image_generate), ImageRecognitionTool
│   │   ├── voice/                     ← TtsTool, TTSEngine, TimbreSession
│   │   ├── document/                  ← GenerateDocumentTool, DocumentGenerator
│   │   ├── outline/                   ← CreateOutlineTool, ConfirmOutlineTool
│   │   ├── weather/                   ← WeatherTool
│   │   ├── navigation/                ← NavigationTool
│   │   ├── file/                      ← FileTool
│   │   ├── idiom/                     ← IdiomGameTool
│   │   ├── reminder/                  ← ReminderTool
│   │   └── memory/                    ← MemoryStatusTool
│   ├── service/                       ← ChatService, ChatPersistenceService
│   ├── entity/                        ← MyBatis-Plus 实体
│   ├── mapper/                        ← Mapper 接口
│   ├── session/                       ← SessionStateManager (挂起/恢复)
│   ├── rag/                           ← RAG 检索
│   └── config/                        ← AiConfig, BotProperties, ExceptionHandler
├── common/
│   ├── exception/                     ← 异常体系
│   └── enums/                         ← Timbre, RouteContext
└── resources/
    ├── prompts/system.txt              ← 系统提示词
    └── db/migration/                   ← Flyway 迁移
```

---

## 四、文档生成流程

```
createOutline(type, title, outlineJson)
  → 大纲写入 DB (DRAFT)，AgentLoop 挂起
  → 用户看到大纲

confirmOutline(outlineId, modifiedOutlineData?)
  → DRAFT → CONFIRMED

generateDocument(type, outlineId)
  → Apache POI 渲染 .docx/.pptx/.xlsx
  → 文件自动下发给用户
```

---

## 五、关键设计

### UserContextHolder
工具方法不再需要 LLM 传入 userId——通过 ThreadLocal 在请求入口注入：

```
AgentLoop → UserContextHolder.setUserId(userId)
    → @Tool method → UserContextHolder.getUserId()
AgentLoop.finally → UserContextHolder.clear()
```

### ArgumentResolver
工具参数中的占位符在执行前替换：

| 占位符 | 替换为 |
|--------|--------|
| `"${message.image}"` | `BotMessage.imageBytes()` |
| `"${message.file}"` | `BotMessage.fileBytes()` |
| `"${message.fileName}"` | `BotMessage.fileName()` |

### 多重防御工具回调注入
`ChatService.chatWithTools()` 通过三条通道注入工具回调，确保 `DashScopeChatModel` 能找到：

1. `DashScopeChatOptions.withToolCallbacks()` — 通过 Builder
2. `ChatClient.prompt().toolCallbacks()` — 通过 PromptSpec
3. `ToolCallbackProvider` Bean — 全局回退解析器

同时设置 `internalToolExecutionEnabled = false` 禁止模型内部自动执行工具，保持 AgentLoop 手动控制。

---

## 六、构建与运行

```bash
# 编译
mvn compile

# 测试 (53 tests)
mvn test

# 启动
mvn spring-boot:run
```

前置条件：JDK 21, Maven 3.9+, MySQL 8.x（库 `wxbot_db`）, DashScope API Key, 高德 API Key。
