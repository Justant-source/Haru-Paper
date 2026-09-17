package com.harupaper.server.widget.basic;

import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPageBuilder;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 기본 위젯 3종(dateHeader·text)을 실제 렌더 골격 그대로 PNG로 떠서 눈으로 확인하는 개발용
 * 테스트(.temp/07-위젯그리드-작업지시서.md 9절). Chromium을 띄우므로 느려서 일반 ./gradlew test
 * 에서는 돌지 않는다 — gradle이 시스템 프로퍼티를 테스트 JVM에 넘기지 않으므로 환경변수로 켠다:
 *
 * <pre>WIDGET_PREVIEW=1 ./gradlew test --tests 'com.harupaper.server.widget.basic.WidgetPreviewTest'</pre>
 *
 * 결과: build/widget-previews/basic-widgets.png (합격/불합격 판정 없음 — 눈으로 본다)
 */
@EnabledIfEnvironmentVariable(named = "WIDGET_PREVIEW", matches = "1")
@DisplayName("기본 위젯 미리보기 PNG (WIDGET_PREVIEW=1일 때만 실행)")
class WidgetPreviewTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 18); // 금요일

    @Test
    @DisplayName("dateHeader + text를 한 장으로 떠서 build/widget-previews/basic-widgets.png에 저장한다")
    void previewDateHeaderAndText() throws Exception {
        DateHeaderWidget dateHeader = new DateHeaderWidget();
        TextWidget text = new TextWidget();

        WidgetSize headerSize = dateHeader.descriptor().size("4x1");
        WidgetRenderContext headerCtx = WidgetPreviewHarness.context(headerSize, DATE);
        String headerHtml = dateHeader.renderHtml(
                new WidgetInstance("w1", "dateHeader", "4x1", Map.of()), headerCtx);

        WidgetSize textSize = text.descriptor().size("4xauto");
        WidgetRenderContext textCtx = WidgetPreviewHarness.context(textSize, DATE);
        String textHtml = text.renderHtml(
                new WidgetInstance("w2", "text", "4xauto",
                        Map.of("text", "오늘은 {{date}} {{weekday}}입니다.\n둘째 줄도 잘 보여야 한다.\n"
                                + "긴 문장이 폭을 넘으면 자연스럽게 줄바꿈되는지도 확인한다 — 자동 높이 위젯이라 내용 길이만큼 늘어난다.")),
                textCtx);

        WidgetPreviewHarness.renderToPng("basic-widgets", List.of(
                new WidgetPageBuilder.Cell(headerSize, headerHtml),
                new WidgetPageBuilder.Cell(textSize, textHtml)
        ));
    }
}
