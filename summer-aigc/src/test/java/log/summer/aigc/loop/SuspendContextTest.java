package log.summer.aigc.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SuspendContext}.
 */
class SuspendContextTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Test
    void ofCreatesContextWithCurrentTime() {
        LocalDateTime before = LocalDateTime.now().withNano(0);

        List<MessageSnapshot> snapshots = List.of(
                new MessageSnapshot(null, "test", null, null, null));

        SuspendContext ctx = SuspendContext.of("myTool", "等待用户确认", "conv-123", snapshots);

        assertEquals("myTool", ctx.toolName());
        assertEquals("等待用户确认", ctx.suspendReason());
        assertEquals("conv-123", ctx.conversationId());
        assertSame(snapshots, ctx.messagesSnapshot());
        assertNotNull(ctx.createdAt());
        // createdAt should be at or after 'before'
        assertTrue(ctx.createdAt().isAfter(before) || ctx.createdAt().equals(before));
    }

    @Test
    void ofWithEmptyMessages() {
        SuspendContext ctx = SuspendContext.of("tool", "reason", "conv-1", List.of());

        assertNotNull(ctx.messagesSnapshot());
        assertTrue(ctx.messagesSnapshot().isEmpty());
    }

    @Test
    void serializesToJsonAndBack() throws Exception {
        List<MessageSnapshot> snapshots = List.of(
                new MessageSnapshot(
                        org.springframework.ai.chat.messages.MessageType.USER,
                        "Hello", null, null, null));

        SuspendContext ctx = SuspendContext.of("testTool", "test reason", "conv-42", snapshots);

        String json = objectMapper.writeValueAsString(ctx);
        assertTrue(json.contains("testTool"));
        assertTrue(json.contains("test reason"));
        assertTrue(json.contains("conv-42"));
        assertTrue(json.contains("USER"));
        assertTrue(json.contains("Hello"));

        SuspendContext deserialized = objectMapper.readValue(json, SuspendContext.class);

        assertEquals("testTool", deserialized.toolName());
        assertEquals("test reason", deserialized.suspendReason());
        assertEquals("conv-42", deserialized.conversationId());
        assertNotNull(deserialized.messagesSnapshot());
        assertEquals(1, deserialized.messagesSnapshot().size());
        assertEquals("Hello", deserialized.messagesSnapshot().get(0).text());
        assertNotNull(deserialized.createdAt());
    }

    @Test
    void preservesFullMessageSnapshotList() throws Exception {
        var snapshots = MessageSnapshot.fromMessages(List.of(
                new org.springframework.ai.chat.messages.UserMessage("Hi"),
                new org.springframework.ai.chat.messages.AssistantMessage("Hello there!")));

        SuspendContext ctx = SuspendContext.of("tool", "reason", "conv-1", snapshots);

        String json = objectMapper.writeValueAsString(ctx);
        SuspendContext deserialized = objectMapper.readValue(json, SuspendContext.class);

        assertEquals(2, deserialized.messagesSnapshot().size());
        assertEquals("Hi", deserialized.messagesSnapshot().get(0).text());
        assertEquals("Hello there!", deserialized.messagesSnapshot().get(1).text());
    }

    @Test
    void nullFieldsAreOmittedInJson() throws Exception {
        SuspendContext ctx = SuspendContext.of("tool", "reason", "conv-1", List.of());

        String json = objectMapper.writeValueAsString(ctx);

        // The JSON should NOT contain null entries for snapshot fields
        assertFalse(json.contains("\"messagesSnapshot\":null"));
    }
}
