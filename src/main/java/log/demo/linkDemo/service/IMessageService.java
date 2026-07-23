package log.demo.linkDemo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.demo.linkDemo.entity.Message;

import java.util.List;

public interface IMessageService extends IService<Message> {

    List<Message> getMessages(Long convId);

    long countMessages(String userId);
}
