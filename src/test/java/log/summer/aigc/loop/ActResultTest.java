package log.summer.aigc.loop;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ActResultTest {

    @Test
    void successShouldNotSuspend() {
        ActResult result = ActResult.success("done");
        assertTrue(result.success());
        assertEquals("done", result.data());
        assertFalse(result.suspend());
        assertFalse(result.requiresConfirmation());
        assertNull(result.suspendReason());
    }

    @Test
    void failureShouldNotSuspend() {
        ActResult result = ActResult.failure("boom");
        assertFalse(result.success());
        assertEquals("boom", result.errorMessage());
        assertFalse(result.suspend());
        assertFalse(result.requiresConfirmation());
    }

    @Test
    void suspendShouldSetAllFlags() {
        ActResult result = ActResult.suspend(
                java.util.Map.of("title", "My Outline", "sections", java.util.List.of("A", "B")),
                "等待确认大纲内容");
        assertTrue(result.success());
        assertTrue(result.suspend());
        assertTrue(result.requiresConfirmation());
        assertEquals("等待确认大纲内容", result.suspendReason());
        assertNotNull(result.data());
    }

    @Test
    void confirmRequiredShouldSetAllFlags() {
        ActResult result = ActResult.confirmRequired("请确认是否继续", "需要用户确认");
        assertTrue(result.success());
        assertTrue(result.suspend());
        assertTrue(result.requiresConfirmation());
        assertEquals("需要用户确认", result.suspendReason());
    }
}
