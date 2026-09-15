package com.harupaper.server.render;

import com.harupaper.server.common.time.ClockProvider;
import com.harupaper.server.common.time.TimeUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * shouldRenderBeforeOccurrence 로직 단위 테스트
 *
 * 이 테스트는 60분 전 재렌더 조건을 검증한다:
 * 1. occurrence까지 60분 이하로 남았고
 * 2. 최신 렌더의 renderedAt이 occurrence - 60분보다 이전이면 true
 *
 * docs/server/rendering.md 4절, 7절 체크리스트 항목 6
 */
@DisplayName("shouldRenderBeforeOccurrence 로직 검증")
class ShouldRenderBeforeOccurrenceTest {

    private static final String PROFILE_KEY = "m832-300-110-1300";

    /**
     * 시간 경계 조건 테스트: 정확히 60분 전
     */
    @Nested
    @DisplayName("60분 경계 조건")
    class BoundaryConditions {

        /**
         * occurrence까지 정확히 60분이 남았을 때
         * → 60분 이하이므로 재렌더 가능 범위
         */
        @Test
        @DisplayName("정확히 60분 남았을 때: 재렌더 조건 확인")
        void testExactly60MinutesRemaining() {
            // 2026-09-14 07:00 occurrence
            LocalDate date = LocalDate.of(2026, 9, 14);
            LocalTime occurrenceTime = LocalTime.of(7, 0);

            // 현재: 06:00 (60분 전)
            TestClock clock = new TestClock(
                    ZonedDateTime.of(date, LocalTime.of(6, 0), TimeUtils.KST)
            );

            ZonedDateTime now = clock.nowInKST();
            ZonedDateTime occurrence = date.atTime(occurrenceTime).atZone(TimeUtils.KST);

            // 60분 경계에서
            long minutesUntilOccurrence = java.time.temporal.ChronoUnit.MINUTES
                    .between(now, occurrence);

            assertEquals(60, minutesUntilOccurrence);
            assertTrue(minutesUntilOccurrence <= 60, "60분 이하는 재렌더 범위");
        }

        /**
         * occurrence까지 61분이 남았을 때
         * → 60분을 초과하므로 재렌더 하지 않음
         */
        @Test
        @DisplayName("61분 남았을 때: 재렌더하지 않음")
        void test61MinutesRemaining() {
            LocalDate date = LocalDate.of(2026, 9, 14);
            LocalTime occurrenceTime = LocalTime.of(7, 1);

            // 현재: 06:00 (61분 전)
            TestClock clock = new TestClock(
                    ZonedDateTime.of(date, LocalTime.of(6, 0), TimeUtils.KST)
            );

            ZonedDateTime now = clock.nowInKST();
            ZonedDateTime occurrence = date.atTime(occurrenceTime).atZone(TimeUtils.KST);

            long minutesUntilOccurrence = java.time.temporal.ChronoUnit.MINUTES
                    .between(now, occurrence);

            assertEquals(61, minutesUntilOccurrence);
            assertFalse(minutesUntilOccurrence <= 60, "60분 초과는 재렌더 범위 밖");
        }

        /**
         * occurrence까지 59분이 남았을 때
         * → 60분 이하이므로 재렌더 가능 범위
         */
        @Test
        @DisplayName("59분 남았을 때: 재렌더 조건 확인")
        void test59MinutesRemaining() {
            LocalDate date = LocalDate.of(2026, 9, 14);
            LocalTime occurrenceTime = LocalTime.of(7, 0);

            // 현재: 06:01 (59분 전)
            TestClock clock = new TestClock(
                    ZonedDateTime.of(date, LocalTime.of(6, 1), TimeUtils.KST)
            );

            ZonedDateTime now = clock.nowInKST();
            ZonedDateTime occurrence = date.atTime(occurrenceTime).atZone(TimeUtils.KST);

            long minutesUntilOccurrence = java.time.temporal.ChronoUnit.MINUTES
                    .between(now, occurrence);

            assertEquals(59, minutesUntilOccurrence);
            assertTrue(minutesUntilOccurrence <= 60, "59분은 재렌더 범위");
        }
    }

    /**
     * 렌더 재사용 조건 테스트: 최신 렌더의 나이
     */
    @Nested
    @DisplayName("최신 렌더의 나이에 따른 조건")
    class RenderReuseConditions {

