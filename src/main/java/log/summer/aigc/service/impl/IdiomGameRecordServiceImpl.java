package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.IdiomGameRecord;
import log.summer.aigc.mapper.IdiomGameRecordMapper;
import log.summer.aigc.service.IIdiomGameRecordService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IdiomGameRecordServiceImpl
        extends ServiceImpl<IdiomGameRecordMapper, IdiomGameRecord>
        implements IIdiomGameRecordService {

    @Override
    public List<IdiomGameRecord> getRecentByUser(String userId, int limit) {
        return list(new LambdaQueryWrapper<IdiomGameRecord>()
                .eq(IdiomGameRecord::getUserId, userId)
                .orderByDesc(IdiomGameRecord::getCreatedAt)
                .last("LIMIT " + limit));
    }
}
