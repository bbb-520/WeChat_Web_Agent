package log.demo.linkDemo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.demo.linkDemo.entity.Conversation;
import log.demo.linkDemo.mapper.ConversationMapper;
import log.demo.linkDemo.service.IConversationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class ConversationServiceImpl
        extends ServiceImpl<ConversationMapper, Conversation>
        implements IConversationService {

    @Override
    public void updateTitle(Long convId, String text) {
        String title = text.length() > 50 ? text.substring(0, 50) : text;
        lambdaUpdate()
                .set(Conversation::getTitle, title)
                .set(Conversation::getUpdatedAt, LocalDateTime.now())
                .eq(Conversation::getId, convId)
                .update();
    }

    @Override
    public void closeConversation(Long convId) {
        lambdaUpdate()
                .set(Conversation::getStatus, 0)
                .set(Conversation::getEndTime, LocalDateTime.now())
                .set(Conversation::getUpdatedAt, LocalDateTime.now())
                .eq(Conversation::getId, convId)
                .update();
    }

    @Override
    public Conversation getActiveConversation(String userId) {
        return getOne(new LambdaQueryWrapper<Conversation>()
                .eq(Conversation::getUserId, userId)
                .eq(Conversation::getStatus, 1)
                .orderByDesc(Conversation::getCreatedAt)
                .last("LIMIT 1"));
    }
}
