package log.demo.linkDemo.agent.chat;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import jakarta.annotation.PostConstruct;
import log.demo.linkDemo.exception.AIServiceException;
import log.demo.linkDemo.rag.RAGContextAugmenter;
import log.demo.linkDemo.rag.RAGRetrievalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

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
