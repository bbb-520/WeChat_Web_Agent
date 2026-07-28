package log.summer.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.summer.aigc.entity.Message;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {
}
