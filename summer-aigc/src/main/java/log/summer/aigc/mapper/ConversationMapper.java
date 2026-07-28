package log.summer.aigc.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.summer.aigc.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
