package com.harupaper.server.widget;

import com.harupaper.server.device.PrinterProfile;
import com.harupaper.server.format.FormatStyle;

import java.util.List;
import java.util.Locale;

/**
 * 이미 그려진 위젯 조각들을 4열 CSS 그리드 한 장의 HTML로 조립한다 (docs/server/rendering.md).
 * 순수 함수다 — 외부 조회도 스프링 의존도 없다. 실제 렌더(HtmlTemplateBuilder)와 위젯 단독 미리보기
 * (테스트의 WidgetPreviewHarness)가 같은 골격을 쓰게 하려고 분리했다.
 *
 * 배치 규칙: 문서 순서대로 왼쪽→오른쪽, 넘치면 다음 줄. {@code grid-auto-flow: row dense}라서
 * 앞줄에 남은 빈칸은 뒤에 오는 작은 위젯이 메운다(앱 배치도도 같은 CSS를 써서 결과가 일치한다).
 */
public final class WidgetPageBuilder {

    /** 조립 대상 1개: 크기와 안쪽 HTML */
    public record Cell(WidgetSize size, String innerHtml) {
    }

    private WidgetPageBuilder() {
    }

    /** 좌우 여백을 뺀 콘텐츠 폭(px) */
    public static int contentWidthPx(FormatStyle style, PrinterProfile profile) {
        FormatStyle s = FormatStyle.withDefaults(style);
        return profile.printableWidthPx()
                - GridSpec.mmToPx(s.marginMm().left(), profile.dpi())
                - GridSpec.mmToPx(s.marginMm().right(), profile.dpi());
    }

    public static String build(FormatStyle style, PrinterProfile profile, List<Cell> cells) {
        FormatStyle s = FormatStyle.withDefaults(style);
        int dpi = profile.dpi();
        int gap = GridSpec.gapPx(dpi);
        int rowUnit = GridSpec.rowUnitPx(dpi);

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n<html lang=\"ko\">\n<head>\n<meta charset=\"UTF-8\">\n<style>\n");
        html.append("* { margin: 0; padding: 0; box-sizing: border-box; }\n");
        html.append("body { font-family: '").append(WidgetHtml.escape(s.fontFamily())).append("', sans-serif; ")
                .append("background: #fff; color: #000; ")
                .append(String.format(Locale.ROOT, "font-size: %.1fpx; ", GridSpec.ptToPx(s.baseFontSizePt(), dpi)))
                .append("line-height: ").append(s.lineHeight()).append("; ")
                .append("word-break: keep-all; overflow-wrap: anywhere; }\n");
        html.append(".page { padding: ")
                .append(GridSpec.mmToPx(s.marginMm().top(), dpi)).append("px ")
                .append(GridSpec.mmToPx(s.marginMm().right(), dpi)).append("px ")
                .append(GridSpec.mmToPx(s.marginMm().bottom(), dpi)).append("px ")
                .append(GridSpec.mmToPx(s.marginMm().left(), dpi)).append("px; }\n");
        html.append(".grid { display: grid; grid-template-columns: repeat(").append(GridSpec.COLUMNS)
                .append(", minmax(0, 1fr)); grid-auto-rows: minmax(").append(rowUnit).append("px, auto); ")
                .append("grid-auto-flow: row dense; gap: ").append(gap).append("px; }\n");
        // 고정 크기 위젯: contain:size로 내용이 행 높이를 밀어내지 못하게 하고, 넘치는 부분은 자른다
        html.append(".w { min-width: 0; overflow: hidden; }\n");
        html.append(".w-fixed { contain: size; }\n");
        html.append("</style>\n</head>\n<body>\n<div class=\"page\">\n<div class=\"grid\">\n");

        for (Cell cell : cells) {
            WidgetSize size = cell.size();
            int rowSpan = size.isAutoHeight() ? 1 : size.rows();
            html.append("<div class=\"w").append(size.isAutoHeight() ? "" : " w-fixed")
                    .append("\" style=\"grid-column: span ").append(size.cols())
                    .append("; grid-row: span ").append(rowSpan).append(";\">\n")
                    .append(cell.innerHtml() == null ? "" : cell.innerHtml())
                    .append("\n</div>\n");
        }

        html.append("</div>\n</div>\n</body>\n</html>\n");
        return html.toString();
    }
}
