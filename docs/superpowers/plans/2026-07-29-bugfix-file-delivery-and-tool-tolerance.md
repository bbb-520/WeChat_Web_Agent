# Bug Fix: File Delivery & Tool Call Tolerance — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two bugs: (A) file results leaking as unopenable URLs instead of direct WeChat delivery, (B) LLM-invented tool names crashing the session with no self-correction path.

**Architecture:** Two-pronged: (1) Promote file bytes to first-class `ActResult` fields with typed `FileType` enum, centralize all file dispatch in `AgentLoop`, and remove scattered `MessageSender` calls from individual tools. (2) Add four-layer defense against hallucinated tool names — AgentLoop IllegalStateException catch with available-tool listing, reinforced `@Tool` descriptions, system prompt tool catalog, and startup registration logging.

**Tech Stack:** Java 21, Spring Boot 3.2.10, Spring AI Alibaba DashScope, MyBatis-Plus, Lombok 1.18.46

## Global Constraints

- Java 21, Spring Boot 3.2.10 parent POM
- Lombok 1.18.46
- Every `@Tool` method MUST return `ActResult`
- `ActResult` remains a Java record — all fields immutable via canonical constructor
- Backward compatibility: all existing factory methods (`success`, `failure`, `suspend`, `confirmRequired`) continue to work; new `fileBytes`/`fileName`/`fileType` fields default to `null`
- `MessageSender` is only injected/called by `AgentLoop` after migration — tools must NOT inject it
- File size limits: IMAGE ≤ 10MB, AUDIO/DOCUMENT ≤ 20MB
- `hasFile()` returns true iff `fileBytes != null && fileBytes.length > 0`
- `data` format when `hasFile()` is true: `"文件已通过微信发送: {fileName}"`

---

## File Structure

```
summer-aigc/src/main/java/log/summer/aigc/
  loop/
    FileType.java                  ← NEW: enum IMAGE/AUDIO/DOCUMENT
    ActResult.java                 ← MODIFY: add fileBytes/fileName/fileType + factories + hasFile()
    AgentLoop.java                 ← MODIFY: file dispatch + IllegalStateException tolerance
  tool/
    ToolRegistry.java              ← MODIFY: getAvailableToolNames() + detailed startup log
    image/
      ImageGenTool.java            ← MODIFY: remove MessageSender, return ActResult.image()
      ImageRecognitionTool.java    ← MODIFY: remove MessageSender from editImage()
    voice/
      TtsTool.java                 ← MODIFY: remove MessageSender, return ActResult.audio()
    document/
      GenerateDocumentTool.java    ← MODIFY: return ActResult.document(), remove fileBytes from Map
    file/FileTool.java             ← MODIFY: @Tool description
    idiom/IdiomGameTool.java       ← MODIFY: @Tool description
    memory/MemoryStatusTool.java   ← MODIFY: @Tool description
    navigation/NavigationTool.java ← MODIFY: @Tool description
    reminder/ReminderTool.java     ← MODIFY: @Tool description
    TextTool.java                  ← MODIFY: @Tool description
    weather/WeatherTool.java       ← MODIFY: @Tool description
    outline/
      CreateOutlineTool.java       ← MODIFY: @Tool description
      ConfirmOutlineTool.java      ← MODIFY: @Tool description
summer-common/src/main/resources/prompts/
  system.txt                       ← MODIFY: append tool catalog section
summer-aigc/src/test/java/log/summer/aigc/
  loop/
    ActResultTest.java             ← MODIFY: add hasFile/file factory tests
  tool/
    image/ImageGenToolTest.java    ← NEW/MODIFY: adapt to ActResult.image() returns
    voice/TtsToolTest.java         ← NEW/MODIFY: adapt to ActResult.audio() returns
    document/
      GenerateDocumentToolTest.java ← MODIFY: adapt to ActResult.document() returns
```

---

