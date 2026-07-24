package log.demo.linkDemo.tools;

import log.demo.linkDemo.enums.RouteContext;
import log.demo.linkDemo.service.MessageSender;

/**
 * Agent 上下文 —— 封装一次智能体调用的全部入参。
 *
 * <p>与旧 {@code ToolContext} 的区别：新增 {@link #intent} 字段，
 * 由 {@link IntentClassifier} 在路由前填充，Agent 据此判断是否处理。</p>
 *
 * @author bbb
 * @since 2026-07-23
 */
public class AgentContext {

    private final String userId;
    private String text;  // mutable: allows VoiceAgent to strip prefix and continue chain
    private final Intent intent;
    private final RouteContext routeContext;
    private final byte[] imageBytes;
    private final byte[] fileBytes;
    private final String fileName;
    private final MessageSender sender;
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
