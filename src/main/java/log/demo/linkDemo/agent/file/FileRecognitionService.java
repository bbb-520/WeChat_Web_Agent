package log.demo.linkDemo.agent.file;

import jakarta.annotation.PostConstruct;
import log.demo.linkDemo.config.BotProperties;
import log.demo.linkDemo.entity.DocumentChunk;
import log.demo.linkDemo.exception.AIServiceException;
import log.demo.linkDemo.exception.FileRecognitionException;
import log.demo.linkDemo.rag.DocumentChunkingService;
import log.demo.linkDemo.rag.EmbeddingService;
import log.demo.linkDemo.rag.VectorStoreService;
import log.demo.linkDemo.service.IDocumentChunkService;
import log.demo.linkDemo.agent.chat.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 文件识别服务 —— MIME 类型检测 + 文本内容提取 + AI 分析编排。
 * 通过 Apache Tika 统一处理 PDF / Word / Excel / 纯文本等格式。
 *
 * @author bbb
 * @since 2026-07-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileRecognitionService {

    private final ChatService aiChatService;
    private final BotProperties botProperties;
    private final ResourceLoader resourceLoader;
    private final DocumentChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final IDocumentChunkService documentChunkService;
    private final VectorStoreService vectorStoreService;
    private final log.demo.linkDemo.service.IFileRecordService fileRecordService;

    private final Tika tika = new Tika();
    private static final int MAX_CONTENT_CHARS = 8000;
    private final ExecutorService ragExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private String fileSystemPrompt;

    @PostConstruct
    public void init() {
        String path = botProperties.getFile().getSystemPromptPath();
        try {
            Resource resource = resourceLoader.getResource(path);
            this.fileSystemPrompt = resource.getContentAsString(StandardCharsets.UTF_8);
            log.info("[FILE-REC] 文件分析系统提示词已加载 | path={} | len={}", path, fileSystemPrompt.length());
        } catch (IOException e) {
            log.warn("[FILE-REC] 加载系统提示词失败，使用默认提示词 | path={}", path, e);
            this.fileSystemPrompt = "你是一个专业的文档分析助手。请根据提取到的文本内容进行分析总结。";
        }
    }

    public String recognize(String userId, byte[] fileBytes, String fileName) {
        long start = System.currentTimeMillis();
        if (fileBytes == null || fileBytes.length == 0) {
            return "文件为空，无法识别";
        }

        String mimeType = detectType(fileBytes, fileName);
        log.info("[FILE-REC] 类型检测 | fileName={} | mimeType={} | size={}bytes",
                fileName, mimeType, fileBytes.length);

        String extractedText;
        if (mimeType.startsWith("image/")) {
            try {
                String description = aiChatService.analyzeImage(fileBytes);
                extractedText = (description != null && !description.isEmpty())
                        ? description
                        : "[图片内容无法识别]";
            } catch (AIServiceException e) {
                log.error("[FILE-REC] 图片分析失败 | fileName={}", fileName, e);
                throw new FileRecognitionException("analyze",
                        "图片「" + fileName + "」分析失败: " + e.getMessage(), e);
            }
        } else {
            extractedText = extractTextWithTika(fileBytes, fileName, mimeType);
        }

        if (extractedText == null || extractedText.isBlank()) {
            return "文件中未检测到可读文本内容，请确认文件格式是否正确";
        }

        log.info("[FILE-REC] 内容提取完成 | fileName={} | textLen={}", fileName, extractedText.length());

        String aiResult;
        try {
            aiResult = analyzeWithAI(userId, fileName, mimeType, extractedText);
        } catch (FileRecognitionException e) {
            log.error("[FILE-REC] 文件分析失败 | userId={} | fileName={} | stage={}",
                    userId, fileName, e.getStage(), e);
            return "文件分析失败：" + e.getMessage();
        }
        if (aiResult == null || aiResult.isBlank()) {
            return "AI 分析失败，请稍后重试";
        }

        // ── 持久化 FileRecord ──
        String displayType = simplifyMimeType(mimeType);
        int elapsed = (int) (System.currentTimeMillis() - start);
        Long fileRecordId = saveFileRecord(userId, fileName, fileBytes.length,
                mimeType, displayType, extractedText, aiResult, elapsed);

        // ── RAG: 异步切片 + Embedding + 存储 ──
        final Long finalFileRecordId = fileRecordId;
        ragExecutor.submit(() -> chunkAndEmbed(finalFileRecordId, userId,
                fileName, displayType, extractedText));

        return formatResponse(fileName, mimeType, aiResult, extractedText.length());
    }

    /**
     * RAG 后处理：将提取的文本切片、向量化、存入向量库。
     * 异步执行，不阻塞主流程。
     */
    private void chunkAndEmbed(Long fileRecordId, String userId, String fileName,
                               String displayType, String extractedText) {
        try {
            log.info("[FILE-RAG] 开始切片 | userId={} | fileName={} | fileRecordId={} | textLen={}",
                    userId, fileName, fileRecordId, extractedText.length());

            // 1. 切片
            List<DocumentChunkingService.Chunk> chunks = chunkingService.chunk(extractedText);
            if (chunks.isEmpty()) {
                log.warn("[FILE-RAG] 切片为空 | fileName={}", fileName);
                return;
            }

            // 2. 先存切片到 DB（关联 file_record_id）
            List<DocumentChunk> entities = new java.util.ArrayList<>();
            for (var c : chunks) {
                var chunk = new DocumentChunk();
                chunk.setFileRecordId(fileRecordId);
                chunk.setUserId(userId);
                chunk.setChunkIndex(c.index());
                chunk.setChunkText(c.text());
                chunk.setChunkSize(c.text().length());
                chunk.setCreatedAt(java.time.LocalDateTime.now());
                entities.add(chunk);
                documentChunkService.save(chunk);
            }

            // 3. 批量 Embedding
            List<String> texts = chunks.stream().map(DocumentChunkingService.Chunk::text).toList();
            List<float[]> embeddings;
            try {
                embeddings = embeddingService.embedBatch(texts);
            } catch (Exception e) {
                log.error("[FILE-RAG] Embedding 失败 | fileName={}", fileName, e);
                return;
            }

            // 4. 更新 embedding 字段
            for (int i = 0; i < entities.size(); i++) {
                if (i < embeddings.size() && embeddings.get(i) != null) {
                    entities.get(i).setEmbedding(EmbeddingService.toJson(embeddings.get(i)));
                    documentChunkService.updateById(entities.get(i));
                }
            }

            // 5. 更新内存索引
            for (int i = 0; i < entities.size(); i++) {
                if (i < embeddings.size() && embeddings.get(i) != null) {
                    vectorStoreService.storeDocumentChunk(entities.get(i), embeddings.get(i));
                }
            }

            log.info("[FILE-RAG] RAG 处理完成 | userId={} | fileName={} | chunks={}",
                    userId, fileName, chunks.size());
        } catch (Exception e) {
            log.error("[FILE-RAG] RAG 处理异常 | userId={} | fileName={}", userId, fileName, e);
        }
    }

    private String detectType(byte[] fileBytes, String fileName) {
        try (InputStream is = new ByteArrayInputStream(fileBytes)) {
            String detected = tika.detect(is, fileName);
            if ("application/octet-stream".equals(detected) && fileName != null) {
                String byName = tika.detect(fileName);
                if (!"application/octet-stream".equals(byName)) {
                    return byName;
                }
            }
            return detected;
        } catch (IOException e) {
            log.warn("[FILE-REC] MIME 检测异常 | fileName={}", fileName, e);
            return "application/octet-stream";
        }
    }

    private String extractTextWithTika(byte[] fileBytes, String fileName, String mimeType) {
        try (InputStream is = new ByteArrayInputStream(fileBytes)) {
            String text = tika.parseToString(is);
            if (text != null) {
                text = text.trim();
                if (text.isEmpty() || text.equals(fileName)) {
                    return "";
                }
            }
            return text != null ? text : "";
        } catch (Exception e) {
            log.error("[FILE-REC] Tika 提取失败 | fileName={} | mimeType={}", fileName, mimeType, e);
            return "";
        }
    }

    private String analyzeWithAI(String userId, String fileName,
                                  String mimeType, String extractedText) {
        String displayType = simplifyMimeType(mimeType);
        String content = truncateForContext(extractedText);

        try {
            return aiChatService.analyzeDocument(userId, fileName, displayType,
                    content, fileSystemPrompt);
        } catch (AIServiceException e) {
            log.error("[FILE-REC] AI 分析失败 | userId={} | fileName={} | operation={}",
                    userId, fileName, e.getOperation(), e);
            throw new FileRecognitionException("analyze",
                    "文档「" + fileName + "」AI 分析失败: " + e.getMessage(), e);
        }
    }

    private String formatResponse(String fileName, String mimeType,
                                   String aiAnalysis, int contentLength) {
        String typeLabel = simplifyMimeType(mimeType);
        String sizeLabel = contentLength > 1000
                ? String.format("%.1f KB", contentLength / 1000.0)
                : contentLength + " 字符";

        return String.format("【文件分析】%s（%s，%s）\n\n%s",
                fileName, typeLabel, sizeLabel, aiAnalysis);
    }

    private String truncateForContext(String text) {
        if (text.length() <= MAX_CONTENT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_CONTENT_CHARS)
                + "\n\n[内容过长，已截断前 " + MAX_CONTENT_CHARS + " 字符...]";
    }

    private String simplifyMimeType(String mimeType) {
        if (mimeType == null) return "未知";
        return switch (mimeType) {
            case "application/pdf" -> "PDF 文档";
            case "application/msword" -> "Word 文档 (.doc)";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "Word 文档 (.docx)";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "Excel 表格 (.xlsx)";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PPT 演示 (.pptx)";
            case "text/plain" -> "纯文本";
            case "text/html" -> "HTML 网页";
            case "text/csv" -> "CSV 表格";
            default -> {
                if (mimeType.startsWith("image/")) yield "图片 (" + mimeType + ")";
                if (mimeType.startsWith("text/")) yield "文本 (" + mimeType + ")";
                yield mimeType;
            }
        };
    }

    /**
     * 持久化文件处理记录到 DB，返回 fileRecordId 用于 RAG 切片关联。
     */
    private Long saveFileRecord(String userId, String fileName, long fileSize,
                                String mimeType, String fileTypeLabel,
                                String extractedText, String aiAnalysis,
                                int elapsedMs) {
        try {
            var rec = new log.demo.linkDemo.entity.FileRecord();
            rec.setUserId(userId);
            rec.setMessageId(java.util.UUID.randomUUID().toString().substring(0, 8));
            rec.setFileName(fileName);
            rec.setFileSize(fileSize);
            rec.setMimeType(mimeType);
            rec.setFileTypeLabel(fileTypeLabel);
            rec.setExtractedText(extractedText);
            rec.setTextLength(extractedText != null ? extractedText.length() : 0);
            rec.setAiAnalysis(aiAnalysis);
            rec.setAnalysisElapsedMs(elapsedMs);
            rec.setStatus("SUCCESS");
            rec.setCreatedAt(java.time.LocalDateTime.now());
            rec.setUpdatedAt(java.time.LocalDateTime.now());
            fileRecordService.save(rec);
            log.info("[FILE-REC] FileRecord 已持久化 | id={} | fileName={}", rec.getId(), fileName);
            return rec.getId();
        } catch (Exception e) {
            log.warn("[FILE-REC] FileRecord 持久化失败（不影响主流程） | fileName={}", fileName, e);
            return null;
        }
    }
}
