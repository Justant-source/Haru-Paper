package com.harupaper.server.common.time;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeUtilsTest {

    @Test
    void parseIso8601AcceptsOffsetUsedInApiResponses() {
        Instant parsed = TimeUtils.parseIso8601("2026-09-14T07:00:00.000+09:00");
        assertEquals(Instant.parse("2026-09-13T22:00:00Z"), parsed);
    }

    @Test
    void parseIso8601AcceptsZulu() {
        Instant parsed = TimeUtils.parseIso8601("2026-09-13T22:00:00Z");
        assertEquals(Instant.parse("2026-09-13T22:00:00Z"), parsed);
    }

    @Test
    void parseIso8601BlankIsNull() {
        assertNull(TimeUtils.parseIso8601(null));
        assertNull(TimeUtils.parseIso8601(""));
    }

    @Test
    void parseIso8601RejectsGarbage() {
        assertThrows(java.time.format.DateTimeParseException.class,
                () -> TimeUtils.parseIso8601("not-a-timestamp"));
    }

    @Test
    void toIso8601RoundTripWithParse() {
        Instant original = Instant.parse("2026-09-13T22:00:00Z");
        Instant again = TimeUtils.parseIso8601(TimeUtils.toIso8601(original));
        assertEquals(original, again);
    }
}
