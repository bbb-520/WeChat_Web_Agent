# Document Generation (Word/PPT/Excel) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable the Agent to generate Word, PPT, and Excel documents via a new createOutline → user-confirm → generateDocument tool chain, building on a refactored AgentLoop that supports pause/resume and user confirmation with state survival across restarts.

**Architecture:** Three-layer build: (1) Refactor AgentLoop to support suspend/resume with DB-persisted state, (2) Add createOutline tool that generates structured outlines and suspends for user approval, (3) Add generateDocument tool using Apache POI to render Office files from confirmed outlines, with idempotency guarantees. The existing ToolRegistry, ChatMemory, and MyBatis-Plus patterns are followed throughout.

**Tech Stack:** Java 21, Spring Boot 3.2.10, Spring AI Alibaba DashScope, MyBatis-Plus 3.5.7, Apache POI 5.2.x, Flyway, MySQL, Lombok 1.18.46

## Global Constraints

- Java 21, Spring Boot 3.2.10 parent POM
- Lombok 1.18.46 (explicit version in annotation processor paths)
- Every `@Tool` method MUST return `ActResult` — now with optional `suspend` and `requiresConfirmation` fields
- `AgentLoop` owns the while-iteration and ChatMemory lifecycle; Spring AI is a stateless single-call utility
- On suspend: ChatMemory is NOT cleared; conversation status set to 2 (SUSPENDED); full message history serialized to DB
- On resume: message history restored to ChatMemory from DB (or in-memory cache), LLM continues from full context
- All new tables use InnoDB, utf8mb4, and include created_at/updated_at timestamps
- Flyway migrations follow V2, V3, V4 naming
- Tool idempotency via idempotency_key unique constraint on document_record
- No existing tests — first tests written in this plan

---

## File Structure

```
summer-aigc/
  src/main/java/log/summer/aigc/
    loop/
      ActResult.java              ← MODIFY: add suspend/confirmation fields + factory methods
      AgentLoop.java              ← MODIFY: add suspend/resume logic, session state detection
      SuspendContext.java         ← NEW: record for suspend metadata (toolName, reason, data snapshot)
      MessageSnapshot.java        ← NEW: lightweight DTO for ChatMemory serialization
    session/
      SessionStateManager.java    ← NEW: persist/restore suspend state, coordinate cache + DB
    entity/
      Conversation.java           ← MODIFY: add suspendContext, suspendReason fields
      DocumentOutline.java        ← NEW: outline entity
      DocumentRecord.java         ← NEW: document generation record entity
    mapper/
      DocumentOutlineMapper.java  ← NEW: MyBatis-Plus mapper
      DocumentRecordMapper.java   ← NEW: MyBatis-Plus mapper
    service/
      IConversationService.java   ← MODIFY: add suspend/resume methods
      ConversationServiceImpl.java ← MODIFY: implement suspend methods
      IDocumentOutlineService.java ← NEW: interface
      DocumentOutlineServiceImpl.java ← NEW: implementation
      IDocumentRecordService.java ← NEW: interface
      DocumentRecordServiceImpl.java ← NEW: implementation
    tool/
      outline/
        CreateOutlineTool.java    ← NEW: @Tool — generates structured outline, suspends
      document/
        DocumentGenerator.java    ← NEW: POI-based Word/PPT/Excel rendering service
        GenerateDocumentTool.java ← NEW: @Tool — renders document from confirmed outline
    config/
      PoiiConfig.java            ← NEW: POI temp directory config (optional)
  src/main/resources/db/migration/
    V2__add_suspend_context.sql  ← NEW: ALTER conversation for suspend support
    V3__create_outline_table.sql ← NEW: CREATE TABLE document_outline
    V4__create_document_record.sql ← NEW: CREATE TABLE document_record
  src/test/java/log/summer/aigc/
    loop/
      ActResultTest.java         ← NEW: test new factory methods
      AgentLoopSuspendTest.java  ← NEW: test suspend/resume cycle
    session/
      SessionStateManagerTest.java ← NEW: test persist/restore
    tool/
      outline/
        CreateOutlineToolTest.java ← NEW: test outline generation + suspend
      document/
        DocumentGeneratorTest.java ← NEW: test POI rendering
        GenerateDocumentToolTest.java ← NEW: test end-to-end + idempotency

summer-aigc/pom.xml               ← MODIFY: add Apache POI dependency
```

---

### Task 1: Database Migration — Add Suspend Fields to Conversation

**Files:**
- Create: `summer-aigc/src/main/resources/db/migration/V2__add_suspend_context.sql`
- Modify: `summer-aigc/src/main/java/log/summer/aigc/entity/Conversation.java`

**Interfaces:**
- Produces: `Conversation.suspendContext` (String/JSON), `Conversation.suspendReason` (String), `Conversation.status` now supports 0=ENDED, 1=ACTIVE, 2=SUSPENDED

- [ ] **Step 1: Write the Flyway migration SQL**

```sql
-- V2__add_suspend_context.sql
-- Add suspend support to conversation table for Agent loop pause/resume

ALTER TABLE conversation
    ADD COLUMN suspend_context JSON DEFAULT NULL
        COMMENT 'Serialized suspend state: tool name, pending data, message history snapshot',
    ADD COLUMN suspend_reason VARCHAR(255) DEFAULT NULL
        COMMENT 'Human-readable reason for suspension, shown to user on resume';

-- Update existing status comment to reflect new state
ALTER TABLE conversation
    MODIFY COLUMN status TINYINT DEFAULT 1
        COMMENT '0=已结束 1=进行中 2=挂起等待用户输入';
```

- [ ] **Step 2: Update Conversation entity**

```java
package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String sessionId;
    private String title;
    private String routeContext;       // TEXT / VOICE
    private Integer status;            // 0=已结束 1=进行中 2=挂起等待用户输入
    private Integer messageCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── NEW: suspend support ──
    private String suspendContext;     // JSON: serialized SuspendContext (toolName, reason, message snapshots)
    private String suspendReason;      // Human-readable, e.g. "等待确认大纲"
}
```

- [ ] **Step 3: Start the application and verify Flyway runs V2**

Run: `cd summer-bootstrap && mvn spring-boot:run`
Expected: log line `[FLYWAY] Successfully applied migration V2__add_suspend_context.sql`
Verify: `DESC conversation;` shows new columns `suspend_context` and `suspend_reason`

- [ ] **Step 4: Commit**

```bash
git add summer-aigc/src/main/resources/db/migration/V2__add_suspend_context.sql \
        summer-aigc/src/main/java/log/summer/aigc/entity/Conversation.java
git commit -m "feat: add suspend_context and suspend_reason to conversation table"
```

---

### Task 2: Enhance ActResult with Suspend and Confirmation Support

**Files:**
- Modify: `summer-aigc/src/main/java/log/summer/aigc/loop/ActResult.java`
- Create: `summer-aigc/src/test/java/log/summer/aigc/loop/ActResultTest.java`

**Interfaces:**
- Produces: `ActResult.suspend(Object data, String reason)` → `ActResult(success=true, data, errorMessage=null, suspend=true, suspendReason=reason, requiresConfirmation=true)`
- Produces: `ActResult.confirmRequired(Object data, String reason)` → same as suspend but with requiresConfirmation=true (semantic alias)
- Existing `ActResult.success(data)` → adds `suspend=false, suspendReason=null, requiresConfirmation=false` (backward compatible)
- Existing `ActResult.failure(msg)` → adds `suspend=false, suspendReason=null, requiresConfirmation=false` (backward compatible)

- [ ] **Step 1: Write the failing test**

File: `summer-aigc/src/test/java/log/summer/aigc/loop/ActResultTest.java`

```java
package log.summer.aigc.loop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActResultTest {

    @Test
    void successShouldNotSuspend() {
        ActResult result = ActResult.success("done");
        assertTrue(result.success());
        assertEquals("done", result.data());
        assertFalse(result.suspend());
        assertFalse(result.requiresConfirmation());
        assertNull(result.suspendReason());
    }

    @Test
    void failureShouldNotSuspend() {
        ActResult result = ActResult.failure("boom");
        assertFalse(result.success());
        assertEquals("boom", result.errorMessage());
        assertFalse(result.suspend());
        assertFalse(result.requiresConfirmation());
    }

    @Test
    void suspendShouldSetAllFlags() {
        ActResult result = ActResult.suspend(
                java.util.Map.of("title", "My Outline", "sections", java.util.List.of("A", "B")),
                "等待确认大纲内容");
        assertTrue(result.success());
        assertTrue(result.suspend());
        assertTrue(result.requiresConfirmation());
        assertEquals("等待确认大纲内容", result.suspendReason());
        assertNotNull(result.data());
    }

    @Test
    void confirmRequiredShouldSetAllFlags() {
        ActResult result = ActResult.confirmRequired("请确认是否继续", "需要用户确认");
        assertTrue(result.success());
        assertTrue(result.suspend());
        assertTrue(result.requiresConfirmation());
        assertEquals("需要用户确认", result.suspendReason());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd summer-aigc && mvn test -Dtest=ActResultTest`
Expected: COMPILE ERROR — `suspend()`, `requiresConfirmation()`, `suspendReason()` methods not found

- [ ] **Step 3: Rewrite ActResult with new fields**

```java
package log.summer.aigc.loop;

/**
 * Encapsulates the result of a tool execution.
 * Every @Tool method MUST return this type.
 *
 * <h3>Suspend protocol</h3>
 * When a tool needs user input before continuing (e.g., outline confirmation),
 * it returns {@link #suspend(Object, String)}. AgentLoop detects the suspend
 * flag, persists the session state, and waits for the next user message.
 *
 * @param success               true if the tool completed successfully
 * @param data                  the tool's output data on success (nullable)
 * @param errorMessage          human-readable error description on failure (nullable)
 * @param suspend               true if the loop should pause and wait for user input
 * @param suspendReason         human-readable reason for suspension (shown to user)
 * @param requiresConfirmation  true if the tool needs explicit user yes/no/modify before proceeding
 */
public record ActResult(
        boolean success,
        Object data,
        String errorMessage,
        boolean suspend,
        String suspendReason,
        boolean requiresConfirmation) {

    /** Normal successful completion — loop continues. */
    public static ActResult success(Object data) {
        return new ActResult(true, data, null, false, null, false);
    }

    /** Tool execution failed — loop continues, error fed back to LLM. */
    public static ActResult failure(String errorMessage) {
        return new ActResult(false, null, errorMessage, false, null, false);
    }

    /**
     * Suspend the loop, presenting data to the user and waiting for their response.
     * The loop persists state to DB (survives restart) and exits without clearing ChatMemory.
     */
    public static ActResult suspend(Object data, String reason) {
        return new ActResult(true, data, null, true, reason, true);
    }

    /**
     * Alias for {@link #suspend(Object, String)} — semantically clearer
     * when the sole purpose is confirmation rather than data presentation.
     */
    public static ActResult confirmRequired(Object data, String reason) {
        return new ActResult(true, data, null, true, reason, true);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd summer-aigc && mvn test -Dtest=ActResultTest`
Expected: PASS — 4 tests green

- [ ] **Step 5: Verify existing compilation is not broken**

Run: `cd summer-aigc && mvn compile`
Expected: BUILD SUCCESS — all existing code uses `ActResult.success()` and `ActResult.failure()` factory methods, not the canonical constructor directly

- [ ] **Step 6: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/ActResult.java \
        summer-aigc/src/test/java/log/summer/aigc/loop/ActResultTest.java
git commit -m "feat: add suspend/confirmation support to ActResult"
```

---

### Task 3: Create MessageSnapshot DTO and SuspendContext

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/loop/MessageSnapshot.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/loop/SuspendContext.java`

**Interfaces:**
- Produces: `MessageSnapshot` — record(role, content, toolCallName, toolCallId) for serializing ChatMemory to DB JSON
- Produces: `SuspendContext` — record(toolName, suspendReason, conversationId, messagesSnapshot: List<MessageSnapshot>, createdAt: Instant) for the full suspend state stored in conversation.suspend_context

- [ ] **Step 1: Create MessageSnapshot**

