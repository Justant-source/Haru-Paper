package com.harupaper.server.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.widget.PropField;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPropsValidator;
import com.harupaper.server.widget.WidgetRegistry;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * FormatValidator v3(위젯 그리드) 검증(.temp/07-위젯그리드-작업지시서.md 3절).
 * 이 사본에는 letter/stock/weather 위젯이 없으므로 테스트 전용 가짜 위젯으로 검증한다 —
 * FormatValidator 자체는 위젯 종류에 의존하지 않고 WidgetRegistry/WidgetDescriptor만 본다.
 */
@DisplayName("FormatValidator - v3(위젯 그리드) 검증")
class FormatValidatorTest {

    private FormatValidator validator;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        AssetRepository assetRepository = mock(AssetRepository.class);
        WidgetPropsValidator propsValidator = new WidgetPropsValidator(assetRepository, false);
        WidgetRegistry registry = new WidgetRegistry(List.of(new FakeWidget()));
        validator = new FormatValidator(registry, propsValidator);
    }

    private Map<String, Object> validDocument() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("schemaVersion", 3);
        raw.put("meta", Map.of("name", "테스트"));
        raw.put("widgets", List.of(
                widgetJson("w1", "fake", "2x4", Map.of("name", "hello"))
        ));
        return raw;
    }

    private Map<String, Object> widgetJson(String id, String type, String size, Map<String, Object> props) {
        Map<String, Object> widget = new HashMap<>();
        widget.put("id", id);
        widget.put("type", type);
        widget.put("size", size);
        widget.put("props", props);
        return widget;
    }

    @Test
    @DisplayName("정상 문서는 통과한다")
    void validDocumentPasses() {
        FormatDocument doc = validator.validateAndParse(validDocument(), false, mapper, "tester");
        assertEquals(3, doc.schemaVersion());
        assertEquals(1, doc.widgets().size());
    }

    @Test
    @DisplayName("루트에 알 수 없는 키가 있으면 거부한다")
    void unknownRootKeyRejected() {
        Map<String, Object> raw = validDocument();
        raw.put("rows", List.of());

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateAndParse(raw, false, mapper, "tester"));

        assertTrue(ex.getFieldErrors().stream().anyMatch(e -> e.path().equals("rows")));
    }

    @Test
    @DisplayName("widgets가 비어 있으면 거부한다")
    void emptyWidgetsRejected() {
        Map<String, Object> raw = validDocument();
        raw.put("widgets", List.of());

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateAndParse(raw, false, mapper, "tester"));

        assertTrue(ex.getFieldErrors().stream().anyMatch(e -> e.path().equals("widgets")));
    }

    @Test
    @DisplayName("등록되지 않은 type은 widgets[i].type 경로로 거부한다")
    void unknownTypeRejected() {
        Map<String, Object> raw = validDocument();
        raw.put("widgets", List.of(widgetJson("w1", "ghost", "2x4", Map.of("name", "x"))));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateAndParse(raw, false, mapper, "tester"));

        assertTrue(ex.getFieldErrors().stream().anyMatch(e -> e.path().equals("widgets[0].type")));
    }

    @Test
    @DisplayName("카탈로그에 없는 size는 widgets[i].size 경로로 거부한다")
    void invalidSizeRejected() {
        Map<String, Object> raw = validDocument();
        raw.put("widgets", List.of(widgetJson("w1", "fake", "9x9", Map.of("name", "hello"))));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateAndParse(raw, false, mapper, "tester"));

        assertTrue(ex.getFieldErrors().stream().anyMatch(e -> e.path().equals("widgets[0].size")));
    }

    @Test
    @DisplayName("중복된 위젯 id는 widgets[1].id 경로로 거부한다")
    void duplicateIdRejected() {
        Map<String, Object> raw = validDocument();
        raw.put("widgets", List.of(
                widgetJson("dup", "fake", "2x4", Map.of("name", "a")),
                widgetJson("dup", "fake", "2x4", Map.of("name", "b"))
        ));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateAndParse(raw, false, mapper, "tester"));

        assertTrue(ex.getFieldErrors().stream().anyMatch(e -> e.path().equals("widgets[1].id")));
    }

    @Test
    @DisplayName("props 오류 경로는 widgets[i].props.<key> 형식이다")
    void propsErrorPathFormat() {
        Map<String, Object> raw = validDocument();
        raw.put("widgets", List.of(widgetJson("w1", "fake", "2x4", Map.of())));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateAndParse(raw, false, mapper, "tester"));

        assertTrue(ex.getFieldErrors().stream().anyMatch(e -> e.path().equals("widgets[0].props.name")));
    }

    @Test
    @DisplayName("schemaVersion이 3이 아니면 거부한다")
    void wrongSchemaVersionRejected() {
        Map<String, Object> raw = validDocument();
        raw.put("schemaVersion", 2);

        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.validateAndParse(raw, false, mapper, "tester"));

        assertTrue(ex.getFieldErrors().stream().anyMatch(e -> e.path().equals("schemaVersion")));
    }

    /** 테스트 전용 가짜 위젯. props로 문자열 필드 name(필수)만 받는다. */
    private static class FakeWidget implements Widget {
        private static final WidgetDescriptor DESCRIPTOR = new WidgetDescriptor(
                "fake", "가짜 위젯", "테스트용", "text", false, true,
                List.of(WidgetSize.fixed(2, 4, "절반"), WidgetSize.auto(2, "자동")),
                "2x4",
                List.of(PropField.string("name", "이름", true, null, 20, null, null, null))
        );

        @Override
        public WidgetDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
            return "<div></div>";
        }
    }
}
