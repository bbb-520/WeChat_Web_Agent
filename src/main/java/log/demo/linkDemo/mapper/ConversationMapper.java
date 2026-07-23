package log.demo.linkDemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.demo.linkDemo.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
