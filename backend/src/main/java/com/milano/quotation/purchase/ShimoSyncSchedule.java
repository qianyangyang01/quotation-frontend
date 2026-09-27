package com.milano.quotation.purchase;

import java.time.*;

final class ShimoSyncSchedule {
    static final ZoneId ZONE=ZoneId.of("Asia/Shanghai");
    private ShimoSyncSchedule() {}
    static String dueMode(Instant instant, Instant enabledAt) {
        var local=instant.atZone(ZONE);
        int hour=local.getHour()>=18?18:local.getHour()>=12?12:-1;
        if(hour<0) return null;
        var scheduled=local.toLocalDate().atTime(hour,0).atZone(ZONE).toInstant();
        // Enabling late does not unexpectedly update all existing products immediately.
        if(scheduled.isBefore(enabledAt)) return null;
        return hour==12?"daily_noon":"daily_evening";
    }
    static boolean daily(String mode) {return mode!=null&&mode.startsWith("daily");}
    static long nextTickMillis(Instant instant) {return 30_000-Math.floorMod(instant.toEpochMilli(),30_000);}
    static LocalDate day(Instant instant) {return instant.atZone(ZONE).toLocalDate();}
}
