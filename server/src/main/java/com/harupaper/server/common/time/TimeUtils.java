package com.harupaper.server.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Time utilities for Asia/Seoul timezone handling.
 */
public class TimeUtils {
    public static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_8601_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private TimeUtils() {
        // Utility class
    }

    /**
     * Convert UTC Instant to ISO-8601 string with +09:00 offset (KST).
     *
     * @param instant UTC instant
     * @return ISO-8601 formatted string with +09:00 offset
     */
    public static String toIso8601(Instant instant) {
        return ZonedDateTime.ofInstant(instant, KST).format(ISO_8601_FORMATTER);
    }

    /**
     * Get today's date in KST.
     *
     * @return LocalDate in KST
     */
    public static LocalDate todayInKST() {
        return LocalDate.now(KST);
    }

    /**
     * Get current instant as ZonedDateTime in KST.
     *
     * @return ZonedDateTime in KST
     */
    public static ZonedDateTime nowInKST() {
        return ZonedDateTime.now(KST);
    }
}
