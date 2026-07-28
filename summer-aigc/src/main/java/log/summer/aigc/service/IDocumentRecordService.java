package log.summer.aigc.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.summer.aigc.entity.DocumentRecord;

public interface IDocumentRecordService extends IService<DocumentRecord> {

    /** Find an existing document record by idempotency key. */
    DocumentRecord findByIdempotencyKey(String idempotencyKey);
}
