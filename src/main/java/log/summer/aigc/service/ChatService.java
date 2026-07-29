package log.summer.aigc.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import jakarta.annotation.PostConstruct;
import log.summer.aigc.rag.RAGContextAugmenter;
import log.summer.aigc.rag.RAGRetrievalService;
import log.summer.common.exception.AIServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.content.Media;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI 对话服务 —— 通义千问（qwen-plus）文本对话、多模态图片分析、文档分析。
 * 会话记忆由 MessageChatMemoryAdvisor 按 userId 自动管理。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final RAGContextAugmenter ragAugmenter;

    @Value("${spring.ai.system-prompt-path}")
    private Resource systemPromptResource;
    private String chatSystemPrompt;

    @Value("${bot.intent.image-edit-system-prompt}")
    private String imageEditSystemPrompt;

    @PostConstruct
    public void initSystemPrompt() {
        try {
            this.chatSystemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
            log.info("[CHAT-SERVICE] 系统提示词加载完成 | len={}", chatSystemPrompt.length());
        } catch (Exception e) {
            log.error("[CHAT-SERVICE] 加载系统提示词失败", e);
            this.chatSystemPrompt = "你是一个智能助手。";
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 新方法 —— AgentLoop 使用（返回完整 ChatResponse，支持工具调用）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 带工具调用的对话 —— AgentLoop Think 阶段使用。
     *
     * <p>关键设计：</p>
     * <ol>
     *   <li>设置 {@code internalToolExecutionEnabled = false} —
     *   阻止 DashScopeChatModel 内部自动执行工具，原始 tool calls
     *   返回给 AgentLoop 手动执行。</li>
     *   <li>通过三条通道注入 toolCallbacks：
     *     <ul>
     *       <li>{@code .options(options)} — 将 callbacks 传入 DashScope 请求</li>
     *       <li>{@code .toolCallbacks(tools)} — PromptSpec 级别设置（优先）</li>
     *       <li>{@code ToolCallbackProvider} Bean — 全局回退解析器</li>
     *     </ul>
     *   </li>
     * </ol>
     */
    public ChatResponse chatWithTools(List<Message> messages, List<ToolCallback> tools) {
        try {
            // 构建 DashScope 专用选项：禁用内部自动执行 + 注入工具回调
            var options = DashScopeChatOptions.builder()
                    .withModel("qwen-plus")
                    .withInternalToolExecutionEnabled(false)     // 关键：禁止自动执行
                    .withToolCallbacks(tools)                    // 通过 Builder 注入回调
                    .build();

            log.debug("[CHAT-SERVICE] chatWithTools | messages={} | tools={} | internalExecEnabled={}",
                    messages.size(), tools.size(),
                    options.getInternalToolExecutionEnabled());

            return chatClient.prompt()
                    .system(chatSystemPrompt)
                    .messages(messages)
                    .options(options)          // 通道1：通过 ChatOptions
                    .toolCallbacks(tools)      // 通道2：通过 PromptSpec（优先于 options）
                    .call()
                    .chatResponse();
        } catch (AIServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CHAT-SERVICE] Agent chatWithTools failed", e);
            throw new AIServiceException("chatWithTools", "Agent tool chat failed: " + e.getMessage(), e);
        }
    }

    /**
     * 纯文本对话（无工具） —— AgentLoop 回退使用。
     */
    public ChatResponse chat(List<Message> messages) {
        try {
            return chatClient.prompt()
                    .system(chatSystemPrompt)
                    .messages(messages)
                    .call()
                    .chatResponse();
        } catch (AIServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CHAT-SERVICE] Agent chat failed", e);
            throw new AIServiceException("chat", "Agent chat failed: " + e.getMessage(), e);
        }
    }

    /**
     * RAG 增强对话 —— AgentLoop 使用。
     * 将 Spring AI Document 列表作为上下文注入到系统提示词中。
     */
    public ChatResponse chatWithRAG(List<Message> messages, List<Document> docs) {
        try {
            var builder = chatClient.prompt().messages(messages);
            if (docs != null && !docs.isEmpty()) {
                String ragContext = docs.stream()
                        .map(Document::getText)
                        .collect(Collectors.joining("\n\n"));
                builder.system(s -> s.text(chatSystemPrompt + "\n\n参考信息：\n" + ragContext));
            }
            return builder.call().chatResponse();
        } catch (AIServiceException e) {
            throw e;
        } catch (Exception e) {
            log.error("[CHAT-SERVICE] Agent RAG chat failed", e);
            throw new AIServiceException("chatWithRAG", "Agent RAG chat failed: " + e.getMessage(), e);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 现有方法 —— 面向最终用户的便捷入口（保留不变）
    // ═══════════════════════════════════════════════════════════════

    /** 文本对话 —— 带会话记忆 */
    public String chat(String userId, String userMessage) {
        try {
            return chatClient.prompt().system(chatSystemPrompt).user(userMessage)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, userId)).call().content();
        } catch (AIServiceException e) { throw e; }
        catch (Exception e) {
            log.error("AI 对话失败 | userId={}", userId, e);
            throw new AIServiceException("chat", "AI 对话失败: " + e.getMessage(), e);
        }
    }

    /** RAG 增强对话 */
    public String chatWithRAG(String userId, String query, List<RAGRetrievalService.RetrievedDoc> docs) {
        try {
            String augmented = ragAugmenter.buildAugmentedUserMessage(query, docs);
            String ragPrompt = ragAugmenter.buildRAGSystemPrompt(chatSystemPrompt);
            return chatClient.prompt().system(ragPrompt).user(augmented)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID().toString()))
                    .call().content();
        } catch (AIServiceException e) { throw e; }
        catch (Exception e) {
            log.error("RAG 对话失败 | userId={}", userId, e);
            throw new AIServiceException("chatWithRAG", "RAG 增强对话失败: " + e.getMessage(), e);
        }
    }

    /** 多模态图片分析 —— qwen-vl-plus */
    public String analyzeImage(byte[] imageBytes) {
        try {
            ByteArrayResource resource = new ByteArrayResource(imageBytes) {
                @Override public String getFilename() { return "image.jpg"; }
            };
            Media imageMedia = new Media(MimeType.valueOf("image/jpeg"), resource);
            return chatClient.prompt().system(chatSystemPrompt)
                    .user(u -> u.text("请详细描述这张图片的内容，包括物体、场景、文字等信息。").media(imageMedia))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID().toString()))
                    .options(DashScopeChatOptions.builder().withModel("qwen-vl-plus").withMultiModel(true).build())
                    .call().content();
        } catch (AIServiceException e) { throw e; }
        catch (Exception e) {
            log.error("图片分析失败 | size={}bytes", imageBytes.length, e);
            throw new AIServiceException("analyzeImage", "图片解析失败: " + e.getMessage(), e);
        }
    }

    /** 图片编辑描述 —— qwen-vl-plus */
    public String describeImageEdit(byte[] imageBytes, String editInstruction) {
        try {
            ByteArrayResource resource = new ByteArrayResource(imageBytes) {
                @Override public String getFilename() { return "image.jpg"; }
            };
            Media imageMedia = new Media(MimeType.valueOf("image/jpeg"), resource);
            return chatClient.prompt().system(imageEditSystemPrompt)
                    .user(u -> u.text("修改要求：" + editInstruction + "\n请描述修改后的图片画面：").media(imageMedia))
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, UUID.randomUUID().toString()))
                    .options(DashScopeChatOptions.builder().withModel("qwen-vl-plus").withMultiModel(true).build())
                    .call().content();
        } catch (AIServiceException e) { throw e; }
        catch (Exception e) {
            log.error("图片编辑描述失败 | instruction={}", editInstruction, e);
            throw new AIServiceException("describeImageEdit", "图片编辑理解失败: " + e.getMessage(), e);
        }
    }

    /** 文档分析 —— 带重试 + 指数退避，防止长文本导致超时 */
    public String analyzeDocument(String userId, String fileName, String fileType,
                                   String extractedText, String systemPrompt) {
        // 截断过长文本，避免 token 超限 / 响应超时
        final int maxChars = 8000;
        String text = extractedText.length() > maxChars
                ? extractedText.substring(0, maxChars)
                    + "\n\n[内容过长，已截断前 " + maxChars + " 字符...]"
                : extractedText;

        String userMessage = String.format("文件名：%s\n文件类型：%s\n\n提取的文本内容：\n\n%s",
                fileName, fileType, text);

        int maxRetries = 2;
        long backoffMs = 2000;
        Exception lastException = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                if (attempt > 0) {
                    log.info("[CHAT] analyzeDocument 重试 {}/{} | fileName={}",
                            attempt, maxRetries, fileName);
                }
                return chatClient.prompt().system(systemPrompt).user(userMessage)
                        .advisors(a -> a.param(ChatMemory.CONVERSATION_ID,
                                UUID.randomUUID().toString()))
                        .call().content();
            } catch (AIServiceException e) {
                throw e; // 业务异常不重试
            } catch (Exception e) {
                lastException = e;
                if (attempt < maxRetries) {
                    log.warn("[CHAT] analyzeDocument 失败，{}ms 后重试 | fileName={} | error={}",
                            backoffMs, fileName, e.getMessage());
                    try { Thread.sleep(backoffMs); } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt(); break;
                    }
                    backoffMs *= 2;
                }
            }
        }

        log.error("文档分析失败（已重试{}次） | fileName={}", maxRetries, lastException);
        throw new AIServiceException("analyzeDocument",
                String.format("文档「%s」分析失败（已重试%d次）: %s",
                        fileName, maxRetries,
                        lastException != null ? lastException.getMessage() : "未知错误"),
                lastException);
    }

    public void clearHistory(String userId) {
        chatMemory.clear(userId);
        log.info("清除对话历史 - userId: {}", userId);
    }
}
