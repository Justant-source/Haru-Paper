package com.harupaper.server.widget.stock;

import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPageBuilder;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * stockChart 위젯을 실제 렌더 골격 그대로 PNG로 떠서 눈으로 확인하는 개발용 도구(.temp/07 6절).
 * 합격/불합격 판정은 하지 않는다 — 사람이 build/widget-previews/stock-*.png 를 열어 본다.
 *
 * {@code WIDGET_PREVIEW=1 ./gradlew test --tests '*StockChartWidgetPreviewTest*'} 로만 실행된다.
 */
@EnabledIfEnvironmentVariable(named = "WIDGET_PREVIEW", matches = "1")
class StockChartWidgetPreviewTest {

    private static List<Candle> generateCandles(int n, double startPrice, long seed) {
        Random random = new Random(seed); // 재현 가능한 지어낸 값(.temp/07 6절)
        List<Candle> candles = new ArrayList<>();
        double prevClose = startPrice;
        LocalDate date = LocalDate.of(2026, 6, 1);
        for (int i = 0; i < n; i++) {
            double open = prevClose;
            double drift = (random.nextDouble() - 0.5) * startPrice * 0.04;
            double close = Math.max(0.5, open + drift);
            double high = Math.max(open, close) + startPrice * 0.01 * random.nextDouble();
            double low = Math.max(0.1, Math.min(open, close) - startPrice * 0.01 * random.nextDouble());
            candles.add(new Candle(date, open, high, low, close));
            prevClose = close;
            date = date.plusDays(1);
        }
        return candles;
    }

    private static final class FixedProvider implements StockQuoteProvider {
        private final StockSeries series;

        FixedProvider(StockSeries series) {
            this.series = series;
        }

        @Override
        public StockSeries getDaily(String symbol, int days) {
            return series;
        }
    }

    @Test
    @DisplayName("세 크기 x 세 가격대 x 봉 개수 조합을 PNG로 떠 본다")
    void renderPreviewGrid() throws Exception {
        StockChartWidget widget = new StockChartWidget(new FixedProvider(null));

        record Scenario(String name, String sizeId, int candleCount, double startPrice, long seed) {
        }
        List<Scenario> scenarios = List.of(
                new Scenario("2x4-14d-cheap", "2x4", 14, 6.30, 1),
                new Scenario("2x4-30d-mid", "2x4", 30, 332.10, 2),
                new Scenario("2x4-60d-expensive", "2x4", 60, 4321.50, 3),
                new Scenario("4x4-14d-mid", "4x4", 14, 332.10, 4),
                new Scenario("4x4-30d-expensive", "4x4", 30, 4321.50, 5),
                new Scenario("4x6-60d-mid", "4x6", 60, 332.10, 6),
                new Scenario("4x6-14d-cheap", "4x6", 14, 6.30, 7)
        );

        for (Scenario sc : scenarios) {
            List<Candle> candles = generateCandles(sc.candleCount(), sc.startPrice(), sc.seed());
            Double previousClose = candles.size() >= 2
                    ? candles.get(candles.size() - 2).close() : null;
            StockSeries series = new StockSeries("TEST", "Test Widget Co. Long Name Inc.", "USD",
                    candles, previousClose, Instant.now(), false);
            StockChartWidget scenarioWidget = new StockChartWidget(new FixedProvider(series));

            WidgetSize size = scenarioWidget.descriptor().size(sc.sizeId());
            WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));
            String inner = scenarioWidget.renderHtml(
                    new WidgetInstance("w-" + sc.name(), "stockChart", sc.sizeId(),
                            Map.of("ticker", "TEST", "days", sc.candleCount())),
                    ctx);

            WidgetPreviewHarness.renderToPng("stock-" + sc.name(),
                    List.of(new WidgetPageBuilder.Cell(size, inner)));
        }

        // 2x4 두 개를 나란히 놓은 모습(.temp/07 6절: "2x4와 다른 2x4 상자를 나란히 놓은 모습도 본다")
        List<Candle> a = generateCandles(14, 6.30, 1);
        List<Candle> b = generateCandles(14, 332.10, 2);
        StockChartWidget widgetA = new StockChartWidget(new FixedProvider(
                new StockSeries("CHEAP", "Cheap Co.", "USD", a, a.get(a.size() - 2).close(), Instant.now(), false)));
        StockChartWidget widgetB = new StockChartWidget(new FixedProvider(
                new StockSeries("MIDCO", "Mid Co.", "USD", b, b.get(b.size() - 2).close(), Instant.now(), false)));
        WidgetSize size2x4 = widget.descriptor().size("2x4");
        WidgetRenderContext ctxA = WidgetPreviewHarness.context(size2x4, LocalDate.of(2026, 9, 18));
        String innerA = widgetA.renderHtml(new WidgetInstance("wa", "stockChart", "2x4",
                Map.of("ticker", "CHEAP", "days", 14)), ctxA);
        String innerB = widgetB.renderHtml(new WidgetInstance("wb", "stockChart", "2x4",
                Map.of("ticker", "MIDCO", "days", 14)), ctxA);
        WidgetPreviewHarness.renderToPng("stock-2x4-side-by-side", List.of(
                new WidgetPageBuilder.Cell(size2x4, innerA),
                new WidgetPageBuilder.Cell(size2x4, innerB)));

        // 실패 표시도 한 번 떠 본다
        StockChartWidget failingWidget = new StockChartWidget(
                (symbol, days) -> { throw new SymbolNotFoundException(symbol); });
        String errorHtml = failingWidget.renderHtml(
                new WidgetInstance("we", "stockChart", "2x4", Map.of("ticker", "ZZZZQ")),
                WidgetPreviewHarness.context(size2x4, LocalDate.of(2026, 9, 18)));
        WidgetPreviewHarness.renderToPng("stock-error", List.of(new WidgetPageBuilder.Cell(size2x4, errorHtml)));
    }
}
