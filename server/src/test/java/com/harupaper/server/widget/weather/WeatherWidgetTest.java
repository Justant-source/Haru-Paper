package com.harupaper.server.widget.weather;

import com.harupaper.server.weather.DayForecast;
import com.harupaper.server.weather.ForecastProvider;
import com.harupaper.server.weather.HourPoint;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WeatherWidget: descriptor·세 크기 렌더·실패/비정상 props 처리·escape.
 * (.temp/07-위젯그리드-작업지시서.md 5.3절, Widget.java javadoc)
 */
@DisplayName("WeatherWidget")
class WeatherWidgetTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 18);

    private static DayForecast sampleForecast() {
        List<HourPoint> hours = List.of(
                new HourPoint(6, 18, 10, 1),
                new HourPoint(9, 22, 20, 2),
                new HourPoint(12, 27, 30, 3),
                // 15시는 일부러 뺀다 — 4x4에서 "–"로 나와야 한다
                new HourPoint(18, 24, 40, 61),
                new HourPoint(21, 20, 50, 0)
        );
        return new DayForecast(DATE, 18, 27, 30, 2, "구름 조금", hours, Instant.parse("2026-09-18T05:00:00Z"), false);
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

    private WidgetRenderContext ctx(WidgetSize size) {
        return WidgetPreviewHarness.context(size, DATE);
    }

    @Test
    @DisplayName("descriptor: type·크기 3종·필수 location 필드")
    void descriptor() {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> sampleForecast());
        var descriptor = widget.descriptor();

        assertThat(descriptor.type()).isEqualTo("weather");
        assertThat(descriptor.dynamic()).isTrue();
        assertThat(descriptor.catalog()).isTrue();
        assertThat(descriptor.sizes()).extracting(WidgetSize::id).containsExactly("2x4", "4x2", "4x4");
        assertThat(descriptor.defaultSize()).isEqualTo("2x4");
        assertThat(descriptor.fields()).hasSize(1);
        assertThat(descriptor.fields().get(0).key()).isEqualTo("location");
        assertThat(descriptor.fields().get(0).required()).isTrue();
    }

    @Test
    @DisplayName("2x4: 위치 이름(시·도 뗀)·기온·강수확률이 들어간다")
    void rendersHalfSize() {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> sampleForecast());
        WidgetInstance instance = new WidgetInstance("w1", "weather", "2x4",
                locationProps("경기도 성남시 분당구", 37.38, 127.12));

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(2, 4, "절반")));

        assertThat(html).contains("성남시 분당구"); // 첫 공백(시·도) 뒤만
        assertThat(html).doesNotContain("경기도");
        assertThat(html).contains("최고 27°").contains("최저 18°");
        assertThat(html).contains("강수확률 30%");
        assertThat(html).contains("<svg ");
    }

    @Test
    @DisplayName("4x2: 한 줄 요약에 위치·하늘상태·기온·강수확률이 들어간다")
    void rendersRowSize() {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> sampleForecast());
        WidgetInstance instance = new WidgetInstance("w1", "weather", "4x2",
                locationProps("서울특별시 강남구", 37.51, 127.04));

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(4, 2, "한줄")));

        assertThat(html).contains("강남구");
        assertThat(html).contains("구름 조금");
        assertThat(html).contains("최고 27°").contains("최저 18°");
    }

    @Test
    @DisplayName("4x4: 6칸 시간대 중 데이터 없는 15시는 '–'로 나온다")
    void rendersHourlySizeWithMissingHourAsDash() {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> sampleForecast());
        WidgetInstance instance = new WidgetInstance("w1", "weather", "4x4",
                locationProps("서울특별시 강남구", 37.51, 127.04));

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(4, 4, "시간대별")));

        assertThat(html).contains("06시").contains("09시").contains("12시").contains("15시").contains("18시").contains("21시");
        assertThat(html).contains("–"); // 15시 자리
        assertThat(html).contains("22°"); // 09시 기온
    }

    @Test
    @DisplayName("stale이면 'MM-dd HH:mm 기준' 표시가 붙는다")
    void showsStaleMarker() {
        DayForecast stale = new DayForecast(DATE, 18, 27, 30, 2, "구름 조금", List.of(),
                Instant.parse("2026-09-18T05:00:00Z"), true);
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> stale);
        WidgetInstance instance = new WidgetInstance("w1", "weather", "2x4",
                locationProps("서울특별시 강남구", 37.51, 127.04));

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(2, 4, "절반")));

        assertThat(html).contains("09-18 14:00 기준"); // Asia/Seoul(UTC+9)로 변환된 시각
    }

    @Test
    @DisplayName("외부에서 온 문자열(위치 label)은 escape된다")
    void escapesUntrustedLabel() {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> sampleForecast());
        WidgetInstance instance = new WidgetInstance("w1", "weather", "2x4",
                locationProps("서울 <script>alert(1)</script>", 37.51, 127.04));

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(2, 4, "절반")));

        assertThat(html).doesNotContain("<script>");
        assertThat(html).contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("location이 없으면 예외 없이 errorBox를 낸다")
    void missingLocationProducesErrorBox() {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> sampleForecast());
        WidgetInstance instance = new WidgetInstance("w1", "weather", "2x4", Map.of());

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(2, 4, "절반")));

        assertThat(html).contains("위치가 설정되지 않았습니다");
    }

    @Test
    @DisplayName("props가 null이어도 NPE 없이 errorBox를 낸다")
    void nullPropsProducesErrorBoxWithoutException() {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> sampleForecast());
        WidgetInstance instance = new WidgetInstance("w1", "weather", "2x4", null);

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(2, 4, "절반")));

        assertThat(html).contains("위치가 설정되지 않았습니다");
    }

    @Test
    @DisplayName("location 형태가 이상해도(문자열, lat/lon 누락 등) errorBox를 낸다")
    void malformedLocationProducesErrorBox() {
        WeatherWidget widget = new WeatherWidget((lat, lon, date) -> sampleForecast());
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("location", "그냥 문자열"); // Map이 아님
        WidgetInstance instance = new WidgetInstance("w1", "weather", "2x4", props);

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(2, 4, "절반")));

        assertThat(html).contains("위치가 설정되지 않았습니다");
    }

    @Test
    @DisplayName("예보 조회 실패는 인쇄 전체를 막지 않고 errorBox로 표시한다")
    void forecastFailureProducesErrorBox() {
        ForecastProvider failing = (lat, lon, date) -> {
            throw new RuntimeException("network down");
        };
        WeatherWidget widget = new WeatherWidget(failing);
        WidgetInstance instance = new WidgetInstance("w1", "weather", "2x4",
                locationProps("서울특별시 강남구", 37.51, 127.04));

        String html = widget.renderHtml(instance, ctx(WidgetSize.fixed(2, 4, "절반")));

        assertThat(html).contains("강남구");
        assertThat(html).contains("날씨 정보를 가져오지 못했습니다");
    }
}
