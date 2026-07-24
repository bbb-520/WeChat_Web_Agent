package log.demo.linkDemo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.demo.linkDemo.entity.IdiomGameRecord;
import log.demo.linkDemo.mapper.IdiomGameRecordMapper;
import log.demo.linkDemo.service.IIdiomGameRecordService;
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
