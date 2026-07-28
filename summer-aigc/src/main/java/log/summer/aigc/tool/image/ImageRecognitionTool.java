package log.summer.aigc.tool.image;

import log.summer.aigc.loop.ActResult;
import log.summer.aigc.port.MessageSender;
import log.summer.aigc.service.ChatService;
import log.summer.common.exception.AIServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 图片识别 & 编辑工具 —— 万象多模态（qwen-vl-plus）+ 文生图（wan2.5-t2i-preview）。
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageRecognitionTool {

    private final ChatService chatService;
    private final ImageGenService imageGenService;
    private final ImageContextManager imageContextManager;
    private final MessageSender messageSender;

    @Tool(name = "image_recognize", description = "识别图片内容并返回文字描述")
    public ActResult recognizeImage(
            @ToolParam(description = "图片的字节数据") byte[] image) {

        if (image == null || image.length == 0) {
            return ActResult.failure("图片数据为空");
        }

        try {
            log.info("[IMG-REC] 图片识别 | size={}bytes", image.length);
            String desc = chatService.analyzeImage(image);
            return ActResult.success(desc);
        } catch (AIServiceException e) {
            log.error("[IMG-REC] 图片识别失败", e);
            return ActResult.failure("图片识别失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[IMG-REC] 图片识别异常", e);
            return ActResult.failure("图片处理异常：" + e.getMessage());
        }
    }

    @Tool(name = "image_edit", description = "根据文字描述修改或编辑图片")
    public ActResult editImage(
            @ToolParam(description = "原始图片的字节数据") byte[] image,
            @ToolParam(description = "编辑指令，例如 '改成黑白风格'、'把背景换成蓝天'") String editPrompt) {

        if (image == null || image.length == 0) {
            return ActResult.failure("原始图片数据为空");
        }
        if (editPrompt == null || editPrompt.isBlank()) {
            return ActResult.failure("请提供编辑指令");
        }

        try {
            String userId = "default";

            log.info("[IMG-EDIT] 编辑请求 | userId={} | instruction={}", userId, editPrompt);

            // 1) 多模态理解：原图 + 编辑指令 → 文生图 prompt
            String generatedPrompt = chatService.describeImageEdit(image, editPrompt);
            log.info("[IMG-EDIT] 编辑 prompt 生成完成 | userId={} | promptLen={}",
                    userId, generatedPrompt.length());

            // 2) 文生图：根据 prompt 生成新图片
            String imageUrl = imageGenService.generateImageUrl(generatedPrompt);
            if (imageUrl == null) {
                return ActResult.failure("图片生成失败，请稍后重试或尝试其他编辑指令");
            }

            // 3) 下载生成的图片
            byte[] resultBytes = imageGenService.downloadImage(imageUrl);
            if (resultBytes == null || resultBytes.length == 0) {
                return ActResult.failure("图片下载失败，请稍后重试");
            }

            // 4) 保存到图片上下文（供后续迭代编辑使用）
            imageContextManager.save(userId, imageUrl, resultBytes);

            // 5) 发送结果
            messageSender.sendImage(userId, resultBytes, "edited-image.png",
                    "编辑: " + editPrompt);
            log.info("[IMG-EDIT] 编辑完成 | userId={} | instruction={}",
                    userId, editPrompt);

            return ActResult.success("图片已发送");
        } catch (AIServiceException e) {
            log.error("[IMG-EDIT] AI 服务异常 | editPrompt={}", editPrompt, e);
            return ActResult.failure("图片编辑失败：" + e.getMessage());
        } catch (Exception e) {
            log.error("[IMG-EDIT] 编辑异常 | editPrompt={}", editPrompt, e);
            return ActResult.failure("图片编辑失败：" + e.getMessage());
        }
    }
}