### Task 1: FileType Enum + ActResult Media Fields

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/loop/FileType.java`
- Modify: `summer-aigc/src/main/java/log/summer/aigc/loop/ActResult.java`
- Modify: `summer-aigc/src/test/java/log/summer/aigc/loop/ActResultTest.java`

**Interfaces:**
- Produces: `FileType` enum with `IMAGE`, `AUDIO`, `DOCUMENT`
- Produces: `ActRecord` new fields: `byte[] fileBytes`, `String fileName`, `FileType fileType`
- Produces: `ActResult.hasFile()` → boolean
- Produces: `ActResult.file(byte[], String, FileType)` factory
- Produces: `ActResult.image(byte[], String)` / `ActResult.audio(byte[], String)` / `ActResult.document(byte[], String)` shortcuts
- Produces: All old factories updated to pass `null, null, null` for new fields

- [ ] **Step 1: Create FileType enum**

```java
package log.summer.aigc.loop;

/**
 * Media file type for ActResult file delivery.
 * Determines which MessageSender method AgentLoop uses and the size limit.
 *
 * <ul>
 *   <li>{@link #IMAGE}    — {@code sender.sendImage()}, max 10 MB</li>
 *   <li>{@link #AUDIO}    — {@code sender.sendFile()}, max 20 MB</li>
 *   <li>{@link #DOCUMENT} — {@code sender.sendFile()}, max 20 MB</li>
 * </ul>
 */
public enum FileType {
    IMAGE,
    AUDIO,
    DOCUMENT
}
```

- [ ] **Step 2: Write failing ActResult tests**

In `summer-aigc/src/test/java/log/summer/aigc/loop/ActResultTest.java`, append:

```java
@Test
void shouldHaveFileWhenBytesPresent() {
    ActResult result = ActResult.file(new byte[]{1, 2, 3}, "test.png", FileType.IMAGE);
    assertTrue(result.success());
    assertTrue(result.hasFile());
    assertEquals(3, result.fileBytes().length);
    assertEquals("test.png", result.fileName());
    assertEquals(FileType.IMAGE, result.fileType());
    assertEquals("文件已通过微信发送: test.png", result.data());
}

@Test
void shouldNotHaveFileWhenBytesNull() {
    ActResult result = ActResult.success("done");
    assertFalse(result.hasFile());
    assertNull(result.fileBytes());
}

@Test
void shouldNotHaveFileWhenBytesEmpty() {
    ActResult result = ActResult.file(new byte[0], "empty.png", FileType.IMAGE);
    assertFalse(result.hasFile());
}

@Test
void imageFactoryShouldSetCorrectType() {
    ActResult result = ActResult.image(new byte[]{1}, "photo.png");
    assertTrue(result.hasFile());
    assertEquals(FileType.IMAGE, result.fileType());
    assertEquals("photo.png", result.fileName());
}

@Test
void audioFactoryShouldSetCorrectType() {
    ActResult result = ActResult.audio(new byte[]{1, 2, 3, 4}, "voice.wav");
    assertTrue(result.hasFile());
    assertEquals(FileType.AUDIO, result.fileType());
}

@Test
void documentFactoryShouldSetCorrectType() {
    ActResult result = ActResult.document(new byte[]{5, 6}, "report.pptx");
    assertTrue(result.hasFile());
    assertEquals(FileType.DOCUMENT, result.fileType());
}

@Test
void legacySuccessShouldNotHaveFile() {
    ActResult result = ActResult.success("text only");
    assertTrue(result.success());
    assertFalse(result.hasFile());
    assertNull(result.fileBytes());
    assertNull(result.fileName());
    assertNull(result.fileType());
}

@Test
void legacySuspendShouldNotHaveFile() {
    ActResult result = ActResult.suspend(java.util.Map.of("key", "val"), "reason");
    assertTrue(result.suspend());
    assertFalse(result.hasFile());
}
```

- [ ] **Step 3: Run test to verify it fails**

Run: `cd summer-aigc && mvn test -Dtest=ActResultTest`
Expected: COMPILE ERROR — `file()`, `image()`, `audio()`, `document()`, `hasFile()`, `fileBytes()`, `fileName()`, `fileType()` not found

- [ ] **Step 4: Rewrite ActResult with media fields**

```java
package log.summer.aigc.loop;

