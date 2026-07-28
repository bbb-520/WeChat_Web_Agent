package log.summer.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.summer.aigc.entity.WeatherQuery;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WeatherQueryMapper extends BaseMapper<WeatherQuery> {
}