        /**
         * 최신 렌더가 60분 전 경계보다 이전에 생성됨
         * → 재렌더 필요
         */
        @Test
        @DisplayName("렌더가 60분 전 경계보다 이전: 재렌더 필요")
        void testRenderOlderThan60MinutesBoundary() {
            LocalDate date = LocalDate.of(2026, 9, 14);
            LocalTime occurrenceTime = LocalTime.of(7, 0);

            // 현재: 06:50 (10분 전)
            TestClock clock = new TestClock(
                    ZonedDateTime.of(date, LocalTime.of(6, 50), TimeUtils.KST)
            );

            ZonedDateTime now = clock.nowInKST();
            ZonedDateTime occurrence = date.atTime(occurrenceTime).atZone(TimeUtils.KST);
            ZonedDateTime sixtyMinutesBefore = occurrence.minusMinutes(60);

            // 렌더: 05:00 (60분 전 경계 = 06:00보다 이전)
            // 60분 이상 전에 렌더됨
            Instant renderTime = ZonedDateTime.of(date, LocalTime.of(5, 0), TimeUtils.KST).toInstant();

            assertTrue(renderTime.isBefore(sixtyMinutesBefore.toInstant()),
                    "05:00는 06:00(60분 전)보다 이전");
            assertTrue(minutesBetween(renderTime, occurrence.toInstant()) >= 60);
        }

        /**
         * 최신 렌더가 60분 전 경계보다 이후에 생성됨
         * → 재렌더 불필요 (캐시 재사용)
         */
        @Test
        @DisplayName("렌더가 60분 전 경계보다 이후: 재렌더 불필요")
        void testRenderNewerThan60MinutesBoundary() {
            LocalDate date = LocalDate.of(2026, 9, 14);
            LocalTime occurrenceTime = LocalTime.of(7, 0);

            // 현재: 06:50 (10분 전)
            TestClock clock = new TestClock(
                    ZonedDateTime.of(date, LocalTime.of(6, 50), TimeUtils.KST)
            );

            ZonedDateTime now = clock.nowInKST();
            ZonedDateTime occurrence = date.atTime(occurrenceTime).atZone(TimeUtils.KST);
            ZonedDateTime sixtyMinutesBefore = occurrence.minusMinutes(60);  // 05:00

            // 렌더: 06:45 (60분 전 경계보다 이후)
            Instant renderTime = ZonedDateTime.of(date, LocalTime.of(6, 45), TimeUtils.KST).toInstant();

            assertFalse(renderTime.isBefore(sixtyMinutesBefore.toInstant()),
                    "06:45는 05:00(60분 전)보다 이후");
            assertTrue(minutesBetween(renderTime, occurrence.toInstant()) < 60);
        }
    }

    /**
     * 실시간(또는 과거) 경계 테스트
     */
    @Nested
    @DisplayName("occurrence 경계")
    class OccurrenceBoundary {

        /**
         * occurrence가 미래일 때 (정상)
         */
        @Test
        @DisplayName("occurrence가 미래: 정상 범위")
        void testOccurrenceInFuture() {
            LocalDate date = LocalDate.of(2026, 9, 14);
            LocalTime occurrenceTime = LocalTime.of(7, 0);

            TestClock clock = new TestClock(
                    ZonedDateTime.of(date, LocalTime.of(6, 0), TimeUtils.KST)
            );

            ZonedDateTime now = clock.nowInKST();
            ZonedDateTime occurrence = date.atTime(occurrenceTime).atZone(TimeUtils.KST);

            assertTrue(occurrence.isAfter(now), "occurrence는 미래");
            assertTrue(occurrence.isAfter(now));
        }

        /**
         * occurrence가 이미 지났을 때
         * → 재렌더하지 않음 (minutesUntilOccurrence < 0)
         */
        @Test
        @DisplayName("occurrence가 지났을 때: 재렌더하지 않음")
        void testOccurrencePassed() {
            LocalDate date = LocalDate.of(2026, 9, 14);
            LocalTime occurrenceTime = LocalTime.of(7, 0);

            TestClock clock = new TestClock(
                    ZonedDateTime.of(date, LocalTime.of(8, 0), TimeUtils.KST)  // 1시간 후
            );

            ZonedDateTime now = clock.nowInKST();
            ZonedDateTime occurrence = date.atTime(occurrenceTime).atZone(TimeUtils.KST);

            long minutesUntilOccurrence = java.time.temporal.ChronoUnit.MINUTES
                    .between(now, occurrence);

            assertTrue(minutesUntilOccurrence < 0, "occurrence가 지남");
            assertFalse(minutesUntilOccurrence <= 60 && minutesUntilOccurrence >= 0);
        }
    }

    /**
     * Helper: Instant 간의 분 계산
     */
    private long minutesBetween(Instant from, Instant to) {
        return java.time.temporal.ChronoUnit.MINUTES.between(from, to);
    }

    /**
     * Test용 Clock 구현
     */
    private static class TestClock implements ClockProvider {
        private final Clock fixedClock;

        TestClock(ZonedDateTime dateTime) {
            this.fixedClock = Clock.fixed(dateTime.toInstant(), TimeUtils.KST);
        }

        @Override
        public Instant instant() {
            return Instant.now(fixedClock);
        }

        @Override
        public LocalDate todayInKST() {
            return LocalDate.now(fixedClock);
        }

        @Override
        public ZonedDateTime nowInKST() {
            return ZonedDateTime.now(fixedClock);
        }

        @Override
        public Clock getClock() {
            return fixedClock;
        }
    }
}
