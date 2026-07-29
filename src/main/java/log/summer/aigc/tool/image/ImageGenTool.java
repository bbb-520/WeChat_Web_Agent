package log.summer.aigc.tool.image;

import jakarta.annotation.PostConstruct;
import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.port.MessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 文生图工具 —— 万象（wan2.5-t2i-preview）。
 * <p>
 * 支持基于上一张图片迭代生成。
 *
 * <h3>BUG FIX (2026-07-29)</h3>
 * Previously hard-coded {@code userId = "default"}, which routed all
 * generated images to a non-existent user, leaving real users without
 * their pictures. The real {@code userId} is now read from
 * {@link UserContextHolder} which {@code AgentLoop} populates before
 * invoking any tool.
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenTool {

    private final ImageGenService imageGenService;
    private final ImageContextManager imageContextManager;
    private final ImageCacheManager imageCacheManager;
    private final MessageSender messageSender;

    @PostConstruct
    void init() {
        log.info("[IMAGE-GEN-TOOL] ✅ 已初始化 | deps: ImageGenService={}, ImageContextManager={}, ImageCacheManager={}, MessageSender={}",
                imageGenService != null, imageContextManager != null,
                imageCacheManager != null, messageSender != null);
    }

    @Tool(name = "image_generate", description = "根据文字描述生成图片")
    public ActResult generateImage(
            @ToolParam(description = "图片的文字描述，越详细越好") String prompt) {
        return doGenerate(prompt);
    }

    @Tool(name = "draw", description = "根据文字描述生成图片，当用户说'画图/生成图片/画一只小狗'时调用")
    public ActResult draw(
            @ToolParam(required = true, description = "图片的文字描述，越详细越好") String prompt) {
        return doGenerate(prompt);
    }

    /** Shared implementation for both image_generate and draw. */
    private ActResult doGenerate(String prompt) {

        if (prompt == null || prompt.isBlank()) {
            return ActResult.failure("请提供图片描述文字");
        }

        try {
            // BUG FIX: read the real user from the per-request context instead
            // of hard-coding "default".
            String userId = UserContextHolder.getUserId();

            // 尝试获取上一张图片的 CDN URL 作为参考图
            String refUrl = imageContextManager.getLastRefImage(userId).orElse(null);

            String url;
            if (refUrl != null) {
                url = imageGenService.generateImageUrl(prompt, refUrl);
                if (url == null) {
                    log.info("[IMAGE-GEN] 带参考图生成失败，尝试纯文本生成 | userId={}", userId);
                    url = imageGenService.generateImageUrl(prompt);
                }
            } else {
                url = imageGenService.generateImageUrl(prompt);
            }

            if (url == null) {
                return ActResult.failure("图片生成失败，请稍后重试");
            }

            byte[] bytes = imageGenService.downloadImage(url);
            if (bytes != null && bytes.length > 0) {
                imageContextManager.save(userId, url, bytes);
                imageCacheManager.put(userId, bytes);
                // BUG FIX: send to the actual user, not "default".
                messageSender.sendImage(userId, bytes, "ai-gen.png", prompt);
                log.info("[IMAGE-GEN] 生成完成 | userId={} | size={}bytes", userId, bytes.length);
                return ActResult.success("图片已发送");
            } else {
                return ActResult.failure("图片下载失败，请稍后重试");
            }
        } catch (Exception e) {
            log.error("[IMAGE-GEN] 失败 | prompt={}", prompt, e);
            return ActResult.failure("图片生成失败：" + e.getMessage());
        }
    }
}
