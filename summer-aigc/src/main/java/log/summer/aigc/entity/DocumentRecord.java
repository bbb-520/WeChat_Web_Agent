package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_record")
public class DocumentRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long conversationId;
    private Long outlineId;
    private String documentType;        // WORD / PPT / EXCEL
    private String fileName;            // 生成的文件名
    private String filePath;            // 文件存储路径（可为空，直接下发 bytes）
    private Long fileSize;              // 文件大小（字节）
    private String status;              // GENERATING / GENERATED / FAILED
    private String idempotencyKey;      // 幂等键: outlineId_type_version
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
