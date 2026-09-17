package com.harupaper.server.render;

import com.harupaper.server.device.PrinterProfile;
import com.harupaper.server.format.FormatDocument;
import com.harupaper.server.format.FormatMeta;
import com.harupaper.server.format.FormatStyle;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetRegistry;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HtmlTemplateBuilder가 WidgetRegistry에 등록된 위젯을 조립하는 방식을 검증한다
 * (.temp/07-위젯그리드-작업지시서.md 4·6절). 날씨·에셋 조회는 이제 이 클래스의 책임이 아니므로
 * 여기서는 가짜 Widget 빈으로 조립·오류 처리 로직만 본다.
 */
@DisplayName("HtmlTemplateBuilder - 위젯 조립·오류 처리")
class HtmlTemplateBuilderTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 18);

    @Test
    @DisplayName("정상 위젯이 돌려준 HTML 조각이 그리드 페이지에 그대로 들어간다")
    void rendersWidgetHtml() {
        Widget widget = fixedWidget("ok", ctx -> "<p>hello-widget</p>");
        HtmlTemplateBuilder builder = new HtmlTemplateBuilder(new WidgetRegistry(List.of(widget)));
        FormatDocument doc = document(instance("w1", "ok", "4x1"));

        String html = builder.buildHtml(doc, DATE, PrinterProfile.DEFAULT, "user-1");

        assertTrue(html.contains("hello-widget"));
        assertTrue(html.contains("grid-auto-flow: row dense"));
    }

    @Test
    @DisplayName("renderHtml이 예외를 던지면 errorBox로 대체하고 인쇄 전체를 막지 않는다")
    void widgetExceptionBecomesErrorBox() {
        Widget widget = fixedWidget("boom", ctx -> {
            throw new RuntimeException("fail");
        });
        HtmlTemplateBuilder builder = new HtmlTemplateBuilder(new WidgetRegistry(List.of(widget)));
        FormatDocument doc = document(instance("w1", "boom", "4x1"));

        String html = builder.buildHtml(doc, DATE, PrinterProfile.DEFAULT, "user-1");

        assertTrue(html.contains("위젯을 그리지 못했습니다"));
        assertTrue(html.contains("boom"));
    }

    @Test
    @DisplayName("등록되지 않은 type은 \"알 수 없는 위젯\" 오류 상자로 대체되고, type 이름은 escape된다")
    void unknownTypeBecomesEscapedErrorBox() {
        HtmlTemplateBuilder builder = new HtmlTemplateBuilder(new WidgetRegistry(List.of()));
        FormatDocument doc = document(instance("w1", "<b>ghost</b>", "4x1"));

        String html = builder.buildHtml(doc, DATE, PrinterProfile.DEFAULT, "user-1");

        assertTrue(html.contains("알 수 없는 위젯"));
        assertFalse(html.contains("<b>ghost</b>"));
        assertTrue(html.contains("&lt;b&gt;ghost&lt;/b&gt;"));
    }

    @Test
    @DisplayName("카탈로그에 없는 size가 저장돼 있으면 기본 크기로 대체해서 렌더한다")
    void unknownSizeFallsBackToDefault() {
        Widget widget = fixedWidget("ok", ctx -> "<p>x</p>");
        HtmlTemplateBuilder builder = new HtmlTemplateBuilder(new WidgetRegistry(List.of(widget)));
        FormatDocument doc = document(instance("w1", "ok", "9x9"));

        String html = builder.buildHtml(doc, DATE, PrinterProfile.DEFAULT, "user-1");

        assertTrue(html.contains("<p>x</p>"));
    }

    private FormatDocument document(WidgetInstance... widgets) {
        return new FormatDocument(3, new FormatMeta("t", "", "", null), FormatStyle.defaults(), List.of(widgets));
    }

    private WidgetInstance instance(String id, String type, String size) {
        return new WidgetInstance(id, type, size, Map.of());
    }

    private interface RenderFn {
        String render(WidgetRenderContext ctx);
    }

    private Widget fixedWidget(String type, RenderFn fn) {
        WidgetDescriptor descriptor = new WidgetDescriptor(
                type, type, "설명", "text", false, true,
                List.of(WidgetSize.fixed(4, 1, "L")), "4x1", List.of());
        return new Widget() {
            @Override
            public WidgetDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
                return fn.render(ctx);
            }
        };
    }
}
