package log.summer.aigc.tool;

import log.summer.aigc.loop.ActResult;
import log.summer.aigc.tool.image.ImageGenTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 画图工具 —— 用户自然语言 "画图/生成图片/画一只小狗" 的前端工具。
 * <p>
 * 本质上是 {@link ImageGenTool} 的薄封装，提供一个 LLM 更容易命中的工具名 "draw"。
 * 系统提示词中大量使用 "/draw" 和 "画图" 等表述，LLM 会倾向于调用 "draw" 而非
 * "image_generate"，因此需要一个以 "draw" 为名的工具。
 *
 * <h3>BUG FIX (2026-07-29)</h3>
 * 此前 LLM 调用 {@code draw} 工具时报 "No ToolCallback found for tool name: draw"，
 * 因为 ToolRegistry 中只有 {@code image_generate}（ImageGenTool）而没有任何名为
 * {@code draw} 的工具。
 *
 * @author bbb
 * @since 2026-07-29
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DrawTool {

    private final ImageGenTool imageGenTool;

    @Tool(name = "draw", description = "根据文字描述生成图片，当用户说'画图/生成图片/画一只小狗'时调用")
    public ActResult draw(
            @ToolParam(required = true, description = "图片的文字描述，越详细越好") String prompt) {
        log.info("[DRAW-TOOL] 用户请求画图 | prompt={}", prompt);
        return imageGenTool.generateImage(prompt);
    }
}
