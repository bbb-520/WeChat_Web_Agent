package log.demo.linkDemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.demo.linkDemo.entity.FileRecord;
import log.demo.linkDemo.mapper.FileRecordMapper;
import log.demo.linkDemo.service.IFileRecordService;
import org.springframework.stereotype.Service;

@Service
public class FileRecordServiceImpl
        extends ServiceImpl<FileRecordMapper, FileRecord>
        implements IFileRecordService {
}
