package log.demo.linkDemo.exception;

import log.demo.linkDemo.service.MessageSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 全局异常拦截器 —— 统一捕获 Agent 链路中的未处理异常，返回标准化的错误响应。
 *
 * <h3>职责</h3>
 * <ol>
 *   <li>捕获 {@link BotException} 及其子类，提取结构化错误信息</li>
 *   <li>捕获未预期的 {@link RuntimeException} 和 {@link Exception}，防止裸堆栈暴露给用户</li>
 *   <li>统一格式化错误输出，向用户发送友好的中文提示</li>
 *   <li>记录完整异常日志用于排查</li>
 * </ol>
 *
 * <h3>错误响应格式</h3>
 * <pre>{@code
 * 【操作失败】
 * ──────────────
 * 错误类型：图片生成失败
 * 详情：AI 服务暂时不可用，请稍后重试
 * 时间：2026-07-24 15:30:00
 * 追踪ID：err-a1b2c3d4
 * ──────────────
 * 如需帮助，请输入 /help 查看可用命令
 * }</pre>
 *
 * @author bbb
 * @since 2026-07-24
 */
@Slf4j
@Component
public class GlobalExceptionHandler {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 处理异常并发送统一格式的错误响应给用户。
     *
     * @param userId    用户 ID
     * @param sender    消息发送器
     * @param operation 当前操作描述（如 "图片生成"、"天气查询"）
     * @param e         异常
     */
    public void handle(String userId, MessageSender sender,
                       String operation, Exception e) {
        String trackingId = "err-" + Integer.toHexString(
                System.identityHashCode(e) & 0xFFFF);

        // 结构化错误信息提取
        ErrorInfo info = classify(e);

        // 日志记录（完整堆栈）
        if (info.severity == Severity.WARN) {
            log.warn("[GLOBAL-ERROR] {} | userId={} | trackingId={} | type={} | detail={}",
                    operation, userId, trackingId, info.errorType, info.detail);
        } else {
            log.error("[GLOBAL-ERROR] {} | userId={} | trackingId={} | type={} | detail={}",
                    operation, userId, trackingId, info.errorType, info.detail, e);
        }

        // 构造用户可见的错误消息
        String message = buildErrorMessage(info, operation, trackingId);

        // 发送给用户
        try {
            sender.sendText(userId, message);
        } catch (Exception sendError) {
            log.error("[GLOBAL-ERROR] 无法发送错误消息 | userId={}", userId, sendError);
        }
    }

    /**
     * 分类异常，提取用户友好的错误类型和详情。
     */
    private ErrorInfo classify(Exception e) {
        if (e instanceof AIServiceException ai) {
            String friendlyType = switch (ai.getOperation()) {
                case "chat" -> "AI 对话失败";
                case "analyzeImage" -> "图片识别失败";
                case "analyzeDocument" -> "文档分析失败";
                case "describeImageEdit" -> "图片编辑理解失败";
                case "chatWithRAG" -> "知识库对话失败";
                default -> "AI 服务异常";
            };
            return new ErrorInfo(friendlyType,
                    ai.getMessage() != null ? ai.getMessage() : "AI 服务暂时不可用",
                    Severity.WARN);

        } else if (e instanceof ImageGenerationException ig) {
            String friendlyType = switch (ig.getStage()) {
                case "ai-describe" -> "图片编辑理解失败";
                case "generate" -> "图片生成失败";
                case "download" -> "图片下载失败";
                default -> "图片处理异常";
            };
            return new ErrorInfo(friendlyType,
                    ig.getMessage() != null ? ig.getMessage() : "图片处理失败，请稍后重试",
                    Severity.WARN);

        } else if (e instanceof VoiceSynthesisException vs) {
            return new ErrorInfo("语音生成失败",
                    vs.getMessage() != null ? vs.getMessage() : "语音合成服务暂不可用",
                    Severity.WARN);

        } else if (e instanceof FileRecognitionException fr) {
            String friendlyType = switch (fr.getStage()) {
                case "detect" -> "文件类型检测失败";
                case "extract" -> "文件内容提取失败";
                case "analyze" -> "文件分析失败";
                default -> "文件处理异常";
            };
            return new ErrorInfo(friendlyType,
                    fr.getMessage() != null ? fr.getMessage() : "文件处理失败",
                    Severity.WARN);

        } else if (e instanceof ConfigurationException ce) {
            return new ErrorInfo("配置错误",
                    "服务配置异常，请联系管理员",
                    Severity.ERROR);

        } else if (e instanceof BotException be) {
            return new ErrorInfo("服务异常",
                    be.getMessage() != null ? be.getMessage() : "服务处理异常，请稍后重试",
                    Severity.WARN);

        } else {
            // 未预期的异常：不暴露原始错误消息给用户
            return new ErrorInfo("系统错误",
                    "系统内部异常，请稍后重试或联系管理员",
                    Severity.ERROR);
        }
    }

    /**
     * 构造统一的用户可见错误消息。
     */
    private String buildErrorMessage(ErrorInfo info, String operation, String trackingId) {
        String time = LocalDateTime.now().format(TIME_FMT);

        StringBuilder sb = new StringBuilder();
        sb.append("【").append(operation).append("失败】\n");
        sb.append("──────────────\n");
        sb.append("错误类型：").append(info.errorType).append("\n");
        sb.append("详情：").append(info.detail).append("\n");
        sb.append("时间：").append(time).append("\n");
        sb.append("追踪ID：").append(trackingId).append("\n");
        sb.append("──────────────\n");
        sb.append("如需帮助，请输入 /help 查看可用命令");

        return sb.toString();
    }

    // ── 内部数据类 ──

    private record ErrorInfo(String errorType, String detail, Severity severity) {}

    private enum Severity { WARN, ERROR }
}
