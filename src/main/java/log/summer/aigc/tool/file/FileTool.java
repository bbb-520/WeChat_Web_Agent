package log.summer.aigc.tool.file;

import jakarta.annotation.PostConstruct;
import log.summer.aigc.loop.ActResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 文件处理工具 —— Tika 文本提取 + AI 分析 + RAG 切片。
 *
 * @author bbb
 * @since 2026-07-28
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileTool {

    private final FileRecognitionService fileRecognitionService;

    @PostConstruct
    void init() {
        log.info("[FILE-TOOL] ✅ 已初始化 | deps: FileRecognitionService={}",
                fileRecognitionService != null);
    }

    @Tool(name = "file_analyze", description = "分析上传的文件内容")
    public ActResult analyzeFile(
            @ToolParam(description = "文件的字节数据") byte[] fileBytes,
            @ToolParam(description = "文件名（含扩展名）") String fileName) {

        if (fileBytes == null || fileBytes.length == 0) {
            return ActResult.failure("文件数据为空");
        }
        if (fileName == null || fileName.isBlank()) {
            return ActResult.failure("文件名不能为空");
        }

        try {
            log.info("[FILE] 开始分析 | fileName={} | size={}bytes", fileName, fileBytes.length);
            String result = fileRecognitionService.recognize("default", fileBytes, fileName);
            return ActResult.success(result);
        } catch (Exception e) {
            log.error("[FILE] 分析失败 | fileName={}", fileName, e);
            return ActResult.failure("文件分析失败：" + e.getMessage());
        }
    }
}
