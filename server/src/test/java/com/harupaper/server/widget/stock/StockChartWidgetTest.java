package com.harupaper.server.widget.stock;

import com.harupaper.server.widget.PropField;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StockChartWidget — descriptor·SVG 그리기·실패 표시")
class StockChartWidgetTest {

    /** 고정 결과(또는 예외)를 돌려주는 가짜 제공자 — 위젯 단위 테스트는 네트워크를 전혀 모른다 */
    private static final class FakeProvider implements StockQuoteProvider {
        private StockSeries series;
        private RuntimeException failure;

        static FakeProvider returning(StockSeries series) {
            FakeProvider p = new FakeProvider();
            p.series = series;
            return p;
        }

        static FakeProvider throwing(RuntimeException failure) {
            FakeProvider p = new FakeProvider();
            p.failure = failure;
            return p;
        }

        @Override
        public StockSeries getDaily(String symbol, int days) {
            if (failure != null) {
                throw failure;
            }
            return series;
        }
    }

    private static StockSeries series(String symbol, String shortName, List<Candle> candles, Double previousClose) {
        // candles()가 만드는 날짜는 항상 2026-08-01부터라 실제 "지금"보다 과거다 — 확정 종가로 둔다
        return series(symbol, shortName, candles, previousClose, true);
    }

    private static StockSeries series(String symbol, String shortName, List<Candle> candles, Double previousClose,
                                       boolean lastCandleSettled) {
        return new StockSeries(symbol, shortName, "USD", candles, previousClose, Instant.now(), false,
                lastCandleSettled);
    }

    private static List<Candle> candles(double... closes) {
        // 임의 값이지만 open==전 close, high/low는 open·close 위아래로 살짝 벌린다(.temp/07: "숫자는 지어낸 값이어도 된다")
        List<Candle> list = new java.util.ArrayList<>();
        double prevClose = closes[0] - 1;
        LocalDate date = LocalDate.of(2026, 8, 1);
        for (double close : closes) {
            double open = prevClose;
            double high = Math.max(open, close) + 1;
            double low = Math.min(open, close) - 1;
            list.add(new Candle(date, open, high, low, close));
            prevClose = close;
            date = date.plusDays(1);
        }
        return list;
    }

    private static int count(String html, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = html.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ---- descriptor ----

    @Test
    @DisplayName("descriptor: type·크기 3종·필드 2개가 작업지시서 그대로다")
    void descriptorMatchesSpec() {
        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(null));
        WidgetDescriptor d = widget.descriptor();

        assertEquals("stockChart", d.type());
        assertTrue(d.dynamic());
        assertTrue(d.catalog());
        assertEquals("2x4", d.defaultSize());
        assertEquals(List.of("2x4", "4x4", "4x6"), d.sizes().stream().map(WidgetSize::id).toList());

        PropField ticker = d.fields().stream().filter(f -> f.key().equals("ticker")).findFirst().orElseThrow();
        assertTrue(ticker.required());
        assertEquals(PropField.Kind.STRING, ticker.kind());
        assertEquals(8, ticker.maxLength());

        PropField days = d.fields().stream().filter(f -> f.key().equals("days")).findFirst().orElseThrow();
        assertFalse(days.required());
        assertEquals(14, days.defaultValue());
        assertEquals(5, days.min());
        assertEquals(60, days.max());
    }

    // ---- 정상 렌더 ----

