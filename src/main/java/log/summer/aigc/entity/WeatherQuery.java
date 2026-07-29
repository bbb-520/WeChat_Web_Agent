package log.summer.aigc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("weather_query")
public class WeatherQuery {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String userId;
    private String messageId;
    private String queryText;       // 用户查询原文
    private String city;            // 提取的城市名
    private String queryType;       // now / forecast / multi-day
    private String apiResponse;     // API 原始 JSON
    private String reportText;      // 生成的报告文本
    private Integer apiElapsedMs;   // API 耗时
    private String status;          // SUCCESS / FAILED
    private LocalDateTime createdAt;
}