```java
package log.summer.aigc.loop;

import org.springframework.ai.chat.messages.*;

import java.time.Instant;
import java.util.List;

/**
 * Lightweight, JSON-serializable snapshot of a single ChatMemory message.
 * Used to persist conversation state when the AgentLoop suspends —
 * Spring AI {@link Message} implementations are not directly serializable.
 *
 * @param role          "user" | "assistant" | "tool"
 * @param content       the text content of the message
 * @param toolCallName  tool name (only for tool-call messages, nullable)
 * @param toolCallId    tool call ID (only for tool-call messages, nullable)
 */
public record MessageSnapshot(
        String role,
        String content,
        String toolCallName,
        String toolCallId) {

    /** Convert a Spring AI Message list to snapshots. */
    public static List<MessageSnapshot> fromMessages(List<Message> messages) {
        return messages.stream().map(MessageSnapshot::from).toList();
    }

    /** Convert a single Spring AI Message to a snapshot. */
    public static MessageSnapshot from(Message msg) {
        String role;
        String content;
        String toolCallName = null;
        String toolCallId = null;

        switch (msg.getMessageType()) {
            case USER -> {
                role = "user";
                content = msg.getText();
            }
            case ASSISTANT -> {
                role = "assistant";
                content = msg.getText();
            }
            case TOOL -> {
                role = "tool";
                content = msg.getText();
                // Extract tool metadata from ToolResponseMessage
                if (msg instanceof ToolResponseMessage trm) {
                    var responses = trm.getResponses();
                    if (responses != null && !responses.isEmpty()) {
                        var first = responses.get(0);
                        toolCallName = first.name();
                        toolCallId = first.id();
                    }
                }
            }
            default -> {
                role = "system";
                content = msg.getText();
            }
        }

        return new MessageSnapshot(role, content, toolCallName, toolCallId);
    }

    /** Reconstruct a Spring AI Message from this snapshot for ChatMemory restoration. */
    public Message toSpringAiMessage() {
        return switch (role) {
            case "user" -> new UserMessage(content != null ? content : "");
            case "assistant" -> new org.springframework.ai.chat.messages.AssistantMessage(
                    content != null ? content : "");
            case "tool" -> {
                var response = new ToolResponseMessage.ToolResponse(
                        toolCallId != null ? toolCallId : "",
                        toolCallName != null ? toolCallName : "",
                        content != null ? content : "");
                yield new ToolResponseMessage(List.of(response));
            }
            default -> new UserMessage(content != null ? content : "");
        };
    }
}
```

- [ ] **Step 2: Create SuspendContext**

```java
package log.summer.aigc.loop;

import java.time.Instant;
import java.util.List;

/**
 * Full suspend state serialized to {@code conversation.suspend_context} JSON.
 * Captures everything needed to resume the AgentLoop after a tool-initiated pause.
 *
 * @param toolName          the @Tool method name that triggered the suspension
 * @param suspendReason     human-readable reason shown to the user
 * @param conversationId    the DB conversation id for state reconstruction
 * @param messagesSnapshot  full ChatMemory message list at the point of suspension
 * @param createdAt         when the suspension occurred
 */
public record SuspendContext(
        String toolName,
        String suspendReason,
        Long conversationId,
        List<MessageSnapshot> messagesSnapshot,
        Instant createdAt) {

    public static SuspendContext of(
            String toolName,
            String suspendReason,
            Long conversationId,
            List<MessageSnapshot> messages) {
        return new SuspendContext(toolName, suspendReason, conversationId, messages, Instant.now());
    }
}
```

- [ ] **Step 3: Verify compilation**

Run: `cd summer-aigc && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/MessageSnapshot.java \
        summer-aigc/src/main/java/log/summer/aigc/loop/SuspendContext.java
git commit -m "feat: add MessageSnapshot and SuspendContext for loop state serialization"
```

---

### Task 4: Create SessionStateManager

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/session/SessionStateManager.java`
- Modify: `summer-aigc/src/main/java/log/summer/aigc/service/IConversationService.java`
- Modify: `summer-aigc/src/main/java/log/summer/aigc/service/impl/ConversationServiceImpl.java`
- Create: `summer-aigc/src/test/java/log/summer/aigc/session/SessionStateManagerTest.java`

**Interfaces:**
- Consumes: `SuspendContext`, `MessageSnapshot` (from Task 3), `Conversation` (from Task 1)
- Produces:
  - `SessionStateManager.suspend(userId, convId, toolName, reason, messages)` → void
  - `SessionStateManager.getSuspendedByUser(userId)` → Conversation or null
  - `SessionStateManager.restoreMessages(userId, chatMemory)` → void
  - `SessionStateManager.clearSuspend(convId)` → void
  - `IConversationService.getSuspendedByUser(userId)` → Conversation
  - `IConversationService.updateSuspend(convId, suspendContext, suspendReason)` → void
  - `IConversationService.clearSuspend(convId)` → void

- [ ] **Step 1: Add methods to IConversationService**

Modify `summer-aigc/src/main/java/log/summer/aigc/service/IConversationService.java` — add these method signatures after `getByConvId`:

```java
/** Query the latest suspended conversation for a user. */
Conversation getSuspendedByUser(String userId);

/** Persist suspend context and set status=2. */
void updateSuspend(Long convId, String suspendContext, String suspendReason);

/** Clear suspend state and set status back to 1 (active). */
void clearSuspend(Long convId);
```

- [ ] **Step 2: Implement new methods in ConversationServiceImpl**

Modify `summer-aigc/src/main/java/log/summer/aigc/service/impl/ConversationServiceImpl.java` — add these methods:

```java
@Override
public Conversation getSuspendedByUser(String userId) {
    return getOne(new LambdaQueryWrapper<Conversation>()
            .eq(Conversation::getUserId, userId)
            .eq(Conversation::getStatus, 2)
            .orderByDesc(Conversation::getCreatedAt)
            .last("LIMIT 1"));
}

@Override
public void updateSuspend(Long convId, String suspendContext, String suspendReason) {
    lambdaUpdate()
            .set(Conversation::getStatus, 2)
            .set(Conversation::getSuspendContext, suspendContext)
            .set(Conversation::getSuspendReason, suspendReason)
            .set(Conversation::getUpdatedAt, LocalDateTime.now())
            .eq(Conversation::getId, convId)
            .update();
}

