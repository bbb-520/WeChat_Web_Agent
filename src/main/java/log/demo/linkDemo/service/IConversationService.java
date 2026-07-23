package log.demo.linkDemo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.demo.linkDemo.entity.Conversation;

public interface IConversationService extends IService<Conversation> {

    void updateTitle(Long convId, String text);

    void closeConversation(Long convId);

    Conversation getActiveConversation(String userId);
}
