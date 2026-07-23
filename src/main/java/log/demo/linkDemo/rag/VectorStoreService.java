package log.demo.linkDemo.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.PostConstruct;
import log.demo.linkDemo.entity.DocumentChunk;
import log.demo.linkDemo.entity.UserMemory;
import log.demo.linkDemo.service.IDocumentChunkService;
import log.demo.linkDemo.service.IUserMemoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 向量存储服务 —— 内存向量索引 + MySQL 持久化。
 *
 * <p>索引结构：{@code Map<userId, List<VectorEntry>>}，按用户分区。</p>
 * <p>启动时从 MySQL 加载所有已有 embedding 到内存。</p>
 * <p>检索使用余弦相似度，取 Top-K，低于阈值的结果丢弃。</p>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorStoreService {

    private final IDocumentChunkService documentChunkService;
    private final IUserMemoryService userMemoryService;

    /** 相似度阈值 */
    private static final float SIMILARITY_THRESHOLD = 0.6f;

    // ── 内存索引 ──

    /** userId → document chunk vectors */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<DocVectorEntry>> docIndex = new ConcurrentHashMap<>();
    /** userId → user memory vectors */
    private final ConcurrentHashMap<String, CopyOnWriteArrayList<MemoryVectorEntry>> memoryIndex = new ConcurrentHashMap<>();

    @PostConstruct
    public void loadFromDatabase() {
        log.info("[VECTOR-STORE] 开始从 MySQL 加载向量...");
        long start = System.currentTimeMillis();

        int docCount = 0;
        int memCount = 0;

        // 加载文档切片
        List<DocumentChunk> allChunks = documentChunkService.list(
                new LambdaQueryWrapper<DocumentChunk>()
                        .isNotNull(DocumentChunk::getEmbedding));
        for (DocumentChunk chunk : allChunks) {
            float[] vec = EmbeddingService.fromJson(chunk.getEmbedding());
            if (vec != null) {
                docIndex.computeIfAbsent(chunk.getUserId(), k -> new CopyOnWriteArrayList<>())
                        .add(new DocVectorEntry(chunk, vec));
                docCount++;
            }
        }

        // 加载用户记忆
        List<UserMemory> allMemories = userMemoryService.list(
                new LambdaQueryWrapper<UserMemory>()
                        .isNotNull(UserMemory::getEmbedding));
        for (UserMemory mem : allMemories) {
            float[] vec = EmbeddingService.fromJson(mem.getEmbedding());
            if (vec != null) {
                memoryIndex.computeIfAbsent(mem.getUserId(), k -> new CopyOnWriteArrayList<>())
                        .add(new MemoryVectorEntry(mem, vec));
                memCount++;
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[VECTOR-STORE] 加载完成 | docChunks={} | memories={} | elapsed={}ms",
                docCount, memCount, elapsed);
    }

    // ═══════════════════════════════════════════════════════════
    // 文档切片
    // ═══════════════════════════════════════════════════════════

    /**
     * 存储文档切片到 MySQL 并更新内存索引
     */
    public void storeDocumentChunk(DocumentChunk chunk, float[] embedding) {
        String json = EmbeddingService.toJson(embedding);
        chunk.setEmbedding(json);
        documentChunkService.updateById(chunk); // 更新 embedding 字段

        docIndex.computeIfAbsent(chunk.getUserId(), k -> new CopyOnWriteArrayList<>())
                .add(new DocVectorEntry(chunk, embedding));
    }

    /**
     * 批量存储文档切片（先批量写 MySQL，再更新内存）
     */
    public void storeDocumentChunks(List<DocumentChunk> chunks, List<float[]> embeddings) {
        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk c = chunks.get(i);
            float[] emb = i < embeddings.size() ? embeddings.get(i) : null;
            if (emb == null) continue;
            String json = EmbeddingService.toJson(emb);
            c.setEmbedding(json);
            documentChunkService.updateById(c);

            docIndex.computeIfAbsent(c.getUserId(), k -> new CopyOnWriteArrayList<>())
                    .add(new DocVectorEntry(c, emb));
        }
        log.debug("[VECTOR-STORE] 批量存储文档切片 | count={}", chunks.size());
    }

    /**
     * 在文档切片中检索 Top-K
     *
     * @return 按相似度降序排列的结果（已过滤低于阈值的结果）
     */
    public List<DocSearchResult> searchDocumentChunks(String userId, float[] queryVec, int topK) {
        List<DocVectorEntry> entries = docIndex.get(userId);
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        return search(entries, queryVec, topK).stream()
                .map(r -> new DocSearchResult(r.entry().chunk, r.similarity()))
                .toList();
    }

    // ═══════════════════════════════════════════════════════════
    // 用户记忆
    // ═══════════════════════════════════════════════════════════

    /**
     * 存储用户记忆到 MySQL 并更新内存索引
     */
    public void storeUserMemory(UserMemory memory, float[] embedding) {
        String json = EmbeddingService.toJson(embedding);
        memory.setEmbedding(json);
        userMemoryService.updateById(memory);

        memoryIndex.computeIfAbsent(memory.getUserId(), k -> new CopyOnWriteArrayList<>())
                .add(new MemoryVectorEntry(memory, embedding));
    }

    /**
     * 在用户记忆中检索 Top-K
     */
    public List<MemorySearchResult> searchUserMemory(String userId, float[] queryVec, int topK) {
        List<MemoryVectorEntry> entries = memoryIndex.get(userId);
        if (entries == null || entries.isEmpty()) {
            return Collections.emptyList();
        }
        return search(entries, queryVec, topK).stream()
                .map(r -> new MemorySearchResult(r.entry().memory, r.similarity()))
                .toList();
    }

    // ═══════════════════════════════════════════════════════════
    // 通用余弦相似度检索
    // ═══════════════════════════════════════════════════════════

    /**
     * 对条目列表计算余弦相似度，取 Top-K（过滤低于阈值的结果）
     */
    private <T> List<ScoredEntry<T>> search(List<T> entries, float[] queryVec, int topK) {
        if (entries == null || entries.isEmpty() || queryVec == null) {
            return Collections.emptyList();
        }
        List<ScoredEntry<T>> scored = new ArrayList<>();
        for (T entry : entries) {
            float[] vec = getVector(entry);
            if (vec == null) continue;
            float sim = cosineSimilarity(queryVec, vec);
            if (sim >= SIMILARITY_THRESHOLD) {
                scored.add(new ScoredEntry<>(entry, sim));
            }
        }
        scored.sort(Comparator.comparingDouble(ScoredEntry<T>::similarity).reversed());
        if (scored.size() > topK) {
            scored = scored.subList(0, topK);
        }
        return scored;
    }

    @SuppressWarnings("unchecked")
    private <T> float[] getVector(T entry) {
        if (entry instanceof DocVectorEntry d) return d.vec;
        if (entry instanceof MemoryVectorEntry m) return m.vec;
        return null;
    }

    // ── 余弦相似度 ──

    /**
     * cos(θ) = A·B / (|A| × |B|)
     */
    public static float cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return 0f;
        }
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0f;
        return (float) (dot / (Math.sqrt(normA) * Math.sqrt(normB)));
    }

    // ═══════════════════════════════════════════════════════════
    // Inner types
    // ═══════════════════════════════════════════════════════════

    private record DocVectorEntry(DocumentChunk chunk, float[] vec) {}
    private record MemoryVectorEntry(UserMemory memory, float[] vec) {}
    private record ScoredEntry<T>(T entry, float similarity) {}

    /** 文档切片检索结果 */
    public record DocSearchResult(DocumentChunk chunk, float similarity) {}
    /** 用户记忆检索结果 */
    public record MemorySearchResult(UserMemory memory, float similarity) {}
}