/**
 * Encapsulates the result of a tool execution.
 * Every @Tool method MUST return this type.
 *
 * <h3>Media file delivery</h3>
 * When a tool produces a file (image, audio, document), use the file factories:
 * <ul>
 *   <li>{@link #image(byte[], String)} — for generated images</li>
 *   <li>{@link #audio(byte[], String)} — for synthesized audio</li>
 *   <li>{@link #document(byte[], String)} — for Word/PPT/Excel files</li>
 * </ul>
 * AgentLoop detects {@link #hasFile()} and dispatches via {@code MessageSender}.
 * The {@code data} field carries a human-readable confirmation injected into ChatMemory.
 *
 * <h3>Suspend protocol</h3>
 * When a tool needs user input before continuing, it returns {@link #suspend(Object, String)}.
 *
 * @param success               true if the tool completed successfully
 * @param data                  the tool's output text; when hasFile() use "文件已通过微信发送: {name}"
 * @param errorMessage          human-readable error description on failure (nullable)
 * @param suspend               true if the loop should pause and wait for user input
 * @param suspendReason         human-readable reason for suspension (shown to user)
 * @param requiresConfirmation  true if the tool needs explicit user yes/no/modify before proceeding
 * @param fileBytes             binary file data for direct delivery (nullable)
 * @param fileName              file name with extension, e.g. "report.pptx" (nullable)
 * @param fileType              IMAGE / AUDIO / DOCUMENT (nullable)
 */
