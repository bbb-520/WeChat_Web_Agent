package log.summer.aigc.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 文档切片服务 —— 递归语义文本切分。
 *
 * <p>策略（由粗到细）：</p>
 * <ol>
 *   <li>按段落（双换行 / 连续空行）切分</li>
 *   <li>超过 maxChars 的段落按句子终结符（。！？.!? + 换行）切分</li>
 *   <li>仍超长的强制按 maxChars 定长切分，相邻块 overlapChars 重叠</li>
 * </ol>
 *
 * <p>默认: maxChars=500, overlapChars=100。适合中文为主的文档。</p>
 *
 * @author bbb
 * @since 2026-07-23
 */
@Slf4j
@Service
public class DocumentChunkingService {

    private static final int DEFAULT_MAX_CHARS = 500;
    private static final int DEFAULT_OVERLAP = 100;
    /** 句子终结符 */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile(
            "(?<=[。！？.!?\n])(?=\\S)");

    /**
     * 切片结果
     */
    public record Chunk(int index, String text) {}

    /**
     * 使用默认参数切片（500 字/块，100 字重叠）
     */
    public List<Chunk> chunk(String text) {
        return chunk(text, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP);
    }

    /**
     * @param text     原始文本
     * @param maxChars 单块最大字符数
     * @param overlap  相邻块重叠字符数
     */
    public List<Chunk> chunk(String text, int maxChars, int overlap) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        // Step 1: 按段落切
        List<String> paragraphs = splitByParagraph(text);
        List<String> rawChunks = new ArrayList<>();

        // Step 2: 对每个段落进一步拆分
        for (String para : paragraphs) {
            if (para.length() <= maxChars) {
                rawChunks.add(para);
            } else {
                // Step 2a: 按句子切
                List<String> sentences = splitBySentence(para);
                for (String sent : sentences) {
                    if (sent.length() <= maxChars) {
                        rawChunks.add(sent);
                    } else {
                        // Step 2b: 强制定长切分（带重叠）
                        rawChunks.addAll(splitFixed(sent, maxChars, overlap));
                    }
                }
            }
        }

        // 编号 + 去空白
        List<Chunk> result = new ArrayList<>();
        for (String raw : rawChunks) {
            String t = raw.trim();
            if (!t.isEmpty()) {
                result.add(new Chunk(result.size(), t));
            }
        }
        log.debug("[CHUNK] 切片完成 | textLen={} → chunks={}", text.length(), result.size());
        return result;
    }

    // ── private helpers ──

    /** 按双换行 / 连续空行切段落 */
    private List<String> splitByParagraph(String text) {
        String[] parts = text.split("\\n\\s*\\n");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 按句子终结符切分 */
    private List<String> splitBySentence(String text) {
        String[] parts = SENTENCE_SPLIT.split(text);
        List<String> result = new ArrayList<>();
        for (String s : parts) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 固定长度切分（带重叠） */
    private List<String> splitFixed(String text, int maxChars, int overlap) {
        List<String> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChars, text.length());
            result.add(text.substring(start, end));
            if (end >= text.length()) break;
            start = end - overlap;
            // 防止死循环：确保 start 前进
            if (start <= 0 || start >= text.length()) break;
        }
        return result;
    }
}
