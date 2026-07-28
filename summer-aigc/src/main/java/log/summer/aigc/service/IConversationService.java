package log.summer.aigc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.summer.aigc.entity.Conversation;

import java.util.List;

public interface IConversationService extends IService<Conversation> {

    void updateTitle(Long convId, String text);

    void closeConversation(Long convId);

    Conversation getActiveConversation(String userId);

    /** 查询用户最近 N 个会话（按时间降序） */
    List<Conversation> getRecentByUser(String userId, int limit);

    /** 查询用户全部会话（按时间降序） */
    List<Conversation> getAllByUser(String userId);

    /** 查询指定会话 */
    Conversation getByConvId(Long convId);

    /** Query the latest suspended conversation for a user. */
    Conversation getSuspendedByUser(String userId);

    /** Persist suspend context and set status=2. */
    void updateSuspend(Long convId, String suspendContext, String suspendReason);

    /** Clear suspend state and set status back to 1 (active). */
    void clearSuspend(Long convId);
}
