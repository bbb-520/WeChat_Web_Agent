package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户历史记忆实体 —— 存储用户对话摘要、偏好、行为等长期记忆，用于跨会话 RAG 检索。
 * embedding 字段以 JSON 数组形式存储 float[] 向量。
 *
 * @author bbb
 * @since 2026-07-23
 */
@Data
@TableName("user_memory")
public class UserMemory {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    /** chat / preference / behavior */
    private String memoryType;
    private String content;
    /** float[] vector serialized as JSON array string; null until embedding generated */
    private String embedding;
    /** importance weight, default 0.5 */
    private Float importance;
    private LocalDateTime createdAt;
}
