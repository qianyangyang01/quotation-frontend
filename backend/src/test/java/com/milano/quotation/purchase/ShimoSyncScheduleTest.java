package com.milano.quotation.purchase;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
class ShimoSyncScheduleTest {
    @Test void usesIndependentNoonAndEveningSlotsInShanghai() {
        var enabled=Instant.parse("2026-09-26T16:00:00Z");
        assertNull(ShimoSyncSchedule.dueMode(Instant.parse("2026-09-27T03:59:59Z"),enabled));
        assertEquals("daily_noon",ShimoSyncSchedule.dueMode(Instant.parse("2026-09-27T04:00:00Z"),enabled));
        assertEquals("daily_noon",ShimoSyncSchedule.dueMode(Instant.parse("2026-09-27T09:59:59Z"),enabled));
        assertEquals("daily_evening",ShimoSyncSchedule.dueMode(Instant.parse("2026-09-27T10:00:00Z"),enabled));
        assertNull(ShimoSyncSchedule.dueMode(Instant.parse("2026-09-27T16:00:00Z"),enabled));
        assertEquals("2026-09-28",ShimoSyncSchedule.day(Instant.parse("2026-09-27T16:00:00Z")).toString());
    }
    @Test void enablingAfterASlotWaitsForTheNextSlotAndChecksAlignWithTheClock() {
        var enabled=Instant.parse("2026-09-27T05:00:00Z");
        assertNull(ShimoSyncSchedule.dueMode(Instant.parse("2026-09-27T05:01:00Z"),enabled));
        assertEquals("daily_evening",ShimoSyncSchedule.dueMode(Instant.parse("2026-09-27T10:00:00Z"),enabled));
        assertEquals(1000,ShimoSyncSchedule.nextTickMillis(Instant.parse("2026-09-27T03:59:59Z")));
    }
}
