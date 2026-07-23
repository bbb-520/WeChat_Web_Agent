package log.demo.linkDemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("image_context")
public class ImageRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String messageId;
    private String imageUrl;            // CDN URL
    private Long imageSize;             // 字节
    private String description;         // AI 分析结果
    private String editInstruction;     // 编辑指令
    private Integer isRefImage;         // 0/1
    private LocalDateTime expiredAt;
    private LocalDateTime createdAt;
}
