package log.demo.linkDemo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 成语接龙游戏记录。
 *
 * @author bbb
 * @since 2026-07-24
 */
@Data
@TableName("idiom_game_record")
public class IdiomGameRecord {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private Integer score;          // 最终积分
    private Integer rounds;         // 总轮数
    private String endReason;       // USER_WIN / USER_STOP / TIMEOUT
    private LocalDateTime createdAt;
}
