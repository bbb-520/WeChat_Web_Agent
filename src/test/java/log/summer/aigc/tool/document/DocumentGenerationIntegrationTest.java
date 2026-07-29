package log.summer.aigc.tool.document;

import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.entity.DocumentOutline;
import log.summer.aigc.entity.DocumentRecord;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.ChatPersistenceService;
import log.summer.aigc.service.IDocumentOutlineService;
import log.summer.aigc.service.IDocumentRecordService;
import log.summer.aigc.tool.outline.CreateOutlineTool;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.Mockito.*;

/**
 * End-to-end integration test: outline creation -> confirmation -> document generation.
 */
@ExtendWith(MockitoExtension.class)
class DocumentGenerationIntegrationTest {

    @Mock
    private IDocumentOutlineService outlineService;
    @Mock
    private IDocumentRecordService documentRecordService;
    @Mock
    private ChatPersistenceService persistence;

    private DocumentGenerator generator;
    private CreateOutlineTool createOutlineTool;
    private GenerateDocumentTool generateDocumentTool;

    private static final String OUTLINE_JSON = """
        {
          "title": "Q2业绩报告",
          "sections": [
            {"title": "业绩概览", "points": ["总营收 5000万", "同比增长 15%"]},
            {"title": "各部门分析", "points": ["销售部 120%达成", "研发部 3个项目上线"]}
          ]
        }""";

    @BeforeEach
    void setUp() {
        generator = new DocumentGenerator();
        createOutlineTool = new CreateOutlineTool(outlineService, persistence);
        generateDocumentTool = new GenerateDocumentTool(
                generator, outlineService, documentRecordService);
        // userId / conversationId are sourced from UserContextHolder
        // (populated by AgentLoop in production).
        UserContextHolder.setUserId("user123");
        UserContextHolder.setConversationId(1L);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    @Test
    void fullWorkflowOutlineConfirmGenerateWord() {
        // -- Step 1: Create outline (LLM calls createOutline) --
        when(outlineService.save(any())).thenReturn(true);

        ActResult outlineResult = createOutlineTool.createOutline(
                "WORD", "Q2业绩报告", OUTLINE_JSON);

        assertTrue(outlineResult.suspend());
        assertTrue(outlineResult.requiresConfirmation());
        verify(outlineService).save(any());

        // -- Step 2: User confirms, outline status updated --
        DocumentOutline savedOutline = new DocumentOutline();
        savedOutline.setId(100L);
        savedOutline.setUserId("user123");
        savedOutline.setConversationId(1L);
        savedOutline.setOutlineType("WORD");
        savedOutline.setTitle("Q2业绩报告");
        savedOutline.setOutlineData(OUTLINE_JSON);
        savedOutline.setStatus("CONFIRMED");
        savedOutline.setVersion(1);
        savedOutline.setCreatedAt(LocalDateTime.now());
        savedOutline.setUpdatedAt(LocalDateTime.now());

        when(outlineService.getById(100L)).thenReturn(savedOutline);
        when(documentRecordService.save(any())).thenReturn(true);

        // -- Step 3: Generate document (LLM calls generateDocument after confirmation) --
        ActResult docResult = generateDocumentTool.generateDocument("WORD", 100L);

        assertTrue(docResult.success());
        assertFalse(docResult.suspend());
        verify(outlineService).updateStatus(100L, "DONE");
        verify(documentRecordService).save(any());
    }

    @Test
    void generateDocumentShouldBeIdempotentAcrossMultipleCalls() {
        // Arrange
        DocumentOutline outline = new DocumentOutline();
        outline.setId(100L);
        outline.setUserId("user123");
        outline.setOutlineType("PPT");
        outline.setTitle("报告");
        outline.setOutlineData(OUTLINE_JSON);
        outline.setStatus("CONFIRMED");
        outline.setVersion(1);

        DocumentRecord existing = new DocumentRecord();
        existing.setId(200L);
        existing.setIdempotencyKey("100_PPT_1");
        existing.setFileName("报告.pptx");
        existing.setFileSize(12345L);
        existing.setStatus("GENERATED");

        when(outlineService.getById(100L)).thenReturn(outline);
        when(documentRecordService.findByIdempotencyKey("100_PPT_1")).thenReturn(existing);

        // Act: call generateDocument twice
        ActResult result1 = generateDocumentTool.generateDocument("PPT", 100L);
        ActResult result2 = generateDocumentTool.generateDocument("PPT", 100L);

        // Assert: both should return success, no regeneration
        assertTrue(result1.success());
        assertTrue(result2.success());
        // DocumentGenerator.generatePpt was never called (no updateStatus call)
        verify(outlineService, never()).updateStatus(anyLong(), anyString());
    }
}
