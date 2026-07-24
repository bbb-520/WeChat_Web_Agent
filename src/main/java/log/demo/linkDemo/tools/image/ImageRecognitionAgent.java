package log.demo.linkDemo.tools.image;

import jakarta.annotation.PreDestroy;
import log.demo.linkDemo.tools.Agent;
import log.demo.linkDemo.tools.AgentContext;
import log.demo.linkDemo.tools.Intent;
import log.demo.linkDemo.tools.chat.ChatService;
import log.demo.linkDemo.exception.AIServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 图片识别 & 编辑智能体 —— 万象多模态（qwen-vl-plus）+ 文生图（wan2.5-t2i-preview）。
 * <p>
 * <b>v2.0 优化：</b>完整实现图片编辑管线。
 * <ol>
 *   <li><b>上传识别：</b>用户上传图片 → qwen-vl-plus 识别描述 → 缓存图片待编辑</li>
 *   <li><b>编辑生成：</b>用户发送编辑指令 → qwen-vl-plus 理解原图+指令生成详细 prompt → wan2.5 文生图</li>
 * </ol>
 * <p>
 * 编辑采用 "多模态理解 + 文生图重绘" 策略：
 * qwen-vl-plus 同时接收原始图片和编辑指令，输出可直接用于文生图的详细画面描述，
 * 再由 wan2.5 根据描述生成新图片。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageRecognitionAgent implements Agent {

    private final ChatService chatService;
    private final ImageCacheManager imageCacheManager;
    private final ImageGenService imageGenService;
    private final ImageContextManager imageContextManager;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public String name() {
        return "image-recognition";
    }

    @Override
    public Intent intent() {
        return Intent.IMAGE_EDIT;
    }

    @Override
    public boolean execute(AgentContext ctx) {
        if (ctx.hasImage()) {
            // 新图片上传 → 识别并缓存
            return handleImageUpload(ctx);
        }
        if (ctx.hasText() && imageCacheManager.containsKey(ctx.userId())) {
            // 有待处理图片 + 编辑指令 → 异步编辑生成
            executor.submit(() -> doImageEdit(ctx));
            return true;
        }
        return false;
    }

    // ── 图片上传 & 识别 ──

    private boolean handleImageUpload(AgentContext ctx) {
        log.info("[IMG-REC] 图片识别 | userId={} | size={}bytes",
                ctx.userId(), ctx.imageBytes().length);
        try {
            String desc = chatService.analyzeImage(ctx.imageBytes());
            imageCacheManager.put(ctx.userId(), ctx.imageBytes());
            ctx.sender().sendText(ctx.userId(),
                    "【图片描述】\n" + desc
                            + "\n\n💡 你可以对我说：\"改成黑白风格\"、\"把背景换成蓝天\"、" +
                            "\"添加一只猫\" 等进行编辑");
            return true;
        } catch (AIServiceException e) {
            log.error("[IMG-REC] 图片识别失败 | userId={}", ctx.userId(), e);
            ctx.sender().sendText(ctx.userId(), "图片识别失败：" + e.getMessage());
            return true;
        } catch (Exception e) {
            log.error("[IMG-REC] 图片识别异常 | userId={}", ctx.userId(), e);
            ctx.sender().sendText(ctx.userId(), "图片处理异常，请稍后重试");
            return true;
        }
    }

    // ── 图片编辑管线（异步，加锁串行化） ──

    private void doImageEdit(AgentContext ctx) {
        String userId = ctx.userId();
        String instruction = ctx.text();

        // 串行化：同一用户同时只能有一个编辑流程
        imageCacheManager.lock(userId);
        try {
            // 1) 获取缓存的原始图片（peek 不删除，失败可重试）
            byte[] originalBytes = imageCacheManager.peek(userId);
            if (originalBytes == null || originalBytes.length == 0) {
                ctx.sender().sendText(userId, "待编辑图片已过期，请重新上传图片");
                return;
            }

            ctx.sender().sendText(userId, "正在分析编辑需求：\"" + instruction + "\"...");

            // 2) 多模态理解：原图 + 编辑指令 → 文生图 prompt
            String editPrompt = chatService.describeImageEdit(originalBytes, instruction);
            log.info("[IMG-EDIT] 编辑 prompt 生成完成 | userId={} | promptLen={}",
                    userId, editPrompt.length());

            ctx.sender().sendText(userId, "正在生成编辑后的图片...");

            // 3) 文生图：根据 prompt 生成新图片
            String imageUrl = imageGenService.generateImageUrl(editPrompt);
            if (imageUrl == null) {
                ctx.sender().sendText(userId, "图片生成失败，请稍后重试或尝试其他编辑指令");
                return;
            }

            // 4) 下载生成的图片
            byte[] resultBytes = imageGenService.downloadImage(imageUrl);
            if (resultBytes == null || resultBytes.length == 0) {
                ctx.sender().sendText(userId, "图片下载失败，请稍后重试");
                return;
            }

            // 5) 保存到图片上下文（供后续迭代编辑使用）
            imageContextManager.save(userId, imageUrl, resultBytes);

            // 6) 更新待编辑缓存为新图片（支持连续编辑）
            imageCacheManager.removeSilently(userId);
            imageCacheManager.put(userId, resultBytes);

            // 7) 发送结果
            ctx.sender().sendImage(userId, resultBytes, "edited-image.png",
                    "编辑: " + instruction);
            log.info("[IMG-EDIT] 编辑完成 | userId={} | instruction={} | url={}",
                    userId, instruction,
                    imageUrl.substring(0, Math.min(60, imageUrl.length())) + "...");

        } catch (AIServiceException e) {
            log.error("[IMG-EDIT] AI 服务异常 | userId={} | instruction={}", userId, instruction, e);
            ctx.sender().sendText(userId, "图片编辑失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[IMG-EDIT] 编辑异常 | userId={} | instruction={}", userId, instruction, e);
            ctx.sender().sendText(userId, "图片编辑失败：" + e.getMessage());
        } finally {
            imageCacheManager.unlock(userId);
        }
    }

    // ── 生命周期 ──

    @PreDestroy
    public void shutdown() {
        log.info("[IMG-REC] 正在关闭...");
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