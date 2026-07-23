package log.demo.linkDemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.demo.linkDemo.entity.UserMemory;
import log.demo.linkDemo.mapper.UserMemoryMapper;
import log.demo.linkDemo.service.IUserMemoryService;
import org.springframework.stereotype.Service;

@Service
public class UserMemoryServiceImpl
        extends ServiceImpl<UserMemoryMapper, UserMemory>
        implements IUserMemoryService {
}
