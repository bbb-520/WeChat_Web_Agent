# Bug Fix: File Delivery & Tool Call Tolerance — Design Spec

> **日期:** 2026-07-29 | **作者:** bbb | **类型:** Bug 修复 + 架构加固

**问题 A:** 生成文件时 Bot 返回无法打开的网址链接（而非文件本身）。
**问题 B:** 大模型自创工具名（如 `draw`）导致异常，会话中断。

---

## 一、问题 A：文件以链接形式返回

### 现状诊断

当前文件发送路径分散在两个层面：

| 文件类型 | 当前实现 | 问题 |
|---------|---------|------|
| 图片生成 | `ImageGenTool` 内部调用 `MessageSender.sendImage()` | 正确 —— 但逻辑分散在工具层 |
| 图片编辑 | `ImageRecognitionTool.editImage()` 内部调用 `MessageSender.sendImage()` | 同上 |
| 语音合成 | `TtsTool.synthesize()` 内部调用 `MessageSender.sendFile()` | 同上 |
| 文档生成 | `GenerateDocumentTool` 在 result Map 中放 `fileBytes`/`fileName`，AgentLoop 检测 Map 后调用 `sendFile()` | 文档路径正确，但与图片/语音路径不一致 |

**问题根源：** 文件分发逻辑分散在工具层和 AgentLoop 层两处，没有统一的文件抽象。部分工具返回 text 时可能意外包含 URL，或 LLM 自身在 THINK 阶段生成 URL 并直接输出给用户。此外，ActResult 没有一等公民的文件字段，导致文件传递依赖隐式约定（Map key 名）。

### 设计

#### FileType 枚举

```java
// summer-aigc/src/main/java/log/summer/aigc/loop/FileType.java
package log.summer.aigc.loop;

public enum FileType {
    IMAGE,    // → MessageSender.sendImage()，限制 10MB
    AUDIO,    // → MessageSender.sendFile()，限制 20MB
    DOCUMENT  // → MessageSender.sendFile()，限制 20MB
}
```

#### ActResult 扩展

新增三个字段和配套工厂方法：

```java
public record ActResult(
    boolean success, Object data, String errorMessage,
    boolean suspend, String suspendReason, boolean requiresConfirmation,
    // ── NEW: 媒体文件字段 ──
    byte[] fileBytes,
    String fileName,
    FileType fileType
) {
    /** 文件不为空且长度 > 0 */
    public boolean hasFile() {
        return fileBytes != null && fileBytes.length > 0;
    }

    // 通用文件工厂
    public static ActResult file(byte[] bytes, String name, FileType type) {
        return new ActResult(true,
                "文件已通过微信发送: " + name,
                null, false, null, false,
                bytes, name, type);
    }

    // 快捷工厂
    public static ActResult image(byte[] bytes, String name) {
        return file(bytes, name, FileType.IMAGE);
    }

    public static ActResult audio(byte[] bytes, String name) {
        return file(bytes, name, FileType.AUDIO);
    }

    public static ActResult document(byte[] bytes, String name) {
        return file(bytes, name, FileType.DOCUMENT);
    }

    // ── 向后兼容的旧工厂（fileBytes=null） ──
    public static ActResult success(Object data) {
        return new ActResult(true, data, null, false, null, false, null, null, null);
    }

    public static ActResult failure(String errorMessage) {
        return new ActResult(false, null, errorMessage, false, null, false, null, null, null);
    }

    public static ActResult suspend(Object data, String reason) {
        return new ActResult(true, data, null, true, reason, true, null, null, null);
    }

    public static ActResult confirmRequired(Object data, String reason) {
        return new ActResult(true, data, null, true, reason, true, null, null, null);
    }
}
```

向后兼容：所有旧工厂方法 `fileBytes` 设为 `null`，现有调用点无需修改。

#### AgentLoop 文件分发逻辑

在 ACT 阶段，`toolRegistry.execute()` 之后新增统一分发：

