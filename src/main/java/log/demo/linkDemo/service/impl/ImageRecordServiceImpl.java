package log.demo.linkDemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.demo.linkDemo.entity.ImageRecord;
import log.demo.linkDemo.mapper.ImageRecordMapper;
import log.demo.linkDemo.service.IImageRecordService;
import org.springframework.stereotype.Service;

@Service
public class ImageRecordServiceImpl
        extends ServiceImpl<ImageRecordMapper, ImageRecord>
        implements IImageRecordService {
}
