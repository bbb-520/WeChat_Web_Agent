package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.Message;
import log.summer.aigc.mapper.MessageMapper;
import log.summer.aigc.service.IMessageService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageServiceImpl
        extends ServiceImpl<MessageMapper, Message>
        implements IMessageService {

    @Override
    public List<Message> getMessages(Long convId) {
        return list(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, convId)
                .orderByAsc(Message::getCreatedAt));
    }

    @Override
    public long countMessages(String userId) {
        return count(new LambdaQueryWrapper<Message>()
                .eq(Message::getUserId, userId));
    }
}