```
result = toolRegistry.execute(...)

if result.hasFile():
    // 1. 大小校验
    maxSize = fileType == IMAGE ? 10MB : 20MB
    if too large:
        sender.sendText(userId, "文件过大 …，请简化")
        chatMemory.add(userId, failure-text)
        continue

    // 2. 根据类型分发
    switch fileType:
        IMAGE    → sender.sendImage(userId, bytes, fileName, description)
        AUDIO    → sender.sendFile(userId, bytes, fileName, description)
        DOCUMENT → sender.sendFile(userId, bytes, fileName, description)

    // 3. 注入文本给 LLM 参考
    chatMemory.add(userId, new ToolResponseMessage(..., result.data().toString()))
    continue  // 不进入普通文本分支
```

**data 规范：** 当 `hasFile() == true` 时，`data` 统一格式为 `"文件已通过微信发送: {fileName}"`。AgentLoop 将此文本注入 ChatMemory，供后续 THINK 引用。

#### 迁移清单

以下工具移除自身内部对 `MessageSender` 的注入和调用，改为返回 `ActResult.file()` / `ActResult.image()` / `ActResult.audio()` / `ActResult.document()`：

| 工具 | 方法 | 改动 |
|------|------|------|
| `ImageGenTool` | `generateImage()` | 删除 `messageSender` 字段 + import；删除 `sendImage()` 调用；`return ActResult.image(bytes, "ai-gen.png")` |
| `ImageRecognitionTool` | `editImage()` | 删除 `messageSender` 字段 + import；删除 `sendImage()` 调用；`return ActResult.image(bytes, "edited-image.png")` |
| `TtsTool` | `synthesize()` | 删除 `messageSender` 字段 + import；删除 `sendFile()` 调用；`return ActResult.audio(wav, "tts-{ts}.wav")` |
| `GenerateDocumentTool` | `generateDocument()` | 从 result Map 中移除 `fileBytes`，返回 `ActResult.document(bytes, fileName)`，其余 metadata 放 `data` |

清理后，`MessageSender` 仅由 AgentLoop 持有 —— 单一职责。

---

## 二、问题 B：大模型自行发明工具名

### 现状诊断

LLM 的 THINK 阶段可能返回不存在于 `ToolRegistry.callbackMap` 中的工具名（如 `draw`）。当前 ToolRegistry.execute() 返回 `ActResult.failure("Unknown tool: xxx")`，但：

1. 错误信息未列出可用工具列表，LLM 无法自我纠正，可能重复调用失败
2. 所有 @Tool 的 description 未显式声明自己的函数名
3. 系统提示词未列出工具清单，LLM 缺乏参考
4. 启动日志未打印注册的工具名，运维无法快速核验

### 设计：四层防线

#### 防线 1：AgentLoop ACT 阶段容错

在 `orchestrate()` 和 `resumeOrchestrate()` 的 `toolRegistry.execute()` 调用外层，新增 `IllegalStateException` 捕获：

```java
try {
    Map<String, Object> resolvedArgs = argumentResolver.resolve(toolCall.arguments(), msg);
    result = toolRegistry.execute(toolCall.name(), resolvedArgs);
} catch (ArgumentResolver.PlaceholderResolutionException e) {
    result = ActResult.failure(e.getMessage());
} catch (IllegalStateException e) {
    String available = toolRegistry.getAvailableToolNames();
    result = ActResult.failure(
        "工具 '" + toolCall.name() + "' 不存在或调用失败。" +
        "当前可用工具（必须使用准确的函数名）: " + available);
}
```

`ToolRegistry` 新增方法：
```java
public String getAvailableToolNames() {
    return callbackMap.keySet().stream()
            .sorted()
            .collect(Collectors.joining(", "));
}
```

#### 防线 2：@Tool 描述强化

所有 @Tool annotation 的 `description` 追加函数名声明。格式为：`"…。调用此工具时必须使用函数名 {name}"`。

例如：
```java
@Tool(name = "image_generate",
      description = "根据文字描述生成图片。调用此工具时必须使用函数名 image_generate")
@Tool(name = "tts_synthesize",
      description = "将文字转为语音朗读。调用此工具时必须使用函数名 tts_synthesize")
```

遍历所有现有工具（`FileTool`、`IdiomGameTool`、`ImageGenTool`、`ImageRecognitionTool`、`MemoryStatusTool`、`NavigationTool`、`ReminderTool`、`TextTool`、`TtsTool`、`WeatherTool`、`CreateOutlineTool`、`ConfirmOutlineTool`、`GenerateDocumentTool`），逐一添加。

