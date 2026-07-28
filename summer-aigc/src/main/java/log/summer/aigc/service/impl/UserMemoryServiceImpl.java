package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.UserMemory;
import log.summer.aigc.mapper.UserMemoryMapper;
import log.summer.aigc.service.IUserMemoryService;
import org.springframework.stereotype.Service;

@Service
public class UserMemoryServiceImpl
        extends ServiceImpl<UserMemoryMapper, UserMemory>
        implements IUserMemoryService {
}
