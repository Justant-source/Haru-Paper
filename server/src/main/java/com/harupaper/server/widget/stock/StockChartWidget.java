package com.harupaper.server.widget.stock;

import com.harupaper.server.widget.PropField;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetHtml;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * "미국 증시 일봉" 위젯 (.temp/07-위젯그리드-작업지시서.md 5.2절, 담당 S3).
 *
 * 인라인 SVG 캔들스틱 차트를 그린다. 데이터 조회 실패는 절대 예외로 밖에 나가지 않는다 —
 * {@link Widget} 구현 규칙 ②를 따라 항상 {@link WidgetHtml#errorBox}로 대체한다.
 */
@Slf4j
@Component
public class StockChartWidget implements Widget {

    static final String TYPE = "stockChart";
    // 위젯 props와 YahooChartStockQuoteProvider.SYMBOL_PATTERN이 같은 규칙을 쓴다(.temp/07 5.2절)
    private static final String TICKER_PATTERN = "^[A-Z]{1,5}([.-][A-Z]{1,2})?$";
    private static final int DEFAULT_DAYS = 14;
    private static final int MIN_DAYS = 5;
    private static final int MAX_DAYS = 60;

    // 흑백 1비트 프린터용 색상은 이 둘뿐이다(CLAUDE.md 코드 규칙, Widget 구현 규칙 ③)
    private static final String BLACK = "#000";
    private static final String WHITE = "#fff";

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M/d", Locale.KOREA);

    private final StockQuoteProvider quoteProvider;

    public StockChartWidget(StockQuoteProvider quoteProvider) {
        this.quoteProvider = quoteProvider;
    }

    @Override
    public WidgetDescriptor descriptor() {
        return new WidgetDescriptor(
                TYPE,
                "미국 증시 일봉",
                "티커를 넣으면 최근 며칠간의 일봉 캔들스틱 차트를 인쇄합니다",
                "chart",
                true,
                true,
                List.of(
                        WidgetSize.fixed(2, 4, "절반 폭 · 51×54mm"),
                        WidgetSize.fixed(4, 4, "전체 폭 · 104×54mm"),
                        WidgetSize.fixed(4, 6, "전체 폭 크게 · 104×82mm")
                ),
                "2x4",
                List.of(
                        PropField.string("ticker", "티커", true, null, 8, TICKER_PATTERN,
                                "AAPL", "미국 증시 티커(대문자). 예: AAPL, TSLA, BRK-B"),
                        PropField.integer("days", "표시 거래일 수", false, DEFAULT_DAYS, MIN_DAYS, MAX_DAYS,
                                "최근 N거래일의 일봉")
                )
        );
    }

    @Override
    public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
        Map<String, Object> props = instance.props();
        String ticker = stringProp(props, "ticker");
        String title = (ticker == null || ticker.isBlank()) ? descriptor().name() : ticker;

        if (ticker == null || ticker.isBlank()) {
            // props가 비정상이어도 NPE 없이 errorBox(.temp/07 5.2절)
            log.warn("stockChart 위젯: ticker props가 비어 있음 (instance id={})", instance.id());
            return WidgetHtml.errorBox(title, "티커를 찾을 수 없습니다", ctx);
        }
        int days = intProp(props, "days", DEFAULT_DAYS, MIN_DAYS, MAX_DAYS);

        StockSeries series;
        try {
            series = quoteProvider.getDaily(ticker, days);
        } catch (SymbolNotFoundException e) {
            log.warn("stockChart 위젯: 티커를 찾을 수 없음 ticker={}", ticker);
            return WidgetHtml.errorBox(ticker, "티커를 찾을 수 없습니다", ctx);
        } catch (RuntimeException e) {
            log.warn("stockChart 위젯: 시세 조회 실패 ticker={}: {}", ticker, e.getMessage());
            return WidgetHtml.errorBox(ticker, "시세를 가져오지 못했습니다", ctx);
        }

        if (series.candles().isEmpty()) {
            log.warn("stockChart 위젯: 유효한 봉이 0개 ticker={}", ticker);
            return WidgetHtml.errorBox(ticker, "시세를 가져오지 못했습니다", ctx);
        }

        try {
            return StockChartRenderer.render(series, ctx);
        } catch (RuntimeException e) {
            // 그리기 단계에서 무엇이 터지든 인쇄 전체를 막지 않는다(Widget 구현 규칙 ②)
            log.warn("stockChart 위젯: 렌더 실패 ticker={}: {}", ticker, e.getMessage(), e);
            return WidgetHtml.errorBox(ticker, "시세를 가져오지 못했습니다", ctx);
        }
    }

    private static String stringProp(Map<String, Object> props, String key) {
        if (props == null) {
            return null;
        }
        Object v = props.get(key);
        return (v instanceof String s && !s.isBlank()) ? s.trim() : null;
    }

    private static int intProp(Map<String, Object> props, String key, int fallback, int min, int max) {
        if (props == null) {
            return fallback;
        }
        Object v = props.get(key);
        Integer parsed = null;
        if (v instanceof Number n) {
            parsed = n.intValue();
        } else if (v instanceof String s) {
            try {
                parsed = Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                // fallback으로 내려간다
            }
        }
        if (parsed == null) {
            return fallback;
        }
        return Math.max(min, Math.min(max, parsed));
    }
}
