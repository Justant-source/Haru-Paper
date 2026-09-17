package com.harupaper.server.widget.basic;

import com.harupaper.server.widget.PropField;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetHtml;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.harupaper.server.widget.basic.BasicWidgetSupport.ALIGN_OPTIONS;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.boolProp;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.intProp;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.justifyContent;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.stringProp;

/**
 * 날짜 머리글 위젯(.temp/07-위젯그리드-작업지시서.md 5.4절). 옛 FormatDocument v2의
 * "dateHeader" 블록을 그대로 옮긴 것 — 토큰 치환 규칙(formatDateHeader)은 동일하다.
 */
@Component
public class DateHeaderWidget implements Widget {

    // 기본 패턴·글자 크기는 format-schema.md v2 시절부터 쓰던 값 그대로다(호환).
    private static final String DEFAULT_PATTERN = "YYYY년 M월 D일 dddd";
    private static final int DEFAULT_FONT_SIZE_PT = 16;

    private static final WidgetDescriptor DESCRIPTOR = new WidgetDescriptor(
            "dateHeader",
            "날짜 머리글",
            "오늘 날짜를 큰 제목으로 표시합니다",
            "calendar",
            false,
            true,
            List.of(WidgetSize.fixed(4, 1, "띠 · 104×12mm")),
            "4x1",
            List.of(
                    PropField.string("pattern", "날짜 형식", false, DEFAULT_PATTERN, 100, null,
                            DEFAULT_PATTERN, "YYYY/MM/DD(년/월/일)·dddd/ddd(요일) 토큰을 조합합니다"),
                    PropField.enumOf("align", "정렬", false, "center", ALIGN_OPTIONS, null),
                    PropField.integer("fontSizePt", "글자 크기(pt)", false, DEFAULT_FONT_SIZE_PT, 6, 72, null),
                    PropField.bool("bold", "굵게", true, null)
            )
    );

    @Override
    public WidgetDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
        Map<String, Object> props = instance.props() != null ? instance.props() : Map.of();
        String pattern = stringProp(props, "pattern", DEFAULT_PATTERN);
        String align = stringProp(props, "align", "center");
        int fontSizePt = intProp(props, "fontSizePt", DEFAULT_FONT_SIZE_PT);
        boolean bold = boolProp(props, "bold", true);

        String text = formatDateHeader(pattern, ctx.targetDate());

        StringBuilder css = new StringBuilder();
        css.append("width:100%;height:100%;display:flex;align-items:center;justify-content:")
                .append(justifyContent(align)).append(";text-align:").append(WidgetHtml.escape(align)).append(';');
        if (bold) {
            css.append("font-weight:bold;");
        }
        css.append("font-size:").append(ctx.ptCss(fontSizePt)).append(';');

        return "<div style=\"" + css + "\">" + WidgetHtml.escape(text) + "</div>";
    }

    /**
     * dateHeader 패턴 포맷팅(format-schema.md 4.3절 토큰만 치환, 나머지 문자는 그대로).
     * 토큰: YYYY(2026), MM(09), M(9), DD(05), D(5), dddd(월요일), ddd(월).
     * 긴 토큰(dddd, YYYY, MM, DD)부터 치환해야 짧은 토큰(ddd, M, D)이 먼저 먹어버리지 않는다.
     */
    private String formatDateHeader(String pattern, LocalDate targetDate) {
        String[] weekdaysFull = {"월요일", "화요일", "수요일", "목요일", "금요일", "토요일", "일요일"};
        String[] weekdaysShort = {"월", "화", "수", "목", "금", "토", "일"};
        int dow = targetDate.getDayOfWeek().getValue() - 1; // MONDAY=1 -> index 0

        String result = pattern;
        result = result.replace("dddd", weekdaysFull[dow]);
        result = result.replace("YYYY", String.format("%04d", targetDate.getYear()));
        result = result.replace("MM", String.format("%02d", targetDate.getMonthValue()));
        result = result.replace("DD", String.format("%02d", targetDate.getDayOfMonth()));
        result = result.replace("ddd", weekdaysShort[dow]);
        result = result.replace("M", String.valueOf(targetDate.getMonthValue()));
        result = result.replace("D", String.valueOf(targetDate.getDayOfMonth()));
        return result;
    }
}
