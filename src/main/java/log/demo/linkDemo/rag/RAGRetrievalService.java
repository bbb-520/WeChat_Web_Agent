package log.demo.linkDemo.rag;

import log.demo.linkDemo.entity.DocumentChunk;
import log.demo.linkDemo.entity.UserMemory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * RAG 检索编排服务 —— 将用户问题进行 embedding，从向量库中检索最相关的文档切片和用户记忆。
 *
 * <h3>检索策略</h3>
 * <ul>
 *   <li>并行搜索文档切片 (topK=5) 和用户记忆 (topK=3)</li>
 *   <li>合并后按相似度降序排列</li>
 *   <li>相似度阈值由 {@link VectorStoreService} 控制 (≥0.6)</li>
 * </ul>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGRetrievalService {

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;

    /** 文档切片 Top-K */
    private static final int DOC_TOP_K = 5;
    /** 用户记忆 Top-K */
    private static final int MEM_TOP_K = 3;

    /**
     * 检索结果 —— 统一包装文档切片和用户记忆
     */
    public sealed interface RetrievedDoc
            permits RetrievedChunk, RetrievedMemory {

        /** 用于在 prompt 中展示的文本内容 */
        String displayText();

        /** 相似度 (0~1) */
        float similarity();
    }

    public record RetrievedChunk(DocumentChunk chunk, float similarity) implements RetrievedDoc {
        @Override
        public String displayText() {
            return chunk.getChunkText();
        }
    }

    public record RetrievedMemory(UserMemory memory, float similarity) implements RetrievedDoc {
        @Override
        public String displayText() {
            return memory.getContent();
        }
    }

    /**
     * 根据用户查询检索相关内容（文档 + 记忆）
     *
     * @param userId 用户 ID
     * @param query  用户查询文本
     * @return 按相似度降序的结果列表，空列表表示无相关结果
     */
    public List<RetrievedDoc> retrieve(String userId, String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }

        // 1. 嵌入查询
        float[] queryVec;
        try {
            queryVec = embeddingService.embed(query);
        } catch (Exception e) {
            log.error("[RAG] 查询 Embedding 失败 | userId={} | query={}", userId, query, e);
            return List.of();
        }
        if (queryVec == null) {
            return List.of();
        }

        // 2. 并行检索
        List<VectorStoreService.DocSearchResult> docResults =
                vectorStoreService.searchDocumentChunks(userId, queryVec, DOC_TOP_K);
        List<VectorStoreService.MemorySearchResult> memResults =
                vectorStoreService.searchUserMemory(userId, queryVec, MEM_TOP_K);

        // 3. 合并排序
        List<RetrievedDoc> merged = new ArrayList<>();
        for (var dr : docResults) {
            merged.add(new RetrievedChunk(dr.chunk(), dr.similarity()));
        }
        for (var mr : memResults) {
            merged.add(new RetrievedMemory(mr.memory(), mr.similarity()));
        }
        merged.sort(Comparator.comparingDouble(RetrievedDoc::similarity).reversed());

        log.info("[RAG] 检索完成 | userId={} | query=\"{}\" | results={} (doc={} mem={})",
                userId, query.length() > 50 ? query.substring(0, 50) + "..." : query,
                merged.size(), docResults.size(), memResults.size());
        return merged;
    }

    /**
     * 检查是否有相关结果
     */
    public boolean hasRelevant(List<RetrievedDoc> docs) {
        return docs != null && !docs.isEmpty();
    }
}
