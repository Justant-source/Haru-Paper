package com.harupaper.server.widget.basic;

import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("DateHeaderWidget - 날짜 머리글")
class DateHeaderWidgetTest {

    // 2026-09-18은 금요일이다.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 18);
    private final DateHeaderWidget widget = new DateHeaderWidget();

    private WidgetRenderContext ctx() {
        WidgetSize size = widget.descriptor().size("4x1");
        return WidgetPreviewHarness.context(size, FRIDAY);
    }

    @Test
    @DisplayName("기본 패턴으로 년/월/일/요일 토큰을 치환한다")
    void defaultPatternFormatsDate() {
        WidgetInstance instance = new WidgetInstance("w1", "dateHeader", "4x1", Map.of());
        String html = widget.renderHtml(instance, ctx());
        assertTrue(html.contains("2026년 9월 18일 금요일"));
    }

    @Test
    @DisplayName("pattern props로 다른 형식을 쓸 수 있다")
    void customPattern() {
        WidgetInstance instance = new WidgetInstance("w1", "dateHeader", "4x1",
                Map.of("pattern", "YYYY-MM-DD (ddd)"));
        String html = widget.renderHtml(instance, ctx());
        assertTrue(html.contains("2026-09-18 (금)"));
    }

    @Test
    @DisplayName("pattern 문자열은 escape된다")
    void patternIsEscaped() {
        WidgetInstance instance = new WidgetInstance("w1", "dateHeader", "4x1",
                Map.of("pattern", "<b>YYYY</b>"));
        String html = widget.renderHtml(instance, ctx());
        assertTrue(html.contains("&lt;b&gt;2026&lt;/b&gt;"));
    }
}
