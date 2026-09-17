package com.harupaper.server.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.widget.WidgetInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v1(blocks)·v2(rows/slots) → v3(widgets) up-convert 검증(.temp/07-위젯그리드-작업지시서.md 3.3절).
 */
@DisplayName("FormatDocumentSupport - v1·v2 → v3 up-convert")
class FormatDocumentSupportTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("v3는 변환 없이 그대로 읽힌다")
    void v3PassesThrough() {
        String body = "{\"schemaVersion\":3,\"meta\":{\"name\":\"t\"},\"widgets\":"
                + "[{\"id\":\"w1\",\"type\":\"text\",\"size\":\"4xauto\",\"props\":{\"text\":\"hi\"}}]}";
        FormatDocument doc = FormatDocumentSupport.readDocument(body, mapper);
        assertEquals(3, doc.schemaVersion());
        assertEquals(1, doc.widgets().size());
        assertEquals("hi", doc.widgets().get(0).props().get("text"));
    }

    @Test
    @DisplayName("v1 text 블록 → text 위젯(4xauto), style의 align/fontSizePt(반올림)/bold가 props로 옮겨진다")
    void v1TextBlockConverted() {
        String body = "{\"schemaVersion\":1,\"meta\":{\"name\":\"t\"},\"style\":{},"
                + "\"blocks\":[{\"type\":\"text\",\"props\":{\"text\":\"hello\"},"
                + "\"style\":{\"align\":\"right\",\"fontSizePt\":12.6,\"bold\":true}}]}";
        FormatDocument doc = FormatDocumentSupport.readDocument(body, mapper);

        assertEquals(3, doc.schemaVersion());
        assertEquals(1, doc.widgets().size());
        WidgetInstance w = doc.widgets().get(0);
        assertEquals("text", w.type());
        assertEquals("4xauto", w.size());
        assertEquals("hello", w.props().get("text"));
        assertEquals("right", w.props().get("align"));
        assertEquals(Integer.valueOf(13), w.props().get("fontSizePt")); // 12.6 반올림
        assertEquals(Boolean.TRUE, w.props().get("bold"));
    }

    @Test
    @DisplayName("v1 dateHeader 블록 → dateHeader 위젯(4x1)")
    void v1DateHeaderBlockConverted() {
        String body = "{\"schemaVersion\":1,\"meta\":{\"name\":\"t\"},"
                + "\"blocks\":[{\"type\":\"dateHeader\",\"props\":{\"pattern\":\"YYYY-MM-DD\"},\"style\":{}}]}";
        FormatDocument doc = FormatDocumentSupport.readDocument(body, mapper);
        WidgetInstance w = doc.widgets().get(0);
        assertEquals("dateHeader", w.type());
        assertEquals("4x1", w.size());
        assertEquals("YYYY-MM-DD", w.props().get("pattern"));
    }

    @Test
    @DisplayName("v1 image 블록 → image 위젯(4xauto), style.align도 옮겨진다")
    void v1ImageBlockConverted() {
        String body = "{\"schemaVersion\":1,\"meta\":{\"name\":\"t\"},"
                + "\"blocks\":[{\"type\":\"image\",\"props\":{\"assetId\":\"a1\",\"widthPercent\":80},"
                + "\"style\":{\"align\":\"center\"}}]}";
        FormatDocument doc = FormatDocumentSupport.readDocument(body, mapper);
        WidgetInstance w = doc.widgets().get(0);
        assertEquals("image", w.type());
        assertEquals("4xauto", w.size());
        assertEquals("a1", w.props().get("assetId"));
        assertEquals(Integer.valueOf(80), w.props().get("widthPercent"));
        assertEquals("center", w.props().get("align"));
    }

    @Test
    @DisplayName("v1 weather 블록은 항상 서울시청 좌표로 변환된다(옛 렌더가 실제로 항상 그렸던 결과와 동일)")
    void v1WeatherBlockConverted() {
        String body = "{\"schemaVersion\":1,\"meta\":{\"name\":\"t\"},"
                + "\"blocks\":[{\"type\":\"weather\",\"props\":{\"fields\":[\"sky\"]},\"style\":{}}]}";
        FormatDocument doc = FormatDocumentSupport.readDocument(body, mapper);
        WidgetInstance w = doc.widgets().get(0);
        assertEquals("weather", w.type());
        assertEquals("4x2", w.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) w.props().get("location");
        assertEquals("서울", location.get("label"));
        assertEquals(37.5665, ((Number) location.get("lat")).doubleValue());
        assertEquals(126.9780, ((Number) location.get("lon")).doubleValue());
    }

    @Test
    @DisplayName("v2 2슬롯 행은 순서대로 펼쳐지고, 위젯 id는 slot id를 재사용한다")
    void v2TwoSlotRowFlattened() {
        String body = "{\"schemaVersion\":2,\"meta\":{\"name\":\"t\"},\"style\":{},"
                + "\"rows\":[{\"id\":\"r1\",\"slots\":["
                + "{\"id\":\"s1\",\"width\":\"1/2\",\"block\":{\"type\":\"text\",\"props\":{\"text\":\"left\"},\"style\":{}}},"
                + "{\"id\":\"s2\",\"width\":\"1/2\",\"block\":{\"type\":\"text\",\"props\":{\"text\":\"right\"},\"style\":{}}}"
                + "]}]}";
        FormatDocument doc = FormatDocumentSupport.readDocument(body, mapper);

        assertEquals(2, doc.widgets().size());
        assertEquals("s1", doc.widgets().get(0).id());
        assertEquals("left", doc.widgets().get(0).props().get("text"));
        assertEquals("s2", doc.widgets().get(1).id());
        assertEquals("right", doc.widgets().get(1).props().get("text"));
    }

    @Test
    @DisplayName("빈 블록(0개)은 빈 text 위젯 1개로 채워진다(위젯 1개 이상 규칙)")
    void emptyBlocksBecomeSingleTextWidget() {
        String body = "{\"schemaVersion\":1,\"meta\":{\"name\":\"t\"},\"blocks\":[]}";
        FormatDocument doc = FormatDocumentSupport.readDocument(body, mapper);
        assertEquals(1, doc.widgets().size());
        assertEquals("text", doc.widgets().get(0).type());
        assertEquals("", doc.widgets().get(0).props().get("text"));
    }

    @Test
    @DisplayName("upConvertRawImportIfNeeded는 v1·v2만 변환하고 assets 키를 보존한다")
    void upConvertPreservesAssets() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = mapper.readValue(
                "{\"schemaVersion\":1,\"meta\":{\"name\":\"t\"},\"blocks\":[],"
                        + "\"assets\":{\"a1\":\"data:image/png;base64,AA==\"}}",
                Map.class);

        Map<String, Object> converted = FormatDocumentSupport.upConvertRawImportIfNeeded(raw, mapper);

        assertEquals(Integer.valueOf(3), converted.get("schemaVersion"));
        assertTrue(converted.containsKey("assets"));
    }

    @Test
    @DisplayName("upConvertRawImportIfNeeded는 이미 v3면 손대지 않고 그대로 돌려준다")
    void upConvertPassesThroughV3() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = mapper.readValue(
                "{\"schemaVersion\":3,\"meta\":{\"name\":\"t\"},\"widgets\":[]}", Map.class);

        Map<String, Object> converted = FormatDocumentSupport.upConvertRawImportIfNeeded(raw, mapper);

        assertSame(raw, converted);
    }
}
