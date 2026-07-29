package log.summer.aigc.tool.outline;

import log.summer.aigc.context.UserContextHolder;
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
                        "modifiedOutlineData为可选参数，如果用户有修改意见则传入修改后的大纲JSON。" +
                        "注意：用户ID由系统自动注入，调用时无需填写。")
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

        boolean updated = outlineService.confirmOutline(outlineId, modifiedOutlineData);
        if (!updated) {
            log.warn("[CONFIRM-OUTLINE] 确认失败（大纲不存在或版本冲突） | outlineId={} | userId={}",
                    outlineId, userId);
            return ActResult.failure("大纲确认失败：大纲不存在或已被修改，请重新生成大纲");
        }

        String action = (modifiedOutlineData != null && !modifiedOutlineData.isBlank())
                ? "修改并确认" : "确认";
        log.info("[CONFIRM-OUTLINE] 大纲已{} | outlineId={} | userId={}",
                action, outlineId, userId);

        return ActResult.success("大纲已" + action);
    }
}
