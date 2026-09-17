package com.harupaper.server.widget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /api/widgets 응답 모양 검증(.temp/07-위젯그리드-작업지시서.md 6절).
 * 앱은 이 응답만 보고 카탈로그·설정 폼을 자동 생성하므로, kind가 소문자 문자열인지·
 * autoHeight가 포함되는지가 중요하다.
 */
@DisplayName("WidgetController - GET /api/widgets 응답 모양")
class WidgetControllerTest {

    @Test
    @DisplayName("grid 정보와 widgets 배열을 내려주고, kind는 소문자 문자열, autoHeight가 포함된다")
    void catalogJsonShape() {
        WidgetDescriptor descriptor = new WidgetDescriptor(
                "fake", "가짜", "설명", "text", false, true,
                List.of(WidgetSize.fixed(2, 4, "절반"), WidgetSize.auto(2, "자동")),
                "2x4",
                List.of(PropField.enumOf("align", "정렬", false, "left",
                        List.of(new PropField.Option("left", "왼쪽")), null))
        );
        Widget widget = new Widget() {
            @Override
            public WidgetDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
                return "";
            }
        };
        WidgetRegistry registry = new WidgetRegistry(List.of(widget));
        WidgetController controller = new WidgetController(registry);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.valueToTree(controller.catalog());

        assertEquals(4, root.path("grid").path("columns").asInt());
        assertEquals(2.0, root.path("grid").path("gapMm").asDouble());
        assertEquals(20, root.path("grid").path("maxWidgets").asInt());

        JsonNode widgets = root.path("widgets");
        assertEquals(1, widgets.size());
        JsonNode fake = widgets.get(0);
        assertEquals("fake", fake.path("type").asText());
        assertEquals("2x4", fake.path("defaultSize").asText());

        boolean sawAutoHeightTrue = false;
        boolean sawAutoHeightFalse = false;
        for (JsonNode size : fake.path("sizes")) {
            assertTrue(size.has("autoHeight"));
            if (size.path("autoHeight").asBoolean()) {
                sawAutoHeightTrue = true;
                assertEquals("4xauto", size.path("id").asText());
            } else {
                sawAutoHeightFalse = true;
            }
        }
        assertTrue(sawAutoHeightTrue, "자동 높이 크기가 있어야 한다");
        assertTrue(sawAutoHeightFalse, "고정 크기도 있어야 한다");

        JsonNode fields = fake.path("fields");
        assertEquals(1, fields.size());
        assertEquals("enum", fields.get(0).path("kind").asText());
    }

    @Test
    @DisplayName("catalog=false 위젯도 목록에 포함된다(이전 버전 포맷 호환)")
    void includesNonCatalogWidgets() {
        WidgetDescriptor descriptor = new WidgetDescriptor(
                "legacy", "레거시", "설명", "text", false, false,
                List.of(WidgetSize.fixed(4, 1, "L")), "4x1", List.of());
        Widget widget = new Widget() {
            @Override
            public WidgetDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
                return "";
            }
        };
        WidgetController controller = new WidgetController(new WidgetRegistry(List.of(widget)));

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.valueToTree(controller.catalog());

        assertEquals(1, root.path("widgets").size());
        assertEquals(false, root.path("widgets").get(0).path("catalog").asBoolean());
    }
}
