package log.summer.aigc.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.summer.aigc.entity.DocumentChunk;
import log.summer.aigc.mapper.DocumentChunkMapper;
import log.summer.aigc.service.IDocumentChunkService;
import org.springframework.stereotype.Service;

@Service
public class DocumentChunkServiceImpl
        extends ServiceImpl<DocumentChunkMapper, DocumentChunk>
        implements IDocumentChunkService {
}
