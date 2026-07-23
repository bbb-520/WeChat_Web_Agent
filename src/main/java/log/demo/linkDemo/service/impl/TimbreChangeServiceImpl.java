package log.demo.linkDemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.demo.linkDemo.entity.TimbreChange;
import log.demo.linkDemo.mapper.TimbreChangeMapper;
import log.demo.linkDemo.service.ITimbreChangeService;
import org.springframework.stereotype.Service;

@Service
public class TimbreChangeServiceImpl
        extends ServiceImpl<TimbreChangeMapper, TimbreChange>
        implements ITimbreChangeService {
}
