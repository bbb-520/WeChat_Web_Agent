package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("timbre_change")
public class TimbreChange {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Long conversationId;
    private String oldVoiceId;
    private String newVoiceId;
    private String newDisplayName;
    private String changeSource;    // command / intent / keyword
    private LocalDateTime createdAt;
}
