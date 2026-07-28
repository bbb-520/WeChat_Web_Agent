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
