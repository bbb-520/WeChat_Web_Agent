package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("file_record")
public class FileRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String messageId;
    private Long conversationId;
    private String fileName;
    private Long fileSize;
    private String mimeType;
    private String fileTypeLabel;
    private String md5Hash;
    private String extractedText;
    private Integer textLength;
    private String aiAnalysis;
    private Integer analysisElapsedMs;
    private String status;      // PENDING / EXTRACTING / ANALYZING / SUCCESS / FAILED
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
