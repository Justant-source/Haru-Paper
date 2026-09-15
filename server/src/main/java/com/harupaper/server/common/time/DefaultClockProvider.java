package com.harupaper.server.common.time;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;

/**
 * Default clock provider using system clock.
 */
@Component
public class DefaultClockProvider implements ClockProvider {
    private final Clock systemClock;

    public DefaultClockProvider() {
        this.systemClock = Clock.systemDefaultZone();
    }

    @Override
    public Instant instant() {
        return Instant.now(systemClock);
    }

    @Override
    public LocalDate todayInKST() {
        return LocalDate.now(Clock.system(TimeUtils.KST));
    }

    @Override
    public ZonedDateTime nowInKST() {
        return ZonedDateTime.now(Clock.system(TimeUtils.KST));
    }

    @Override
    public Clock getClock() {
        return systemClock;
    }
}
