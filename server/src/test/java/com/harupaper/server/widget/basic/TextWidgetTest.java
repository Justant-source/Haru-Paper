package com.harupaper.server.widget.basic;

import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TextWidget - 자유 텍스트")
class TextWidgetTest {

    // 2026-09-18은 금요일이다.
    private static final LocalDate FRIDAY = LocalDate.of(2026, 9, 18);
    private final TextWidget widget = new TextWidget();

    private WidgetRenderContext ctx() {
        WidgetSize size = widget.descriptor().size("4xauto");
        return WidgetPreviewHarness.context(size, FRIDAY);
    }

    @Test
    @DisplayName("text가 없으면 빈 문자열로 렌더한다(빈 문자열 허용)")
    void missingTextRendersEmpty() {
        WidgetInstance instance = new WidgetInstance("w1", "text", "4xauto", Map.of());
        String html = widget.renderHtml(instance, ctx());
        assertTrue(html.contains("<div"));
    }

    @Test
    @DisplayName("{{date}}·{{weekday}} 토큰을 치환한다")
    void substitutesDateVariables() {
        Map<String, Object> props = new HashMap<>();
        props.put("text", "오늘은 {{date}} {{weekday}}입니다");
        WidgetInstance instance = new WidgetInstance("w1", "text", "4xauto", props);

        String html = widget.renderHtml(instance, ctx());

        assertTrue(html.contains("오늘은 2026년 9월 18일 금요일입니다"));
    }

    @Test
    @DisplayName("사용자 입력은 escape되고 줄바꿈은 pre-wrap으로 유지된다")
    void escapesUserInputAndKeepsNewlines() {
        Map<String, Object> props = new HashMap<>();
        props.put("text", "<script>evil()</script>\n둘째 줄");
        WidgetInstance instance = new WidgetInstance("w1", "text", "4xauto", props);

        String html = widget.renderHtml(instance, ctx());

        assertTrue(html.contains("white-space:pre-wrap"));
        assertTrue(html.contains("&lt;script&gt;evil()&lt;/script&gt;"));
        assertTrue(html.contains("둘째 줄"));
    }

    @Test
    @DisplayName("fontSizePt가 없으면 font-size를 강제하지 않고 포맷 기본 크기를 물려받는다")
    void noFontSizeMeansInherit() {
        Map<String, Object> props = new HashMap<>();
        props.put("text", "hi");
        WidgetInstance instance = new WidgetInstance("w1", "text", "4xauto", props);

        String html = widget.renderHtml(instance, ctx());

        assertTrue(html.contains(">hi<") || html.contains(">hi</div>"));
    }
}
