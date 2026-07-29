package log.summer.aigc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.summer.aigc.entity.DocumentOutline;

import java.util.List;

public interface IDocumentOutlineService extends IService<DocumentOutline> {

    /** Query outlines for a conversation. */
    List<DocumentOutline> getByConversationId(Long conversationId);

    /** Query outlines for a user, latest first. */
    List<DocumentOutline> getByUserId(String userId, int limit);

    /** Confirm and optionally update an outline, advancing version. */
    boolean confirmOutline(Long outlineId, String modifiedOutlineData);

    /** Update outline status (DRAFT -> CONFIRMED -> GENERATING -> DONE). */
    boolean updateStatus(Long outlineId, String status);
}
