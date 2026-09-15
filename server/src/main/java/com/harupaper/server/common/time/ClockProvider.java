package com.harupaper.server.common.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Clock provider interface for dependency injection.
 * Allows tests to inject a fixed clock for deterministic time-based testing.
 *
 * docs/server/rendering.md 4절: 동적 포맷 60분 전 재렌더 검증용
 */
public interface ClockProvider {
    /**
     * Get the current instant.
     */
    Instant instant();

    /**
     * Get the current date in KST.
     */
    LocalDate todayInKST();

    /**
     * Get the current ZonedDateTime in KST.
     */
    ZonedDateTime nowInKST();

    /**
     * Get the underlying Clock (for advanced usage).
     */
    Clock getClock();
}
