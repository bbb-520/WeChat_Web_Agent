package log.demo.linkDemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.demo.linkDemo.entity.WeatherQuery;
import log.demo.linkDemo.mapper.WeatherQueryMapper;
import log.demo.linkDemo.service.IWeatherQueryService;
import org.springframework.stereotype.Service;

@Service
public class WeatherQueryServiceImpl
        extends ServiceImpl<WeatherQueryMapper, WeatherQuery>
        implements IWeatherQueryService {
}