public record ActResult(
        boolean success,
        Object data,
        String errorMessage,
        boolean suspend,
        String suspendReason,
        boolean requiresConfirmation,
        byte[] fileBytes,
        String fileName,
        FileType fileType) {

    /** True when file bytes are present and non-empty. */
    public boolean hasFile() {
        return fileBytes != null && fileBytes.length > 0;
    }

    // ── File factories ──

    /** Generic file result. data is set to "文件已通过微信发送: {name}". */
    public static ActResult file(byte[] bytes, String name, FileType type) {
        return new ActResult(true,
                "文件已通过微信发送: " + name,
                null, false, null, false,
                bytes, name, type);
    }

    public static ActResult image(byte[] bytes, String name) {
        return file(bytes, name, FileType.IMAGE);
    }

    public static ActResult audio(byte[] bytes, String name) {
        return file(bytes, name, FileType.AUDIO);
    }

    public static ActResult document(byte[] bytes, String name) {
        return file(bytes, name, FileType.DOCUMENT);
    }

    // ── Legacy factories (backward compatible) ──

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

- [ ] **Step 5: Run test to verify it passes**

Run: `cd summer-aigc && mvn test -Dtest=ActResultTest`
Expected: PASS — 4 original + 8 new = 12 tests green

- [ ] **Step 6: Verify full compilation**

Run: `cd summer-bootstrap && mvn compile`
Expected: BUILD SUCCESS — all existing code uses factory methods, canonical constructor calls are only inside ActResult itself

- [ ] **Step 7: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/FileType.java \
        summer-aigc/src/main/java/log/summer/aigc/loop/ActResult.java \
        summer-aigc/src/test/java/log/summer/aigc/loop/ActResultTest.java
git commit -m "feat: add FileType enum and media fields to ActResult"
```

---

### Task 2: AgentLoop — File Dispatch + IllegalStateException Tolerance

**Files:**
- Modify: `summer-aigc/src/main/java/log/summer/aigc/loop/AgentLoop.java`

**Interfaces:**
- Consumes: `ActResult.hasFile()`, `ActResult.fileBytes()`, `ActResult.fileName()`, `ActResult.fileType()` (from Task 1)
- Consumes: `ToolRegistry.getAvailableToolNames()` (from Task 3 — implemented here first since AgentLoop needs it)
- Produces: ACT phase now (a) dispatches files via `MessageSender` then injects text into `ChatMemory`, (b) catches `IllegalStateException` from `toolRegistry.execute()` and constructs self-correction feedback

> **Note to implementer:** `getAvailableToolNames()` is formally introduced in Task 3. Add it to `ToolRegistry` as part of this task first (one method), then Task 3 adds the startup log line.

- [ ] **Step 1: Add getAvailableToolNames() to ToolRegistry**

In `summer-aigc/src/main/java/log/summer/aigc/tool/ToolRegistry.java`, add:

```java
/**
 * Returns a comma-separated, sorted list of registered tool names
 * for error messages and logging.
 */
public String getAvailableToolNames() {
    return callbackMap.keySet().stream()
            .sorted()
            .collect(java.util.stream.Collectors.joining(", "));
}
```

Add the import: `import java.util.stream.Collectors;`

- [ ] **Step 2: Refactor the ACT phase in AgentLoop.orchestrate()**

In `AgentLoop.orchestrate()`, find the per-tool-call block (around the `toolRegistry.execute()` call) and replace the existing try-catch + result handling with:

```java
ActResult result;
try {
    Map<String, Object> resolvedArgs =
            argumentResolver.resolve(toolCall.arguments(), msg);
    result = toolRegistry.execute(toolCall.name(), resolvedArgs);
} catch (ArgumentResolver.PlaceholderResolutionException e) {
    result = ActResult.failure(e.getMessage());
} catch (IllegalStateException e) {
    log.warn("[AGENT-LOOP] 工具调用异常 | userId={} | tool={}",
            userId, toolCall.name(), e);
    String available = toolRegistry.getAvailableToolNames();
    result = ActResult.failure(
            "工具 '" + toolCall.name() + "' 不存在或调用失败。" +
            "当前可用工具（必须使用准确的函数名）: " + available);
}

// ── NEW: File dispatch ──
if (result.hasFile()) {
    log.info("[AGENT-LOOP] 文件分发 | userId={} | fileName={} | type={} | size={}bytes",
            userId, result.fileName(), result.fileType(), result.fileBytes().length);

    long maxSize = result.fileType() == FileType.IMAGE
            ? 10L * 1024 * 1024
            : 20L * 1024 * 1024;

    if (result.fileBytes().length > maxSize) {
        String limitText = "生成的 " + result.fileName()
                + " 过大（" + (result.fileBytes().length / 1024 / 1024) + "MB），请尝试简化内容";
        sender.sendText(userId, limitText);
        String callId = UUID.randomUUID().toString();
        var toolResponse = new ToolResponseMessage.ToolResponse(
                callId, toolCall.name(), "ERROR: " + limitText);
        chatMemory.add(userId, new ToolResponseMessage(List.of(toolResponse)));
    } else {
        switch (result.fileType()) {
            case IMAGE -> sender.sendImage(userId, result.fileBytes(),
                    result.fileName(), result.data() != null ? result.data().toString() : "");
            case AUDIO, DOCUMENT -> sender.sendFile(userId, result.fileBytes(),
                    result.fileName(), result.data() != null ? result.data().toString() : "");
        }
        // Inject text confirmation for LLM context
        String callId = UUID.randomUUID().toString();
        var toolResponse = new ToolResponseMessage.ToolResponse(
                callId, toolCall.name(),
                result.data() != null ? result.data().toString() : "文件已发送");
        chatMemory.add(userId, new ToolResponseMessage(List.of(toolResponse)));
    }

    // Check for suspend after file delivery
    if (result.suspend()) {
        if (result.suspendReason() != null) sender.sendText(userId, result.suspendReason());
        Conversation activeConv = chatPersistenceService.getActiveConversation(userId);
        long convId = activeConv != null ? activeConv.getId() : 0L;
        sessionStateManager.suspend(userId, convId, toolCall.name(),
                result.suspendReason(), chatMemory);
        suspended = true;
        break;
    }
    continue;
}

// ── Suspend check (existing, keep as-is) ──
if (result.suspend()) {
    // ... existing suspend logic ...
}
```

- [ ] **Step 3: Apply the same refactoring to resumeOrchestrate()**

Copy the identical ACT phase changes from Step 2 into `resumeOrchestrate()` — the per-tool-call block in the resume path must have the same file dispatch + IllegalStateException handling.

- [ ] **Step 4: Remove old Map-based fileBytes detection**

In both `orchestrate()` and `resumeOrchestrate()`, remove the old `instanceof Map` file detection block (from the doc-gen Task 12 integration). The new `hasFile()` path replaces it entirely.

- [ ] **Step 5: Verify compilation**

Run: `cd summer-bootstrap && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Run all tests**

Run: `cd summer-aigc && mvn test`
Expected: All existing tests pass (ActResult, AgentLoopSuspend, etc.)

- [ ] **Step 7: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/AgentLoop.java \
        summer-aigc/src/main/java/log/summer/aigc/tool/ToolRegistry.java
git commit -m "feat: add file dispatch and IllegalStateException tolerance to AgentLoop"
```

---

### Task 3: ToolRegistry — Startup Logging Enhancement

**Files:**
- Modify: `summer-aigc/src/main/java/log/summer/aigc/tool/ToolRegistry.java`

**Interfaces:**
- Consumes: `getAvailableToolNames()` added in Task 2
- Produces: Startup log line listing all registered tool names

- [ ] **Step 1: Add detailed startup log**

In `ToolRegistry.scan()`, after the existing `log.info("[TOOL-REGISTRY] Scanned ...")` line, add:

```java
log.info("[TOOL-REGISTRY] 已注册 {} 个工具: {}",
        callbackMap.size(),
        callbackMap.keySet().stream()
                .sorted()
                .collect(Collectors.joining(", ")));
```

(Import `java.util.stream.Collectors` if not already added in Task 2.)

- [ ] **Step 2: Verify compilation**

Run: `cd summer-aigc && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Verify log output on startup**

Run: `cd summer-bootstrap && mvn spring-boot:run` (briefly, Ctrl+C after startup)
Expected: Log contains `[TOOL-REGISTRY] 已注册 N 个工具: confirmOutline, createOutline, file_analyze, ...`

- [ ] **Step 4: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/ToolRegistry.java
git commit -m "feat: add detailed tool registration list to startup log"
```

---

### Task 4: Migrate Image Tools to ActResult File Factories

**Files:**
- Modify: `summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageGenTool.java`
- Modify: `summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageRecognitionTool.java`
- Remove import of `MessageSender` from both files; remove `messageSender` field + constructor parameter

**Interfaces:**
- Consumes: `ActResult.image(byte[], String)` (from Task 1)
- Produces: Both tools' generate/edit methods return `ActResult.image()` instead of calling `messageSender.sendImage()` internally

- [ ] **Step 1: Migrate ImageGenTool.generateImage()**

In `ImageGenTool.java`:
1. Remove `private final MessageSender messageSender;` field
2. Remove `import log.summer.aigc.port.MessageSender;` if no other usages
3. Remove `messageSender` from the `@RequiredArgsConstructor` parameter list (Lombok auto-generates)
4. In `generateImage()`, replace lines 62-64:

```java
// Before:
messageSender.sendImage(userId, bytes, "ai-gen.png", prompt);
imageContextManager.save(userId, url, bytes);
imageCacheManager.put(userId, bytes);
log.info("[IMAGE-GEN] 生成完成 | userId={} | size={}bytes", userId, bytes.length);
return ActResult.success("图片已发送");

// After:
imageContextManager.save(userId, url, bytes);
imageCacheManager.put(userId, bytes);
log.info("[IMAGE-GEN] 生成完成 | userId={} | size={}bytes", userId, bytes.length);
return ActResult.image(bytes, "ai-gen.png");
```

- [ ] **Step 2: Migrate ImageRecognitionTool.editImage()**

In `ImageRecognitionTool.java`:
1. Remove `private final MessageSender messageSender;` field
2. Remove `import log.summer.aigc.port.MessageSender;`
3. Remove `messageSender` from constructor parameters
4. In `editImage()`, replace lines 88-93:

```java
// Before:
messageSender.sendImage(userId, resultBytes, "edited-image.png",
        "编辑: " + editPrompt);
log.info("[IMG-EDIT] 编辑完成 | userId={} | instruction={}",
        userId, editPrompt);
return ActResult.success("图片已发送");

// After:
log.info("[IMG-EDIT] 编辑完成 | userId={} | instruction={}", userId, editPrompt);
return ActResult.image(resultBytes, "edited-image.png");
```

- [ ] **Step 3: Verify compilation**

Run: `cd summer-aigc && mvn compile`
Expected: BUILD SUCCESS — no more `messageSender` references in image tools

- [ ] **Step 4: Run existing tests**

Run: `cd summer-aigc && mvn test`
Expected: All tests pass — tools still return `ActResult` (now via `image()` factory), AgentLoop handles the file dispatch

- [ ] **Step 5: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageGenTool.java \
        summer-aigc/src/main/java/log/summer/aigc/tool/image/ImageRecognitionTool.java
git commit -m "refactor: migrate image tools to ActResult.image() factory"
```

---

### Task 5: Migrate TtsTool to ActResult Audio Factory

**Files:**
- Modify: `summer-aigc/src/main/java/log/summer/aigc/tool/voice/TtsTool.java`

**Interfaces:**
- Consumes: `ActResult.audio(byte[], String)` (from Task 1)
- Produces: `synthesize()` returns `ActResult.audio()` instead of calling `messageSender.sendFile()` internally

- [ ] **Step 1: Migrate TtsTool.synthesize()**

In `TtsTool.java`:
1. Remove `private final MessageSender messageSender;` field
2. Remove `import log.summer.aigc.port.MessageSender;`
3. In `synthesize()`, replace the sendFile + return block (lines 44-48):

```java
// Before:
if (result.hasAudio()) {
    messageSender.sendFile("default", result.wavAudio(),
            "tts-" + System.currentTimeMillis() + ".wav", ttsText);
    return ActResult.success("语音已发送");
}

// After:
if (result.hasAudio()) {
    String fileName = "tts-" + System.currentTimeMillis() + ".wav";
    return ActResult.audio(result.wavAudio(), fileName);
}
```

- [ ] **Step 2: Verify compilation + tests**

Run: `cd summer-aigc && mvn compile && mvn test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 3: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/voice/TtsTool.java
git commit -m "refactor: migrate TtsTool to ActResult.audio() factory"
```

---

### Task 6: Migrate GenerateDocumentTool to ActResult Document Factory

**Files:**
- Modify: `summer-aigc/src/main/java/log/summer/aigc/tool/document/GenerateDocumentTool.java`
- Modify: `summer-aigc/src/test/java/log/summer/aigc/tool/document/GenerateDocumentToolTest.java`

**Interfaces:**
- Consumes: `ActResult.document(byte[], String)` (from Task 1)
- Produces: `generateDocument()` returns `ActResult.document(bytes, fileName)`, with metadata (documentId, idempotencyKey, etc.) in `data`; idempotency hit path returns `ActResult.success(Map)` without file bytes (no re-generation)

- [ ] **Step 1: Refactor GenerateDocumentTool return to use ActResult.document()**

In `GenerateDocumentTool.generateDocument()`, find the success return block (after file generation + record save). Replace:

```java
// Before — returns Map with fileBytes embedded:
Map<String, Object> data = new LinkedHashMap<>();
data.put("documentId", record.getId());
data.put("fileName", fileName);
data.put("fileSize", fileBytes.length + " bytes");
data.put("fileBytes", fileBytes);
data.put("idempotencyKey", idempotencyKey);
return ActResult.success(data);

// After — separate file from metadata:
Map<String, Object> metadata = new LinkedHashMap<>();
metadata.put("documentId", record.getId());
metadata.put("fileName", fileName);
metadata.put("fileSize", fileBytes.length + " bytes");
metadata.put("idempotencyKey", idempotencyKey);
// fileBytes goes to ActResult.document() — AgentLoop sends it directly
return ActResult.document(fileBytes, fileName);
// Note: ActResult.file() sets data to "文件已通过微信发送: {name}" automatically.
// To include metadata, we need a variant:
// Use ActResult.fileWithData(metadata, bytes, fileName, FileType.DOCUMENT)
```

Wait — `ActResult.document(bytes, name)` sets `data` to the standard "文件已通过微信发送: ..." text, which means the per-document metadata (documentId, idempotencyKey) is lost. We need a variant that accepts custom `data` alongside file bytes.

- [ ] **Step 2: Add fileWithData factory to ActResult**

In `ActResult.java`, add:

```java
/**
 * File result with custom metadata text in data.
 * The data Object is injected into ChatMemory for LLM context.
 */
public static ActResult fileWithData(Object data, byte[] bytes, String name, FileType type) {
    return new ActResult(true, data, null, false, null, false, bytes, name, type);
}
```

- [ ] **Step 3: Update GenerateDocumentTool return**

```java
Map<String, Object> metadata = new LinkedHashMap<>();
metadata.put("documentId", record.getId());
metadata.put("fileName", fileName);
metadata.put("fileSize", fileBytes.length + " bytes");
metadata.put("idempotencyKey", idempotencyKey);
metadata.put("message", "文档已生成并通过微信发送");
return ActResult.fileWithData(metadata, fileBytes, fileName, FileType.DOCUMENT);
```

- [ ] **Step 4: Update GenerateDocumentToolTest**

In `GenerateDocumentToolTest.shouldGenerateDocumentFromConfirmedOutline()`:
- Replace assertion `assertTrue(result.data() instanceof Map)` with checks that `result.hasFile()` is true
- Verify `result.fileType() == FileType.DOCUMENT`
- Verify `result.fileName()` contains ".pptx"

In `GenerateDocumentToolTest.shouldReturnExistingDocumentWhenIdempotent()`:
- The idempotent path still returns `ActResult.success(Map)` without file bytes — this is correct (no re-generation)
- Verify `assertFalse(result.hasFile())` — no bytes on idempotent hit
- Verify `result.data()` is a Map with the expected metadata

- [ ] **Step 5: Verify compilation + tests**

Run: `cd summer-aigc && mvn compile && mvn test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Step 6: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/ActResult.java \
        summer-aigc/src/main/java/log/summer/aigc/tool/document/GenerateDocumentTool.java \
        summer-aigc/src/test/java/log/summer/aigc/tool/document/GenerateDocumentToolTest.java
git commit -m "refactor: migrate GenerateDocumentTool to ActResult.document() with fileWithData"
```

---

### Task 7: @Tool Description Reinforcement

**Files:**
- Modify: All `@Tool`-annotated methods across all tool files

**Interfaces:**
- Produces: Each `@Tool(name = "xxx", description = "...")` now ends with `"调用此工具时必须使用函数名 xxx"`

- [ ] **Step 1: Add function name suffix to every @Tool description**

For each file below, append `调用此工具时必须使用函数名 {name}` to the `description` string:

| File | @Tool name | Current description |
|------|-----------|-------------------|
| `ImageGenTool.java` | `image_generate` | `"根据文字描述生成图片"` |
| `ImageRecognitionTool.java` | `image_recognize` | `"识别图片内容并返回文字描述"` |
| `ImageRecognitionTool.java` | `image_edit` | `"根据文字描述修改或编辑图片"` |
| `TtsTool.java` | `tts_synthesize` | `"将文字转为语音朗读"` |
| `TtsTool.java` | `voice_switch` | `"切换语音合成使用的音色"` |
| `WeatherTool.java` | `weather_query` | `"查询指定城市今天或明天的天气情况"` |
| `FileTool.java` | `file_analyze` | `"分析上传的文件内容"` |
| `IdiomGameTool.java` | `idiom_game_start` | (check actual) |
| `IdiomGameTool.java` | `idiom_game_next` | (check actual) |
| `MemoryStatusTool.java` | `memory_status` | (check actual) |
| `NavigationTool.java` | `navigation_open` | (check actual) |
| `ReminderTool.java` | `reminder_set` | (check actual) |
| `TextTool.java` | (check actual) | (check actual) |
| `CreateOutlineTool.java` | `createOutline` | `"生成文档结构化大纲..."` |
| `ConfirmOutlineTool.java` | `confirmOutline` | (check actual) |
| `GenerateDocumentTool.java` | `generateDocument` | `"根据已确认的大纲..."` |

After (example for image_generate):
```java
@Tool(name = "image_generate",
      description = "根据文字描述生成图片。调用此工具时必须使用函数名 image_generate")
```

Repeat for every @Tool. The suffix format is always: `"。调用此工具时必须使用函数名 {name}"`

- [ ] **Step 2: Verify compilation**

Run: `cd summer-aigc && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/
git commit -m "docs: reinforce @Tool descriptions with explicit function names"
```

---

### Task 8: System Prompt — Tool Catalog Addition

**Files:**
- Modify: `summer-common/src/main/resources/prompts/system.txt`

- [ ] **Step 1: Append tool catalog to system prompt**

Append the following section at the end of `system.txt`:

```

## 可用工具清单（必须使用准确名称）

调用工具时必须使用以下准确的函数名，禁止自行编造、猜测或创造不存在的函数名：

image_generate    — 根据文字描述生成图片
image_recognize   — 识别图片内容并返回文字描述
image_edit        — 根据文字描述修改或编辑图片
tts_synthesize    — 将文字转为语音朗读
voice_switch      — 切换语音合成使用的音色
weather_query     — 查询指定城市今天或明天的天气
file_analyze      — 分析上传的文件内容
createOutline     — 生成文档结构化大纲（支持 WORD/PPT/EXCEL），供用户确认
confirmOutline    — 确认大纲内容，允许附带修改
generateDocument  — 根据已确认的大纲渲染生成 Word/PPT/Excel 文件

如果用户的需求超出以上工具的能力范围，请直接告知用户，不要自行编造函数名。
不要调用以上清单中不存在的工具。
```

> Note: tool list and names must be updated if new tools are added/removed. The list above includes the currently known tools. Confirm by checking ToolRegistry startup output after Task 3.

- [ ] **Step 2: Commit**

```bash
git add summer-common/src/main/resources/prompts/system.txt
git commit -m "docs: add tool catalog to system prompt preventing LLM hallucination"
```

---

### Task 9: Final Verification — Full Build + All Tests

**Files:**
- None new — verification only

- [ ] **Step 1: Full project build**

Run: `cd summer-bootstrap && mvn clean compile`
Expected: BUILD SUCCESS

- [ ] **Step 2: Run all tests**

Run: `cd summer-aigc && mvn test`
Expected: All tests pass (ActResultTest, AgentLoopSuspendTest, GenerateDocumentToolTest, DocumentGeneratorTest, CreateOutlineToolTest, SessionStateManagerTest, MessageSnapshotTest, SuspendContextTest, DocumentGenerationIntegrationTest, etc.)

- [ ] **Step 3: Verify startup log**

Run: `cd summer-bootstrap && mvn spring-boot:run` (check log, stop after init)
Expected: `[TOOL-REGISTRY] 已注册 N 个工具: confirmOutline, createOutline, file_analyze, generateDocument, idiom_game_next, idiom_game_start, image_edit, image_generate, image_recognize, memory_status, navigation_open, reminder_set, tts_synthesize, voice_switch, weather_query`

- [ ] **Step 4: Verify tools no longer inject MessageSender**

Run: `grep -r "MessageSender" summer-aigc/src/main/java/log/summer/aigc/tool/`
Expected: No matches (only AgentLoop should reference MessageSender)

- [ ] **Step 5: Commit (if any final tweaks)**

```bash
git add -A
git commit -m "chore: final verification — all tools migrated, 0 MessageSender references in tools"
```

---

## Self-Review

### 1. Spec Coverage

| Spec Requirement | Task |
|---|---|
| FileType enum IMAGE/AUDIO/DOCUMENT | Task 1 |
| ActResult fileBytes/fileName/fileType fields + hasFile() | Task 1 |
| ActResult.file/image/audio/document factories | Task 1 |
| ActResult.fileWithData for metadata-bearing files | Task 6 |
| data format: "文件已通过微信发送: {fileName}" | Task 1 (file factory) |
| AgentLoop file dispatch (size check, type switch, ChatMemory injection) | Task 2 |
| Size limits: 10MB IMAGE, 20MB AUDIO/DOCUMENT | Task 2 |
| AgentLoop IllegalStateException catch + available tools listing | Task 2 |
| ToolRegistry.getAvailableToolNames() | Task 2 (method) + Task 3 (log) |
| ToolRegistry startup detailed log | Task 3 |
| ImageGenTool migration (remove MessageSender → ActResult.image) | Task 4 |
| ImageRecognitionTool.editImage migration | Task 4 |
| TtsTool.synthesize migration (→ ActResult.audio) | Task 5 |
| GenerateDocumentTool migration (→ ActResult.document) | Task 6 |
| @Tool description reinforcement ("调用时必须使用函数名 xxx") | Task 7 |
| System prompt tool catalog | Task 8 |
| Backward compatibility (old factories unchanged) | Task 1 |
| All tests pass | Task 9 |

### 2. Placeholder Scan

No "TBD", "TODO", "implement later" found. No "add appropriate error handling" without code. All tool descriptions to check are listed with "(check actual)" — the implementer will read the files and verify. No step uses "Similar to Task N" references.

### 3. Type Consistency

- `ActResult.file()/image()/audio()/document()/fileWithData()` — defined in Task 1, consumed in Tasks 4-6
- `FileType.IMAGE/AUDIO/DOCUMENT` — defined in Task 1, used in Tasks 2, 6
- `ActResult.hasFile()` — defined in Task 1, used in Task 2
- `ToolRegistry.getAvailableToolNames()` — added in Task 2, log-enhanced in Task 3
- `MessageSender.sendImage/sendFile` — only in AgentLoop Task 2, removed from all tools Tasks 4-5
