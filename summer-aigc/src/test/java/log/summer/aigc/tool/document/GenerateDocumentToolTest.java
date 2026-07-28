package log.summer.aigc.tool.document;

import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.entity.DocumentRecord;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.IDocumentOutlineService;
import log.summer.aigc.service.IDocumentRecordService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GenerateDocumentToolTest {

    @Mock
    private DocumentGenerator documentGenerator;
    @Mock
    private IDocumentOutlineService outlineService;
    @Mock
    private IDocumentRecordService documentRecordService;

    private GenerateDocumentTool tool;

    private static final String SAMPLE_OUTLINE = """
        {"title":"Test","sections":[{"title":"S1","points":["p1","p2"]}]}""";

    @BeforeEach
    void setUp() {
        tool = new GenerateDocumentTool(
                documentGenerator, outlineService, documentRecordService);
    }

    @Test
    void shouldGenerateDocumentFromConfirmedOutline() {
        // Arrange: confirmed outline in DB
        DocumentOutline outline = new DocumentOutline();
        outline.setId(100L);
        outline.setUserId("user123");
        outline.setConversationId(1L);
        outline.setOutlineType("PPT");
        outline.setTitle("Q2报告");
        outline.setOutlineData(SAMPLE_OUTLINE);
        outline.setStatus("CONFIRMED");
        outline.setVersion(1);
        outline.setCreatedAt(LocalDateTime.now());
        outline.setUpdatedAt(LocalDateTime.now());

        when(outlineService.getById(100L)).thenReturn(outline);
        when(documentGenerator.generatePpt(eq("Q2报告"), anyString()))
                .thenReturn(new byte[]{0x50, 0x4B, 0x03, 0x04}); // OOXML magic bytes
        when(documentRecordService.save(any())).thenReturn(true);

        // Act
        ActResult result = tool.generateDocument(
                "PPT", 100L, "user123", 1L);

        // Assert
        assertTrue(result.success());
        assertFalse(result.suspend());
        assertNotNull(result.data());
        verify(outlineService).updateStatus(100L, "DONE");
        verify(documentRecordService).save(any());
    }

    @Test
    void shouldReturnExistingDocumentWhenIdempotent() {
        // Arrange: outline + existing record
        DocumentOutline outline = new DocumentOutline();
        outline.setId(100L);
        outline.setUserId("user123");
        outline.setConversationId(1L);
        outline.setOutlineType("WORD");
        outline.setTitle("报告");
        outline.setOutlineData(SAMPLE_OUTLINE);
        outline.setStatus("CONFIRMED");
        outline.setVersion(1);

        DocumentRecord existingRecord = new DocumentRecord();
        existingRecord.setId(200L);
        existingRecord.setIdempotencyKey("100_WORD_1");
        existingRecord.setFileName("报告.docx");
        existingRecord.setStatus("GENERATED");

        when(outlineService.getById(100L)).thenReturn(outline);
        when(documentRecordService.findByIdempotencyKey("100_WORD_1"))
                .thenReturn(existingRecord);

        // Act
        ActResult result = tool.generateDocument(
                "WORD", 100L, "user123", 1L);

        // Assert: should return existing record, NOT regenerate
        assertTrue(result.success());
        verify(documentGenerator, never()).generateWord(anyString(), anyString());
        verify(outlineService, never()).updateStatus(anyLong(), anyString());
    }

    @Test
    void shouldReturnFailureForMissingOutline() {
        when(outlineService.getById(999L)).thenReturn(null);

        ActResult result = tool.generateDocument(
                "PPT", 999L, "user123", 1L);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("未找到"));
    }

    @Test
    void shouldReturnFailureForUnconfirmedOutline() {
        DocumentOutline outline = new DocumentOutline();
        outline.setId(100L);
        outline.setStatus("DRAFT");
        when(outlineService.getById(100L)).thenReturn(outline);

        ActResult result = tool.generateDocument(
                "PPT", 100L, "user123", 1L);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("确认"));
    }
}
