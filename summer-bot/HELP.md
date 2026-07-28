# summer-bot — 帮助文档

> 模块定位：**传输适配器层**。把微信 ILink 的收发细节封装起来，对上层暴露统一的 `BotInboundPort` / `MessageSender` 端口。新增其他平台（飞书 / Discord）只需在本模块加一个适配器，无需改动 `summer-aigc`。

## 1. 职责

- 实现 `BotInboundPort`：接收平台消息 → 转换为归一化 `BotMessage` → 进入引擎
- 实现 `MessageSender`：把文本/图片/文件发回平台（图片文件带重试）
- 预拦截 slash 命令（`/help` `/status` `/cancel`）
- 媒体下载（图片/文件 CDN）、大小校验、ASR 文本提取
- 消息与 Bot 回复的 DB 持久化（委托 `ChatPersistenceService`）

## 2. 依赖

- **依赖**：`summer-aigc`（取端口/引擎/指标/持久化）、`wechat-ilink-sdk`、Lombok(provided)
- **被依赖**：`summer-bootstrap`
- **依赖方向**：`bot → aigc`，因此本模块可访问 `AgentLoop`、`BotMetrics`、`ChatPersistenceService`、`MessageSender` 端口，但 `aigc` 完全不知道 `bot` 的存在。

## 3. 包结构

```
summer-bot/
└── log/summer/bot/
    ├── ILinkBotAdapter.java   # 实现 BotInboundPort + MessageSender
    ├── SlashInterceptor.java  # / 命令预拦截
    └── RetrySender.java       # 带重试的发送器
```

## 4. 关键类说明

| 类 | 说明 |
|----|------|
| `ILinkBotAdapter` | `@Service`，核心适配器。`@PostConstruct` 在虚拟线程中启动 ILink 客户端并等待扫码登录；`onMessage(BotMessage)` 先过 `SlashInterceptor`，未消费则 `AgentLoop.orchestrate(msg, this)`。内部按 `VoiceItem/TextItem/ImageItem/FileItem` 分别构建 `BotMessage` 并下载 CDN 媒体。`sendText/sendImage/sendFile` 实现 `MessageSender`，文本直发，图片/文件经 `RetrySender` 重试。 |
| `SlashInterceptor` | `@Component`，`intercept(msg, sender)`：以 `/` 开头时匹配 `/help` `/status` `/cancel`，命中返回 `true`（消息被消费）；未知 `/xxx` 提示帮助。非 `/` 开头直接返回 `false` 转发引擎。`/cancel` 会清空该用户 `ChatMemory`。 |
| `RetrySender` | 工具类，`sendWithRetry(userId, runnable, mediaType, filename, fallbackSender)`：指数退避 1s→2s→4s，最多 3 次；全部失败走 `fallbackSender` 给用户提示。 |

### 4.1 消息处理流程

```
ILink 回调 WeixinMessage
  → handleMessage(): 遍历 item_list
      VoiceItem  → ASR 文本 → BotMessage(VOICE)  → SlashInterceptor? → AgentLoop
      TextItem   → 文本      → BotMessage(TEXT)   → SlashInterceptor? → AgentLoop
      ImageItem  → 下载字节   → BotMessage(含 imageBytes) → AgentLoop
      FileItem   → 大小校验+下载 → BotMessage(含 fileBytes+fileName) → AgentLoop
  → 每条用户/Bot 消息经 ChatPersistenceService 落库（message 表）
```

> 注意：文本消息若与语音 ASR 文本重复会被跳过（微信常同时下发语音与转写文本）。

## 5. 构建

```bash
mvn -pl summer-common,summer-aigc,summer-bot install
mvn -pl summer-bot compile
```

## 6. 扩展指引：新增一个传输平台（如飞书）

1. 在本模块新建类 `FeishuBotAdapter implements BotInboundPort, MessageSender`；
2. 自行拉起平台长连接/Webhook，把入站消息转换为 `BotMessage`（图片用 `${message.image}` 占位符对应的真实字节放进 `imageBytes`）；
3. 在 `onMessage` 中先 `slashInterceptor.intercept(msg, this)`，未消费则 `agentLoop.orchestrate(msg, this)`；
4. 实现三个 `MessageSender` 方法（文本直发、媒体用 `RetrySender`）；
5. **无需改动 `summer-aigc` 任何代码**——这正是端口隔离设计的目的。

## 7. 注意事项

- 媒体下载失败、文件超限等用户可见提示都在本层产生；工具层只负责业务能力。
- `ILinkBotAdapter` 同时是 `MessageSender`，因此 `AgentLoop` 调用 `sender.sendXxx` 时实际落回本适配器发送。
- 登录状态 `isRunning` 控制发送是否真正外发；未登录时仅落库不发送。

## 8. 相关文档

- [上层 README](../README.md)
- [summer-aigc/HELP.md](../summer-aigc/HELP.md)
