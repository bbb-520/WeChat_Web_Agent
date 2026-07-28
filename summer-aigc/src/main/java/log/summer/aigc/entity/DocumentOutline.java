package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档大纲实体 —— 记录用户生成的文档大纲，支持 createOutline -> confirm -> generateDocument 流程。
 *
 * @author bbb
 * @since 2026-07-29
 */
@Data
@TableName("document_outline")
public class DocumentOutline {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long conversationId;
    private String outlineType;       // WORD / PPT / EXCEL
    private String title;              // 文档标题
    private String outlineData;        // JSON: 结构化大纲
    private String status;             // DRAFT / CONFIRMED / MODIFIED / GENERATING / DONE
    private Integer version;           // 修改版本号
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
