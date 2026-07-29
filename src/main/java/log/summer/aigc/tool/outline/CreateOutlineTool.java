package log.summer.aigc.tool.outline;

import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.entity.Conversation;
import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.ChatPersistenceService;
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
 * <h3>BUG FIX (2026-07-29)</h3>
 * Previously this method required the LLM to pass {@code userId} and
 * {@code conversationId} as parameters. The LLM does not know those
 * values and would either skip them or invent placeholders, which made
 * the document-generation flow non-functional. Those parameters have
 * been removed and the values are now sourced from
 * {@link UserContextHolder} (populated by {@code AgentLoop}).
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CreateOutlineTool {

    private final IDocumentOutlineService outlineService;
    private final ChatPersistenceService persistence;

    private static final Set<String> VALID_TYPES = Set.of("WORD", "PPT", "EXCEL");

    @Tool(name = "createOutline",
          description = "生成文档结构化大纲，供用户确认后用于 generateDocument 渲染正式文件。" +
                        "调用后系统会自动暂停等待用户确认/修改。" +
                        "注意：用户ID与会话ID由系统自动注入，调用时无需填写。")
    public ActResult createOutline(
            @ToolParam(description = "文档类型: WORD, PPT, EXCEL") String type,
            @ToolParam(description = "文档标题") String title,
            @ToolParam(description = "结构化大纲 JSON，格式: " +
                    "{\"title\":\"...\", \"sections\":[{\"title\":\"...\", \"points\":[\"...\"]}]}") String outlineJson) {

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

        // BUG FIX: source userId / conversationId from the per-request
        // ThreadLocal instead of asking the LLM to fill them in. If there
        // is no active conversation yet (e.g. the user started with a
        // document request without any prior text), create one on the fly
        // so the subsequent AgentLoop.suspend() has a real convId to bind to.
        String userId = UserContextHolder.getUserId();
        Long conversationId = UserContextHolder.hasConversationId()
                ? UserContextHolder.getConversationId() : null;

        if (conversationId == null) {
            try {
                Conversation conv = persistence.newConversation(userId, "TEXT");
                conversationId = conv.getId();
                UserContextHolder.setConversationId(conversationId);
                log.info("[OUTLINE] 为大纲创建新会话 | userId={} | convId={}",
                        userId, conversationId);
            } catch (Exception e) {
                log.warn("[OUTLINE] 创建会话失败（继续） | userId={} | {}",
                        userId, e.getMessage());
            }
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
            log.info("[OUTLINE] 大纲已保存 | id={} | type={} | title={} | userId={}",
                    outline.getId(), type, title, userId);
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
