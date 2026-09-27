package com.milano.quotation.purchase;

import java.time.*;

final class ShimoSyncSchedule {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private ShimoSyncSchedule() {}
    static boolean dailyWindow(Instant instant) {
        var time=instant.atZone(ZONE).toLocalTime();
        return !time.isBefore(LocalTime.of(12,30))&&time.isBefore(LocalTime.of(13,0));
    }
    static LocalDate day(Instant instant) {return instant.atZone(ZONE).toLocalDate();}
}
