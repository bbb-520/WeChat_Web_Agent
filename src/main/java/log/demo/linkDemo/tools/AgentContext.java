package log.demo.linkDemo.tools;

import log.demo.linkDemo.enums.RouteContext;
import log.demo.linkDemo.service.MessageSender;

/**
 * Agent 上下文 —— 封装一次智能体调用的全部入参。
 * 用于在一次智能体调用中封装所有需要的上下文信息
 *
 * <p>与旧 {@code ToolContext} 的区别：新增 {@link #intent} 字段，
 * 由 {@link IntentClassifier} 在路由前填充，Agent 据此判断是否处理。</p>
 *
 * @author bbb
 * @since 2026-07-23
 */
public class AgentContext {

    //用户ID
    private final String userId;
    //用户发送的文本消息
    private String text;  // mutable: allows VoiceAgent to strip prefix and continue chain
    //已识别的意图枚举
    private final Intent intent;
    //路由场景标记 表示消息来源或类型（例如 TEXT、VOICE 等），可影响 Agent 的响应方式。若未指定，默认为 TEXT
    private final RouteContext routeContext;
    //用户上传的图片原始字节数据，为 null 表示没有图片。
    private final byte[] imageBytes;
    //用户上传的文件原始字节数据，为 null 表示没有文件。
    private final byte[] fileBytes;
    //上传文件的原始文件名，可用于日志记录或展示给用户
    private final String fileName;
    //消息发送器，Agent 执行结束后通过它向用户回复文本、图片等结果，是 Agent 与外部通信的唯一出口
    private final MessageSender sender;
    //流程是否继续的标志。在某些异步或流式场景下，可被设置为 false 来提前终止后续处理，默认为 true。
    private final boolean isRunning;

    private AgentContext(Builder builder) {
        this.userId = builder.userId;
        this.text = builder.text;
        this.intent = builder.intent != null ? builder.intent : Intent.CHAT;
        this.routeContext = builder.routeContext != null ? builder.routeContext : RouteContext.TEXT;
        this.imageBytes = builder.imageBytes;
        this.fileBytes = builder.fileBytes;
        this.fileName = builder.fileName;
        this.sender = builder.sender;
        this.isRunning = builder.isRunning;
    }

    // ── Getters ──

    public String userId() { return userId; }
    public String text() { return text; }
    public Intent intent() { return intent; }
    public RouteContext routeContext() { return routeContext; }
    public byte[] imageBytes() { return imageBytes; }
    public byte[] fileBytes() { return fileBytes; }
    public String fileName() { return fileName; }
    public MessageSender sender() { return sender; }
    public boolean isRunning() { return isRunning; }

    public boolean hasImage() { return imageBytes != null && imageBytes.length > 0; }
    public boolean hasFile() { return fileBytes != null && fileBytes.length > 0; }
    public boolean hasText() { return text != null && !text.isBlank(); }

    public void setText(String text) { this.text = text; }

    // ── Builder ──

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String userId;
        private String text;
        private Intent intent;
        private RouteContext routeContext = RouteContext.TEXT;
        private byte[] imageBytes;
        private byte[] fileBytes;
        private String fileName;
        private MessageSender sender;
        private boolean isRunning = true;

        public Builder userId(String v) { userId = v; return this; }
        public Builder text(String v) { text = v; return this; }
        public Builder intent(Intent v) { intent = v; return this; }
        public Builder routeContext(RouteContext v) { routeContext = v; return this; }
        public Builder imageBytes(byte[] v) { imageBytes = v; return this; }
        public Builder fileBytes(byte[] v) { fileBytes = v; return this; }
        public Builder fileName(String v) { fileName = v; return this; }
        public Builder sender(MessageSender v) { sender = v; return this; }
        public Builder isRunning(boolean v) { isRunning = v; return this; }

        public AgentContext build() {
            if (userId == null) throw new IllegalArgumentException("userId is required");
            if (sender == null) throw new IllegalArgumentException("sender is required");
            return new AgentContext(this);
        }
    }
}
