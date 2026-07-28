package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.WeatherQuery;
import log.summer.aigc.mapper.WeatherQueryMapper;
import log.summer.aigc.service.IWeatherQueryService;
import org.springframework.stereotype.Service;

@Service
public class WeatherQueryServiceImpl
        extends ServiceImpl<WeatherQueryMapper, WeatherQuery>
        implements IWeatherQueryService {
}
