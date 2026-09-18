package com.harupaper.server.widget.stock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link YahooChartStockQuoteProvider} 단위 테스트. 네트워크를 전혀 타지 않는다 — 저장해 둔
 * fixture JSON(src/test/resources/widgets/stock/*.json)만 쓴다(.temp/07 "데이터 제공자 공통 규칙").
 */
@DisplayName("YahooChartStockQuoteProvider — 파싱·캐시·폴백")
class YahooChartStockQuoteProviderTest {

    private static String fixture(String name) {
        try {
            Path path = Path.of("src/test/resources/widgets/stock", name);
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 호출된 url을 세면서 고정 결과를 돌려주는 가짜 HTTP 클라이언트 */
    private static final class FakeHttpClient implements YahooChartStockQuoteProvider.ChartHttpClient {
        private final List<YahooChartStockQuoteProvider.FetchResult> results;
        private int callCount = 0;

        FakeHttpClient(YahooChartStockQuoteProvider.FetchResult... results) {
            this.results = List.of(results);
        }

        @Override
        public YahooChartStockQuoteProvider.FetchResult fetch(String url) {
            int idx = Math.min(callCount, results.size() - 1);
            callCount++;
            return results.get(idx);
        }
    }

    @Test
    @DisplayName("null 섞인 봉을 버리고, 현지 날짜로 변환하고, days만큼 자른다")
    void parsesFixtureAndTrimsDays() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-aapl.json")));
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client);

        StockSeries series = provider.getDaily("AAPL", 14);

        // fixture는 5개 타임스탬프 중 3번째 봉이 close:null이라 버려지고 4개만 남는다
        assertEquals(4, series.candles().size());
        assertEquals("AAPL", series.symbol());
        assertEquals("Apple Inc. (Test Fixture)", series.shortName());
        assertEquals("USD", series.currency());
        assertFalse(series.stale());

        // epoch 1704729600 = 2024-01-08 16:00 UTC = America/New_York 기준 2024-01-08(EST, UTC-5)
        assertEquals(LocalDate.of(2024, 1, 8), series.candles().get(3).date());
        assertEquals(LocalDate.of(2024, 1, 2), series.candles().get(0).date());
        assertEquals(183.5, series.candles().get(3).close());

        // previousClose = 전체(4봉) 기준 끝에서 두 번째 = 2024-01-05 종가
        assertEquals(182.75, series.previousClose());
    }

    @Test
    @DisplayName("days가 전체 봉 수보다 작으면 뒤에서 그만큼만 자르고, previousClose는 전체 기준으로 그대로다")
    void trimsToRequestedDaysButKeepsFullPreviousClose() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-aapl.json")));
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client);

        StockSeries series = provider.getDaily("AAPL", 2);

        assertEquals(2, series.candles().size());
        assertEquals(LocalDate.of(2024, 1, 5), series.candles().get(0).date());
        assertEquals(LocalDate.of(2024, 1, 8), series.candles().get(1).date());
        assertEquals(182.75, series.previousClose());
    }

    @Test
    @DisplayName("404 + chart.error.code=Not Found → SymbolNotFoundException, 재시도 없이 1회만 호출")
    void notFoundThrowsWithoutRetry() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(404, fixture("chart-not-found.json")));
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client);

        assertThrows(SymbolNotFoundException.class, () -> provider.getDaily("ZZZZQ", 14));
        assertEquals(1, client.callCount);
    }

    @Test
    @DisplayName("유효한 봉이 하나도 없으면 조회 실패로 취급한다")
    void allNullCandlesIsFailure() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-all-null.json")));
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client);

        assertThrows(StockQuoteException.class, () -> provider.getDaily("ZZZZ", 14));
    }

    @Test
    @DisplayName("잘못된 심볼 형식(SSRF 위험 문자 포함)은 네트워크를 타지 않고 바로 거부한다")
    void invalidSymbolNeverHitsNetwork() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-aapl.json")));
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client);

        // 경로 조작·SQL 인젝션류 문자, 6자 이상 티커 등은 정규식에 안 맞아 전부 거부되고
        // client.fetch가 한 번도 호출되지 않아야 한다(SSRF 방지, .temp/07 5.2절)
        assertThrows(SymbolNotFoundException.class, () -> provider.getDaily("../../etc/passwd", 14));
        assertThrows(SymbolNotFoundException.class, () -> provider.getDaily("AAPL; DROP TABLE", 14));
        assertThrows(SymbolNotFoundException.class, () -> provider.getDaily("TOOLONGTICKER", 14));
        assertThrows(SymbolNotFoundException.class, () -> provider.getDaily("", 14));
        assertEquals(0, client.callCount);
    }

    @Test
    @DisplayName("소문자 티커는 대문자로 정규화해 정상 조회한다(Yahoo 티커는 대소문자 무관)")
    void lowercaseSymbolIsNormalized() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-aapl.json")));
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client);

        StockSeries series = provider.getDaily("aapl", 14);

        assertEquals("AAPL", series.symbol());
        assertEquals(1, client.callCount);
    }

    @Test
    @DisplayName("TTL(15분) 안에는 같은 심볼을 다시 호출하지 않는다(캐시 적중)")
    void cachesWithinTtl() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-aapl.json")));
        AtomicLong clock = new AtomicLong(0);
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client, clock::get);

        provider.getDaily("AAPL", 14);
        clock.addAndGet(14L * 60 * 1000); // 14분 경과 — TTL 15분 이내
        provider.getDaily("AAPL", 14);

        assertEquals(1, client.callCount);
    }

    @Test
    @DisplayName("TTL이 지나 재조회가 실패하면 72시간 이내의 직전 값을 stale로 돌려준다")
    void fallsBackToStaleCacheWithin72Hours() {
        YahooChartStockQuoteProvider.FetchResult ok =
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-aapl.json"));
        YahooChartStockQuoteProvider.FetchResult serverError =
                new YahooChartStockQuoteProvider.FetchResult(500, "Internal Server Error");
        // 첫 조회는 query1에서 성공, 이후 만료된 뒤의 재조회는 query1·query2 둘 다 실패
        FakeHttpClient client = new FakeHttpClient(ok, serverError, serverError);
        AtomicLong clock = new AtomicLong(0);
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client, clock::get);

        StockSeries first = provider.getDaily("AAPL", 14);
        assertFalse(first.stale());

        clock.addAndGet(20L * 60 * 1000); // TTL(15분) 초과, 폴백 한도(72시간) 이내
        StockSeries stale = provider.getDaily("AAPL", 14);

        assertTrue(stale.stale());
        assertEquals(first.candles(), stale.candles());
        assertEquals(3, client.callCount); // 최초 1회 + 만료 후 query1·query2 각 1회
    }

    // ---- 마지막 봉 확정 여부 (2026-09-18 사고 수정) ----
    // fixture chart-mid-session.json: 마지막 봉(2024-01-08)의 정규장은 09:30~16:00 ET
    // (epoch 1704724200~1704747600)다.

    @Test
    @DisplayName("정규장이 열려 있는 동안 조회하면 lastCandleSettled=false다(장중 값을 종가로 단정하지 않는다)")
    void lastCandleNotSettledDuringRegularSession() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-mid-session.json")));
        // 12:00 ET(장중, 09:30~16:00 사이)
        AtomicLong clock = new AtomicLong(1704733200_000L);
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client, clock::get);

        StockSeries series = provider.getDaily("SOXL", 14);

        assertFalse(series.lastCandleSettled());
        assertEquals(clock.get(), series.fetchedAt().toEpochMilli());
    }

    @Test
    @DisplayName("정규장 마감(16:00 ET) 이후 조회하면 같은 날짜라도 lastCandleSettled=true다")
    void lastCandleSettledAfterRegularSessionCloses() {
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-mid-session.json")));
        // 17:00 ET(마감 16:00을 지남, 날짜는 마지막 봉과 여전히 같은 2024-01-08)
        AtomicLong clock = new AtomicLong(1704751200_000L);
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client, clock::get);

        StockSeries series = provider.getDaily("SOXL", 14);

        assertTrue(series.lastCandleSettled());
    }

    @Test
    @DisplayName("마지막 봉 날짜가 거래소 현지 '오늘'보다 이전이면 정규장 정보가 없어도 확정이다")
    void lastCandleSettledWhenClearlyPastDay() {
        // 기존 chart-aapl.json fixture는 currentTradingPeriod가 없다 — 날짜 비교만으로 확정 판단
        FakeHttpClient client = new FakeHttpClient(
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-aapl.json")));
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client); // 실제 시계(2024년보다 훨씬 나중)

        StockSeries series = provider.getDaily("AAPL", 14);

        assertTrue(series.lastCandleSettled());
    }

    @Test
    @DisplayName("폴백 한도(72시간)를 넘기면 stale 캐시도 포기하고 실패시킨다")
    void givesUpAfterFallbackWindowExpires() {
        YahooChartStockQuoteProvider.FetchResult ok =
                new YahooChartStockQuoteProvider.FetchResult(200, fixture("chart-aapl.json"));
        YahooChartStockQuoteProvider.FetchResult serverError =
                new YahooChartStockQuoteProvider.FetchResult(500, "Internal Server Error");
        FakeHttpClient client = new FakeHttpClient(ok, serverError, serverError);
        AtomicLong clock = new AtomicLong(0);
        YahooChartStockQuoteProvider provider = new YahooChartStockQuoteProvider(client, clock::get);

        provider.getDaily("AAPL", 14);
        clock.addAndGet(73L * 60 * 60 * 1000); // 72시간 초과

        assertThrows(StockQuoteException.class, () -> provider.getDaily("AAPL", 14));
    }
}