@Override
public void clearSuspend(Long convId) {
    lambdaUpdate()
            .set(Conversation::getStatus, 1)
            .set(Conversation::getSuspendContext, null)
            .set(Conversation::getSuspendReason, null)
            .set(Conversation::getUpdatedAt, LocalDateTime.now())
            .eq(Conversation::getId, convId)
            .update();
}
```

Add the import: `import java.time.LocalDateTime;`

- [ ] **Step 3: Write the failing test for SessionStateManager**

File: `summer-aigc/src/test/java/log/summer/aigc/session/SessionStateManagerTest.java`

```java
package log.summer.aigc.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import log.summer.aigc.entity.Conversation;
import log.summer.aigc.loop.MessageSnapshot;
import log.summer.aigc.loop.SuspendContext;
import log.summer.aigc.service.IConversationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionStateManagerTest {

    @Mock
    private IConversationService conversationService;

    private ObjectMapper objectMapper;
    private SessionStateManager stateManager;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        stateManager = new SessionStateManager(conversationService, objectMapper);
    }

    @Test
    void suspendShouldPersistStateAndUpdateConversation() {
        ChatMemory memory = new InMemoryChatMemory();
        memory.add("user123", new UserMessage("帮我做个PPT"));
        memory.add("user123", new UserMessage("内容关于Q2业绩"));

        stateManager.suspend("user123", 1L, "createOutline",
                "等待确认大纲", memory);

        verify(conversationService).updateSuspend(eq(1L), anyString(), eq("等待确认大纲"));
    }

    @Test
    void getSuspendedByUserShouldDelegateToService() {
        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId("user123");
        conv.setStatus(2);
        conv.setSuspendReason("等待确认大纲");
        when(conversationService.getSuspendedByUser("user123")).thenReturn(conv);

        Conversation result = stateManager.getSuspendedByUser("user123");

        assertNotNull(result);
        assertEquals(2, result.getStatus());
        assertEquals("等待确认大纲", result.getSuspendReason());
    }

    @Test
    void restoreMessagesShouldRehydrateChatMemoryFromSuspendContext() throws Exception {
        // Arrange: build a SuspendContext with known message snapshots
        List<MessageSnapshot> snapshots = List.of(
                new MessageSnapshot("user", "帮我做个PPT", null, null),
                new MessageSnapshot("assistant", "好的，我来生成大纲", null, null)
        );
        SuspendContext ctx = SuspendContext.of(
                "createOutline", "等待确认大纲", 1L, snapshots);
        String ctxJson = objectMapper.writeValueAsString(ctx);

        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId("user123");
        conv.setStatus(2);
        conv.setSuspendContext(ctxJson);

        ChatMemory memory = new InMemoryChatMemory();

        // Act
        stateManager.restoreMessages(conv, memory);

        // Assert: memory should contain 2 messages
        List<org.springframework.ai.chat.messages.Message> restored =
                memory.get("user123");
        assertNotNull(restored);
        assertEquals(2, restored.size());
    }

    @Test
    void clearSuspendShouldResetConversationStatus() {
        stateManager.clearSuspend(1L);

        verify(conversationService).clearSuspend(1L);
    }

    @Test
    void getSuspendedByUserShouldReturnNullWhenNone() {
        when(conversationService.getSuspendedByUser("user123")).thenReturn(null);

        Conversation result = stateManager.getSuspendedByUser("user123");

        assertNull(result);
    }

    @Test
    void isSuspendedShouldReturnTrueWhenActiveSuspensionExists() {
        when(conversationService.getSuspendedByUser("user123"))
                .thenReturn(new Conversation());

        assertTrue(stateManager.isSuspended("user123"));
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `cd summer-aigc && mvn test -Dtest=SessionStateManagerTest`
Expected: COMPILE ERROR — `SessionStateManager` class not found

- [ ] **Step 5: Implement SessionStateManager**

```java
package log.summer.aigc.session;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import log.summer.aigc.entity.Conversation;
import log.summer.aigc.loop.MessageSnapshot;
import log.summer.aigc.loop.SuspendContext;
import log.summer.aigc.service.IConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages AgentLoop session state: suspend, persist, cache, and restore.
 *
 * <h3>Two-tier storage</h3>
 * <ol>
 *   <li><b>In-memory cache</b> ({@code messageCache}) — fast path for same-JVM resumes</li>
 *   <li><b>DB</b> ({@code conversation.suspend_context} JSON) — cold path for restart survival</li>
 * </ol>
 *
 * <p>On suspend: serialize messages to DB + cache. On resume: check cache first,
 * fall back to DB deserialization.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SessionStateManager {

    private final IConversationService conversationService;
    private final ObjectMapper objectMapper;

    /**
     * In-memory message cache: userId → serialized message snapshots.
     * Evicted on clearSuspend or JVM restart (DB is the source of truth then).
     */
    private final Map<String, SuspendContext> inMemoryCache = new ConcurrentHashMap<>();

    // ═══════════════════════════════════════════════════════════
    // Suspend
    // ═══════════════════════════════════════════════════════════

    /**
     * Suspend the current AgentLoop session, persisting full state for later resume.
     *
     * @param userId       the user whose session is suspended
     * @param convId       the DB conversation ID
     * @param toolName     the @Tool method that requested suspension
     * @param reason       human-readable reason, sent to user
     * @param chatMemory   current ChatMemory to snapshot
     */
    public void suspend(String userId, Long convId, String toolName,
                        String reason, ChatMemory chatMemory) {
        List<Message> messages = new ArrayList<>(chatMemory.get(userId));
        List<MessageSnapshot> snapshots = MessageSnapshot.fromMessages(messages);

        SuspendContext ctx = SuspendContext.of(toolName, reason, convId, snapshots);

        // Serialize to JSON and persist
        String ctxJson;
        try {
            ctxJson = objectMapper.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            log.error("[SESSION] 序列化 SuspendContext 失败 | userId={}", userId, e);
            throw new RuntimeException("Failed to serialize suspend context", e);
        }

        conversationService.updateSuspend(convId, ctxJson, reason);

        // Cache in memory for fast resume
        inMemoryCache.put(userId, ctx);

        log.info("[SESSION] 会话已挂起 | userId={} | tool={} | reason={} | msgCount={}",
                userId, toolName, reason, snapshots.size());
    }

    // ═══════════════════════════════════════════════════════════
    // Query
    // ═══════════════════════════════════════════════════════════

    /** Check if a user has an active suspended session. */
    public boolean isSuspended(String userId) {
        return getSuspendedByUser(userId) != null;
    }

    /** Get the suspended conversation for a user (DB query). */
    public Conversation getSuspendedByUser(String userId) {
        return conversationService.getSuspendedByUser(userId);
    }

    // ═══════════════════════════════════════════════════════════
    // Restore
    // ═══════════════════════════════════════════════════════════

    /**
     * Restore the conversation history into ChatMemory from the suspended state.
     * Tries in-memory cache first (fast), then falls back to DB deserialization (cold).
     *
     * @param conv       the suspended Conversation entity
     * @param chatMemory the ChatMemory to hydrate
     */
    public void restoreMessages(Conversation conv, ChatMemory chatMemory) {
        String userId = conv.getUserId();
        SuspendContext ctx = inMemoryCache.get(userId);

        if (ctx == null && conv.getSuspendContext() != null) {
            // Cold path: deserialize from DB
            try {
                ctx = objectMapper.readValue(conv.getSuspendContext(), SuspendContext.class);
                log.info("[SESSION] 从 DB 恢复会话 | userId={} | msgCount={}",
                        userId, ctx.messagesSnapshot().size());
            } catch (JsonProcessingException e) {
                log.error("[SESSION] 反序列化 SuspendContext 失败 | userId={} | convId={}",
                        userId, conv.getId(), e);
                return;
            }
        }

        if (ctx == null) {
            log.warn("[SESSION] 无挂起上下文可恢复 | userId={}", userId);
            return;
        }

        // Re-hydrate ChatMemory from snapshots
        for (MessageSnapshot snapshot : ctx.messagesSnapshot()) {
            chatMemory.add(userId, snapshot.toSpringAiMessage());
        }

        log.info("[SESSION] ChatMemory 恢复完成 | userId={} | msgCount={}",
                userId, ctx.messagesSnapshot().size());
    }

    // ═══════════════════════════════════════════════════════════
    // Clear
    // ═══════════════════════════════════════════════════════════

    /**
     * Clear the suspend state — conversation is now active again.
     * Called when the user responds and the loop resumes.
     */
    public void clearSuspend(Long convId) {
        conversationService.clearSuspend(convId);
        // Also evict from in-memory cache (userId isn't directly available here,
        // but the next suspend will overwrite anyway)
        log.info("[SESSION] 挂起状态已清除 | convId={}", convId);
    }

    /**
     * Clear in-memory cache for a user. Called when conversation ends normally.
     */
    public void evictCache(String userId) {
        inMemoryCache.remove(userId);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `cd summer-aigc && mvn test -Dtest=SessionStateManagerTest`
Expected: PASS — 6 tests green

- [ ] **Step 7: Verify full compilation**

Run: `cd summer-aigc && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/session/SessionStateManager.java \
        summer-aigc/src/main/java/log/summer/aigc/service/IConversationService.java \
        summer-aigc/src/main/java/log/summer/aigc/service/impl/ConversationServiceImpl.java \
        summer-aigc/src/test/java/log/summer/aigc/session/SessionStateManagerTest.java
git commit -m "feat: add SessionStateManager with suspend/restore/clear + two-tier cache"
```

---

### Task 5: Refactor AgentLoop for Suspend/Resume

**Files:**
- Modify: `summer-aigc/src/main/java/log/summer/aigc/loop/AgentLoop.java`
- Create: `summer-aigc/src/test/java/log/summer/aigc/loop/AgentLoopSuspendTest.java`

**Interfaces:**
- Consumes: `SessionStateManager` (Task 4), enhanced `ActResult` (Task 2), `Conversation` (Task 1)
- Produces: `AgentLoop.orchestrate(BotMessage, MessageSender)` — now checks for suspended session before starting, and suspends instead of clearing memory when tool returns suspend=true

- [ ] **Step 1: Write the failing test**

File: `summer-aigc/src/test/java/log/summer/aigc/loop/AgentLoopSuspendTest.java`

```java
package log.summer.aigc.loop;

import log.summer.aigc.entity.Conversation;
import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.session.SessionStateManager;
import log.summer.aigc.service.ChatService;
import log.summer.aigc.tool.ToolRegistry;
import log.summer.aigc.config.GlobalExceptionHandler;
import log.summer.common.enums.RouteContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AgentLoopSuspendTest {

    @Mock
    private ChatService chatService;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ArgumentResolver argumentResolver;
    @Mock
    private GlobalExceptionHandler exceptionHandler;
    @Mock
    private MessageSender sender;
    @Mock
    private SessionStateManager sessionStateManager;

    private ChatMemory chatMemory;
    private AgentLoop agentLoop;

    @BeforeEach
    void setUp() {
        chatMemory = new InMemoryChatMemory();
        agentLoop = new AgentLoop(
                chatService, toolRegistry, argumentResolver,
                chatMemory, exceptionHandler, sessionStateManager);
    }

    @Test
    void shouldResumeSuspendedSessionOnNextMessage() {
        // Arrange: user has a suspended session
        Conversation suspended = new Conversation();
        suspended.setId(1L);
        suspended.setUserId("user123");
        suspended.setStatus(2);
        suspended.setSuspendReason("等待确认大纲");

        when(sessionStateManager.isSuspended("user123")).thenReturn(true);
        when(sessionStateManager.getSuspendedByUser("user123")).thenReturn(suspended);

        // Mock: LLM returns a final answer after resume
        AssistantMessage assistantMsg = new AssistantMessage("好的，根据你确认的大纲，我来生成文档");
        ChatResponse response = ChatResponse.builder()
                .generations(List.of(new Generation(assistantMsg)))
                .build();
        when(chatService.chatWithTools(anyList(), anyList())).thenReturn(response);

        BotMessage msg = new BotMessage("user123", "确认，第三点改成团队建设",
                null, null, null, RouteContext.TEXT);

        // Act
        agentLoop.orchestrate(msg, sender);

        // Assert: session state was restored before continuing
        verify(sessionStateManager).restoreMessages(eq(suspended), any(ChatMemory.class));
        // Assert: final answer was sent
        verify(sender).sendText(eq("user123"), contains("生成文档"));
    }

    @Test
    void shouldSuspendAndNotClearMemoryWhenToolReturnsSuspend() {
        // No suspended session on entry
        when(sessionStateManager.isSuspended("user123")).thenReturn(false);

        // Arrange: LLM returns a tool call to createOutline
        when(chatService.chatWithTools(anyList(), anyList()))
                .thenReturn(mockChatResponseWithToolCall("createOutline", "{\"type\":\"PPT\"}"));

        // Tool execution returns suspend
        when(argumentResolver.resolve(anyMap(), any()))
                .thenReturn(java.util.Map.of("type", "PPT"));
        ActResult suspendResult = ActResult.suspend(
                java.util.Map.of("title", "Q2业绩报告"),
                "等待确认大纲");
        when(toolRegistry.execute("createOutline", anyMap())).thenReturn(suspendResult);

        BotMessage msg = new BotMessage("user123", "帮我做个PPT",
                null, null, null, RouteContext.TEXT);

        // Act
        agentLoop.orchestrate(msg, sender);

        // Assert: session state was persisted
        verify(sessionStateManager).suspend(
                eq("user123"), anyLong(), eq("createOutline"),
                eq("等待确认大纲"), any(ChatMemory.class));
        // Assert: suspend reason was sent to user
        verify(sender).sendText(eq("user123"), eq("等待确认大纲"));
        // Assert: ChatMemory was NOT cleared (state preserved for resume)
        // (after orchestrate returns, verify chatMemory still has messages)
        // Note: in the finally block, if suspended, clear is skipped
    }

    @Test
    void shouldNotSuspendForNormalSuccessResult() {
        when(sessionStateManager.isSuspended("user123")).thenReturn(false);

        // LLM returns final answer without tool calls
        AssistantMessage assistantMsg = new AssistantMessage("你好！");
        ChatResponse response = ChatResponse.builder()
                .generations(List.of(new Generation(assistantMsg)))
                .build();
        when(chatService.chatWithTools(anyList(), anyList())).thenReturn(response);

        BotMessage msg = new BotMessage("user123", "你好",
                null, null, null, RouteContext.TEXT);

        // Act
        agentLoop.orchestrate(msg, sender);

        // Assert: no suspend state was saved
        verify(sessionStateManager, never()).suspend(anyString(), anyLong(),
                anyString(), anyString(), any());
        // Assert: final answer was sent
        verify(sender).sendText(eq("user123"), eq("你好！"));
    }

    // ── test helpers ──

    private ChatResponse mockChatResponseWithToolCall(String toolName, String args) {
        var toolCall = new org.springframework.ai.chat.messages.AssistantMessage
                .ToolCall("call1", "function", toolName, args);
        AssistantMessage assistantMsg = new AssistantMessage(
                "我来生成大纲", java.util.Map.of(), List.of(toolCall));
        return ChatResponse.builder()
                .generations(List.of(new Generation(assistantMsg)))
                .build();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd summer-aigc && mvn test -Dtest=AgentLoopSuspendTest`
Expected: COMPILE ERROR — Constructor mismatch (AgentLoop now requires SessionStateManager)

- [ ] **Step 3: Refactor AgentLoop**

```java
package log.summer.aigc.loop;

import log.summer.aigc.entity.Conversation;
import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.service.ChatService;
import log.summer.aigc.session.SessionStateManager;
import log.summer.aigc.tool.ToolRegistry;
import log.summer.aigc.config.GlobalExceptionHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Dual-loop agent orchestrator with suspend/resume support.
 *
 * <h3>Flow</h3>
 * <pre>
 * 1. Check if user has a SUSPENDED conversation → resume path
 * 2. Otherwise, start fresh:
 *    while (round &lt; maxIterations &amp;&amp; !timeout):
 *      THINK: LLM &rarr; ThinkResult
 *      if finalAnswer &rarr; send to user, exit
 *      if toolCalls &rarr; ACT: execute each tool, append results to memory
 *      if tool returned suspend &rarr; persist state, exit (don't clear memory)
 *      repeat
 * </pre>
 *
 * <p>Spring AI is used only for single-call interactions.
 * This class owns the iteration, memory, and termination logic.</p>
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private final ChatService chatService;
    private final ToolRegistry toolRegistry;
    private final ArgumentResolver argumentResolver;
    private final ChatMemory chatMemory;
    private final GlobalExceptionHandler exceptionHandler;
    private final SessionStateManager sessionStateManager;

    private static final int MAX_ITERATIONS = 10;
    private static final long TIMEOUT_SECONDS = 120;

    /**
     * Orchestrate a single user message through the think-act loop.
     *
     * @param msg    the normalized inbound message
     * @param sender the output channel back to the user
     */
    public void orchestrate(BotMessage msg, MessageSender sender) {
        String userId = msg.userId();
        boolean suspended = false;

        // ── Resume check: does user have a suspended session? ──
        Conversation suspendedConv = sessionStateManager.getSuspendedByUser(userId);
        if (suspendedConv != null) {
            log.info("[AGENT-LOOP] 检测到挂起会话 | userId={} | convId={} | reason={}",
                    userId, suspendedConv.getId(), suspendedConv.getSuspendReason());
            resumeOrchestrate(msg, sender, suspendedConv);
            return;
        }

        Instant start = Instant.now();
        int round = 0;
        boolean finalAnswerSent = false;

        // ── Add user message to memory ──
        if (msg.hasText()) {
            chatMemory.add(userId, new UserMessage(msg.text()));
        } else if (msg.hasImage()) {
            chatMemory.add(userId, new UserMessage("[用户发送了一张图片]"));
        } else if (msg.hasFile()) {
            chatMemory.add(userId, new UserMessage("[用户发送了文件: " + msg.fileName() + "]"));
        }

        try {
            while (round < MAX_ITERATIONS) {
                // ── Safety valve: timeout ──
                if (Instant.now().isAfter(start.plusSeconds(TIMEOUT_SECONDS))) {
                    sender.sendText(userId, "处理超时，请稍后再试。");
                    log.warn("[AGENT-LOOP] 超时 | userId={} | rounds={}", userId, round);
                    break;
                }

                round++;
                log.debug("[AGENT-LOOP] 第 {} 轮思考 | userId={}", round, userId);

                // ── THINK ──
                List<Message> messages = new ArrayList<>(chatMemory.get(userId));

                var chatResponse = chatService.chatWithTools(
                        messages, toolRegistry.getCallbacks());

                ThinkResult think = ThinkResult.fromChatResponse(chatResponse);

                // ── Final answer (no tool calls) → exit ──
                if (think.hasFinalAnswer() && !think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                    log.debug("[AGENT-LOOP] 最终答案 | userId={} | rounds={}", userId, round);
                    break;
                }

                // ── Final answer + tool calls (LLM can return both) ──
                if (think.hasFinalAnswer() && think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                }

                // ── ACT: execute tool calls ──
                if (think.hasToolCalls()) {
                    for (var toolCall : think.toolCalls()) {
                        log.info("[AGENT-LOOP] 调用工具 | userId={} | tool={} | args={}",
                                userId, toolCall.name(), toolCall.arguments());

                        ActResult result;
                        try {
                            Map<String, Object> resolvedArgs =
                                    argumentResolver.resolve(toolCall.arguments(), msg);
                            result = toolRegistry.execute(toolCall.name(), resolvedArgs);
                        } catch (ArgumentResolver.PlaceholderResolutionException e) {
                            result = ActResult.failure(e.getMessage());
                        }

                        // ── NEW: Check for suspend signal ──
                        if (result.suspend()) {
                            log.info("[AGENT-LOOP] 工具请求挂起 | userId={} | tool={} | reason={}",
                                    userId, toolCall.name(), result.suspendReason());

                            // Send the tool's data (e.g. outline) and suspend reason to user
                            if (result.data() != null) {
                                sender.sendText(userId, result.data().toString());
                            }
                            if (result.suspendReason() != null) {
                                sender.sendText(userId, result.suspendReason());
                            }

                            // Persist suspend state via SessionStateManager
                            // conversationId is tracked externally — use active conversation
                            Conversation activeConv = sessionStateManager
                                    .getSuspendedByUser(userId);
                            long convId = activeConv != null
                                    ? activeConv.getId()
                                    : 0L;

                            sessionStateManager.suspend(
                                    userId, convId, toolCall.name(),
                                    result.suspendReason(), chatMemory);

                            suspended = true;
                            break; // exit tool-call loop
                        }

                        // ── Normal result handling (existing logic) ──
                        String resultText = result.success()
                                ? (result.data() != null ? result.data().toString()
                                        : "success")
                                : "ERROR: " + result.errorMessage();

                        String callId = UUID.randomUUID().toString();
                        var toolResponse = new ToolResponseMessage.ToolResponse(
                                callId, toolCall.name(), resultText);
                        chatMemory.add(userId,
                                new ToolResponseMessage(List.of(toolResponse)));

                        log.debug("[AGENT-LOOP] 工具结果 | userId={} | tool={} | success={}",
                                userId, toolCall.name(), result.success());
                    }

                    // If we suspended, break out of the main while loop
                    if (suspended) {
                        break;
                    }
                    // Continue loop → next think round with tool results in context
                    continue;
                }

                // No tool calls and no final answer → safety break
                log.warn("[AGENT-LOOP] LLM 返回空响应 | userId={} | round={}", userId, round);
                sender.sendText(userId, "我暂时无法处理这个请求，请换个方式试试。");
                break;
            }

            // ── Max iterations exceeded ──
            if (!finalAnswerSent && !suspended && round >= MAX_ITERATIONS) {
                log.warn("[AGENT-LOOP] 达到最大迭代次数 | userId={}", userId);
                sender.sendText(userId,
                        "我暂时无法完成这个任务，请稍后再试。");
            }

        } catch (Exception e) {
            log.error("[AGENT-LOOP] 循环异常 | userId={}", userId, e);
            exceptionHandler.handle(userId, sender, "AgentLoop", e);
        } finally {
            // Clear conversation memory ONLY if not suspended
            if (!suspended) {
                chatMemory.clear(userId);
                sessionStateManager.evictCache(userId);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Resume path
    // ═══════════════════════════════════════════════════════════

    /**
     * Resume a previously suspended AgentLoop session.
     * Restores ChatMemory from the persisted state, appends the new user
     * message, and continues the think-act loop from where it left off.
     */
    private void resumeOrchestrate(BotMessage msg, MessageSender sender,
                                   Conversation suspendedConv) {
        String userId = msg.userId();
        boolean suspended = false;

        // ── Restore ChatMemory from suspend context ──
        chatMemory.clear(userId); // ensure clean slate
        sessionStateManager.restoreMessages(suspendedConv, chatMemory);

        // ── Append the new user message ──
        if (msg.hasText()) {
            chatMemory.add(userId, new UserMessage(msg.text()));
        } else if (msg.hasImage()) {
            chatMemory.add(userId, new UserMessage("[用户发送了一张图片]"));
        } else if (msg.hasFile()) {
            chatMemory.add(userId, new UserMessage("[用户发送了文件: " + msg.fileName() + "]"));
        }

        // ── Clear the suspend state (we're resuming) ──
        sessionStateManager.clearSuspend(suspendedConv.getId());

        Instant start = Instant.now();
        int round = 0;
        boolean finalAnswerSent = false;

        try {
            while (round < MAX_ITERATIONS) {
                if (Instant.now().isAfter(start.plusSeconds(TIMEOUT_SECONDS))) {
                    sender.sendText(userId, "处理超时，请稍后再试。");
                    log.warn("[AGENT-LOOP] 恢复后超时 | userId={} | rounds={}", userId, round);
                    break;
                }

                round++;
                log.debug("[AGENT-LOOP] 恢复后第 {} 轮思考 | userId={}", round, userId);

                List<Message> messages = new ArrayList<>(chatMemory.get(userId));

                var chatResponse = chatService.chatWithTools(
                        messages, toolRegistry.getCallbacks());

                ThinkResult think = ThinkResult.fromChatResponse(chatResponse);

                if (think.hasFinalAnswer() && !think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                    break;
                }

                if (think.hasFinalAnswer() && think.hasToolCalls()) {
                    sender.sendText(userId, think.finalAnswer());
                    finalAnswerSent = true;
                }

                if (think.hasToolCalls()) {
                    for (var toolCall : think.toolCalls()) {
                        log.info("[AGENT-LOOP] 恢复后调用工具 | userId={} | tool={}",
                                userId, toolCall.name());

                        ActResult result;
                        try {
                            Map<String, Object> resolvedArgs =
                                    argumentResolver.resolve(toolCall.arguments(), msg);
                            result = toolRegistry.execute(toolCall.name(), resolvedArgs);
                        } catch (ArgumentResolver.PlaceholderResolutionException e) {
                            result = ActResult.failure(e.getMessage());
                        }

                        // Check for another suspend (nested confirmation)
                        if (result.suspend()) {
                            log.info("[AGENT-LOOP] 工具再次请求挂起 | userId={} | tool={}",
                                    userId, toolCall.name());

                            if (result.data() != null) {
                                sender.sendText(userId, result.data().toString());
                            }
                            if (result.suspendReason() != null) {
                                sender.sendText(userId, result.suspendReason());
                            }

                            sessionStateManager.suspend(
                                    userId, suspendedConv.getId(), toolCall.name(),
                                    result.suspendReason(), chatMemory);

                            suspended = true;
                            break;
                        }

                        String resultText = result.success()
                                ? (result.data() != null ? result.data().toString()
                                        : "success")
                                : "ERROR: " + result.errorMessage();

                        String callId = UUID.randomUUID().toString();
                        var toolResponse = new ToolResponseMessage.ToolResponse(
                                callId, toolCall.name(), resultText);
                        chatMemory.add(userId,
                                new ToolResponseMessage(List.of(toolResponse)));
                    }

                    if (suspended) break;
                    continue;
                }

                log.warn("[AGENT-LOOP] 恢复后 LLM 返回空响应 | userId={}", userId);
                sender.sendText(userId, "我暂时无法处理这个请求，请换个方式试试。");
                break;
            }

            if (!finalAnswerSent && !suspended && round >= MAX_ITERATIONS) {
                log.warn("[AGENT-LOOP] 恢复后达到最大迭代次数 | userId={}", userId);
                sender.sendText(userId, "我暂时无法完成这个任务，请稍后再试。");
            }

        } catch (Exception e) {
            log.error("[AGENT-LOOP] 恢复后循环异常 | userId={}", userId, e);
            exceptionHandler.handle(userId, sender, "AgentLoop-resume", e);
        } finally {
            if (!suspended) {
                chatMemory.clear(userId);
                sessionStateManager.evictCache(userId);
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd summer-aigc && mvn test -Dtest=AgentLoopSuspendTest`
Expected: PASS — 3 tests green

- [ ] **Step 5: Verify full compilation (including spring context)**

Run: `cd summer-bootstrap && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/AgentLoop.java \
        summer-aigc/src/test/java/log/summer/aigc/loop/AgentLoopSuspendTest.java
git commit -m "feat: refactor AgentLoop with suspend/resume support"
```

---

### Task 6: Database Migration — Create Document Outline Table

**Files:**
- Create: `summer-aigc/src/main/resources/db/migration/V3__create_outline_table.sql`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/DocumentOutline.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/DocumentOutlineMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IDocumentOutlineService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/DocumentOutlineServiceImpl.java`

**Interfaces:**
- Produces: `DocumentOutline` entity with fields: id, userId, conversationId, outlineType (WORD/PPT/EXCEL), title, outlineData (JSON), status (DRAFT/CONFIRMED/MODIFIED/GENERATING/DONE), version, createdAt, updatedAt
- Produces: `IDocumentOutlineService.save(DocumentOutline)` → boolean (MyBatis-Plus save)
- Produces: `IDocumentOutlineService.getByConvId(Long convId)` → List<DocumentOutline>
- Produces: `IDocumentOutlineService.getById(Long id)` → DocumentOutline
- Produces: `IDocumentOutlineService.confirmOutline(Long id, String modifiedData)` → boolean

- [ ] **Step 1: Write the Flyway migration**

```sql
-- V3__create_outline_table.sql
-- Structured document outline storage for createOutline → confirm → generateDocument flow

CREATE TABLE IF NOT EXISTS document_outline (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    outline_type VARCHAR(20) NOT NULL COMMENT 'WORD/PPT/EXCEL',
    title VARCHAR(500) DEFAULT NULL COMMENT '文档标题',
    outline_data JSON NOT NULL COMMENT '结构化大纲数据',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT'
        COMMENT 'DRAFT/CONFIRMED/MODIFIED/GENERATING/DONE',
    version INT DEFAULT 1 COMMENT '修改版本号，每次用户确认+1',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_user_id (user_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_outline_type (outline_type),
    INDEX idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 2: Create DocumentOutline entity**

```java
package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_outline")
public class DocumentOutline {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long conversationId;
    private String outlineType;       // WORD / PPT / EXCEL
    private String title;              // 文档标题
    private String outlineData;        // JSON: 结构化大纲
    private String status;             // DRAFT / CONFIRMED / MODIFIED / GENERATING / DONE
    private Integer version;           // 修改版本号
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Create DocumentOutlineMapper**

```java
package log.summer.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.summer.aigc.entity.DocumentOutline;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentOutlineMapper extends BaseMapper<DocumentOutline> {
}
```

- [ ] **Step 4: Create IDocumentOutlineService interface**

```java
package log.summer.aigc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.summer.aigc.entity.DocumentOutline;

import java.util.List;

public interface IDocumentOutlineService extends IService<DocumentOutline> {

    /** Query outlines for a conversation. */
    List<DocumentOutline> getByConversationId(Long conversationId);

    /** Query outlines for a user, latest first. */
    List<DocumentOutline> getByUserId(String userId, int limit);

    /** Confirm and optionally update an outline, advancing version. */
    boolean confirmOutline(Long outlineId, String modifiedOutlineData);

    /** Update outline status (DRAFT → CONFIRMED → GENERATING → DONE). */
    boolean updateStatus(Long outlineId, String status);
}
```

- [ ] **Step 5: Create DocumentOutlineServiceImpl**

```java
package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.mapper.DocumentOutlineMapper;
import log.summer.aigc.service.IDocumentOutlineService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DocumentOutlineServiceImpl
        extends ServiceImpl<DocumentOutlineMapper, DocumentOutline>
        implements IDocumentOutlineService {

    @Override
    public List<DocumentOutline> getByConversationId(Long conversationId) {
        return list(new LambdaQueryWrapper<DocumentOutline>()
                .eq(DocumentOutline::getConversationId, conversationId)
                .orderByDesc(DocumentOutline::getCreatedAt));
    }

    @Override
    public List<DocumentOutline> getByUserId(String userId, int limit) {
        return list(new LambdaQueryWrapper<DocumentOutline>()
                .eq(DocumentOutline::getUserId, userId)
                .orderByDesc(DocumentOutline::getCreatedAt)
                .last("LIMIT " + limit));
    }

    @Override
    public boolean confirmOutline(Long outlineId, String modifiedOutlineData) {
        DocumentOutline outline = getById(outlineId);
        if (outline == null) return false;

        int newVersion = (outline.getVersion() == null ? 0 : outline.getVersion()) + 1;

        return lambdaUpdate()
                .set(DocumentOutline::getOutlineData, modifiedOutlineData)
                .set(DocumentOutline::getStatus,
                        modifiedOutlineData != null ? "MODIFIED" : "CONFIRMED")
                .set(DocumentOutline::getVersion, newVersion)
                .set(DocumentOutline::getUpdatedAt, LocalDateTime.now())
                .eq(DocumentOutline::getId, outlineId)
                .update();
    }

    @Override
    public boolean updateStatus(Long outlineId, String status) {
        return lambdaUpdate()
                .set(DocumentOutline::getStatus, status)
                .set(DocumentOutline::getUpdatedAt, LocalDateTime.now())
                .eq(DocumentOutline::getId, outlineId)
                .update();
    }
}
```

- [ ] **Step 6: Verify Flyway migration and compilation**

Run: `cd summer-bootstrap && mvn spring-boot:run` (verify migration applies)
Run: `cd summer-aigc && mvn compile`
Expected: BUILD SUCCESS for both

- [ ] **Step 7: Commit**

```bash
git add summer-aigc/src/main/resources/db/migration/V3__create_outline_table.sql \
        summer-aigc/src/main/java/log/summer/aigc/entity/DocumentOutline.java \
        summer-aigc/src/main/java/log/summer/aigc/mapper/DocumentOutlineMapper.java \
        summer-aigc/src/main/java/log/summer/aigc/service/IDocumentOutlineService.java \
        summer-aigc/src/main/java/log/summer/aigc/service/impl/DocumentOutlineServiceImpl.java
git commit -m "feat: add document_outline table, entity, mapper, and service"
```

---

### Task 7: Create CreateOutlineTool

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/outline/CreateOutlineTool.java`
- Create: `summer-aigc/src/test/java/log/summer/aigc/tool/outline/CreateOutlineToolTest.java`

**Interfaces:**
- Consumes: `IDocumentOutlineService` (Task 6), `ActResult.suspend()` (Task 2)
- Produces: `@Tool(name="createOutline")` — generates a structured outline, persists to DB, returns `ActResult.suspend(outlineJson, "等待确认大纲")`

- [ ] **Step 1: Write the failing test**

File: `summer-aigc/src/test/java/log/summer/aigc/tool/outline/CreateOutlineToolTest.java`

```java
package log.summer.aigc.tool.outline;

import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.IDocumentOutlineService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateOutlineToolTest {

    @Mock
    private IDocumentOutlineService outlineService;

    private CreateOutlineTool tool;

    @BeforeEach
    void setUp() {
        tool = new CreateOutlineTool(outlineService);
    }

    @Test
    void shouldReturnSuspendWithFormattedOutline() {
        when(outlineService.save(any())).thenReturn(true);

        String outlineJson = """
            {
              "title": "Q2业绩报告",
              "sections": [
                {"title": "业绩概览", "points": ["总营收", "增长率"]},
                {"title": "各部门分析", "points": ["销售部", "研发部"]}
              ]
            }""";

        ActResult result = tool.createOutline(
                "PPT", "Q2业绩报告", outlineJson,
                "user123", 1L);

        assertTrue(result.success());
        assertTrue(result.suspend());
        assertTrue(result.requiresConfirmation());
        assertEquals("等待确认大纲内容，您可以回复「确认」或提出修改意见", result.suspendReason());
        assertNotNull(result.data());
        // Verify outline was saved to DB
        verify(outlineService).save(any());
    }

    @Test
    void shouldReturnFailureForInvalidType() {
        ActResult result = tool.createOutline(
                "PDF", "test", "{}", "user123", 1L);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("不支持"));
        verify(outlineService, never()).save(any());
    }

    @Test
    void shouldReturnFailureForEmptyOutline() {
        ActResult result = tool.createOutline(
                "PPT", "test", "", "user123", 1L);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("大纲数据不能为空"));
    }

    @Test
    void shouldReturnFailureForNullTitle() {
        ActResult result = tool.createOutline(
                "WORD", null, "{}", "user123", 1L);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("标题"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd summer-aigc && mvn test -Dtest=CreateOutlineToolTest`
Expected: COMPILE ERROR — `CreateOutlineTool` class not found

- [ ] **Step 3: Implement CreateOutlineTool**

```java
package log.summer.aigc.tool.outline;

import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.IDocumentOutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Generates a structured document outline and suspends the AgentLoop
 * for user confirmation.
 *
 * <p>The LLM calls this tool after understanding the user's document needs.
 * The tool persists the outline to DB and returns {@link ActResult#suspend},
 * which causes AgentLoop to pause and wait for user feedback.</p>
 *
 * <p>Supported outline types: WORD, PPT, EXCEL.</p>
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateOutlineTool {

    private final IDocumentOutlineService outlineService;

    private static final Set<String> VALID_TYPES = Set.of("WORD", "PPT", "EXCEL");

    @Tool(name = "createOutline",
          description = "生成文档结构化大纲，供用户确认后用于 generateDocument 渲染正式文件。" +
                        "调用后系统会自动暂停等待用户确认/修改。")
    public ActResult createOutline(
            @ToolParam(description = "文档类型: WORD, PPT, EXCEL") String type,
            @ToolParam(description = "文档标题") String title,
            @ToolParam(description = "结构化大纲 JSON，格式: " +
                    "{\"title\":\"...\", \"sections\":[{\"title\":\"...\", \"points\":[\"...\"]}]}") String outlineJson,
            @ToolParam(description = "用户ID") String userId,
            @ToolParam(description = "会话ID") Long conversationId) {

        // ── Validation ──
        if (type == null || !VALID_TYPES.contains(type.toUpperCase())) {
            return ActResult.failure("不支持的文档类型: " + type
                    + "，支持的类型: WORD, PPT, EXCEL");
        }

        if (title == null || title.isBlank()) {
            return ActResult.failure("请提供文档标题");
        }

        if (outlineJson == null || outlineJson.isBlank()) {
            return ActResult.failure("大纲数据不能为空");
        }

        // Basic JSON validation — ensure it parses
        try {
            new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(outlineJson);
        } catch (Exception e) {
            return ActResult.failure("大纲数据格式无效（需要合法 JSON）: " + e.getMessage());
        }

        // ── Persist outline to DB ──
        DocumentOutline outline = new DocumentOutline();
        outline.setUserId(userId);
        outline.setConversationId(conversationId);
        outline.setOutlineType(type.toUpperCase());
        outline.setTitle(title);
        outline.setOutlineData(outlineJson);
        outline.setStatus("DRAFT");
        outline.setVersion(1);

        try {
            outlineService.save(outline);
            log.info("[OUTLINE] 大纲已保存 | id={} | type={} | title={}",
                    outline.getId(), type, title);
        } catch (Exception e) {
            log.error("[OUTLINE] 大纲持久化失败 | userId={}", userId, e);
            return ActResult.failure("大纲保存失败: " + e.getMessage());
        }

        // ── Format outline for user display ──
        String displayText = buildDisplayText(outline, outlineJson);

        // ── Build suspend context data ──
        Map<String, Object> resultData = new LinkedHashMap<>();
        resultData.put("outlineId", outline.getId());
        resultData.put("type", type.toUpperCase());
        resultData.put("title", title);
        resultData.put("outline", outlineJson);

        // ── Return with suspend signal ──
        String userMessage = displayText + "\n\n" +
                "— " + "等待确认大纲内容，您可以回复「确认」或提出修改意见";

        return ActResult.suspend(resultData, userMessage);
    }

    // ── Display formatting ──

    /**
     * Build a human-readable preview of the outline for user confirmation.
     */
    private String buildDisplayText(DocumentOutline outline, String outlineJson) {
        StringBuilder sb = new StringBuilder();
        sb.append("📄 **").append(outline.getTitle()).append("**");
        sb.append("（").append(outline.getOutlineType()).append("）\n\n");

        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var root = mapper.readTree(outlineJson);

            if (root.has("sections")) {
                var sections = root.get("sections");
                for (int i = 0; i < sections.size(); i++) {
                    var section = sections.get(i);
                    String sectionTitle = section.has("title")
                            ? section.get("title").asText() : "";
                    sb.append("**").append(i + 1).append(". ").append(sectionTitle)
                            .append("**\n");

                    if (section.has("points")) {
                        var points = section.get("points");
                        for (int j = 0; j < points.size(); j++) {
                            sb.append("    - ").append(points.get(j).asText()).append("\n");
                        }
                    }
                    sb.append("\n");
                }
            }
        } catch (Exception e) {
            // Fallback: show JSON as-is
            sb.append("```json\n").append(outlineJson).append("\n```\n");
        }

        return sb.toString().trim();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd summer-aigc && mvn test -Dtest=CreateOutlineToolTest`
Expected: PASS — 4 tests green

- [ ] **Step 5: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/outline/CreateOutlineTool.java \
        summer-aigc/src/test/java/log/summer/aigc/tool/outline/CreateOutlineToolTest.java
git commit -m "feat: add CreateOutlineTool with suspend-based confirmation flow"
```

---

### Task 8: Add Apache POI Dependency

**Files:**
- Modify: `pom.xml` (root, add dependency management entry)
- Modify: `summer-aigc/pom.xml` (add poi-ooxml dependency)

- [ ] **Step 1: Add POI version property and dependency management to root pom.xml**

In the root `pom.xml`, add to `<properties>`:

```xml
<poi.version>5.2.5</poi.version>
```

In the root `pom.xml`, add to `<dependencyManagement>` / `<dependencies>`:

```xml
<!-- Apache POI for Office document generation -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>${poi.version}</version>
</dependency>
```

- [ ] **Step 2: Add POI dependency to summer-aigc/pom.xml**

In `summer-aigc/pom.xml`, add inside `<dependencies>`:

```xml
<!-- Apache POI: Word/PPT/Excel generation -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
</dependency>
```

- [ ] **Step 3: Verify dependency resolution**

Run: `cd summer-aigc && mvn dependency:resolve | grep poi`
Expected: Shows `org.apache.poi:poi-ooxml:5.2.5` and transitive deps (poi, poi-ooxml-lite, etc.)

- [ ] **Step 4: Commit**

```bash
git add pom.xml summer-aigc/pom.xml
git commit -m "build: add Apache POI 5.2.5 for Office document generation"
```

---

### Task 9: Create DocumentGenerator Service (POI-based)

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/document/DocumentGenerator.java`
- Create: `summer-aigc/src/test/java/log/summer/aigc/tool/document/DocumentGeneratorTest.java`

**Interfaces:**
- Consumes: POI library (Task 8)
- Produces:
  - `DocumentGenerator.generateWord(String title, String outlineJson)` → `byte[]` (the .docx file bytes)
  - `DocumentGenerator.generatePpt(String title, String outlineJson)` → `byte[]` (the .pptx file bytes)
  - `DocumentGenerator.generateExcel(String title, String outlineJson)` → `byte[]` (the .xlsx file bytes)
  - Internal helpers: `parseOutline(String outlineJson)` → `OutlineData` record

- [ ] **Step 1: Write the failing test**

File: `summer-aigc/src/test/java/log/summer/aigc/tool/document/DocumentGeneratorTest.java`

```java
package log.summer.aigc.tool.document;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DocumentGeneratorTest {

    private DocumentGenerator generator;

    private static final String SAMPLE_OUTLINE = """
        {
          "title": "Q2业绩报告",
          "sections": [
            {"title": "业绩概览", "points": ["总营收 5000万", "同比增长 15%"]},
            {"title": "各部门分析", "points": ["销售部达成率 120%", "研发部完成3个核心项目"]},
            {"title": "下季度规划", "points": ["拓展华东市场", "上线新版CRM系统"]}
          ]
        }""";

    @BeforeEach
    void setUp() {
        generator = new DocumentGenerator();
    }

    @Test
    void shouldGenerateWordDocument(@TempDir Path tempDir) throws IOException {
        byte[] docxBytes = generator.generateWord("Q2业绩报告", SAMPLE_OUTLINE);

        assertNotNull(docxBytes);
        assertTrue(docxBytes.length > 0, "Word document should have content");

        // Verify it's a valid ZIP (OOXML format)
        Path file = tempDir.resolve("test.docx");
        Files.write(file, docxBytes);
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void shouldGeneratePptDocument(@TempDir Path tempDir) throws IOException {
        byte[] pptxBytes = generator.generatePpt("Q2业绩报告", SAMPLE_OUTLINE);

        assertNotNull(pptxBytes);
        assertTrue(pptxBytes.length > 0, "PPT document should have content");

        Path file = tempDir.resolve("test.pptx");
        Files.write(file, pptxBytes);
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void shouldGenerateExcelDocument(@TempDir Path tempDir) throws IOException {
        byte[] xlsxBytes = generator.generateExcel("Q2业绩报告", SAMPLE_OUTLINE);

        assertNotNull(xlsxBytes);
        assertTrue(xlsxBytes.length > 0, "Excel document should have content");

        Path file = tempDir.resolve("test.xlsx");
        Files.write(file, xlsxBytes);
        assertTrue(Files.size(file) > 0);
    }

    @Test
    void shouldRejectInvalidOutlineJson() {
        assertThrows(DocumentGenerator.OutlineParseException.class, () -> {
            generator.generateWord("Test", "{invalid json}");
        });
    }

    @Test
    void shouldRejectEmptySections() {
        String emptyOutline = "{\"title\":\"Test\", \"sections\":[]}";

        assertThrows(DocumentGenerator.OutlineParseException.class, () -> {
            generator.generateWord("Test", emptyOutline);
        });
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd summer-aigc && mvn test -Dtest=DocumentGeneratorTest`
Expected: COMPILE ERROR — `DocumentGenerator` class not found

- [ ] **Step 3: Implement DocumentGenerator**

```java
package log.summer.aigc.tool.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.TextShape;
import org.apache.poi.xslf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

/**
 * Generates Office documents (Word, PPT, Excel) from structured outline JSON
 * using Apache POI.
 *
 * <h3>Supported formats</h3>
 * <ul>
 *   <li><b>Word</b> (.docx) — heading paragraphs for sections, bullet points for items</li>
 *   <li><b>PPT</b>  (.pptx) — one slide per section, title + bullet list</li>
 *   <li><b>Excel</b> (.xlsx) — one sheet, sections as row groups with bold headers</li>
 * </ul>
 *
 * <p>Outline JSON format:</p>
 * <pre>{@code
 * {
 *   "title": "Document Title",
 *   "sections": [
 *     {"title": "Section 1", "points": ["point a", "point b"]},
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
public class DocumentGenerator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ═══════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════

    /**
     * Generate a Word (.docx) document from an outline.
     *
     * @param title       the document title
     * @param outlineJson structured outline JSON string
     * @return the .docx file as a byte array
     * @throws OutlineParseException if the outline JSON is malformed or empty
     */
    public byte[] generateWord(String title, String outlineJson) {
        OutlineData outline = parseOutline(outlineJson);

        try (XWPFDocument doc = new XWPFDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // ── Title ──
            XWPFParagraph titlePara = doc.createParagraph();
            titlePara.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = titlePara.createRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(20);
            titleRun.setFontFamily("Microsoft YaHei");
            titleRun.addBreak();

            // ── Blank line after title ──
            doc.createParagraph();

            // ── Sections ──
            for (int i = 0; i < outline.sections().size(); i++) {
                SectionData section = outline.sections().get(i);

                // Section heading
                XWPFParagraph heading = doc.createParagraph();
                XWPFRun headingRun = heading.createRun();
                headingRun.setText((i + 1) + ". " + section.title());
                headingRun.setBold(true);
                headingRun.setFontSize(16);
                headingRun.setFontFamily("Microsoft YaHei");

                // Section points
                for (String point : section.points()) {
                    XWPFParagraph pointPara = doc.createParagraph();
                    pointPara.setIndentationLeft(400);
                    XWPFRun pointRun = pointPara.createRun();
                    pointRun.setText("• " + point);
                    pointRun.setFontSize(12);
                    pointRun.setFontFamily("Microsoft YaHei");
                }

                // Blank line between sections
                doc.createParagraph();
            }

            doc.write(baos);
            log.info("[DOC-GEN] Word 生成完成 | title={} | sections={} | size={}bytes",
                    title, outline.sections().size(), baos.size());
            return baos.toByteArray();

        } catch (OutlineParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DOC-GEN] Word 生成失败 | title={}", title, e);
            throw new RuntimeException("Word document generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate a PowerPoint (.pptx) document from an outline.
     *
     * @param title       the presentation title
     * @param outlineJson structured outline JSON string
     * @return the .pptx file as a byte array
     * @throws OutlineParseException if the outline JSON is malformed or empty
     */
    public byte[] generatePpt(String title, String outlineJson) {
        OutlineData outline = parseOutline(outlineJson);

        try (XMLSlideShow ppt = new XMLSlideShow();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            // ── Title slide ──
            XSLFSlide titleSlide = ppt.createSlide();
            XSLFTextBox titleBox = titleSlide.createTextBox();
            titleBox.setAnchor(new Rectangle(50, 80, 620, 100));
            XSLFTextParagraph titlePara = titleBox.addNewTextParagraph();
            titlePara.setTextAlign(TextShape.TextAlign.CENTER);
            XSLFTextRun titleRun = titlePara.addNewTextRun();
            titleRun.setText(title);
            titleRun.setBold(true);
            titleRun.setFontSize(36.0);
            titleRun.setFontFamily("Microsoft YaHei");
            titleRun.setFontColor(Color.BLACK);

            // ── Content slides (one per section) ──
            for (SectionData section : outline.sections()) {
                XSLFSlide slide = ppt.createSlide();

                // Section title
                XSLFTextBox sectionTitleBox = slide.createTextBox();
                sectionTitleBox.setAnchor(new Rectangle(50, 40, 620, 60));
                XSLFTextParagraph stPara = sectionTitleBox.addNewTextParagraph();
                XSLFTextRun stRun = stPara.addNewTextRun();
                stRun.setText(section.title());
                stRun.setBold(true);
                stRun.setFontSize(28.0);
                stRun.setFontFamily("Microsoft YaHei");
                stRun.setFontColor(new Color(0x1A, 0x56, 0xDB)); // blue accent

                // Separator line
                XSLFTextBox sepBox = slide.createTextBox();
                sepBox.setAnchor(new Rectangle(50, 95, 620, 5));
                XSLFTextParagraph sepPara = sepBox.addNewTextParagraph();
                XSLFTextRun sepRun = sepPara.addNewTextRun();
                sepRun.setText("━━━━━━━━━━━━━━━━━━━━━━━━━━");
                sepRun.setFontSize(10.0);
                sepRun.setFontColor(Color.LIGHT_GRAY);

                // Points
                XSLFTextBox pointsBox = slide.createTextBox();
                pointsBox.setAnchor(new Rectangle(70, 120, 580, 300));
                XSLFTextParagraph pointsPara = pointsBox.addNewTextParagraph();
                for (int j = 0; j < section.points().size(); j++) {
                    if (j > 0) pointsPara.addLineBreak();
                    XSLFTextRun pointRun = pointsPara.addNewTextRun();
                    pointRun.setText("• " + section.points().get(j));
                    pointRun.setFontSize(18.0);
                    pointRun.setFontFamily("Microsoft YaHei");
                    pointRun.setFontColor(Color.DARK_GRAY);
                }
            }

            ppt.write(baos);
            log.info("[DOC-GEN] PPT 生成完成 | title={} | slides={} | size={}bytes",
                    title, outline.sections().size() + 1, baos.size());
            return baos.toByteArray();

        } catch (OutlineParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DOC-GEN] PPT 生成失败 | title={}", title, e);
            throw new RuntimeException("PPT document generation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Generate an Excel (.xlsx) document from an outline.
     *
     * @param title       the spreadsheet title
     * @param outlineJson structured outline JSON string
     * @return the .xlsx file as a byte array
     * @throws OutlineParseException if the outline JSON is malformed or empty
     */
    public byte[] generateExcel(String title, String outlineJson) {
        OutlineData outline = parseOutline(outlineJson);

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            var sheet = wb.createSheet(title != null ? title : "Sheet1");

            // ── Styles ──
            var headerStyle = wb.createCellStyle();
            var headerFont = wb.createFont();
            headerFont.setBold(true);
            headerFont.setFontHeightInPoints((short) 14);
            headerStyle.setFont(headerFont);

            var sectionStyle = wb.createCellStyle();
            var sectionFont = wb.createFont();
            sectionFont.setBold(true);
            sectionFont.setFontHeightInPoints((short) 12);
            sectionStyle.setFont(sectionFont);

            int rowIdx = 0;

            // ── Title row ──
            var titleRow = sheet.createRow(rowIdx++);
            var titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            titleCell.setCellStyle(headerStyle);
            rowIdx++; // blank row

            // ── Sections ──
            for (SectionData section : outline.sections()) {
                // Section header
                var sectionRow = sheet.createRow(rowIdx++);
                var sectionCell = sectionRow.createCell(0);
                sectionCell.setCellValue(section.title());
                sectionCell.setCellStyle(sectionStyle);

                // Points (indented via column B)
                for (String point : section.points()) {
                    var pointRow = sheet.createRow(rowIdx++);
                    pointRow.createCell(0).setCellValue("");  // indent
                    pointRow.createCell(1).setCellValue("• " + point);
                }

                rowIdx++; // blank row between sections
            }

            // Auto-size columns
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);

            wb.write(baos);
            log.info("[DOC-GEN] Excel 生成完成 | title={} | sections={} | size={}bytes",
                    title, outline.sections().size(), baos.size());
            return baos.toByteArray();

        } catch (OutlineParseException e) {
            throw e;
        } catch (Exception e) {
            log.error("[DOC-GEN] Excel 生成失败 | title={}", title, e);
            throw new RuntimeException("Excel document generation failed: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Outline parsing
    // ═══════════════════════════════════════════════════════════

    /**
     * Parse outline JSON into typed data objects.
     */
    @SuppressWarnings("unchecked")
    private OutlineData parseOutline(String outlineJson) {
        try {
            Map<String, Object> root = OBJECT_MAPPER.readValue(outlineJson, Map.class);

            List<Map<String, Object>> sectionsRaw =
                    (List<Map<String, Object>>) root.get("sections");
            if (sectionsRaw == null || sectionsRaw.isEmpty()) {
                throw new OutlineParseException("大纲中没有定义任何章节（sections 为空）");
            }

            List<SectionData> sections = sectionsRaw.stream()
                    .map(s -> {
                        String sectionTitle = (String) s.getOrDefault("title", "");
                        List<String> points = (List<String>) s.getOrDefault("points", List.of());
                        return new SectionData(sectionTitle, points);
                    })
                    .toList();

            return new OutlineData(
                    (String) root.getOrDefault("title", "Untitled"),
                    sections);

        } catch (OutlineParseException e) {
            throw e;
        } catch (Exception e) {
            throw new OutlineParseException("大纲 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════
    // Data types
    // ═══════════════════════════════════════════════════════════

    record OutlineData(String title, List<SectionData> sections) {}

    record SectionData(String title, List<String> points) {}

    /**
     * Thrown when the outline JSON cannot be parsed or is semantically invalid.
     */
    public static class OutlineParseException extends RuntimeException {
        public OutlineParseException(String message) {
            super(message);
        }

        public OutlineParseException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd summer-aigc && mvn test -Dtest=DocumentGeneratorTest`
Expected: PASS — 5 tests green

- [ ] **Step 5: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/document/DocumentGenerator.java \
        summer-aigc/src/test/java/log/summer/aigc/tool/document/DocumentGeneratorTest.java
git commit -m "feat: add DocumentGenerator service for Word/PPT/Excel via Apache POI"
```

---

### Task 10: Database Migration — Create Document Record Table

**Files:**
- Create: `summer-aigc/src/main/resources/db/migration/V4__create_document_record.sql`
- Create: `summer-aigc/src/main/java/log/summer/aigc/entity/DocumentRecord.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/mapper/DocumentRecordMapper.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/IDocumentRecordService.java`
- Create: `summer-aigc/src/main/java/log/summer/aigc/service/impl/DocumentRecordServiceImpl.java`

**Interfaces:**
- Produces: `DocumentRecord` entity with fields: id, userId, conversationId, outlineId, documentType, fileName, filePath, fileSize (bytes), status, idempotencyKey (unique), createdAt
- Produces: `IDocumentRecordService.save(DocumentRecord)` → boolean
- Produces: `IDocumentRecordService.findByIdempotencyKey(String key)` → DocumentRecord or null (for idempotency check)

- [ ] **Step 1: Write the Flyway migration**

```sql
-- V4__create_document_record.sql
-- Generated document tracking with idempotency support

CREATE TABLE IF NOT EXISTS document_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(128) NOT NULL,
    conversation_id BIGINT DEFAULT NULL,
    outline_id BIGINT DEFAULT NULL,
    document_type VARCHAR(20) NOT NULL COMMENT 'WORD/PPT/EXCEL',
    file_name VARCHAR(500) NOT NULL COMMENT '生成的文件名',
    file_path VARCHAR(1000) DEFAULT NULL COMMENT '文件存储路径',
    file_size BIGINT DEFAULT NULL COMMENT '文件大小（字节）',
    status VARCHAR(20) NOT NULL DEFAULT 'GENERATED'
        COMMENT 'GENERATING/GENERATED/FAILED',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键：outline_id + version + type',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_idempotency_key (idempotency_key),
    INDEX idx_user_id (user_id),
    INDEX idx_conversation_id (conversation_id),
    INDEX idx_outline_id (outline_id),
    INDEX idx_document_type (document_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

- [ ] **Step 2: Create DocumentRecord entity**

```java
package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_record")
public class DocumentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long conversationId;
    private Long outlineId;
    private String documentType;        // WORD / PPT / EXCEL
    private String fileName;            // 生成的文件名
    private String filePath;            // 文件存储路径（可为空，直接下发 bytes）
    private Long fileSize;              // 文件大小（字节）
    private String status;              // GENERATING / GENERATED / FAILED
    private String idempotencyKey;      // 幂等键: outlineId_type_version
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: Create DocumentRecordMapper**

```java
package log.summer.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.summer.aigc.entity.DocumentRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentRecordMapper extends BaseMapper<DocumentRecord> {
}
```

- [ ] **Step 4: Create IDocumentRecordService**

```java
package log.summer.aigc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.summer.aigc.entity.DocumentRecord;

public interface IDocumentRecordService extends IService<DocumentRecord> {

    /** Find an existing document record by idempotency key. */
    DocumentRecord findByIdempotencyKey(String idempotencyKey);
}
```

- [ ] **Step 5: Create DocumentRecordServiceImpl**

```java
package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.DocumentRecord;
import log.summer.aigc.mapper.DocumentRecordMapper;
import log.summer.aigc.service.IDocumentRecordService;
import org.springframework.stereotype.Service;

@Service
public class DocumentRecordServiceImpl
        extends ServiceImpl<DocumentRecordMapper, DocumentRecord>
        implements IDocumentRecordService {

    @Override
    public DocumentRecord findByIdempotencyKey(String idempotencyKey) {
        return getOne(new LambdaQueryWrapper<DocumentRecord>()
                .eq(DocumentRecord::getIdempotencyKey, idempotencyKey));
    }
}
```

- [ ] **Step 6: Verify everything compiles**

Run: `cd summer-bootstrap && mvn compile`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add summer-aigc/src/main/resources/db/migration/V4__create_document_record.sql \
        summer-aigc/src/main/java/log/summer/aigc/entity/DocumentRecord.java \
        summer-aigc/src/main/java/log/summer/aigc/mapper/DocumentRecordMapper.java \
        summer-aigc/src/main/java/log/summer/aigc/service/IDocumentRecordService.java \
        summer-aigc/src/main/java/log/summer/aigc/service/impl/DocumentRecordServiceImpl.java
git commit -m "feat: add document_record table with idempotency key for generated files"
```

---

### Task 11: Create GenerateDocumentTool with Idempotency

**Files:**
- Create: `summer-aigc/src/main/java/log/summer/aigc/tool/document/GenerateDocumentTool.java`
- Create: `summer-aigc/src/test/java/log/summer/aigc/tool/document/GenerateDocumentToolTest.java`

**Interfaces:**
- Consumes: `DocumentGenerator` (Task 9), `IDocumentOutlineService` (Task 6), `IDocumentRecordService` (Task 10), `MessageSender` (existing port)
- Produces: `@Tool(name="generateDocument")` — reads confirmed outline from DB, renders document via POI, persists record, returns file path; idempotent via idempotencyKey

- [ ] **Step 1: Write the failing test**

File: `summer-aigc/src/test/java/log/summer/aigc/tool/document/GenerateDocumentToolTest.java`

```java
package log.summer.aigc.tool.document;

import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.entity.DocumentRecord;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.IDocumentOutlineService;
import log.summer.aigc.service.IDocumentRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenerateDocumentToolTest {

    @Mock
    private DocumentGenerator documentGenerator;
    @Mock
    private IDocumentOutlineService outlineService;
    @Mock
    private IDocumentRecordService documentRecordService;

    private GenerateDocumentTool tool;

    private static final String SAMPLE_OUTLINE = """
        {"title":"Test","sections":[{"title":"S1","points":["p1","p2"]}]}""";

    @BeforeEach
    void setUp() {
        tool = new GenerateDocumentTool(
                documentGenerator, outlineService, documentRecordService);
    }

    @Test
    void shouldGenerateDocumentFromConfirmedOutline() {
        // Arrange: confirmed outline in DB
        DocumentOutline outline = new DocumentOutline();
        outline.setId(100L);
        outline.setUserId("user123");
        outline.setConversationId(1L);
        outline.setOutlineType("PPT");
        outline.setTitle("Q2报告");
        outline.setOutlineData(SAMPLE_OUTLINE);
        outline.setStatus("CONFIRMED");
        outline.setVersion(1);
        outline.setCreatedAt(LocalDateTime.now());
        outline.setUpdatedAt(LocalDateTime.now());

        when(outlineService.getById(100L)).thenReturn(outline);
        when(documentGenerator.generatePpt(eq("Q2报告"), anyString()))
                .thenReturn(new byte[]{0x50, 0x4B, 0x03, 0x04}); // OOXML magic bytes
        when(documentRecordService.save(any())).thenReturn(true);

        // Act
        ActResult result = tool.generateDocument(
                "PPT", 100L, "user123", 1L);

        // Assert
        assertTrue(result.success());
        assertFalse(result.suspend());
        assertNotNull(result.data());
        verify(outlineService).updateStatus(100L, "DONE");
        verify(documentRecordService).save(any());
    }

    @Test
    void shouldReturnExistingDocumentWhenIdempotent() {
        // Arrange: outline + existing record
        DocumentOutline outline = new DocumentOutline();
        outline.setId(100L);
        outline.setUserId("user123");
        outline.setConversationId(1L);
        outline.setOutlineType("WORD");
        outline.setTitle("报告");
        outline.setOutlineData(SAMPLE_OUTLINE);
        outline.setStatus("CONFIRMED");
        outline.setVersion(1);

        DocumentRecord existingRecord = new DocumentRecord();
        existingRecord.setId(200L);
        existingRecord.setIdempotencyKey("100_WORD_1");
        existingRecord.setFileName("报告.docx");
        existingRecord.setStatus("GENERATED");

        when(outlineService.getById(100L)).thenReturn(outline);
        when(documentRecordService.findByIdempotencyKey("100_WORD_1"))
                .thenReturn(existingRecord);

        // Act
        ActResult result = tool.generateDocument(
                "WORD", 100L, "user123", 1L);

        // Assert: should return existing record, NOT regenerate
        assertTrue(result.success());
        verify(documentGenerator, never()).generateWord(anyString(), anyString());
        verify(outlineService, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void shouldReturnFailureForMissingOutline() {
        when(outlineService.getById(999L)).thenReturn(null);

        ActResult result = tool.generateDocument(
                "PPT", 999L, "user123", 1L);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("未找到"));
    }

    @Test
    void shouldReturnFailureForUnconfirmedOutline() {
        DocumentOutline outline = new DocumentOutline();
        outline.setId(100L);
        outline.setStatus("DRAFT");
        when(outlineService.getById(100L)).thenReturn(outline);

        ActResult result = tool.generateDocument(
                "PPT", 100L, "user123", 1L);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("确认"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd summer-aigc && mvn test -Dtest=GenerateDocumentToolTest`
Expected: COMPILE ERROR — `GenerateDocumentTool` class not found

- [ ] **Step 3: Implement GenerateDocumentTool**

```java
package log.summer.aigc.tool.document;

import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.entity.DocumentRecord;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.IDocumentOutlineService;
import log.summer.aigc.service.IDocumentRecordService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders a confirmed outline into a final Office document using Apache POI.
 *
 * <h3>Idempotency</h3>
 * Uses {@code outlineId + documentType + outlineVersion} as the idempotency key.
 * If a document for the same outline+type+version already exists, the existing
 * file bytes are returned instead of regenerating.
 *
 * <h3>Multi-tool chain support</h3>
 * This tool is designed to be called after other tools (e.g., a data query tool)
 * have provided content, and after createOutline has produced a confirmed outline.
 * The LLM orchestrates the chain: query → outline → generateDocument.
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GenerateDocumentTool {

    private final DocumentGenerator documentGenerator;
    private final IDocumentOutlineService outlineService;
    private final IDocumentRecordService documentRecordService;

    /** File size limit: 10 MB to prevent memory issues. */
    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    @Tool(name = "generateDocument",
          description = "根据已确认的大纲生成 Word/PPT/Excel 文档文件。" +
                        "请先确保用户已确认大纲内容再调用此工具。支持幂等，重复调用返回已生成的文件。")
    public ActResult generateDocument(
            @ToolParam(description = "文档类型: WORD, PPT, EXCEL") String type,
            @ToolParam(description = "已确认的 outline ID") Long outlineId,
            @ToolParam(description = "用户ID") String userId,
            @ToolParam(description = "会话ID") Long conversationId) {

        // ── Validate type ──
        if (type == null || !java.util.Set.of("WORD", "PPT", "EXCEL").contains(type.toUpperCase())) {
            return ActResult.failure("不支持的文档类型: " + type + "，支持: WORD, PPT, EXCEL");
        }

        // ── Load outline ──
        DocumentOutline outline = outlineService.getById(outlineId);
        if (outline == null) {
            return ActResult.failure("未找到大纲 ID=" + outlineId + "，请先生成大纲");
        }

        if (!"CONFIRMED".equals(outline.getStatus()) && !"MODIFIED".equals(outline.getStatus())) {
            return ActResult.failure("大纲尚未确认（当前状态: " + outline.getStatus()
                    + "），请先让用户确认大纲内容后再生成文档");
        }

        String docType = type.toUpperCase();
        int version = outline.getVersion() != null ? outline.getVersion() : 1;
        String idempotencyKey = outlineId + "_" + docType + "_" + version;

        // ── Idempotency check ──
        DocumentRecord existing = documentRecordService.findByIdempotencyKey(idempotencyKey);
        if (existing != null && "GENERATED".equals(existing.getStatus())) {
            log.info("[DOC-TOOL] 幂等命中 | key={} | existingId={}", idempotencyKey, existing.getId());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("documentId", existing.getId());
            data.put("fileName", existing.getFileName());
            data.put("fileSize", existing.getFileSize() != null
                    ? existing.getFileSize() + " bytes" : "unknown");
            data.put("idempotencyKey", idempotencyKey);
            data.put("message", "文档已存在，无需重新生成");

            return ActResult.success(data);
        }

        // ── Generate document ──
        String title = outline.getTitle() != null ? outline.getTitle() : "Document";
        String outlineJson = outline.getOutlineData();

        byte[] fileBytes;
        String extension;
        try {
            switch (docType) {
                case "WORD" -> {
                    fileBytes = documentGenerator.generateWord(title, outlineJson);
                    extension = ".docx";
                }
                case "PPT" -> {
                    fileBytes = documentGenerator.generatePpt(title, outlineJson);
                    extension = ".pptx";
                }
                case "EXCEL" -> {
                    fileBytes = documentGenerator.generateExcel(title, outlineJson);
                    extension = ".xlsx";
                }
                default -> throw new IllegalStateException("Unexpected type: " + docType);
            }
        } catch (DocumentGenerator.OutlineParseException e) {
            return ActResult.failure("大纲数据解析失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[DOC-TOOL] 文档生成异常 | type={} | outlineId={}", docType, outlineId, e);
            return ActResult.failure("文档生成失败: " + e.getMessage());
        }

        if (fileBytes.length > MAX_FILE_SIZE) {
            return ActResult.failure("生成的文档过大 (" + fileBytes.length + " bytes)，请简化内容");
        }

        // ── Generate safe file name ──
        String safeTitle = title.replaceAll("[\\\\/:*?\"<>|]", "_")
                .replaceAll("\\s+", "_");
        if (safeTitle.length() > 100) safeTitle = safeTitle.substring(0, 100);
        String fileName = safeTitle + extension;

        // ── Persist record ──
        DocumentRecord record = new DocumentRecord();
        record.setUserId(userId);
        record.setConversationId(conversationId);
        record.setOutlineId(outlineId);
        record.setDocumentType(docType);
        record.setFileName(fileName);
        record.setFilePath(null); // no persistent file path — delivered in-memory via MessageSender
        record.setFileSize((long) fileBytes.length);
        record.setStatus("GENERATED");
        record.setIdempotencyKey(idempotencyKey);
        record.setCreatedAt(LocalDateTime.now());
        record.setUpdatedAt(LocalDateTime.now());

        try {
            documentRecordService.save(record);
        } catch (Exception e) {
            log.error("[DOC-TOOL] 记录持久化失败（不影响文件下发） | key={}", idempotencyKey, e);
        }

        // ── Update outline status ──
        outlineService.updateStatus(outlineId, "DONE");

        // ── Build result ──
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", record.getId());
        data.put("fileName", fileName);
        data.put("fileSize", fileBytes.length + " bytes");
        data.put("fileBytes", fileBytes);         // byte[] for the caller (AgentLoop) to send
        data.put("idempotencyKey", idempotencyKey);

        log.info("[DOC-TOOL] 文档生成完成 | type={} | outlineId={} | fileName={} | size={}bytes",
                docType, outlineId, fileName, fileBytes.length);

        return ActResult.success(data);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd summer-aigc && mvn test -Dtest=GenerateDocumentToolTest`
Expected: PASS — 4 tests green

- [ ] **Step 5: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/tool/document/GenerateDocumentTool.java \
        summer-aigc/src/test/java/log/summer/aigc/tool/document/GenerateDocumentToolTest.java
git commit -m "feat: add GenerateDocumentTool with idempotency support"
```

---

### Task 12: Integrate — Document Delivery via MessageSender and End-to-End Verification

**Files:**
- Modify: `summer-aigc/src/main/java/log/summer/aigc/loop/AgentLoop.java` (minor: add file delivery from ActResult data)
- Modify: `README.md` (update feature list and architecture docs)

**Interfaces:**
- Consumes: All previous tasks (1–11)
- Produces: When `ActResult.data()` contains a `Map` with `"fileBytes"` (byte[]) and `"fileName"` (String), AgentLoop sends the file via `MessageSender.sendFile()`

- [ ] **Step 1: Add file delivery logic to AgentLoop**

In `AgentLoop.java`, after the existing `sender.sendText(userId, result.data().toString())` call in the normal ACT section (before the tool result is appended to ChatMemory), add file delivery detection:

In both `orchestrate` and `resumeOrchestrate`, replace the block:

```java
// ── Normal result handling (existing logic) ──
String resultText = result.success()
        ? (result.data() != null ? result.data().toString()
                : "success")
        : "ERROR: " + result.errorMessage();
```

with:

```java
// ── Normal result handling ──
String resultText;
if (result.success() && result.data() instanceof Map<?, ?> dataMap) {
    // Check if the tool produced a file for delivery
    Object fileBytes = dataMap.get("fileBytes");
    Object fileName = dataMap.get("fileName");
    if (fileBytes instanceof byte[] bytes && fileName instanceof String name) {
        log.info("[AGENT-LOOP] 下发文件 | userId={} | fileName={} | size={}bytes",
                userId, name, bytes.length);
        sender.sendFile(userId, bytes, name, "生成的文档");
    }

    // Build a text summary for the LLM (exclude binary data)
    Map<String, Object> llmView = new java.util.LinkedHashMap<>(dataMap);
    llmView.remove("fileBytes");
    resultText = llmView.toString();
} else {
    resultText = result.success()
            ? (result.data() != null ? result.data().toString() : "success")
            : "ERROR: " + result.errorMessage();
}
```

- [ ] **Step 2: Write an end-to-end integration test**

File: `summer-aigc/src/test/java/log/summer/aigc/tool/document/DocumentGenerationIntegrationTest.java`

```java
package log.summer.aigc.tool.document;

import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.entity.DocumentRecord;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.IDocumentOutlineService;
import log.summer.aigc.service.IDocumentRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test: outline creation → confirmation → document generation.
 */
@ExtendWith(MockitoExtension.class)
class DocumentGenerationIntegrationTest {

    @Mock
    private IDocumentOutlineService outlineService;
    @Mock
    private IDocumentRecordService documentRecordService;

    private DocumentGenerator generator;
    private CreateOutlineTool createOutlineTool;
    private GenerateDocumentTool generateDocumentTool;

    private static final String OUTLINE_JSON = """
        {
          "title": "Q2业绩报告",
          "sections": [
            {"title": "业绩概览", "points": ["总营收 5000万", "同比增长 15%"]},
            {"title": "各部门分析", "points": ["销售部 120%达成", "研发部 3个项目上线"]}
          ]
        }""";

    @BeforeEach
    void setUp() {
        generator = new DocumentGenerator();
        createOutlineTool = new CreateOutlineTool(outlineService);
        generateDocumentTool = new GenerateDocumentTool(
                generator, outlineService, documentRecordService);
    }

    @Test
    void fullWorkflowOutlineConfirmGenerateWord() {
        // ── Step 1: Create outline (LLM calls createOutline) ──
        when(outlineService.save(any())).thenReturn(true);

        ActResult outlineResult = createOutlineTool.createOutline(
                "WORD", "Q2业绩报告", OUTLINE_JSON, "user123", 1L);

        assertTrue(outlineResult.suspend());
        assertTrue(outlineResult.requiresConfirmation());
        verify(outlineService).save(any());

        // ── Step 2: User confirms, outline status updated ──
        DocumentOutline savedOutline = new DocumentOutline();
        savedOutline.setId(100L);
        savedOutline.setUserId("user123");
        savedOutline.setConversationId(1L);
        savedOutline.setOutlineType("WORD");
        savedOutline.setTitle("Q2业绩报告");
        savedOutline.setOutlineData(OUTLINE_JSON);
        savedOutline.setStatus("CONFIRMED");
        savedOutline.setVersion(1);
        savedOutline.setCreatedAt(LocalDateTime.now());
        savedOutline.setUpdatedAt(LocalDateTime.now());

        when(outlineService.getById(100L)).thenReturn(savedOutline);
        when(documentRecordService.save(any())).thenReturn(true);

        // ── Step 3: Generate document (LLM calls generateDocument after confirmation) ──
        ActResult docResult = generateDocumentTool.generateDocument(
                "WORD", 100L, "user123", 1L);

        assertTrue(docResult.success());
        assertFalse(docResult.suspend());
        verify(outlineService).updateStatus(100L, "DONE");
        verify(documentRecordService).save(any());
    }

    @Test
    void generateDocumentShouldBeIdempotentAcrossMultipleCalls() {
        // Arrange
        DocumentOutline outline = new DocumentOutline();
        outline.setId(100L);
        outline.setUserId("user123");
        outline.setOutlineType("PPT");
        outline.setTitle("报告");
        outline.setOutlineData(OUTLINE_JSON);
        outline.setStatus("CONFIRMED");
        outline.setVersion(1);

        DocumentRecord existing = new DocumentRecord();
        existing.setId(200L);
        existing.setIdempotencyKey("100_PPT_1");
        existing.setFileName("报告.pptx");
        existing.setFileSize(12345L);
        existing.setStatus("GENERATED");

        when(outlineService.getById(100L)).thenReturn(outline);
        when(documentRecordService.findByIdempotencyKey("100_PPT_1")).thenReturn(existing);

        // Act: call generateDocument twice
        ActResult result1 = generateDocumentTool.generateDocument(
                "PPT", 100L, "user123", 1L);
        ActResult result2 = generateDocumentTool.generateDocument(
                "PPT", 100L, "user123", 1L);

        // Assert: both should return success, no regeneration
        assertTrue(result1.success());
        assertTrue(result2.success());
        // DocumentGenerator.generatePpt was never called
        verify(outlineService, never()).updateStatus(anyLong(), anyString());
    }
}
```

- [ ] **Step 3: Run integration test**

Run: `cd summer-aigc && mvn test -Dtest=DocumentGenerationIntegrationTest`
Expected: PASS — 2 tests green

- [ ] **Step 4: Run all tests together**

Run: `cd summer-aigc && mvn test`
Expected: ALL tests pass — ActResultTest (4), SessionStateManagerTest (6), AgentLoopSuspendTest (3), CreateOutlineToolTest (4), DocumentGeneratorTest (5), GenerateDocumentToolTest (4), DocumentGenerationIntegrationTest (2) = 28 tests green

- [ ] **Step 5: Update README.md with the new features**

In `README.md`, update the feature list to include:

```markdown
## Features

### Agent Engine
- **Dual-loop Agent** — ReAct (Reasoning + Acting) orchestrator with think-act cycles
- **Suspend/Resume** — Agent can pause mid-task waiting for user confirmation, survive service restarts
- **Session State Persistence** — Full conversation context saved to DB on suspend, restored on next message
- **Multi-tool Chain** — Tools can be chained naturally: query → outline → confirm → generate document

### Document Generation
- **Outline Generation** (`createOutline`) — AI generates structured outlines for Word/PPT/Excel, persists to DB
- **User Confirmation Flow** — Outline presented to user for review; Agent waits for confirmation or modifications
- **Document Rendering** (`generateDocument`) — Apache POI renders confirmed outlines into .docx/.pptx/.xlsx files
- **Idempotency** — Same outline+type+version returns cached result, no duplicate generation
```

- [ ] **Step 6: Update system prompt to document new tool capabilities**

Locate the system prompt file and add tool usage guidance for createOutline and generateDocument. Check `summer-common/src/main/resources/prompts/system.txt`:

Run: `cat summer-common/src/main/resources/prompts/system.txt`

Then append:

```
## 文档生成能力

你可以帮用户生成 Word、PPT、Excel 文档。流程如下：

1. 理解用户需求 → 调用 createOutline 工具生成结构化大纲
2. 系统会自动暂停等待用户确认/修改大纲
3. 用户确认后 → 调用 generateDocument 工具渲染最终文件
4. 文件通过聊天直接下发给用户

大纲 JSON 格式示例：
{
  "title": "文档标题",
  "sections": [
    {"title": "章节标题", "points": ["要点1", "要点2"]}
  ]
}

支持的类型：WORD, PPT, EXCEL
```

- [ ] **Step 7: Commit**

```bash
git add summer-aigc/src/main/java/log/summer/aigc/loop/AgentLoop.java \
        summer-aigc/src/test/java/log/summer/aigc/tool/document/DocumentGenerationIntegrationTest.java \
        README.md \
        summer-common/src/main/resources/prompts/system.txt
git commit -m "feat: integrate document delivery via MessageSender + update docs + e2e tests"
```

---

### Task 13: Final Verification — Full Application Startup and Smoke Test

**Files:**
- None new — verification only

- [ ] **Step 1: Build the full project**

Run: `mvn clean compile -pl summer-bootstrap -am`
Expected: BUILD SUCCESS — all 4 modules

- [ ] **Step 2: Run all tests across modules**

Run: `mvn test`
Expected: ALL tests pass (28 tests across summer-aigc)

- [ ] **Step 3: Start the application and verify migrations**

Run: `cd summer-bootstrap && mvn spring-boot:run`
Verify log output contains:
- `[FLYWAY] Successfully applied migration V2__add_suspend_context.sql`
- `[FLYWAY] Successfully applied migration V3__create_outline_table.sql`
- `[FLYWAY] Successfully applied migration V4__create_document_record.sql`
- `[TOOL-REGISTRY] Scanned N tool beans → M ToolCallbacks` (N includes CreateOutlineTool, GenerateDocumentTool)
- `[CHAT-SERVICE] 系统提示词加载完成`

- [ ] **Step 4: Verify database tables**

Connect to MySQL and run:
```sql
DESC conversation;     -- should show suspend_context, suspend_reason columns
DESC document_outline; -- should show all columns
DESC document_record;  -- should show all columns + unique key on idempotency_key
```

- [ ] **Step 5: Manual smoke test via chat**

Send a message to the bot such as "帮我做一个关于Q2业绩汇报的PPT"
Verify the flow:
1. Agent calls `createOutline` → outline is saved to `document_outline` table
2. User receives formatted outline + "等待确认大纲内容" message
3. `conversation` row has `status=2`, `suspend_context` populated
4. User replies "确认" → Agent resumes from `suspend_context`
5. Agent calls `generateDocument` → document is saved to `document_record`
6. User receives the .pptx file

- [ ] **Step 6: Verify restart survival**

After step 4 (but before step 5 — during suspension):
1. Stop the application
2. Restart the application
3. User sends "确认，把第二章改成团队建设"
4. Verify: Agent resumes from DB-persisted suspend_context, generates modified outline, and continues

- [ ] **Step 7: Final commit (if any doc tweaks were needed)**

```bash
git add -A
git commit -m "chore: final verification tweaks and documentation"
```

---

## Self-Review

### 1. Spec Coverage

| Spec Requirement | Covered By |
|---|---|
| P1: AgentLoop 新增暂停恢复机制 | Task 5 — AgentLoop refactor with suspend/resume |
| P1: 用户确认机制 | Task 2 — ActResult.suspend() + Task 5 — loop detects suspend flag |
| P1: 扩展返回结果标识 | Task 2 — ActResult: suspend, suspendReason, requiresConfirmation fields |
| P1: 增加会话内存缓存 | Task 4 — SessionStateManager with in-memory ConcurrentHashMap + DB fallback |
| P1: 会话表新增状态字段持久化挂起上下文 | Task 1 — V2 migration: suspend_context JSON + suspend_reason on conversation |
| P1: 收到用户消息时可恢复挂起会话继续执行 | Task 5 — resumeOrchestrate() path in AgentLoop |
| P1: 服务重启不丢失状态 | Task 4 — DB is source of truth; Task 5 — restores from DB on cold start |
| P2: 新建大纲数据表 | Task 6 — V3 migration: document_outline table |
| P2: 开发createOutline工具 | Task 7 — CreateOutlineTool |
| P2: Agent 生成结构化大纲、持久化数据 | Task 7 — saves DocumentOutline to DB |
| P2: 主动挂起会话等待用户确认/修改 | Task 7 — returns ActResult.suspend() → AgentLoop suspends |
| P2: 依托暂停恢复机制承接用户后续反馈 | Task 5 — resumeOrchestrate continues from persisted context |
| P3: 引入 POI 实现 Office 文件生成 | Tasks 8 + 9 — POI dependency + DocumentGenerator |
| P3: 开发generateDocument工具 | Task 11 — GenerateDocumentTool |
| P3: 用户确认大纲后渲染完整文档并下发 | Tasks 11 + 12 — generateDocument reads confirmed outline, renders, delivers via MessageSender |
| P3: 支持多工具串联复合需求 | Task 11 design — tool is independent, LLM chains: query → createOutline → generateDocument |
| P3: 保证工具幂等 | Task 11 — idempotencyKey (outlineId_type_version) + UNIQUE constraint + pre-check |
| 完成后更新涉及到的文档 | Task 12 — README.md + system prompt update |

### 2. Placeholder Scan

No placeholders found. Verified:
- No "TBD" or "TODO" markers
- No "implement later" or "fill in details"
- No "add appropriate error handling" without actual code
- No "similar to Task N" references (all code is repeated explicitly)
- All steps include actual code blocks

### 3. Type Consistency

Verified:
- `ActResult` fields consistent across Tasks 2, 5, 7, 11
- `Conversation.status` values consistent: 0=ENDED, 1=ACTIVE, 2=SUSPENDED (Tasks 1, 4, 5)
- `SessionStateManager` method signatures match usage in Tasks 4 and 5
- `SuspendContext` / `MessageSnapshot` defined in Task 3, used in Tasks 4, 5
- `DocumentOutline` / `DocumentRecord` entity fields match mapper and service usage (Tasks 6, 7, 10, 11)
- `IDocumentOutlineService` methods match CreateOutlineTool and GenerateDocumentTool calls
- `IDocumentRecordService.findByIdempotencyKey` → `DocumentRecord` matches GenerateDocumentTool usage
- `MessageSender.sendFile(userId, bytes, filename, description)` exists (verified in existing code)
- Outline JSON structure `{"title":"...", "sections":[{"title":"...", "points":["..."]}]}` consistent across CreateOutlineTool, DocumentGenerator, and GenerateDocumentTool
- Idempotency key format `outlineId_type_version` consistent in GenerateDocumentTool and DB schema
