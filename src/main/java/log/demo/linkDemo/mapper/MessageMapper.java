package log.demo.linkDemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.demo.linkDemo.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