    @Test
    @DisplayName("봉 개수만큼 <rect(몸통)를 그리고, 오른 날은 흰 채움/내린 날은 검은 채움이다")
    void drawsOneRectPerCandleWithCorrectFill() {
        // 첫 봉: open(=prevClose)=99 → close=100 (오름) / 둘째 봉: open=100 → close=95 (내림)
        StockSeries s = series("AAPL", "Apple Inc.", candles(100, 95), 99.0);
        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(s));
        WidgetSize size = widget.descriptor().size("4x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String html = widget.renderHtml(new WidgetInstance("w1", "stockChart", "4x4",
                Map.of("ticker", "AAPL", "days", 14)), ctx);

        assertEquals(2, count(html, "<rect x="));
        assertTrue(html.contains("fill=\"#fff\""), "오른 날은 흰 채움이어야 한다");
        assertTrue(html.contains("fill=\"#000\""), "내린 날은 검은 채움이어야 한다");
    }

    @Test
    @DisplayName("좁은 크기(2x4)는 가격 라벨이 최고/최저 2개, 넓은 크기는 3~4개다")
    void narrowHasFewerGridLabelsThanWide() {
        StockSeries s = series("AAPL", "Apple Inc.", candles(100, 101, 99, 102, 98), 99.5);

        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(s));
        WidgetSize narrowSize = widget.descriptor().size("2x4");
        WidgetSize wideSize = widget.descriptor().size("4x4");

        String narrowHtml = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4",
                Map.of("ticker", "AAPL")), WidgetPreviewHarness.context(narrowSize, LocalDate.of(2026, 9, 18)));
        String wideHtml = widget.renderHtml(new WidgetInstance("w2", "stockChart", "4x4",
                Map.of("ticker", "AAPL")), WidgetPreviewHarness.context(wideSize, LocalDate.of(2026, 9, 18)));

        assertEquals(2, count(narrowHtml, "dominant-baseline"));
        assertEquals(4, count(wideHtml, "dominant-baseline"));
        // 회사명은 좁은 크기에서는 생략된다(.temp/07 5.2절)
        assertFalse(narrowHtml.contains("Apple Inc."));
        assertTrue(wideHtml.contains("Apple Inc."));
    }

    @Test
    @DisplayName("모든 봉의 고가=저가(가격 범위 0)여도 예외 없이 그린다")
    void flatPriceRangeDoesNotDivideByZero() {
        List<Candle> flat = List.of(
                new Candle(LocalDate.of(2026, 9, 1), 50.0, 50.0, 50.0, 50.0),
                new Candle(LocalDate.of(2026, 9, 2), 50.0, 50.0, 50.0, 50.0));
        StockSeries s = series("FLAT", "Flat Co.", flat, 50.0);
        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(s));
        WidgetSize size = widget.descriptor().size("2x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String html = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4",
                Map.of("ticker", "FLAT")), ctx);

        assertFalse(html.isBlank());
        // 캔들 몸통 2개 + 보합(등락 0)을 나타내는 가로 막대 삼각형 자리 1개 = <rect x= 3개
        assertEquals(3, count(html, "<rect x="));
    }

    @Test
    @DisplayName("회사명·티커의 HTML 특수문자를 escape한다")
    void escapesUntrustedText() {
        StockSeries s = series("A&B", "<script>alert(1)</script>", candles(10, 11), 9.5);
        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(s));
        WidgetSize size = widget.descriptor().size("4x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String html = widget.renderHtml(new WidgetInstance("w1", "stockChart", "4x4",
                Map.of("ticker", "A&B")), ctx);

        assertFalse(html.contains("<script>"), "이스케이프 안 된 <script>가 그대로 남으면 안 된다");
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("A&amp;B"));
    }

    // ---- 실패 표시 ----

    @Test
    @DisplayName("없는 티커는 '티커를 찾을 수 없습니다' errorBox다")
    void symbolNotFoundShowsSpecificMessage() {
        StockChartWidget widget = new StockChartWidget(FakeProvider.throwing(new SymbolNotFoundException("ZZZZQ")));
        WidgetSize size = widget.descriptor().size("2x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String html = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4",
                Map.of("ticker", "ZZZZQ")), ctx);

        assertTrue(html.contains("티커를 찾을 수 없습니다"));
        assertTrue(html.contains("ZZZZQ"));
    }

    @Test
    @DisplayName("그 밖의 조회 실패는 '시세를 가져오지 못했습니다' errorBox이고 예외가 밖으로 나가지 않는다")
    void genericFailureShowsFallbackMessage() {
        StockChartWidget widget = new StockChartWidget(FakeProvider.throwing(new StockQuoteException("boom")));
        WidgetSize size = widget.descriptor().size("2x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String html = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4",
                Map.of("ticker", "AAPL")), ctx);

        assertTrue(html.contains("시세를 가져오지 못했습니다"));
    }

    @Test
    @DisplayName("props에 ticker가 없어도 NPE 없이 errorBox를 돌려준다")
    void missingTickerPropDoesNotThrow() {
        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(null));
        WidgetSize size = widget.descriptor().size("2x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String htmlEmptyProps = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4", Map.of()), ctx);
        assertTrue(htmlEmptyProps.contains("티커를 찾을 수 없습니다"));

        String htmlNullProps = widget.renderHtml(new WidgetInstance("w2", "stockChart", "2x4", null), ctx);
        assertTrue(htmlNullProps.contains("티커를 찾을 수 없습니다"));
    }

    @Test
    @DisplayName("빈 봉 목록(모두 걸러진 경우)도 실패로 취급한다")
    void emptyCandleListIsFailure() {
        StockSeries empty = series("AAPL", "Apple Inc.", List.of(), null);
        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(empty));
        WidgetSize size = widget.descriptor().size("2x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String html = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4",
                Map.of("ticker", "AAPL")), ctx);

        assertTrue(html.contains("시세를 가져오지 못했습니다"));
    }

    // ---- 종가 vs 현재가 (2026-09-18 사고 수정) ----

    @Test
    @DisplayName("확정 종가(lastCandleSettled=true)면 \"M/d 종가\"만 나오고 \"현재가\"는 안 나온다")
    void settledSeriesShowsCloseLabel() {
        StockSeries s = series("AAPL", "Apple Inc.", candles(100, 101), 99.5, true);
        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(s));
        WidgetSize size = widget.descriptor().size("2x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String html = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4",
                Map.of("ticker", "AAPL")), ctx);

        assertTrue(html.contains("종가"), "확정이면 종가라고 표시해야 한다");
        assertFalse(html.contains("현재가"), "확정인데 현재가라고 표시하면 안 된다");
        assertFalse(html.contains("기준)"), "확정이면 조회 시각을 굳이 안 보여줘도 된다");
    }

    @Test
    @DisplayName("미확정(lastCandleSettled=false, 장중 조회)이면 \"M/d 현재가\"와 조회 시각(KST)이 나오고 \"종가\"는 안 나온다")
    void unsettledSeriesShowsCurrentPriceLabelWithFetchTime() {
        // 2026-09-18 사고 재현: 장중에 조회한 값을 "종가"로 표시하면 사용자가 오해한다 —
        // 정규장이 아직 안 끝났으면(lastCandleSettled=false) "현재가"로 표시해야 한다
        StockSeries s = series("SOXL", "Direxion Daily Semiconductor Bull 3X", candles(29.1, 30.5), 29.1, false);
        StockChartWidget widget = new StockChartWidget(FakeProvider.returning(s));
        WidgetSize size = widget.descriptor().size("2x4");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        String html = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4",
                Map.of("ticker", "SOXL")), ctx);

        assertTrue(html.contains("현재가"), "미확정이면 현재가라고 표시해야 한다");
        assertFalse(html.contains("종가"), "미확정인데 종가라고 표시하면 안 된다");
        assertTrue(html.contains("기준)"), "미확정이면 조회 시각을 같이 보여줘야 한다(예약 인쇄 대비)");
    }
}
