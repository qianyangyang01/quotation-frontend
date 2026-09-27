package com.milano.quotation.purchase;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class ShimoSyncScheduleTest {
    @Test void usesShanghaiTimeAndOnlyHalfHourWindow() {
        assertFalse(ShimoSyncSchedule.dailyWindow(Instant.parse("2026-09-27T04:29:59Z")));
        assertTrue(ShimoSyncSchedule.dailyWindow(Instant.parse("2026-09-27T04:30:00Z")));
        assertTrue(ShimoSyncSchedule.dailyWindow(Instant.parse("2026-09-27T04:59:59Z")));
        assertFalse(ShimoSyncSchedule.dailyWindow(Instant.parse("2026-09-27T05:00:00Z")));
        assertEquals("2026-09-28",ShimoSyncSchedule.day(Instant.parse("2026-09-27T16:00:00Z")).toString());
    }
}
