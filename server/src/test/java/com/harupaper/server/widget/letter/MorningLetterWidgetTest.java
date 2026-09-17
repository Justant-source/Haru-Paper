package com.harupaper.server.widget.letter;

import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("고도원의 아침편지 위젯")
class MorningLetterWidgetTest {

    private static final MorningLetter SAMPLE = new MorningLetter(
            "2026년 9월 18일", "제목입니다", "인용문 첫 줄\n인용문 둘째 줄",
            "- 지은이의《책》중에서 -", "한마디입니다", Instant.parse("2026-09-18T21:00:00Z"), false);

    private WidgetRenderContext context() {
        WidgetDescriptor descriptor = new MorningLetterWidget(() -> SAMPLE).descriptor();
        return WidgetPreviewHarness.context(descriptor.sizes().get(0), LocalDate.of(2026, 9, 18));
    }

    @Test
    @DisplayName("descriptor 값이 작업지시서 5.1절과 일치한다")
    void descriptorMatchesSpec() {
        WidgetDescriptor descriptor = new MorningLetterWidget(() -> SAMPLE).descriptor();

        assertEquals("morningLetter", descriptor.type());
        assertEquals("고도원의 아침편지", descriptor.name());
        assertEquals("letter", descriptor.icon());
        assertTrue(descriptor.dynamic());
        assertTrue(descriptor.catalog());
        assertEquals(1, descriptor.sizes().size());
        assertEquals("4xauto", descriptor.sizes().get(0).id());
        assertEquals("4xauto", descriptor.defaultSize());
        assertEquals(2, descriptor.fields().size());
        assertEquals("showComment", descriptor.fields().get(0).key());
        assertEquals("fontSize", descriptor.fields().get(1).key());
    }

    @Test
    @DisplayName("showComment=false면 한마디가 렌더에 나오지 않는다")
    void hidesCommentWhenDisabled() {
        MorningLetterWidget widget = new MorningLetterWidget(() -> SAMPLE);
        WidgetRenderContext ctx = context();

        String withComment = widget.renderHtml(
                new WidgetInstance("w1", "morningLetter", "4xauto", Map.of("showComment", true)), ctx);
        String withoutComment = widget.renderHtml(
                new WidgetInstance("w1", "morningLetter", "4xauto", Map.of("showComment", false)), ctx);

        assertTrue(withComment.contains("한마디입니다"));
        assertFalse(withoutComment.contains("한마디입니다"));
    }

    @Test
    @DisplayName("스크랩한 문자열은 escape되어 스크립트 태그가 그대로 나오지 않는다")
    void escapesScrapedContent() {
        MorningLetter malicious = new MorningLetter(
                "2026년 9월 18일", "<script>alert(1)</script>", "인용문", "", "", Instant.now(), false);
        MorningLetterWidget widget = new MorningLetterWidget(() -> malicious);
        WidgetRenderContext ctx = context();

        String html = widget.renderHtml(
                new WidgetInstance("w1", "morningLetter", "4xauto", Map.of()), ctx);

        assertFalse(html.contains("<script>"), "이스케이프되지 않은 <script> 태그가 남으면 안 된다");
        assertTrue(html.contains("&lt;script&gt;"));
    }

    @Test
    @DisplayName("제공자가 실패해도 예외를 던지지 않고 errorBox를 돌려준다")
    void fallsBackToErrorBoxWithoutThrowing() {
        MorningLetterWidget widget = new MorningLetterWidget(() -> {
            throw new MorningLetterProviderException("실패", null);
        });
        WidgetRenderContext ctx = context();

        String html = widget.renderHtml(
                new WidgetInstance("w1", "morningLetter", "4xauto", Map.of()), ctx);

        assertTrue(html.contains("고도원의 아침편지"));
        assertTrue(html.contains("편지를 가져오지 못했습니다"));
    }

    @Test
    @DisplayName("stale이면 하단에 안내 문구가 붙는다")
    void showsStaleNotice() {
        MorningLetter stale = new MorningLetter(SAMPLE.dateText(), SAMPLE.title(), SAMPLE.quote(),
                SAMPLE.source(), SAMPLE.comment(), SAMPLE.fetchedAt(), true);
        MorningLetterWidget widget = new MorningLetterWidget(() -> stale);
        WidgetRenderContext ctx = context();

        String html = widget.renderHtml(
                new WidgetInstance("w1", "morningLetter", "4xauto", Map.of()), ctx);

        assertTrue(html.contains("이전에 받아 둔 편지"));
    }
}
