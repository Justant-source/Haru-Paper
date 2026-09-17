package com.harupaper.server.widget.letter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GodowonMorningLetterProvider}의 캐시·재시도·stale 폴백 로직만 검증한다 — 실제 네트워크는 타지 않는다.
 * fetchHtml()/now()를 오버라이드해 HTTP와 시간 흐름을 흉내 낸다(작업지시서 07 4절 "단위 테스트는 네트워크를 타지 않는다").
 */
@DisplayName("고도원의 아침편지 제공자(캐시·재시도)")
class GodowonMorningLetterProviderTest {

    private static String fixture(String name) {
        try (InputStream in = GodowonMorningLetterProviderTest.class.getResourceAsStream("/widgets/letter/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** fetchHtml() 호출을 세고, now()를 테스트가 마음대로 흘려보낼 수 있게 한 테스트 전용 하위 클래스. */
    private static final class TestableProvider extends GodowonMorningLetterProvider {
        final AtomicInteger fetchCount = new AtomicInteger();
        Instant clock = Instant.parse("2026-09-18T21:00:00Z");
        boolean fail = false;

        @Override
        protected String fetchHtml() {
            fetchCount.incrementAndGet();
            if (fail) {
                throw new MorningLetterProviderException("모의 네트워크 실패", null);
            }
            return fixture("normal.html");
        }

        @Override
        protected Instant now() {
            return clock;
        }
    }

    @Test
    @DisplayName("30분 안에 다시 부르면 캐시를 쓰고 fetchHtml을 다시 호출하지 않는다")
    void cacheHitAvoidsRefetch() {
        TestableProvider provider = new TestableProvider();

        MorningLetter first = provider.getLatest();
        MorningLetter second = provider.getLatest();

        assertEquals(1, provider.fetchCount.get());
        assertEquals(first, second);
        assertFalse(first.stale());
    }

    @Test
    @DisplayName("캐시 만료 후 실패하면 72시간 이내 직전 값을 stale=true로 돌려준다")
    void fallsBackToStaleCacheOnFailure() {
        TestableProvider provider = new TestableProvider();
        MorningLetter first = provider.getLatest();
        assertEquals(1, provider.fetchCount.get());

        // 30분 TTL을 넘기고(31분 경과) 이제부터는 조회가 실패한다고 가정
        provider.clock = provider.clock.plus(Duration.ofMinutes(31));
        provider.fail = true;

        MorningLetter fallback = provider.getLatest();

        assertTrue(fallback.stale());
        assertEquals(first.title(), fallback.title());
        assertEquals(first.quote(), fallback.quote());
        // 재시도 1회를 포함해 2번 더 시도했어야 한다(최초 1 + 이번 2 = 3)
        assertEquals(3, provider.fetchCount.get());
    }

    @Test
    @DisplayName("캐시가 없고 조회도 실패하면 예외를 던진다")
    void throwsWhenNoCacheAndFetchFails() {
        TestableProvider provider = new TestableProvider();
        provider.fail = true;

        assertThrows(MorningLetterProviderException.class, provider::getLatest);
    }

    @Test
    @DisplayName("72시간이 지난 캐시는 폴백으로도 쓰지 않고 예외를 던진다")
    void staleWindowExpiresEventually() {
        TestableProvider provider = new TestableProvider();
        provider.getLatest();

        provider.clock = provider.clock.plus(Duration.ofHours(73));
        provider.fail = true;

        assertThrows(MorningLetterProviderException.class, provider::getLatest);
    }
}
