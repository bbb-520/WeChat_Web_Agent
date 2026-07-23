package log.demo.linkDemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.demo.linkDemo.entity.WeatherQuery;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WeatherQueryMapper extends BaseMapper<WeatherQuery> {
}
