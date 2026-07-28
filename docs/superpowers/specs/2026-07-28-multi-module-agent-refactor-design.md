# Multi-Module Agent Refactor — Design Spec

**Date:** 2026-07-28
**Status:** Draft
**Author:** bbb

## 1. Motivation

The current project is a single-module Spring Boot WeChat bot using a one-shot dispatch model: `IntentClassifier` (qwen-turbo) classifies user intent → `AgentRouter` routes to one `Agent` → Agent executes once and returns. This has three problems:

1. **No multi-step reasoning.** A user request like "画一张日出的图片，然后用语音读一首关于日出的诗" requires two different agents but can only hit one.
2. **No self-correction.** If a tool fails, there is no feedback loop for the LLM to try a different approach.
3. **Tight coupling to WeChat.** ILink is embedded in the core service. Adding Feishu/Discord would require forking.

## 2. Goals

- Split into 4 Maven modules with clean, one-direction dependency
- Replace one-shot dispatch with a **dual-loop ReAct agent**: inner loop (LLM thinks and decides) → outer loop (tool execution) → repeat until complete
- Self-describing tools via Spring AI `@Tool` / `FunctionCallback` (OpenAI function-calling spec)
- Transport-agnostic architecture: ILink as one implementation of `BotInboundPort`
- Slash commands pre-intercepted before the agent loop
- Chat/RAG as the default fallback when no tool is needed

## 3. Module Layout

```
summer-dev/                    ← 父 POM (<packaging>pom), <dependencyManagement>, <modules>
│
├── summer-common/             ← 零依赖工具包（无 Spring）
│   └── log.summer.common/
│       ├── exception/         ← 抽象异常基类
│       ├── enums/             ← Timbre, RouteContext 等
│       ├── constant/          ← 常量
│       ├── util/              ← 纯工具类
│       └── prompts/           ← system.txt, file-system.txt
│
├── summer-aigc/               ← Agent 核心引擎
│   └── log.summer.aigc/
│       ├── port/              ← BotInboundPort, BotMessage, MessageSender 接口
│       ├── loop/              ← AgentLoop (外循环编排器), ThinkResult, ActResult, ArgumentResolver
│       ├── tool/              ← ToolRegistry + Spring AI @Tool 工具实现
│       ├── memory/            ← ChatMemory 适配 + UserMemory 长期记忆
│       ├── rag/               ← RAG 检索服务
│       ├── entity/            ← MyBatis-Plus 实体
│       ├── mapper/            ← MyBatis-Plus Mapper
│       ├── service/           ← ChatService, ChatPersistenceService
│       └── config/            ← AiConfig (ChatClient Bean)
│   └── resources/db/migration/ ← Flyway DDL
│
├── summer-bot/                ← 传输适配器层
│   └── log.summer.bot/
│       ├── ILinkBotAdapter    ← 实现 BotInboundPort (微信 ILink)
│       ├── SlashInterceptor   ← / 命令预拦截
│       └── RetrySender        ← 带重试的发送器
│
└── summer-bootstrap/          ← 启动 + 装配
    └── log.summer.bootstrap/
        ├── SummerApplication  ← @SpringBootApplication
        └── AssemblyConfig     ← 模块 Bean 装配
    └── resources/
        ├── application.yml
        ├── config/security.yml (optional, gitignored)
```

**Dependency chain:** `bootstrap → bot → aigc → common` (zero cycles).

## 4. Dual-Loop Agent Engine

### 4.1 Core Loop

```
用户消息到达 BotInboundPort
    │
    ▼ (BotMessage)
┌─────────────────────────────────────────────────────┐
│  AgentLoop.orchestrate(userId, message)              │
│                                                     │
│  while (round < maxIterations && !timeout):          │
│  ┌───────────────────────────────────────────────┐  │
│  │  THINK:                                       │  │
│  │  chatClient.prompt(messages)                  │  │
│  │             .tools(toolSchemas)               │  │
│  │             .call() → ThinkResult             │  │
│  │                                               │  │
│  │  if ThinkResult.hasFinalAnswer:                │  │
│  │      → send answer to user → EXIT LOOP        │  │
│  │                                               │  │
│  │  if ThinkResult.hasToolCalls:                  │  │
│  │      → for each toolCall:                      │  │
│  │  ┌─────────────────────────────────────────┐  │  │
│  │  │  ACT:                                    │  │  │
│  │  │  ArgumentResolver.resolve(toolCall.args) │  │  │
│  │  │  ToolRegistry.execute(tool) → ActResult  │  │  │
│  │  │  Append tool result to message history   │  │  │
│  │  │  if ActResult.errorMessage:              │  │  │
│  │  │      → LLM sees error, can self-correct  │  │  │
│  │  └─────────────────────────────────────────┘  │  │
│  │  CONTINUE (next think round with new context)  │  │
│  └───────────────────────────────────────────────┘  │
│                                                     │
│  终止: finalAnswer | maxIterations=10 | timeout=120s│
└─────────────────────────────────────────────────────┘
```

