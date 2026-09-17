package com.harupaper.server.widget.weather;

import com.harupaper.server.weather.DayForecast;
import com.harupaper.server.weather.ForecastProvider;
import com.harupaper.server.weather.HourPoint;
import com.harupaper.server.weather.WeatherCodes;
import com.harupaper.server.widget.PropField;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetHtml;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * `weather` — 오늘의 날씨 위젯. .temp/07-위젯그리드-작업지시서.md 5.3절.
 *
 * 위치는 사용자가 고른 대한민국 시·군·구(props.location)뿐이다 — 기본 위치가 없다(필수 필드).
 * 크기 3종(2x4 절반 폭 / 4x2 한 줄 / 4x4 시간대별)은 전부 고정 크기라 박스를 넘는 내용은 잘린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherWidget implements Widget {

    // 서울(위도 37.5N)에서 06시~21시는 낮 시간대 위주로 3시간 간격 6칸을 보여준다 [기본값, .temp/07 5.3절]
    private static final int[] HOURLY_SLOTS = {6, 9, 12, 15, 18, 21};

    private static final String ZONE_KST = "Asia/Seoul"; // 시간대는 Asia/Seoul 고정(CLAUDE.md 코드 규칙)
    private static final DateTimeFormatter STALE_FORMATTER =
            DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.KOREAN).withZone(ZoneId.of(ZONE_KST));

    private final ForecastProvider forecastProvider;

    private static final WidgetDescriptor DESCRIPTOR = new WidgetDescriptor(
            "weather",
            "오늘의 날씨",
            "선택한 지역의 오늘 예보를 보여줍니다",
            "weather",
            true,
            true,
            List.of(
                    WidgetSize.fixed(2, 4, "절반 폭 · 51×54mm"),
                    WidgetSize.fixed(4, 2, "한 줄 요약 · 104×26mm"),
                    WidgetSize.fixed(4, 4, "시간대별 · 104×54mm")
            ),
            "2x4",
            List.of(PropField.koreaLocation("location", "위치", true, null, "대한민국 시·군·구를 고릅니다"))
    );

    @Override
    public WidgetDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
        // Widget.java javadoc ②: 조회 실패는 물론, props가 비정상이어도 예외를 밖으로 던지지 않는다
        try {
            Map<String, Object> props = instance.props() == null ? Map.of() : instance.props();
            Location location = parseLocation(props.get("location"));
            if (location == null) {
                return WidgetHtml.errorBox("오늘의 날씨", "위치가 설정되지 않았습니다", ctx);
            }

            DayForecast forecast;
            try {
                forecast = forecastProvider.getForecast(location.lat(), location.lon(), ctx.targetDate());
            } catch (Exception e) {
                log.warn("날씨 조회 실패: location={}, date={}, reason={}", location.label(), ctx.targetDate(), e.getMessage());
                return WidgetHtml.errorBox(location.label(), "날씨 정보를 가져오지 못했습니다", ctx);
            }
            if (forecast == null) {
                return WidgetHtml.errorBox(location.label(), "날씨 정보를 가져오지 못했습니다", ctx);
            }

            return switch (instance.size()) {
                case "2x4" -> renderHalf(location, forecast, ctx);
                case "4x2" -> renderRow(location, forecast, ctx);
                case "4x4" -> renderHourly(location, forecast, ctx);
                default -> WidgetHtml.errorBox(location.label(), "지원하지 않는 크기입니다", ctx);
            };
        } catch (Exception e) {
            // 여기까지 왔다는 건 props 형태가 예상과 달라 파싱 중 뭔가 던졌다는 뜻 — 그래도 인쇄 전체는 막지 않는다
            log.warn("날씨 위젯 렌더 실패(비정상 props 추정): {}", e.getMessage());
            return WidgetHtml.errorBox("오늘의 날씨", "날씨 정보를 가져오지 못했습니다", ctx);
        }
    }

    // ---- 위치 파싱 -----------------------------------------------------------------------

    private record Location(String label, double lat, double lon) {
    }

    @SuppressWarnings("unchecked")
    private Location parseLocation(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Object labelObj = map.get("label");
        Double lat = toDouble(map.get("lat"));
        Double lon = toDouble(map.get("lon"));
        if (!(labelObj instanceof String label) || label.isBlank() || lat == null || lon == null) {
            return null;
        }
        return new Location(label, lat, lon);
    }

    private Double toDouble(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return null;
    }

    /**
     * 위치 label이 길면 시·도를 떼고 이름 부분만 쓴다("경기도 성남시 분당구" → "성남시 분당구").
     * 첫 공백 뒤를 쓰는 방식 — label이 한 단어(시·도 자체)면 그대로 둔다.
     */
    private static String shortLabel(String label) {
        int idx = label.indexOf(' ');
        return idx < 0 ? label : label.substring(idx + 1);
    }

    // ---- 크기별 렌더 -----------------------------------------------------------------------

    private String renderHalf(Location location, DayForecast forecast, WidgetRenderContext ctx) {
        int iconPx = ctx.mm(16);
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"width:100%;height:100%;box-sizing:border-box;overflow:hidden;")
                .append("display:flex;flex-direction:column;align-items:center;justify-content:space-evenly;")
                .append("text-align:center;padding:").append(ctx.mm(2)).append("px;\">");
        html.append(labelDiv(shortLabel(location.label()), ctx.ptCss(9)));
        html.append(WeatherIcons.svg(WeatherCodes.category(forecast.weatherCode()), iconPx));
        html.append(textDiv(forecast.skyText(), ctx.ptCss(8), false));
        html.append(textDiv(tempsLine(forecast), ctx.ptCss(10), true));
        html.append(textDiv(precipLine(forecast), ctx.ptCss(8.5), false));
        appendStale(html, forecast, ctx, 7);
        html.append("</div>");
        return html.toString();
    }

    private String renderRow(Location location, DayForecast forecast, WidgetRenderContext ctx) {
        int iconPx = ctx.mm(12);
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"width:100%;height:100%;box-sizing:border-box;overflow:hidden;")
                .append("display:flex;flex-direction:row;align-items:center;")
                .append("padding:0 ").append(ctx.mm(2)).append("px;gap:").append(ctx.mm(3)).append("px;\">");
        html.append("<div style=\"flex:0 0 auto;\">").append(WeatherIcons.svg(WeatherCodes.category(forecast.weatherCode()), iconPx)).append("</div>");
        html.append("<div style=\"flex:1 1 auto;min-width:0;text-align:left;\">")
                .append(labelDiv(shortLabel(location.label()) + " · " + forecast.skyText(), ctx.ptCss(9)))
                .append("</div>");
        html.append("<div style=\"flex:0 0 auto;text-align:right;white-space:nowrap;\">")
                .append(textDiv(tempsLine(forecast), ctx.ptCss(9), true))
                .append(textDiv(precipLine(forecast), ctx.ptCss(7.5), false))
                .append("</div>");
        html.append("</div>");
        return html.toString();
    }

    private String renderHourly(Location location, DayForecast forecast, WidgetRenderContext ctx) {
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"width:100%;height:100%;box-sizing:border-box;overflow:hidden;")
                .append("display:flex;flex-direction:row;align-items:stretch;padding:").append(ctx.mm(2)).append("px;")
                .append("gap:").append(ctx.mm(2)).append("px;\">");

        // 왼쪽 1/3: renderHalf와 같은 요약(더 좁으니 아이콘·글자만 살짝 줄인다)
        int summaryIconPx = ctx.mm(11);
        html.append("<div style=\"flex:0 0 33%;min-width:0;display:flex;flex-direction:column;")
                .append("align-items:center;justify-content:space-evenly;text-align:center;overflow:hidden;\">");
        html.append(labelDiv(shortLabel(location.label()), ctx.ptCss(7.5)));
        html.append(WeatherIcons.svg(WeatherCodes.category(forecast.weatherCode()), summaryIconPx));
        html.append(textDiv(forecast.skyText(), ctx.ptCss(7.5), false));
        html.append(textDiv(tempsLine(forecast), ctx.ptCss(8.5), true));
        html.append(textDiv(precipLine(forecast), ctx.ptCss(7), false));
        appendStale(html, forecast, ctx, 7);
        html.append("</div>");

        // 오른쪽 2/3: 06·09·12·15·18·21시 6칸
        html.append("<div style=\"flex:1 1 auto;min-width:0;display:grid;")
                .append("grid-template-columns:repeat(").append(HOURLY_SLOTS.length).append(",1fr);align-items:center;\">");
        int hourIconPx = ctx.mm(7);
        for (int hour : HOURLY_SLOTS) {
            Optional<HourPoint> point = findHour(forecast.hours(), hour);
            html.append("<div style=\"display:flex;flex-direction:column;align-items:center;text-align:center;overflow:hidden;\">");
            html.append(textDiv(String.format(Locale.ROOT, "%02d시", hour), ctx.ptCss(7), true));
            if (point.isPresent()) {
                html.append(WeatherIcons.svg(WeatherCodes.category(point.get().weatherCode()), hourIconPx));
                html.append(textDiv(degreeText(point.get().temp()), ctx.ptCss(7), false));
                html.append(textDiv(percentText(point.get().precipProb()), ctx.ptCss(7), false));
            } else {
                html.append(textDiv("–", ctx.ptCss(7), false));
            }
            html.append("</div>");
        }
        html.append("</div>");

        html.append("</div>");
        return html.toString();
    }

    // ---- 공통 조각 -----------------------------------------------------------------------

    private Optional<HourPoint> findHour(List<HourPoint> hours, int hour) {
        return hours.stream().filter(h -> h.hour() == hour).findFirst();
    }

    private String tempsLine(DayForecast forecast) {
        return "최고 " + degreeText(forecast.tempMax()) + " · 최저 " + degreeText(forecast.tempMin());
    }

    private String precipLine(DayForecast forecast) {
        return "강수확률 " + percentText(forecast.precipProb());
    }

    private String degreeText(Integer value) {
        return value == null ? "–" : value + "°";
    }

    private String percentText(Integer value) {
        return value == null ? "–" : value + "%";
    }

    private String labelDiv(String text, String fontSize) {
        return "<div style=\"font-weight:bold;font-size:" + fontSize + ";white-space:nowrap;overflow:hidden;"
                + "text-overflow:ellipsis;max-width:100%;\">" + WidgetHtml.escape(text) + "</div>";
    }

    private String textDiv(String text, String fontSize, boolean bold) {
        return "<div style=\"font-size:" + fontSize + (bold ? ";font-weight:bold;" : ";")
                + "white-space:nowrap;overflow:hidden;text-overflow:ellipsis;max-width:100%;\">"
                + WidgetHtml.escape(text) + "</div>";
    }

    /** stale이면 "(MM-dd HH:mm 기준)"을 작은 글씨로 덧붙인다(.temp/07 5.3절, weather.md 6절) */
    private void appendStale(StringBuilder html, DayForecast forecast, WidgetRenderContext ctx, double fontSizePt) {
        if (forecast.stale()) {
            html.append(textDiv("(" + STALE_FORMATTER.format(instantOrNow(forecast.fetchedAt())) + " 기준)",
                    ctx.ptCss(fontSizePt), false));
        }
    }

    private Instant instantOrNow(Instant instant) {
        return instant == null ? Instant.now() : instant;
    }
}
