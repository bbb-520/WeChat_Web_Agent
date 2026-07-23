package log.demo.linkDemo.service;

/**
 * 消息发送接口 —— 业务编排层与微信 ILink 客户端之间的抽象。
 * {@code ILinkBotService} 实现此接口，将抽象方法转化为 {@code ILinkClient} 原生调用。
 *
 * @author bbb
 * @since 2026-07-20
 */
public interface MessageSender {

    /** 发送文本消息（含 typing 指示器效果）。 */
    void sendText(String userId, String content);

    /** 发送图片消息。 */
    void sendImage(String userId, byte[] imageBytes, String filename, String description);

    /** 发送文件消息（如 WAV 音频等）。 */
    void sendFile(String userId, byte[] fileBytes, String filename, String description);
}