### 4.2 Key Components

| Component | Module | Role |
|-----------|--------|------|
| `AgentLoop` | aigc/loop | 外循环编排器。维护 while 迭代，检查安全阀（maxIterations=10, timeout=120s），管理 ChatMemory 增删，决定终止并返回最终答案。负责解析 Spring AI `ChatResponse` → `ThinkResult` |
| `ThinkResult` | aigc/loop | 封装当前轮 LLM 响应。从 Spring AI `ChatResponse` 中提取。字段：`finalAnswer` (String, optional), `toolCalls` (List<{name, arguments}>, optional)。二者可同时存在（LLM 可既回答又调用工具） |
| `ActResult` | aigc/loop | 封装工具执行结果。字段：`success` (boolean), `data` (Object, nullable), `errorMessage` (String, nullable) |
| `ArgumentResolver` | aigc/loop | 处理 LLM 生成的参数中的占位符，在执行前完成字节替换。支持的占位符：`"${message.image}"` (当前消息图片字节), `"${message.file}"` (当前消息文件字节), `"${message.fileName}"` (原始文件名)。若 LLM 传了占位符但对应资源为 null → 直接返回 ActResult.failure 让 LLM 纠正 |
| `ToolRegistry` | aigc/tool | name → Tool 映射，生成 `List<FunctionCallback>` 供 LLM 调用 |

### 4.3 Spring AI Role

Spring AI is used as a **stateless utility**, not an orchestrator:

- **Single call:** `chatClient.prompt(messages).tools(schemas).call()` — one round of the loop
- **Schema generation:** Spring AI auto-generates JSON Schema from `@Tool` annotated methods
- **Never:** chain calls, decide iteration, manage memory, or terminate the loop

### 4.4 ChatService

`ChatService` (in `summer-aigc/service/`) wraps the raw ChatClient calls used by `AgentLoop`:

- `chatWithTools(messages, toolSchemas)` → `ChatResponse` (for think rounds with tool catalog)
- `chat(messages)` → `ChatResponse` (for final answer / fallback rounds without tools)
- `chatWithRAG(messages, docs)` → `ChatResponse` (for fallback when RAG finds relevant documents)

`ChatService` also assembles the augmented system prompt (DB history + long-term memory). `AgentLoop` calls it for each think round; the service is stateless — all state lives in `AgentLoop`.

## 5. Tools

### 5.1 Tool Definition

All tools use Spring AI's `@Tool` annotation + `FunctionCallback`. No custom `Tool` interface.

```java
// Example
@Component
public class WeatherTool {

    @Tool(description = "查询指定城市今天或明天的天气")
    public ActResult weatherQuery(
        @ToolParam(description = "城市名称，例如 '北京'、'上海'") String city,
        @ToolParam(description = "'today' 或 'tomorrow'") String day) {
        // ... implementation
        return ActResult.success(weatherData);
        // or: return ActResult.failure("该城市暂无天气数据");
    }
}
```

**Contract:** Every `@Tool` method MUST return `ActResult`. The `errorMessage` field enables LLM self-correction when a tool fails.

### 5.2 Tool Catalog

| Tool Name | Description | Current Source |
|-----------|-------------|----------------|
| `weather_query` | 查询城市天气 | WeatherAgent |
| `image_generate` | 文生图 | ImageGenAgent |
| `image_recognize` | 图片识别 | ImageRecognitionAgent |
| `image_edit` | 图片编辑 | ImageRecognitionAgent |
| `tts_synthesize` | 文字转语音 | VoiceGenAgent |
| `voice_switch` | 切换音色 | VoiceGenAgent |
| `file_analyze` | 文件内容分析与提取 | FileAgent |
| `idiom_game` | 成语接龙游戏 | IdiomGameService |
| `reminder_set` | 设置提醒 | ReminderTools |
| `navigation` | 导航查询 | NavigationTools |
| `memory_status` | 查询 Bot 运行时状态 | MemoryMonitorTools |

**Not in catalog:** Chat/RAG (fallback), slash commands (pre-intercepted).

## 6. BotInboundPort & Transport

### 6.1 Port Interface (summer-aigc)

```java
public interface BotInboundPort {
    void onMessage(BotMessage msg);
}

public record BotMessage(
    String userId,
    String text,
    byte[] imageBytes,
    byte[] fileBytes,
    String fileName,
    RouteContext context
) {}

public interface MessageSender {
    void sendText(String userId, String content);
    void sendImage(String userId, byte[] imageBytes, String filename, String description);
    void sendFile(String userId, byte[] fileBytes, String filename, String description);
}
```

### 6.2 SlashInterceptor (summer-bot)

Invoked inside the adapter **before** `AgentLoop`:

