package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.mapper.DocumentOutlineMapper;
import log.summer.aigc.service.IDocumentOutlineService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class DocumentOutlineServiceImpl
        extends ServiceImpl<DocumentOutlineMapper, DocumentOutline>
        implements IDocumentOutlineService {

    @Override
    public List<DocumentOutline> getByConversationId(Long conversationId) {
        return list(new LambdaQueryWrapper<DocumentOutline>()
                .eq(DocumentOutline::getConversationId, conversationId)
                .orderByDesc(DocumentOutline::getCreatedAt));
    }

    @Override
    public List<DocumentOutline> getByUserId(String userId, int limit) {
        return list(new LambdaQueryWrapper<DocumentOutline>()
                .eq(DocumentOutline::getUserId, userId)
                .orderByDesc(DocumentOutline::getCreatedAt)
                .last("LIMIT " + limit));
    }

    @Override
    public boolean confirmOutline(Long outlineId, String modifiedOutlineData) {
        DocumentOutline outline = getById(outlineId);
        if (outline == null) return false;

        int currentVersion = outline.getVersion() == null ? 0 : outline.getVersion();
        int newVersion = currentVersion + 1;

        var update = lambdaUpdate()
                .set(DocumentOutline::getVersion, newVersion)
                .set(DocumentOutline::getUpdatedAt, LocalDateTime.now())
                .eq(DocumentOutline::getId, outlineId)
                .eq(DocumentOutline::getVersion, currentVersion);

        if (modifiedOutlineData != null) {
            update.set(DocumentOutline::getOutlineData, modifiedOutlineData);
            update.set(DocumentOutline::getStatus, "MODIFIED");
        } else {
            update.set(DocumentOutline::getStatus, "CONFIRMED");
        }

        return update.update();
    }

    @Override
    public boolean updateStatus(Long outlineId, String status) {
        return lambdaUpdate()
                .set(DocumentOutline::getStatus, status)
                .set(DocumentOutline::getUpdatedAt, LocalDateTime.now())
                .eq(DocumentOutline::getId, outlineId)
                .update();
    }
}
