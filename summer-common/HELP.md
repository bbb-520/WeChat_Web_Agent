# summer-common — 帮助文档

> 模块定位：**零依赖工具包（无 Spring）**。被 `summer-aigc` 依赖，为整个项目提供最底层的 POJO、枚举、常量、异常与提示词模板。

## 1. 职责

- 提供**跨模块共享**、且不依赖任何业务框架的基础类型
- 异常体系（含抽象基类与子类）
- 枚举（音色、路由上下文）
- 提示词键常量
- 外部提示词文本资源（`prompts/`）

由于不依赖 Spring，本模块可在任何 JVM 项目中复用，也避免了上层模块循环依赖。

## 2. 包结构

```
summer-common/
├── pom.xml
└── src/main/java/log/summer/common/
    ├── exception/                 # 异常体系
    │   ├── BotException.java          # 抽象基类
    │   ├── AIServiceException.java     # AI 调用异常 (operation)
    │   ├── ConfigurationException.java # 配置错误
    │   ├── FileRecognitionException.java # 文件识别异常 (stage)
    │   ├── ImageGenerationException.java  # 图片生成异常 (stage)
    │   └── VoiceSynthesisException.java   # 语音合成异常
    ├── enums/                     # 枚举
    │   ├── Timbre.java               # 12 种音色定义 + 匹配逻辑
    │   └── RouteContext.java         # TEXT / VOICE
    ├── constant/
    │   └── Prompts.java             # 提示词键常量
    └── resources/prompts/
        ├── system.txt               # 主对话系统提示词
        └── file-system.txt          # 文档分析系统提示词
```

## 3. 关键类说明

| 类 / 资源 | 说明 |
|-----------|------|
| `BotException` | 所有机器人异常的抽象基类，含 `message` / `cause` 构造 |
| `AIServiceException` | AI 调用失败，携带 `operation`（如 chat/analyzeImage）便于定位 |
| `FileRecognitionException` `ImageGenerationException` | 携带 `stage`（detect/extract/analyze 或 generate/download），便于分段报错 |
| `Timbre` | 12 种音色枚举，含 `matchKeyword()` 关键字匹配（如「萝莉」→ 龙小夏） |
| `RouteContext` | 消息路由上下文：`TEXT`（文本/图片/文件）与 `VOICE`（语音 ASR 后） |
| `Prompts` | `SYSTEM_PROMPT="system"`、`FILE_SYSTEM_PROMPT="file-system"`，对应 `classpath:prompts/*.txt` |
| `system.txt` `file-system.txt` | 纯文本提示词，由 `summer-aigc` 的 `AiConfig` 通过 `classpath:prompts/...` 加载 |

> ⚠️ **注意**：`GlobalExceptionHandler` **不在** 本模块。它依赖 `MessageSender` 端口（属于 `summer-aigc`），因此放在 `summer-aigc/config/` 下。

## 4. 依赖与构建

- **依赖**：仅 `lombok`（provided 作用域，编译期注解处理），不引入 Spring / AI / DB。
- **被谁依赖**：`summer-aigc` → `summer-bot` → `summer-bootstrap`（沿依赖链传递）。
- **构建**：
  ```bash
  mvn -pl summer-common compile      # 单独编译
  mvn -pl summer-common install      # 安装到本地仓库（bootstrap 运行前需先 install）
  ```

## 5. 扩展指引

- **新增异常类型**：在 `exception/` 下继承 `BotException`，保持 `message` + `cause` 构造签名一致。
- **新增枚举**：放入 `enums/`，保持零依赖（不要引用 `summer-aigc` 类）。
- **新增提示词**：在 `resources/prompts/` 放 `.txt`，并在 `Prompts` 中加对应常量键；上层通过 `classpath:prompts/<key>.txt` 引用。
- 任何需要 Spring / 数据库 / AI 的能力，**不要**放本模块——应下沉到 `summer-aigc`。

## 6. 相关文档

- [上层 README](../README.md)
- [summer-aigc/HELP.md](../summer-aigc/HELP.md)
