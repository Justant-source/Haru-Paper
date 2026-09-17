package com.harupaper.server.widget.weather;

import com.harupaper.server.weather.WeatherCodes;
import com.harupaper.server.weather.WeatherCodes.IconCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WeatherIcons: 8종 아이콘이 전부 그려지는지, stroke-width 역산이 3px 이상을 보장하는지.
 * (.temp/07-위젯그리드-작업지시서.md 5.3절, Widget.java javadoc ③ "선 굵기 3px 이상")
 */
@DisplayName("WeatherIcons: WMO 코드 → 흑백 선 SVG")
class WeatherIconsTest {

    private static final Pattern STROKE_WIDTH = Pattern.compile("stroke-width=\"([0-9.]+)\"");

    @ParameterizedTest(name = "{0}")
    @EnumSource(IconCategory.class)
    @DisplayName("8종 전부 svg 태그와 viewBox를 낸다")
    void allCategoriesRenderSvg(IconCategory category) {
        String svg = WeatherIcons.svg(category, 64);
        assertThat(svg).startsWith("<svg ");
        assertThat(svg).contains("viewBox=\"0 0 64 64\"");
        assertThat(svg).endsWith("</svg>");
        // 흑/백만 쓴다 — fill은 #000/#fff, stroke는 항상 #000
        assertThat(svg).doesNotContain("fill=\"#f00\"").doesNotContain("rgb(");
    }

    @ParameterizedTest(name = "sizePx={0}")
    @ValueSource(ints = {16, 24, 32, 48, 64, 96, 189})
    @DisplayName("요청 크기가 달라져도 실제 렌더된 선 굵기는 항상 3px 이상이다")
    void strokeWidthAlwaysAtLeast3PxOnScreen(int sizePx) {
        String svg = WeatherIcons.svg(IconCategory.RAIN, sizePx);
        Matcher m = STROKE_WIDTH.matcher(svg);
        assertThat(m.find()).as("stroke-width 속성이 있어야 한다").isTrue();
        double strokeWidthViewBoxUnits = Double.parseDouble(m.group(1));
        // 1 viewBox 단위 = sizePx/64 실제 px
        double renderedPx = strokeWidthViewBoxUnits * sizePx / 64.0;
        assertThat(renderedPx).isGreaterThanOrEqualTo(3.0 - 0.01); // 반올림 오차 허용
    }

    @Test
    @DisplayName("weatherCode → category 매핑은 WeatherCodes에만 있다(svgForCode가 그대로 위임)")
    void svgForCodeDelegatesToWeatherCodes() {
        String direct = WeatherIcons.svg(WeatherCodes.category(95), 32); // 천둥번개
        String viaCode = WeatherIcons.svgForCode(95, 32);
        assertThat(viaCode).isEqualTo(direct);
    }

    @Test
    @DisplayName("아이콘 크기가 0 이하로 들어와도 예외 없이 최소 굵기로 떨어진다(방어적)")
    void handlesNonPositiveSizeDefensively() {
        assertThat(WeatherIcons.strokeWidthForSize(0)).isPositive();
        assertThat(WeatherIcons.strokeWidthForSize(-5)).isPositive();
    }
}
