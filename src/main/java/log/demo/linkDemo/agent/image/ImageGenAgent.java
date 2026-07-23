package log.demo.linkDemo.agent.image;

import jakarta.annotation.PreDestroy;
import log.demo.linkDemo.agent.Agent;
import log.demo.linkDemo.agent.AgentContext;
import log.demo.linkDemo.agent.Intent;
import log.demo.linkDemo.agent.chat.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 文生图智能体 —— 万象（wan2.5-t2i-preview）。
 * 意图 = {@link Intent#IMAGE_GEN}。
 * <p>
 * <b>v2.0 优化：</b>
 * <ul>
 *   <li>异步虚拟线程执行，非阻塞返回</li>
 *   <li>自动获取上一张图片 CDN URL 作为参考图，实现迭代生成一致性</li>
 *   <li>参考图过期/失败时优雅降级为纯文本生成</li>
 *   <li>生成结果自动保存到 {@link ImageContextManager} 供后续迭代</li>
 * </ul>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageGenAgent implements Agent {

    private final ImageGenService imageGenService;
    private final ImageContextManager imageContextManager;
    private final ChatService chatService;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public String name() {
        return "image-gen";
    }

    @Override
    public Intent intent() {
        return Intent.IMAGE_GEN;
    }

    @Override
    public boolean execute(AgentContext ctx) {
        executor.submit(() -> doImageGen(ctx));
        return true;
    }

    private void doImageGen(AgentContext ctx) {
        try {
            String prompt = ctx.text();
            String userId = ctx.userId();

            // 尝试获取上一张图片的 CDN URL 作为参考图
            String refUrl = imageContextManager.getLastRefImage(userId).orElse(null);

            ctx.sender().sendText(userId,
                    "正在生成图片" + (refUrl != null ? "（基于上一张迭代）" : "") + "...");

            String url;
            if (refUrl != null) {
                url = imageGenService.generateImageUrl(prompt, refUrl);
                if (url == null) {
                    // 参考图方式失败，尝试无参考图重新生成
                    log.info("[IMAGE-GEN] 带参考图生成失败，尝试纯文本生成 | userId={}", userId);
                    ctx.sender().sendText(userId, "参考图已过期，使用纯文本描述重新生成...");
                    url = imageGenService.generateImageUrl(prompt);
                }
            } else {
                url = imageGenService.generateImageUrl(prompt);
            }

            if (url == null) {
                ctx.sender().sendText(userId, "图片生成失败，请稍后重试");
                return;
            }

            byte[] bytes = imageGenService.downloadImage(url);
            if (bytes != null && bytes.length > 0) {
                imageContextManager.save(userId, url, bytes);
                ctx.sender().sendImage(userId, bytes, "ai-gen.png", prompt);
                log.info("[IMAGE-GEN] 生成完成 | userId={} | size={}bytes", userId, bytes.length);
            } else {
                ctx.sender().sendText(userId, "图片下载失败，请稍后重试");
            }
        } catch (Exception e) {
            log.error("[IMAGE-GEN] 失败 | userId={}", ctx.userId(), e);
            ctx.sender().sendText(ctx.userId(), "图片生成失败：" + e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[IMAGE-GEN] 正在关闭...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}