package log.demo.linkDemo.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 上下文增强器 —— 将检索到的文档/记忆注入到系统提示词中，
 * 使 AI 能够基于检索到的上下文回答问题。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Component
public class RAGContextAugmenter {

    /**
     * 构建 RAG 增强后的用户消息。
     *
     * <p>格式：</p>
     * <pre>
     * 以下是与用户问题相关的参考信息，请基于这些信息回答问题。
     * 如果参考信息不足以回答问题，请诚实说明，不要编造。
     *
     * ---
     * [参考文档 1] (相似度: 92%)
     * ...文档切片内容...
     * ---
     * [历史记忆 1] (相似度: 85%)
     * ...历史记忆内容...
     * ---
     *
     * 用户问题：...
     * </pre>
     *
     * @param userQuery 用户原始问题
     * @param docs      检索到的相关文档（已按相似度排序）
     * @return 增强后的 prompt，可直接作为 ChatClient 的 user message
     */
    public String buildAugmentedUserMessage(String userQuery,
                                             List<RAGRetrievalService.RetrievedDoc> docs) {
        if (docs == null || docs.isEmpty()) {
            return userQuery;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("以下是与用户问题相关的参考信息，请基于这些信息回答问题。");
        sb.append("如果参考信息不足以回答问题，请诚实说明，不要编造。\n");

        int docIdx = 0, memIdx = 0;
        for (var doc : docs) {
            sb.append("\n---\n");
            int pct = Math.round(doc.similarity() * 100);
            switch (doc) {
                case RAGRetrievalService.RetrievedChunk c -> {
                    docIdx++;
                    sb.append("[参考文档 ").append(docIdx).append("]")
                            .append(" (相关性: ").append(pct).append("%)\n");
                    sb.append(c.displayText());
                }
                case RAGRetrievalService.RetrievedMemory m -> {
                    memIdx++;
                    sb.append("[历史记忆 ").append(memIdx).append("]")
                            .append(" (相关性: ").append(pct).append("%)\n");
                    sb.append(m.displayText());
                }
            }
        }

        sb.append("\n\n---\n");
        sb.append("用户问题：").append(userQuery);

        log.debug("[RAG-AUGMENT] 构建增强 prompt | queryLen={} | docCount={} | memCount={} | totalLen={}",
                userQuery.length(), docIdx, memIdx, sb.length());
        return sb.toString();
    }

    /**
     * 为 RAG 场景构建独立的系统提示词。
     */
    public String buildRAGSystemPrompt(String baseSystemPrompt) {
        return baseSystemPrompt + "\n\n注意：用户消息中可能包含「参考信息」部分。" +
                "请优先使用参考信息中的内容来回答问题。回答时不要编造参考信息中不存在的事实。";
    }
}