| Command | Behavior |
|---------|----------|
| `/help` | 列出所有可用命令 |
| `/status` | 返回 Bot 运行时指标 |
| `/cancel` | 清空当前会话 + 确认消息 |
| 未知 `/xxx` | "未知命令，输入 /help 查看可用命令" |

If `intercept()` returns `true`, the message never reaches `AgentLoop`.

### 6.3 ILinkBotAdapter (summer-bot)

Implements `BotInboundPort`. **Replaces the current `ILinkBotService` class.** The existing logic for ILink connection/session management, message deserialization (text/image/voice/file), CDN download, and sending with retry all move into this single adapter. The old `ILinkBotService` is deleted.

## 7. Memory & Fallback

### 7.1 ChatMemory (per-session, JVM)

Spring AI's `MessageWindowChatMemory` (20 messages sliding window). Managed exclusively by `AgentLoop`:
- Each think/act round appends tool calls + results to the history
- On termination or `/cancel`, memory is cleared

### 7.2 Chat/RAG Fallback

When the LLM determines no tool is needed (returns `finalAnswer` with no `toolCalls`):
- **TEXT context:** RAG retrieval runs first. If relevant documents found → `chatWithRAG()`. Otherwise → plain `chat()`.
- **VOICE context:** Plain `chat()`, result sent via TTS.

### 7.3 Long-Term Memory

`UserMemory` persists extracted facts from conversations (unchanged from current implementation).

### 7.4 Session-Scoped State (JVM)

Two current state holders move to `summer-aigc`:

| State Holder | New Location | Purpose |
|-------------|-------------|---------|
| `TimbreSession` | aigc/tool/ | Tracks per-user voice selection. Shared across `tts_synthesize` and `voice_switch` tools. |
| `BotMetrics` | aigc/tool/ | Runtime message/error counters. Exposed via `/status` command and `memory_status` tool. |
| `ImageCacheManager` | aigc/tool/ | Manages pending image cache for edit operations. |

## 8. Error Handling

| Layer | Mechanism | Behavior |
|-------|-----------|----------|
| Tool execution | `ActResult.errorMessage` | Tool catches errors, returns failure with descriptive message. LLM reads it and self-corrects or informs user. |
| AgentLoop | Safety valve | `maxIterations=10` or `timeout=120s` → force-terminate. Reply: "我暂时无法完成这个任务，请稍后再试。" |
| Global | `GlobalExceptionHandler` (common) | Catch-all for unhandled transport/loop exceptions. |

## 9. Persistence

- **Framework:** MyBatis-Plus (unchanged)
- **Migrations:** Flyway replacing `schema.sql` always-init
- **Migration path:** `summer-aigc/src/main/resources/db/migration/`
- **Entities:** All existing entities moved to `summer-aigc/entity/`
- **Mappers:** All existing mappers moved to `summer-aigc/mapper/`

## 10. What Gets Removed

| Removed | Reason |
|---------|--------|
| `Intent.java` enum | Replaced by tool schema matching |
| `IntentClassifier.java` | Replaced by LLM function-calling decision |
| `AgentRouter.java` | Replaced by AgentLoop orchestration |
| `Agent.java` interface | Replaced by Spring AI @Tool |
| `AgentContext.java` | Replaced by BotMessage + AgentLoop internal state |
| `CommandAgent.java` | Replaced by SlashInterceptor |
| `CommandRegistry.java` | Replaced by SlashInterceptor switch |
| `Command.java` | Replaced by inline handlers |

## 11. Config & Resource Ownership

| Resource | Owner Module |
|----------|-------------|
| `application.yml` | summer-bootstrap |
| `config/security.yml` | summer-bootstrap (optional, gitignored) |
| `config/ai.yml` | summer-aigc |
| `config/bot.yml` | summer-aigc |
| `prompts/system.txt` | summer-common |
| `prompts/file-system.txt` | summer-common |
| `db/migration/*.sql` | summer-aigc (Flyway) |

## 12. Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Tool schema mismatch → LLM hallucinates parameters | Spring AI enforces strict JSON Schema; validation before execution |
| Loop runs too many rounds → high token cost | Hard cap at 10 iterations; conversation-level token limit |
| Flyway migration breaks existing data | Flyway versioned, incremental; existing DB compatible |
| ILink SDK upgrade breaks | Adapter isolates all ILink code in one class; easy to update |

## 13. Success Criteria

1. All existing features (chat, image gen, TTS, weather, file analysis, idiom game, reminders, navigation) work end-to-end
2. A multi-step request (e.g. "查天气 → 生成图片 → 语音播报") completes in a single user session
3. Tool failure (e.g. weather API down) results in LLM informing the user gracefully, not a stack trace
4. `/help`, `/status`, `/cancel` work identically to before
5. Adding a Feishu adapter requires only: implement `BotInboundPort` + register in `summer-bot` — no changes to `summer-aigc`