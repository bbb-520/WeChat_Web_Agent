package log.summer.aigc.service;

import log.summer.aigc.entity.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 对话持久化门面 —— 组合各 IService，对上提供简洁 API。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPersistenceService {

    private final IConversationService conversationService;
    private final IMessageService messageService;
    private final ITimbreChangeService timbreChangeService;
    private final IImageRecordService imageRecordService;
    private final IFileRecordService fileRecordService;
    private final IWeatherQueryService weatherQueryService;
    private final IDocumentChunkService documentChunkService;
    private final IUserMemoryService userMemoryService;

    // ═══════════════════════════════════════════════════════════
    // 会话
    // ═══════════════════════════════════════════════════════════

    public Conversation newConversation(String userId, String routeContext) {
        Conversation conv = new Conversation();
        conv.setUserId(userId);
        conv.setSessionId(UUID.randomUUID().toString().substring(0, 8));
        conv.setRouteContext(routeContext);
        conv.setStatus(1);
        conv.setMessageCount(0);
        conv.setStartTime(LocalDateTime.now());
        conv.setCreatedAt(LocalDateTime.now());
        conv.setUpdatedAt(LocalDateTime.now());
        conversationService.save(conv);
        log.debug("[DB] 新会话 | userId={} | convId={} | ctx={}", userId, conv.getId(), routeContext);
        return conv;
    }

    public void updateTitle(Long convId, String text) {
        conversationService.updateTitle(convId, text);
    }

    public void incrementMessageCount(Long convId) {
        var conv = conversationService.getById(convId);
        if (conv != null) {
            conv.setMessageCount((conv.getMessageCount() == null ? 0 : conv.getMessageCount()) + 1);
            conversationService.updateById(conv);
        }
    }

    public void closeConversation(Long convId) {
        conversationService.closeConversation(convId);
    }

    public Conversation getActiveConversation(String userId) {
        return conversationService.getActiveConversation(userId);
    }

    // ═══════════════════════════════════════════════════════════
    // 消息
    // ═══════════════════════════════════════════════════════════

    /** 保存简洁版消息（兼容旧调用） */
    public Message saveMessage(Long convId, String userId, String role,
                                String content, String msgType) {
        Message msg = new Message();
        msg.setConversationId(convId);
        msg.setUserId(userId);
        msg.setMessageType(role);       // USER / BOT
        msg.setContentType(msgType.toLowerCase());
        msg.setTextContent(content);
        msg.setProcessingStatus("SUCCESS");
        msg.setCreatedAt(LocalDateTime.now());
        messageService.save(msg);
        incrementMessageCount(convId);
        return msg;
    }

    /** 直接保存 Message 实体（富字段由调用方填充） */
    public Message saveMessage(Message msg) {
        if (msg.getProcessingStatus() == null) msg.setProcessingStatus("SUCCESS");
        if (msg.getCreatedAt() == null) msg.setCreatedAt(LocalDateTime.now());
        messageService.save(msg);
        if (msg.getConversationId() != null) incrementMessageCount(msg.getConversationId());
        return msg;
    }

    // ═══════════════════════════════════════════════════════════
    // 音色切换
    // ═══════════════════════════════════════════════════════════

    public void saveTimbreChange(String userId, Long convId,
                                  String oldVoiceId, String newVoiceId,
                                  String displayName, String source) {
        TimbreChange tc = new TimbreChange();
        tc.setUserId(userId);
        tc.setConversationId(convId);
        tc.setOldVoiceId(oldVoiceId);
        tc.setNewVoiceId(newVoiceId);
        tc.setNewDisplayName(displayName);
        tc.setChangeSource(source);
        tc.setCreatedAt(LocalDateTime.now());
        timbreChangeService.save(tc);
    }

    // ═══════════════════════════════════════════════════════════
    // 图片上下文
    // ═══════════════════════════════════════════════════════════

    public void saveImageContext(String userId, String messageId,
                                  String imageUrl, Long imageSize,
                                  String description, String editInstruction) {
        ImageRecord rec = new ImageRecord();
        rec.setUserId(userId);
        rec.setMessageId(messageId);
        rec.setImageUrl(imageUrl);
        rec.setImageSize(imageSize);
        rec.setDescription(description);
        rec.setEditInstruction(editInstruction);
        rec.setIsRefImage(0);
        rec.setCreatedAt(LocalDateTime.now());
        imageRecordService.save(rec);
    }

    // ═══════════════════════════════════════════════════════════
    // 文件识别
    // ═══════════════════════════════════════════════════════════

    public void saveFileRecord(String userId, String messageId, Long convId,
                                String fileName, Long fileSize, String mimeType,
                                String fileTypeLabel, String extractedText,
                                String aiAnalysis, Integer elapsedMs, String status) {
        FileRecord rec = new FileRecord();
        rec.setUserId(userId);
        rec.setMessageId(messageId);
        rec.setConversationId(convId);
        rec.setFileName(fileName);
        rec.setFileSize(fileSize);
        rec.setMimeType(mimeType);
        rec.setFileTypeLabel(fileTypeLabel);
        rec.setExtractedText(extractedText);
        rec.setTextLength(extractedText != null ? extractedText.length() : 0);
        rec.setAiAnalysis(aiAnalysis);
        rec.setAnalysisElapsedMs(elapsedMs);
        rec.setStatus(status);
        rec.setCreatedAt(LocalDateTime.now());
        rec.setUpdatedAt(LocalDateTime.now());
        fileRecordService.save(rec);
    }

    // ═══════════════════════════════════════════════════════════
    // 天气查询
    // ═══════════════════════════════════════════════════════════

    public void saveWeatherQuery(String userId, String messageId,
                                  String queryText, String city, String queryType,
                                  String apiResponse, String reportText,
                                  Integer elapsedMs, String status) {
        WeatherQuery wq = new WeatherQuery();
        wq.setUserId(userId);
        wq.setMessageId(messageId);
        wq.setQueryText(queryText);
        wq.setCity(city);
        wq.setQueryType(queryType);
        wq.setApiResponse(apiResponse);
        wq.setReportText(reportText);
        wq.setApiElapsedMs(elapsedMs);
        wq.setStatus(status);
        wq.setCreatedAt(LocalDateTime.now());
        weatherQueryService.save(wq);
    }

    // ═══════════════════════════════════════════════════════════
    // 文档切片（RAG）
    // ═══════════════════════════════════════════════════════════

    public DocumentChunk saveDocumentChunk(Long fileRecordId, String userId,
                                           int chunkIndex, String chunkText,
                                           int chunkSize, String embeddingJson) {
        DocumentChunk chunk = new DocumentChunk();
        chunk.setFileRecordId(fileRecordId);
        chunk.setUserId(userId);
        chunk.setChunkIndex(chunkIndex);
        chunk.setChunkText(chunkText);
        chunk.setChunkSize(chunkSize);
        chunk.setEmbedding(embeddingJson);
        chunk.setCreatedAt(LocalDateTime.now());
        documentChunkService.save(chunk);
        return chunk;
    }

    // ═══════════════════════════════════════════════════════════
    // 用户记忆（RAG）
    // ═══════════════════════════════════════════════════════════

    public UserMemory saveUserMemory(String userId, String memoryType,
                                     String content, String embeddingJson,
                                     Float importance) {
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setMemoryType(memoryType);
        memory.setContent(content);
        memory.setEmbedding(embeddingJson);
        memory.setImportance(importance != null ? importance : 0.5f);
        memory.setCreatedAt(LocalDateTime.now());
        userMemoryService.save(memory);
        return memory;
    }
}
