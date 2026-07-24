package log.demo.linkDemo.tools.image;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import log.demo.linkDemo.tools.TextTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
public class ImageGenService {

    @Value("${spring.ai.dashscope.api-key}")
    private String apiKey;

    @Value("${spring.ai.dashscope.image.options.model}")
    private String model;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public String generateImageUrl(String prompt) {
        return generateImageUrl(prompt, null);
    }

    public String generateImageUrl(String prompt, String refImageUrl) {
        try {
            log.info("开始生成图片 | prompt=\"{}\" | model={} | refImage={}",
                    TextTool.truncate(prompt, 60), model,
                    refImageUrl != null ? TextTool.truncate(refImageUrl, 50) : "无");

            var builder = ImageSynthesisParam.builder()
                    .apiKey(apiKey)
                    .model(model)
                    .prompt(prompt)
                    .n(1)
                    .size("1024*1024");

            if (refImageUrl != null && !refImageUrl.isBlank()) {
                builder.refImage(refImageUrl);
            }

            ImageSynthesisParam param = builder.build();
            ImageSynthesis imageSynthesis = new ImageSynthesis();

            String taskId = imageSynthesis
                    .asyncCall(param)
                    .getOutput()
                    .getTaskId();
            log.info("文生图任务已提交，taskId: {}", taskId);

            for (int i = 0; i < 90; i++) {
                Thread.sleep(2000);
                ImageSynthesisResult result = imageSynthesis.fetch(taskId, apiKey);
                String status = result.getOutput().getTaskStatus();

                if ("SUCCEEDED".equals(status)) {
                    String url = result.getOutput().getResults().get(0).get("url");
                    log.info("图片生成成功，URL: {}", url);
                    return url;
                } else if ("FAILED".equals(status)) {
                    log.error("图片生成失败: {}", result.getOutput().getMessage());
                    return null;
                }

                if (i % 5 == 0) {
                    log.info("图片生成中... 状态: {}, 已等待 {} 秒", status, (i + 1) * 2);
                }
            }

            log.error("图片生成超时（超过180秒），taskId: {}", taskId);
            return null;
        } catch (Exception e) {
            log.error("调用文生图模型异常 | prompt=\"{}\" | refImage={}",
                    TextTool.truncate(prompt, 60), refImageUrl != null, e);

            if (refImageUrl != null && !refImageUrl.isBlank()) {
                log.warn("[IMG-GEN] refImage 生成失败，尝试无参考图重新生成 | prompt=\"{}\"",
                        TextTool.truncate(prompt, 60));
                return generateImageUrl(prompt, null);
            }
            return null;
        }
    }

    public byte[] downloadImage(String imageUrl) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(imageUrl))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();

            HttpResponse<byte[]> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() == 200) {
                log.info("图片下载成功，大小: {} bytes", response.body().length);
                return response.body();
            } else {
                log.error("下载图片失败，HTTP状态码: {}", response.statusCode());
                return null;
            }
        } catch (Exception e) {
            log.error("下载图片异常，URL: {}", imageUrl, e);
            return null;
        }
    }
}
