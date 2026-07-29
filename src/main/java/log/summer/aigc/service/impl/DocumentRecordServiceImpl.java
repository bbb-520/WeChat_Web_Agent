package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.DocumentRecord;
import log.summer.aigc.mapper.DocumentRecordMapper;
import log.summer.aigc.service.IDocumentRecordService;
import org.springframework.stereotype.Service;

@Service
public class DocumentRecordServiceImpl
        extends ServiceImpl<DocumentRecordMapper, DocumentRecord>
        implements IDocumentRecordService {

    @Override
    public DocumentRecord findByIdempotencyKey(String idempotencyKey) {
        return getOne(new LambdaQueryWrapper<DocumentRecord>()
                .eq(DocumentRecord::getIdempotencyKey, idempotencyKey));
    }
}
