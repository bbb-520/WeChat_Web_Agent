package log.summer.aigc.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionStateManagerTest {

    @Mock
    private IConversationService conversationService;

    @Mock
    private ChatMemory chatMemory;

    private ObjectMapper objectMapper;
    private SessionStateManager stateManager;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        stateManager = new SessionStateManager(conversationService, objectMapper);
    }

    @Test
    void suspendShouldPersistStateAndUpdateConversation() {
        List<Message> messages = List.of(
                new UserMessage("帮我做个PPT"),
                new UserMessage("内容关于Q2业绩"));
        when(chatMemory.get("user123")).thenReturn(messages);

        stateManager.suspend("user123", 1L, "createOutline",
                "等待确认大纲", chatMemory);

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
        List<MessageSnapshot> snapshots = MessageSnapshot.fromMessages(List.of(
                new UserMessage("帮我做个PPT"),
                new AssistantMessage("好的，我来生成大纲")
        ));
        SuspendContext ctx = SuspendContext.of(
                "createOutline", "等待确认大纲", "1", snapshots);
        String ctxJson = objectMapper.writeValueAsString(ctx);

        Conversation conv = new Conversation();
        conv.setId(1L);
        conv.setUserId("user123");
        conv.setStatus(2);
        conv.setSuspendContext(ctxJson);

        // Mock ChatMemory.add() to capture messages
        List<Message> captured = new ArrayList<>();
        doAnswer(inv -> {
            captured.add(inv.getArgument(1));
            return null;
        }).when(chatMemory).add(eq("user123"), any(Message.class));
        when(chatMemory.get("user123")).thenReturn(captured);

        // Act
        stateManager.restoreMessages(conv, chatMemory);

        // Assert: memory should contain 2 messages
        List<Message> restored = chatMemory.get("user123");
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
