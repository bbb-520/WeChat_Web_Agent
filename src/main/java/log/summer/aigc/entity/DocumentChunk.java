package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档切片实体 —— 文件上传后经文本提取、智能切片、向量化后存入此表。
 * embedding 字段以 JSON 数组形式存储 float[] 向量。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Data
@TableName("document_chunks")
public class DocumentChunk {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fileRecordId;
    private String userId;
    private Integer chunkIndex;
    private String chunkText;
    private Integer chunkSize;
    /** float[] vector serialized as JSON array string; null until embedding generated */
    private String embedding;
    private LocalDateTime createdAt;
}
