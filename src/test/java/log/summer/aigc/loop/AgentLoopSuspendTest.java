package log.summer.aigc.loop;

import log.summer.aigc.entity.Conversation;
import log.summer.aigc.port.BotMessage;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.session.SessionStateManager;
import log.summer.aigc.service.ChatPersistenceService;
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
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @Mock
    private ChatPersistenceService chatPersistenceService;

    private ChatMemory chatMemory;
    private ThinkLoop thinkLoop;
    private ActLoop actLoop;
    private AgentLoop agentLoop;

    @BeforeEach
    void setUp() {
        chatMemory = spy(MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build());
        thinkLoop = new ThinkLoop(chatService, toolRegistry);
        actLoop = new ActLoop(toolRegistry, argumentResolver);
        agentLoop = new AgentLoop(
                thinkLoop, actLoop, chatMemory,
                exceptionHandler, sessionStateManager,
                chatPersistenceService);
    }

    @Test
    void shouldResumeSuspendedSessionOnNextMessage() {
        // Arrange: user has a suspended session
        Conversation suspended = new Conversation();
        suspended.setId(1L);
        suspended.setUserId("user123");
        suspended.setStatus(2);
        suspended.setSuspendReason("等待确认大纲");

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
        when(sessionStateManager.getSuspendedByUser("user123")).thenReturn(null);

        // Active conversation exists (architectural fix: use ChatPersistenceService, not getSuspendedByUser)
        Conversation activeConv = new Conversation();
        activeConv.setId(42L);
        when(chatPersistenceService.getActiveConversation("user123")).thenReturn(activeConv);

        // Arrange: LLM returns a tool call to createOutline
        when(chatService.chatWithTools(anyList(), anyList()))
                .thenReturn(mockChatResponseWithToolCall("createOutline", "{\"type\":\"PPT\"}"));

        // Tool execution returns suspend
        when(argumentResolver.resolve(anyMap(), any()))
                .thenReturn(java.util.Map.of("type", "PPT"));
        ActResult suspendResult = ActResult.suspend(
                java.util.Map.of("title", "Q2业绩报告"),
                "等待确认大纲");
        when(toolRegistry.execute(eq("createOutline"), anyMap())).thenReturn(suspendResult);

        // Pre-populate chatMemory with a message to verify it's preserved after suspend
        chatMemory.add("user123", new UserMessage("之前的消息"));

        BotMessage msg = new BotMessage("user123", "帮我做个PPT",
                null, null, null, RouteContext.TEXT);

        // Act
        agentLoop.orchestrate(msg, sender);

        // Assert: session state was persisted with correct convId (42L from active conversation)
        verify(sessionStateManager).suspend(
                eq("user123"), eq(42L), eq("createOutline"),
                eq("等待确认大纲"), any(ChatMemory.class));
        // Assert: suspend reason was sent to user
        verify(sender).sendText(eq("user123"), eq("等待确认大纲"));
        // Assert: ChatMemory was NOT cleared (state preserved for resume)
        // In the finally block, if suspended, clear is skipped
        verify(chatMemory, never()).clear(anyString());
        // Verify chatMemory still has messages (not cleared)
        assertFalse(chatMemory.get("user123").isEmpty(),
                "ChatMemory should NOT be cleared after suspend — state must be preserved for resume");
    }

    @Test
    void shouldNotSuspendForNormalSuccessResult() {
        when(sessionStateManager.getSuspendedByUser("user123")).thenReturn(null);

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
