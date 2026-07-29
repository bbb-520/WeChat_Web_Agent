package log.summer.aigc.tool.outline;

import log.summer.aigc.context.UserContextHolder;
import log.summer.aigc.loop.ActResult;
import log.summer.aigc.service.ChatPersistenceService;
import log.summer.aigc.service.IDocumentOutlineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreateOutlineToolTest {

    @Mock
    private IDocumentOutlineService outlineService;
    @Mock
    private ChatPersistenceService persistence;

    private CreateOutlineTool tool;

    @BeforeEach
    void setUp() {
        tool = new CreateOutlineTool(outlineService, persistence);
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
    void shouldReturnSuspendWithFormattedOutline() {
        when(outlineService.save(any())).thenReturn(true);

        String outlineJson = """
            {
              "title": "Q2业绩报告",
              "sections": [
                {"title": "业绩概览", "points": ["总营收", "增长率"]},
                {"title": "各部门分析", "points": ["销售部", "研发部"]}
              ]
            }""";

        ActResult result = tool.createOutline("PPT", "Q2业绩报告", outlineJson);

        assertTrue(result.success());
        assertTrue(result.suspend());
        assertTrue(result.requiresConfirmation());
        assertTrue(result.suspendReason().contains("等待确认大纲内容，您可以回复「确认」或提出修改意见"));
        assertNotNull(result.data());
        // Verify outline was saved to DB
        verify(outlineService).save(any());
    }

    @Test
    void shouldReturnFailureForInvalidType() {
        ActResult result = tool.createOutline("PDF", "test", "{}");

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("不支持"));
        verify(outlineService, never()).save(any());
    }

    @Test
    void shouldReturnFailureForEmptyOutline() {
        ActResult result = tool.createOutline("PPT", "test", "");

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("大纲数据不能为空"));
    }

    @Test
    void shouldReturnFailureForNullTitle() {
        ActResult result = tool.createOutline("WORD", null, "{}");

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("标题"));
    }
}
