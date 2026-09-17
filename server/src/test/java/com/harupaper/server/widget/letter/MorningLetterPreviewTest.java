package com.harupaper.server.widget.letter;

import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPageBuilder;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 눈으로 확인하는 미리보기. 기본으로는 안 돈다 — 다음처럼 명시적으로 돌린다:
 *   WIDGET_PREVIEW=1 ./gradlew test --tests 'com.harupaper.server.widget.letter.MorningLetterPreviewTest'
 * 결과: build/widget-previews/letter-*.png (작업지시서 07 5.1절 눈으로 확인 절차).
 *
 * 지어낸 편지(실제 저작물 아님) — 인용문 9줄, 출처 1줄, 한마디 7줄, 한 줄 28자 안팎으로 실제 분량을 흉내 냈다.
 */
@DisplayName("고도원의 아침편지 위젯 미리보기(PNG)")
@EnabledIfEnvironmentVariable(named = "WIDGET_PREVIEW", matches = "1")
class MorningLetterPreviewTest {

    private static final MorningLetter SAMPLE = new MorningLetter(
            "2026년 9월 18일",
            "가장 낮은 곳에서 배우는 것",
            "가장 낮은 곳으로 내려가 본 사람만이,\n"
                    + "가장 높은 곳의 풍경을 진짜로 이해할 수 있습니다.\n"
                    + "바람이 세게 부는 날일수록,\n"
                    + "뿌리 깊은 나무는 오히려 더 단단히 섭니다.\n"
                    + "그러니 오늘 조금 흔들리고 있다면,\n"
                    + "그것은 넘어지는 중이 아니라\n"
                    + "뿌리를 내리는 중이라고 생각해 보세요.\n"
                    + "모든 뿌리는 어둠 속에서 자랍니다.\n"
                    + "당신의 오늘도 그렇게 자라는 중입니다.",
            "- 이든의《바람이 지나간 자리》중에서 -",
            "오늘 하루,\n"
                    + "누군가의 낮은 자리를\n"
                    + "한 번쯤 들여다보는\n"
                    + "마음을 가져보세요.\n"
                    + "그 자리에서 배우는 것이\n"
                    + "생각보다 많습니다.\n"
                    + "오늘도 많이 웃으세요.",
            Instant.parse("2026-09-18T21:00:00Z"),
            false);

    @Test
    @DisplayName("small/normal/large x showComment on/off 6장을 PNG로 뜬다")
    void renderAllVariants() throws Exception {
        MorningLetterWidget widget = new MorningLetterWidget(() -> SAMPLE);
        var size = widget.descriptor().sizes().get(0);
        WidgetRenderContext ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));

        for (String fontSize : List.of("small", "normal", "large")) {
            for (boolean showComment : List.of(true, false)) {
                String inner = widget.renderHtml(
                        new WidgetInstance("w1", "morningLetter", "4xauto",
                                Map.of("fontSize", fontSize, "showComment", showComment)),
                        ctx);
                String name = "letter-" + fontSize + (showComment ? "-comment" : "-nocomment");
                WidgetPreviewHarness.renderToPng(name, List.of(new WidgetPageBuilder.Cell(size, inner)));
            }
        }

        // stale 안내 문구도 한 장 따로 확인
        MorningLetter stale = new MorningLetter(SAMPLE.dateText(), SAMPLE.title(), SAMPLE.quote(),
                SAMPLE.source(), SAMPLE.comment(), SAMPLE.fetchedAt(), true);
        MorningLetterWidget staleWidget = new MorningLetterWidget(() -> stale);
        String staleInner = staleWidget.renderHtml(
                new WidgetInstance("w1", "morningLetter", "4xauto", Map.of()), ctx);
        WidgetPreviewHarness.renderToPng("letter-stale",
                List.of(new WidgetPageBuilder.Cell(size, staleInner)));
    }
}
