package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.FileRecord;
import log.summer.aigc.mapper.FileRecordMapper;
import log.summer.aigc.service.IFileRecordService;
import org.springframework.stereotype.Service;

@Service
public class FileRecordServiceImpl
        extends ServiceImpl<FileRecordMapper, FileRecord>
        implements IFileRecordService {
}
