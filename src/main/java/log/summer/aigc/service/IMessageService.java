package log.summer.aigc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.summer.aigc.entity.Message;

import java.util.List;

public interface IMessageService extends IService<Message> {

    List<Message> getMessages(Long convId);

    long countMessages(String userId);
}
