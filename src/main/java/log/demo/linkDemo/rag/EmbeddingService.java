package log.demo.linkDemo.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Embedding 服务 —— 封装 Spring AI {@link EmbeddingModel}（DashScopeEmbeddingModel），
 * 提供文本→向量的转换，以及 float[] ↔ JSON 的序列化。
 *
 * <p>默认模型: text-embedding-v1（1536 维），由 DashScopeEmbeddingAutoConfiguration 自动配置。</p>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    /** DashScope text-embedding-v1: 单次最大 25 条 */
    private static final int MAX_BATCH_SIZE = 25;
    /** embedding-v1 支持的最大输入 token 数 ~2048，这里用字符数（中文约 1:1）做安全截断 */
    private static final int MAX_CHARS = 1800;

    /**
     * 将单段文本转为 float[] 向量。
     *
     * @param text 输入文本，超长自动截断
     * @return 1536 维向量；空文本返回 null
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String safe = truncate(text);
        try {
            EmbeddingResponse response = embeddingModel.call(
                    new EmbeddingRequest(List.of(safe), null));
            if (response.getResults().isEmpty()) {
                log.warn("[EMBED] 返回空结果");
                return null;
            }
            return response.getResult().getOutput();
        } catch (Exception e) {
            log.error("[EMBED] 调用失败 | textLen={}", safe.length(), e);
            throw new RuntimeException("Embedding 生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 批量生成向量，自动拆分为 25 条一批。
     *
     * @param texts 输入文本列表
     * @return 与输入顺序一致的向量列表；单条失败对应位置为 null
     */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> safeTexts = texts.stream()
                .map(this::truncate)
                .toList();
        List<float[]> results = new ArrayList<>();

        for (int i = 0; i < safeTexts.size(); i += MAX_BATCH_SIZE) {
            int end = Math.min(i + MAX_BATCH_SIZE, safeTexts.size());
            List<String> batch = safeTexts.subList(i, end);
            try {
                EmbeddingResponse response = embeddingModel.call(
                        new EmbeddingRequest(batch, null));
                for (var r : response.getResults()) {
                    results.add(r.getOutput());
                }
                log.debug("[EMBED] 批量成功 | batch={}-{}/{}", i, end, safeTexts.size());
            } catch (Exception e) {
                log.error("[EMBED] 批量失败 | batch={}-{}", i, end, e);
                // 失败批次填充 null
                for (int j = 0; j < batch.size(); j++) {
                    results.add(null);
                }
            }
        }
        return results;
    }

    // ── float[] ↔ JSON ──

    /**
     * float[] → JSON array string，例如 "[0.12, -0.34, 0.56]"
     */
    public static String toJson(float[] vec) {
        if (vec == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.6f", vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * JSON array string → float[]
     */
    public static float[] fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        String stripped = json.replace("[", "").replace("]", "").replace(" ", "");
        String[] parts = stripped.split(",");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vec[i] = Float.parseFloat(parts[i]);
        }
        return vec;
    }

    // ── helpers ──

    /** 截断超长文本，避免 embedding API token 超限 */
    private String truncate(String text) {
        if (text.length() <= MAX_CHARS) return text;
        return text.substring(0, MAX_CHARS);
    }
}
