package com.harupaper.server.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

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
     * API가 내보내는 +09:00 오프셋과 Instant.parse가 받는 Z를 모두 받는다.
     * 빈 값은 null. 형식이 아니면 DateTimeParseException.
     */
    public static Instant parseIso8601(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ISO_8601_FORMATTER.parse(value, Instant::from);
        } catch (DateTimeParseException offsetFailed) {
            return Instant.parse(value);
        }
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
