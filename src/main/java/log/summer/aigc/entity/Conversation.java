package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String sessionId;
    private String title;
    private String routeContext;    // TEXT / VOICE
    private Integer status;            // 0=已结束 1=进行中 2=挂起等待用户输入
    private Integer messageCount;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // ── NEW: suspend support ──
    private String suspendContext;     // JSON: serialized SuspendContext (toolName, reason, message snapshots)
    private String suspendReason;      // Human-readable, e.g. "等待确认大纲"
}
