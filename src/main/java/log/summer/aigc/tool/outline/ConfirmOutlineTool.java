package log.summer.aigc.tool.outline;

import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.IDocumentOutlineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * Confirms (and optionally modifies) a draft outline, transitioning it
 * from DRAFT to CONFIRMED/MODIFIED so that generateDocument can proceed.
 *
 * <p>This tool fills the gap between createOutline (DRAFT) and
 * generateDocument (requires CONFIRMED/MODIFIED). The LLM calls this
 * after the user has reviewed the outline and indicated acceptance.</p>
 *
 * <h3>BUG FIX (2026-07-29)</h3>
 * Previously this method required the LLM to pass {@code userId} as a
 * parameter, which the LLM cannot fill correctly. {@code userId} is now
 * sourced from {@link UserContextHolder} instead.
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConfirmOutlineTool {

    private final IDocumentOutlineService outlineService;

    @Tool(name = "confirmOutline",
          description = "确认或修改文档大纲。用户确认大纲后调用此工具，将大纲状态从DRAFT转为CONFIRMED/MODIFIED。" +
                        "modifiedOutlineData为可选参数，如果用户有修改意见则传入修改后的大纲JSON；" +
                        "用户只说'确认'/'好的'等肯定词时不要传 modifiedOutlineData。" +
                        "注意：用户ID由系统自动注入，调用时无需填写。调用成功后必须立即调用 generateDocument。")
    public ActResult confirmOutline(
            @ToolParam(description = "要确认的大纲ID") Long outlineId,
            @ToolParam(description = "修改后的大纲JSON（可选，用户确认无修改时不传）")
            String modifiedOutlineData) {

        if (outlineId == null) {
            return ActResult.failure("请提供要确认的大纲ID");
        }

        // BUG FIX: pull userId from the per-request context.
        String userId = UserContextHolder.getUserId();
        if (userId == null || userId.isBlank() || "anonymous".equals(userId)) {
            return ActResult.failure("无法识别当前用户，会话上下文丢失，请重新发起对话");
        }

        // ── NEW: 状态检查门禁 —— 防止 LLM 反复调用 confirmOutline ──
        DocumentOutline outline = outlineService.getById(outlineId);
        if (outline == null) {
            return ActResult.failure("大纲不存在: " + outlineId + "，请重新生成大纲");
        }
        String currentStatus = outline.getStatus();
        if ("CONFIRMED".equals(currentStatus) || "MODIFIED".equals(currentStatus)) {
            log.info("[CONFIRM-OUTLINE] 大纲已确认，跳过重复调用 → 引导 LLM 进入 generateDocument | outlineId={} | status={}",
                    outlineId, currentStatus);
            return ActResult.success(
                    "大纲已确认（状态: " + currentStatus + "），无需重复确认。" +
                    "请立即调用 generateDocument 工具生成文档。" +
                    "参数: type=" + outline.getOutlineType() + ", outlineId=" + outlineId);
        }
        // ── END 状态检查门禁 ──

        boolean hasModification = modifiedOutlineData != null && !modifiedOutlineData.isBlank();
        boolean updated = outlineService.confirmOutline(outlineId, hasModification ? modifiedOutlineData : null);
        if (!updated) {
            log.warn("[CONFIRM-OUTLINE] 确认失败（大纲不存在或版本冲突） | outlineId={} | userId={}",
                    outlineId, userId);
            return ActResult.failure("大纲确认失败：大纲不存在或已被修改，请重新生成大纲");
        }

        // 重新查询获取最新状态
        outline = outlineService.getById(outlineId);
        String action = hasModification ? "修改并确认" : "确认";
        log.info("[CONFIRM-OUTLINE] 大纲已{} | outlineId={} | userId={} | newStatus={}",
                action, outlineId, userId, outline != null ? outline.getStatus() : "?");

        String docType = outline != null ? outline.getOutlineType() : "WORD";
        return ActResult.success(
                "大纲已" + action + "（状态: " + (outline != null ? outline.getStatus() : "CONFIRMED") + "），" +
                "请立即调用 generateDocument 工具生成文档。" +
                "参数: type=" + docType + ", outlineId=" + outlineId);
    }
}
