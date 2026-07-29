package log.summer.aigc.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link MessageSnapshot}.
 */
class MessageSnapshotTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── from() – single message ──────────────────────────────────

    @Test
    void fromUserMessage() {
        UserMessage original = new UserMessage("Hello, world!");

        MessageSnapshot snapshot = MessageSnapshot.from(original);

        assertEquals(MessageType.USER, snapshot.messageType());
        assertEquals("Hello, world!", snapshot.text());
        assertNull(snapshot.toolCalls());
        assertNull(snapshot.toolResponses());
    }

    @Test
    void fromUserMessageProducesNoMetadata() {
        // UserMessage's multi-arg constructor with metadata is package-private,
        // so we only verify that the public single-arg constructor works correctly.
        UserMessage original = new UserMessage("Hello");

        MessageSnapshot snapshot = MessageSnapshot.from(original);

        assertEquals(MessageType.USER, snapshot.messageType());
        assertEquals("Hello", snapshot.text());
    }

    @Test
    void fromAssistantMessage() {
        var toolCall = new AssistantMessage.ToolCall("call-1", "function", "myTool", "{\"arg\":1}");
        AssistantMessage original = new AssistantMessage(
                "I'll call a tool",
                Map.of("key", "val"),
                List.of(toolCall));

        MessageSnapshot snapshot = MessageSnapshot.from(original);

        assertEquals(MessageType.ASSISTANT, snapshot.messageType());
        assertEquals("I'll call a tool", snapshot.text());
        assertNotNull(snapshot.toolCalls());
        assertEquals(1, snapshot.toolCalls().size());
        assertEquals("call-1", snapshot.toolCalls().get(0).id());
        assertEquals("function", snapshot.toolCalls().get(0).type());
        assertEquals("myTool", snapshot.toolCalls().get(0).name());
        assertEquals("{\"arg\":1}", snapshot.toolCalls().get(0).arguments());
        assertEquals("val", snapshot.metadata().get("key"));
        assertNull(snapshot.toolResponses());
    }

    @Test
    void fromAssistantMessageWithoutToolCalls() {
        AssistantMessage original = new AssistantMessage("Just text");

        MessageSnapshot snapshot = MessageSnapshot.from(original);

        assertEquals(MessageType.ASSISTANT, snapshot.messageType());
        assertEquals("Just text", snapshot.text());
        assertNull(snapshot.toolCalls());
    }

    @Test
    void fromSystemMessage() {
        SystemMessage original = new SystemMessage("You are a helpful assistant.");

        MessageSnapshot snapshot = MessageSnapshot.from(original);

        assertEquals(MessageType.SYSTEM, snapshot.messageType());
        assertEquals("You are a helpful assistant.", snapshot.text());
        assertNull(snapshot.toolCalls());
        assertNull(snapshot.toolResponses());
    }

    @Test
    void fromToolResponseMessage() {
        var tr = new ToolResponseMessage.ToolResponse("call-1", "myTool", "{\"result\":\"ok\"}");
        ToolResponseMessage original = new ToolResponseMessage(List.of(tr));

        MessageSnapshot snapshot = MessageSnapshot.from(original);

        assertEquals(MessageType.TOOL, snapshot.messageType());
        assertNotNull(snapshot.toolResponses());
        assertEquals(1, snapshot.toolResponses().size());
        assertEquals("call-1", snapshot.toolResponses().get(0).id());
        assertEquals("myTool", snapshot.toolResponses().get(0).name());
        assertEquals("{\"result\":\"ok\"}", snapshot.toolResponses().get(0).responseData());
        assertNull(snapshot.toolCalls());
    }

    @Test
    void fromNullMessageThrows() {
        assertThrows(NullPointerException.class, () -> MessageSnapshot.from(null));
    }

    // ── fromMessages() – list conversion ──────────────────────────

    @Test
    void fromMessagesConvertsList() {
        List<Message> messages = List.of(
                new SystemMessage("You are a bot."),
                new UserMessage("Hi"),
                new AssistantMessage("Hello!"));

        List<MessageSnapshot> snapshots = MessageSnapshot.fromMessages(messages);

        assertEquals(3, snapshots.size());
        assertEquals(MessageType.SYSTEM, snapshots.get(0).messageType());
        assertEquals(MessageType.USER, snapshots.get(1).messageType());
        assertEquals(MessageType.ASSISTANT, snapshots.get(2).messageType());
    }

    @Test
    void fromMessagesNullReturnsEmpty() {
        assertTrue(MessageSnapshot.fromMessages(null).isEmpty());
    }

    @Test
    void fromMessagesEmptyReturnsEmpty() {
        assertTrue(MessageSnapshot.fromMessages(List.of()).isEmpty());
    }

    // ── toSpringAiMessage() – reconstruction ──────────────────────

    @Test
    void toSpringAiMessageUser() {
        MessageSnapshot snapshot = new MessageSnapshot(
                MessageType.USER, "Hello", null, null, null);

        Message result = snapshot.toSpringAiMessage();

        assertInstanceOf(UserMessage.class, result);
        assertEquals("Hello", result.getText());
        assertEquals(MessageType.USER, result.getMessageType());
    }

    @Test
    void toSpringAiMessageAssistant() {
        var toolCall = new MessageSnapshot.ToolCallSnapshot("c1", "function", "toolA", "{}");
        MessageSnapshot snapshot = new MessageSnapshot(
                MessageType.ASSISTANT, "Calling tool",
                Map.of("k", "v"), List.of(toolCall), null);

        Message result = snapshot.toSpringAiMessage();

        assertInstanceOf(AssistantMessage.class, result);
        assertEquals("Calling tool", result.getText());
        assertEquals(MessageType.ASSISTANT, result.getMessageType());

        AssistantMessage am = (AssistantMessage) result;
        assertEquals(1, am.getToolCalls().size());
        assertEquals("c1", am.getToolCalls().get(0).id());
        assertEquals("toolA", am.getToolCalls().get(0).name());
        assertEquals("v", am.getMetadata().get("k"));
    }

    @Test
    void toSpringAiMessageAssistantWithoutToolCalls() {
        MessageSnapshot snapshot = new MessageSnapshot(
                MessageType.ASSISTANT, "Just thinking", null, null, null);

        Message result = snapshot.toSpringAiMessage();

        assertInstanceOf(AssistantMessage.class, result);
        assertEquals("Just thinking", result.getText());
        AssistantMessage am = (AssistantMessage) result;
        assertTrue(am.getToolCalls() == null || am.getToolCalls().isEmpty());
    }

    @Test
    void toSpringAiMessageSystem() {
        MessageSnapshot snapshot = new MessageSnapshot(
                MessageType.SYSTEM, "Be helpful.", null, null, null);

        Message result = snapshot.toSpringAiMessage();

        assertInstanceOf(SystemMessage.class, result);
        assertEquals("Be helpful.", result.getText());
    }

    @Test
    void toSpringAiMessageTool() {
        var tr = new MessageSnapshot.ToolResponseSnapshot("c1", "toolA", "{\"ok\":true}");
        MessageSnapshot snapshot = new MessageSnapshot(
                MessageType.TOOL, null, null, null, List.of(tr));

        Message result = snapshot.toSpringAiMessage();

        assertInstanceOf(ToolResponseMessage.class, result);
        ToolResponseMessage trm = (ToolResponseMessage) result;
        assertEquals(1, trm.getResponses().size());
        assertEquals("c1", trm.getResponses().get(0).id());
        assertEquals("{\"ok\":true}", trm.getResponses().get(0).responseData());
    }

    // ── Round-trip: serialize → deserialize via Jackson ────────────

    @Test
    void roundTripUserMessageViaJackson() throws Exception {
        MessageSnapshot snapshot = new MessageSnapshot(
                MessageType.USER, "Hello, world!", null, null, null);
        String json = objectMapper.writeValueAsString(snapshot);
        MessageSnapshot deserialized = objectMapper.readValue(json, MessageSnapshot.class);

        assertEquals(MessageType.USER, deserialized.messageType());
        assertEquals("Hello, world!", deserialized.text());
    }

    @Test
    void roundTripAssistantMessageViaJackson() throws Exception {
        var toolCall = new AssistantMessage.ToolCall("c1", "function", "weather", "{\"city\":\"北京\"}");
        AssistantMessage original = new AssistantMessage(
                "Let me check the weather",
                Map.of("id", "abc"),
                List.of(toolCall));

        MessageSnapshot snapshot = MessageSnapshot.from(original);
        String json = objectMapper.writeValueAsString(snapshot);
        MessageSnapshot deserialized = objectMapper.readValue(json, MessageSnapshot.class);

        assertEquals(MessageType.ASSISTANT, deserialized.messageType());
        assertEquals("Let me check the weather", deserialized.text());
        assertNotNull(deserialized.toolCalls());
        assertEquals(1, deserialized.toolCalls().size());
        assertEquals("c1", deserialized.toolCalls().get(0).id());
        assertEquals("weather", deserialized.toolCalls().get(0).name());
        assertEquals("{\"city\":\"北京\"}", deserialized.toolCalls().get(0).arguments());
        // Reconstruct to Spring AI message
        Message reconstructed = deserialized.toSpringAiMessage();
        assertInstanceOf(AssistantMessage.class, reconstructed);
        AssistantMessage am = (AssistantMessage) reconstructed;
        assertEquals(1, am.getToolCalls().size());
        assertEquals("weather", am.getToolCalls().get(0).name());
    }

    @Test
    void roundTripToolResponseViaJackson() throws Exception {
        var tr = new ToolResponseMessage.ToolResponse("c1", "weather", "{\"temp\":25}");
        ToolResponseMessage original = new ToolResponseMessage(List.of(tr));

        MessageSnapshot snapshot = MessageSnapshot.from(original);
        String json = objectMapper.writeValueAsString(snapshot);
        MessageSnapshot deserialized = objectMapper.readValue(json, MessageSnapshot.class);

        assertEquals(MessageType.TOOL, deserialized.messageType());
        assertNotNull(deserialized.toolResponses());
        assertEquals(1, deserialized.toolResponses().size());
        assertEquals("c1", deserialized.toolResponses().get(0).id());
        assertEquals("{\"temp\":25}", deserialized.toolResponses().get(0).responseData());
    }

    @Test
    void fullRoundTripFromToSpringAi() {
        // Build a realistic mixed message list
        List<Message> original = List.of(
                new SystemMessage("You are helpful."),
                new UserMessage("What's the weather?"),
                new AssistantMessage("Let me check...", Map.of(),
                        List.of(new AssistantMessage.ToolCall("c1", "function", "getWeather", "{\"city\":\"北京\"}"))),
                new ToolResponseMessage(List.of(
                        new ToolResponseMessage.ToolResponse("c1", "getWeather", "{\"temp\":25}"))));

        List<MessageSnapshot> snapshots = MessageSnapshot.fromMessages(original);
        assertEquals(4, snapshots.size());

        // Convert each snapshot back and verify type order
        List<Message> reconstructed = snapshots.stream()
                .map(MessageSnapshot::toSpringAiMessage)
                .toList();

        assertEquals(4, reconstructed.size());
        assertInstanceOf(SystemMessage.class, reconstructed.get(0));
        assertInstanceOf(UserMessage.class, reconstructed.get(1));
        assertInstanceOf(AssistantMessage.class, reconstructed.get(2));
        assertInstanceOf(ToolResponseMessage.class, reconstructed.get(3));

        // Verify content round-trip
        AssistantMessage am = (AssistantMessage) reconstructed.get(2);
        assertEquals(1, am.getToolCalls().size());
        assertEquals("getWeather", am.getToolCalls().get(0).name());

        ToolResponseMessage trm = (ToolResponseMessage) reconstructed.get(3);
        assertEquals(1, trm.getResponses().size());
        assertEquals("getWeather", trm.getResponses().get(0).name());
    }
}
