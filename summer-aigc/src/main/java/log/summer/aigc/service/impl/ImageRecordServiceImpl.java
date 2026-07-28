package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.ImageRecord;
import log.summer.aigc.mapper.ImageRecordMapper;
import log.summer.aigc.service.IImageRecordService;
import org.springframework.stereotype.Service;

@Service
public class ImageRecordServiceImpl
        extends ServiceImpl<ImageRecordMapper, ImageRecord>
        implements IImageRecordService {
}
