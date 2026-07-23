package log.demo.linkDemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.demo.linkDemo.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMemoryMapper extends BaseMapper<UserMemory> {
}
