package log.demo.linkDemo.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import log.demo.linkDemo.entity.DocumentChunk;
import log.demo.linkDemo.mapper.DocumentChunkMapper;
import log.demo.linkDemo.service.IDocumentChunkService;
import org.springframework.stereotype.Service;

@Service
public class DocumentChunkServiceImpl
        extends ServiceImpl<DocumentChunkMapper, DocumentChunk>
        implements IDocumentChunkService {
}
