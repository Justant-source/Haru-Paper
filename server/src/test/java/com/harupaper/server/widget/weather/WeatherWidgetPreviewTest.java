package com.harupaper.server.widget.weather;

import com.harupaper.server.weather.DayForecast;
import com.harupaper.server.weather.HourPoint;
import com.harupaper.server.weather.WeatherCodes;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * weather 위젯을 실제로 PNG로 떠서 눈으로 확인하는 개발용 테스트.
 * 합격/불합격 판정은 하지 않는다(WidgetPreviewHarness 사용법 그대로) — build/widget-previews/*.png를
 * Read 도구로 직접 열어 글자 겹침·잘림·아이콘 판별 가능 여부를 확인한다.
 *
 * 평소 `./gradlew test`에는 안 돈다(Chromium을 띄워 느리다). 눈으로 볼 때만:
 *   WIDGET_PREVIEW=1 ./gradlew test --tests '*WeatherWidgetPreviewTest*'
 */
@EnabledIfEnvironmentVariable(named = "WIDGET_PREVIEW", matches = "1")
@DisplayName("weather 위젯 미리보기(PNG)")
class WeatherWidgetPreviewTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 18);

    private static DayForecast richForecast() {
        List<HourPoint> hours = List.of(
                new HourPoint(0, 17, 10, 1),
                new HourPoint(3, 16, 5, 0),
                new HourPoint(6, 18, 10, 2),
                new HourPoint(9, 22, 20, 2),
                new HourPoint(12, 27, 30, 61),
                // 15시는 일부러 빼서 미리보기에서 "–"가 어떻게 보이는지 확인한다
                new HourPoint(18, 24, 50, 80),
                new HourPoint(21, 20, 40, 95)
        );
        return new DayForecast(DATE, 18, 27, 30, 61, "약한 비", hours,
                Instant.now().minusSeconds(3600), false);
    }

    private static Map<String, Object> locationProps(String label, double lat, double lon) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("label", label);
        location.put("lat", lat);
        location.put("lon", lon);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("location", location);
        return props;
    }

    @Test
    @DisplayName("세 크기(2x4·4x2·4x4)를 PNG로 뜬다")
    void previewThreeSizes() throws Exception {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> richForecast());
        Map<String, Object> props = locationProps("경기도 성남시 분당구", 37.3827, 127.1189);

        for (String sizeId : List.of("2x4", "4x2", "4x4")) {
            WidgetSize size = widget.descriptor().size(sizeId);
            WidgetRenderContext ctx = WidgetPreviewHarness.context(size, DATE);
            String inner = widget.renderHtml(new WidgetInstance("w1", "weather", sizeId, props), ctx);
            WidgetPreviewHarness.renderToPng("weather-" + sizeId, List.of(new WidgetPageBuilder.Cell(size, inner)));
        }
    }

    @Test
    @DisplayName("긴 위치 라벨(말줄임 확인)로도 떠 본다")
    void previewLongLabel() throws Exception {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> richForecast());
        Map<String, Object> props = locationProps("경상남도 창원시 마산합포구", 35.2, 128.57);
        WidgetSize size = WidgetSize.fixed(4, 2, "한 줄 요약");
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, DATE);
        String inner = widget.renderHtml(new WidgetInstance("w1", "weather", "4x2", props), ctx);
        WidgetPreviewHarness.renderToPng("weather-4x2-long-label", List.of(new WidgetPageBuilder.Cell(size, inner)));
    }

    @Test
    @DisplayName("8종 아이콘을 한 장에 늘어놓는다")
    void previewIconSheet() throws Exception {
        List<WidgetPageBuilder.Cell> cells = new ArrayList<>();
        int iconPx = 96;
        for (WeatherCodes.IconCategory category : WeatherCodes.IconCategory.values()) {
            String svg = WeatherIcons.svg(category, iconPx);
            String cellHtml = "<div style=\"width:100%;height:100%;display:flex;flex-direction:column;"
                    + "align-items:center;justify-content:center;gap:4px;\">" + svg
                    + "<div style=\"font-size:11px;\">" + category + "</div></div>";
            cells.add(new WidgetPageBuilder.Cell(WidgetSize.fixed(1, 2, category.name()), cellHtml));
        }
        WidgetPreviewHarness.renderToPng("weather-icons-sheet", cells);
    }
}
