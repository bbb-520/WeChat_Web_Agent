package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.TimbreChange;
import log.summer.aigc.mapper.TimbreChangeMapper;
import log.summer.aigc.service.ITimbreChangeService;
import org.springframework.stereotype.Service;

@Service
public class TimbreChangeServiceImpl
        extends ServiceImpl<TimbreChangeMapper, TimbreChange>
        implements ITimbreChangeService {
}