#### 防线 3：系统提示词工具清单

在 `summer-common/src/main/resources/prompts/system.txt` 末尾追加：

```
## 可用工具清单（必须使用准确名称）

调用工具时必须使用以下准确的函数名，禁止自行编造、猜测或创造不存在的函数名：

| 函数名 | 用途 |
|--------|------|
| image_generate | 根据文字描述生成图片 |
| image_recognize | 识别图片内容并返回文字描述 |
| image_edit | 根据文字描述修改或编辑图片 |
| tts_synthesize | 将文字转为语音朗读 |
| voice_switch | 切换语音合成使用的音色 |
| weather_query | 查询指定城市今天或明天的天气 |
| file_analyze | 分析上传的文件内容 |
| createOutline | 生成文档结构化大纲（WORD/PPT/EXCEL），供用户确认 |
| confirmOutline | 确认大纲内容，允许附带修改 |
| generateDocument | 根据已确认的大纲渲染生成 Word/PPT/Excel 文件 |
| idiom_game_start | 开始成语接龙游戏 |
| idiom_game_next | 成语接龙下一轮 |
| reminder_set | 设置提醒 |
| navigation_open | 打开导航 |
| memory_status | 查看记忆状态 |

如果用户的需求超出以上工具的能力范围，请直接告知用户，不要自行编造函数名。
不要调用 list 中不存在的工具。
```

#### 防线 4：启动时打印工具注册清单

在 `ToolRegistry.@PostConstruct scan()` 末尾添加详细日志：

```java
log.info("[TOOL-REGISTRY] 已注册 {} 个工具: {}",
        callbackMap.size(),
        callbackMap.keySet().stream()
                .sorted()
                .collect(Collectors.joining(", ")));
```

启动日志输出示例：
```
[TOOL-REGISTRY] Scanned 15 tool beans → 15 ToolCallbacks
[TOOL-REGISTRY] 已注册 15 个工具: confirmOutline, createOutline, file_analyze, generateDocument,
  idiom_game_next, idiom_game_start, image_edit, image_generate, image_recognize,
  memory_status, navigation_open, reminder_set, tts_synthesize, voice_switch, weather_query
```

---

## 三、涉及的文件清单

```
summer-aigc/src/main/java/log/summer/aigc/loop/
  FileType.java                     ← NEW: 文件类型枚举
  ActResult.java                     ← MODIFY: 新增 fileBytes/fileName/fileType + 工厂方法
  AgentLoop.java                     ← MODIFY: 统一文件分发 + IllegalStateException 容错
summer-aigc/src/main/java/log/summer/aigc/tool/
  ToolRegistry.java                  ← MODIFY: getAvailableToolNames() + 注册清单日志
  image/ImageGenTool.java            ← MODIFY: 移除 MessageSender, 返回 ActResult.image()
  image/ImageRecognitionTool.java    ← MODIFY: 同上
  voice/TtsTool.java                 ← MODIFY: 移除 MessageSender, 返回 ActResult.audio()
  document/GenerateDocumentTool.java ← MODIFY: 返回 ActResult.document()
  [所有 @Tool].java                  ← MODIFY: description 追加函数名声明
summer-common/src/main/resources/prompts/system.txt ← MODIFY: 追加工具清单
summer-aigc/src/test/java/log/summer/aigc/loop/
  ActResultTest.java                 ← MODIFY: 新增 hasFile/file 工厂测试
summer-aigc/src/test/java/log/summer/aigc/tool/
  [迁移工具对应的测试]                ← MODIFY: 适配新返回类型
```

---

## 四、验证标准

1. 发送图片/语音/PPT/Word/Excel 请求后，用户收到可打开的文件，Bot 不返回任何 URL
2. 发送"生成一只小狗"后，LLM 若尝试调用 `draw`，AgentLoop 返回错误提示（包含可用工具列表），LLM 在下轮自行纠正为 `image_generate`，会话不中断
3. 启动日志打印完整工具注册清单
4. 所有现有测试通过，新增 FileType/ActResult.hasFile() 测试通过
