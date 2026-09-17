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
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static com.harupaper.server.widget.basic.BasicWidgetSupport.ALIGN_OPTIONS;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.boolProp;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.stringProp;

/**
 * 자유 텍스트 위젯(.temp/07-위젯그리드-작업지시서.md 5.4절). 옛 FormatDocument v2의 "text" 블록을
 * 그대로 옮긴 것 — {{date}}·{{weekday}} 치환과 줄바꿈 유지(white-space: pre-wrap)는 동일하다.
 */
@Component
public class TextWidget implements Widget {

    private static final int MAX_TEXT_LENGTH = 5000;

    private static final WidgetDescriptor DESCRIPTOR = new WidgetDescriptor(
            "text",
            "텍스트",
            "자유롭게 쓰는 메모나 문구",
            "text",
            false,
            true,
            List.of(WidgetSize.auto(2, "자동 높이 · 104mm 폭")),
            "4xauto",
            List.of(
                    // 빈 문자열을 허용해야 하므로 required=false로 둔다 — 값이 없으면 ""로 렌더한다.
                    PropField.text("text", "내용", false, "", MAX_TEXT_LENGTH,
                            "오늘 하루 메모…", "{{date}}, {{weekday}}를 쓰면 그날 날짜로 바뀝니다"),
                    PropField.enumOf("align", "정렬", false, "left", ALIGN_OPTIONS, null),
                    PropField.integer("fontSizePt", "글자 크기(pt)", false, null, 6, 72,
                            "비워두면 포맷 기본 글자 크기를 씁니다"),
                    PropField.bool("bold", "굵게", false, null)
            )
    );

    @Override
    public WidgetDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
        Map<String, Object> props = instance.props() != null ? instance.props() : Map.of();
        Object textObj = props.get("text");
        String text = substituteVariables(textObj instanceof String s ? s : "", ctx.targetDate());
        String align = stringProp(props, "align", "left");
        boolean bold = boolProp(props, "bold", false);

        StringBuilder css = new StringBuilder();
        css.append("width:100%;height:100%;white-space:pre-wrap;word-break:keep-all;overflow-wrap:anywhere;");
        css.append("text-align:").append(WidgetHtml.escape(align)).append(';');
        if (bold) {
            css.append("font-weight:bold;");
        }
        Object fontSizeObj = props.get("fontSizePt");
        if (fontSizeObj instanceof Number n) {
            css.append("font-size:").append(ctx.ptCss(n.doubleValue())).append(';');
        }

        return "<div style=\"" + css + "\">" + WidgetHtml.escape(text) + "</div>";
    }

    /** {{date}}·{{weekday}} 치환(format-schema.md 4.3절). */
    private String substituteVariables(String text, LocalDate targetDate) {
        if (text == null) {
            return "";
        }
        // {{date}} = "2026년 9월 14일" — 대문자 y/d는 week-based-year/day-of-year라 소문자를 쓴다
        String dateStr = targetDate.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일"));
        String[] weekdays = {"일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일"};
        String weekday = weekdays[targetDate.getDayOfWeek().getValue() % 7];

        String result = text.replace("{{date}}", dateStr);
        result = result.replace("{{weekday}}", weekday);
        return result;
    }
}
