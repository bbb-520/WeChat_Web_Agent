package log.demo.linkDemo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import log.demo.linkDemo.entity.IdiomGameRecord;

import java.util.List;

public interface IIdiomGameRecordService extends IService<IdiomGameRecord> {

    /** 查询用户最近 N 局记录（按时间降序） */
    List<IdiomGameRecord> getRecentByUser(String userId, int limit);
}
