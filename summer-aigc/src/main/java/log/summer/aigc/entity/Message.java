package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long conversationId;
    private String dialogueId;          // 对话轮次ID
    private String messageType;         // USER / BOT
    private String contentType;         // text / image / voice / file / command
    private String intentType;          // ai-chat / image-gen / image-edit / tts / weather / timbre / file-recognition
    private String textContent;         // 文本内容
    private String mediaUrl;            // 媒体URL
    private Long mediaSize;             // 媒体大小(字节)
    private Integer mediaDurationMs;    // 音频时长(毫秒)
    private String fileName;            // 原始文件名
    private String mimeType;            // MIME类型
    private String processingStatus;    // PENDING / PROCESSING / SUCCESS / FAILED
    private String errorMessage;        // 错误信息
    private String errorStage;          // 失败阶段
    private Integer elapsedMs;          // 处理耗时
    private Integer retryCount;         // 重试次数
    private String requestId;           // API请求ID
    private String commandPrefix;       // /draw /tts /weather 等
    private String voiceId;             // 使用的音色ID
    private String imageUrlGenerated;   // 生成的图片URL
    private String sessionId;           // 图片缓存会话ID
    private LocalDateTime createdAt;
}
