package log.demo.linkDemo.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import log.demo.linkDemo.entity.FileRecord;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FileRecordMapper extends BaseMapper<FileRecord> {
}
