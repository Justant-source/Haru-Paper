package com.harupaper.server.widget.letter;

import com.harupaper.server.widget.PropField;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetHtml;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code morningLetter} 위젯 — 고도원의 아침편지. 선으로 틀을 잡아 글에 집중하게 하는 레이아웃(작업지시서 07 5.1절).
 */
@Slf4j
@Component
public class MorningLetterWidget implements Widget {

    // previewRows=8: 아침편지는 보통 인용문 6~10줄 + 출처 + 한마디 정도라 8행이 앱 배치도의 어림값으로 적당하다
    private static final WidgetDescriptor DESCRIPTOR = new WidgetDescriptor(
            "morningLetter",
            "고도원의 아침편지",
            "매일 아침 도착하는 고도원의 짧은 글을 그대로 싣는다",
            "letter",
            true,
            true,
            List.of(WidgetSize.auto(8, "전체 폭 · 내용 길이만큼")),
            "4xauto",
            List.of(
                    PropField.bool("showComment", "고도원의 한마디 포함", true, null),
                    PropField.enumOf("fontSize", "본문 글자 크기", false, "normal",
                            List.of(
                                    new PropField.Option("small", "작게"),
                                    new PropField.Option("normal", "보통"),
                                    new PropField.Option("large", "크게")
                            ), null)
            )
    );

    // 본문 글자 크기(pt). 작업지시서 5.1절: 작게10 / 보통11 / 크게12.5
    private static final double BODY_PT_SMALL = 10.0;
    private static final double BODY_PT_NORMAL = 11.0;
    private static final double BODY_PT_LARGE = 12.5;

    private final MorningLetterProvider provider;

    public MorningLetterWidget(MorningLetterProvider provider) {
        this.provider = provider;
    }

    @Override
    public WidgetDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
        MorningLetter letter;
        try {
            letter = provider.getLatest();
        } catch (Exception e) {
            log.warn("고도원의 아침편지 위젯 렌더 실패, errorBox로 대체: {}", e.getMessage());
            return WidgetHtml.errorBox("고도원의 아침편지", "편지를 가져오지 못했습니다", ctx);
        }

        boolean showComment = readBoolean(instance.props(), "showComment", true);
        String fontSize = readString(instance.props(), "fontSize", "normal");
        double bodyPt = switch (fontSize) {
            case "small" -> BODY_PT_SMALL;
            case "large" -> BODY_PT_LARGE;
            default -> BODY_PT_NORMAL;
        };

        // 선 굵기: mm 근거값을 px로 바꾸되 감열 인쇄에서 보이도록 최소 3px 보장(Widget 구현 규칙 ③)
        int thickRulePx = Math.max(3, ctx.mm(0.6));
        int thinRulePx = Math.max(3, ctx.mm(0.25));
        int dottedRulePx = Math.max(3, ctx.mm(0.25));
        int insetPx = ctx.mm(3);

        StringBuilder html = new StringBuilder();
        html.append("<div style=\"width:100%;height:100%;display:flex;flex-direction:column;overflow:hidden;\">");

        html.append("<div style=\"border-top:").append(thickRulePx).append("px solid #000;\"></div>");
        html.append("<div style=\"text-align:center;letter-spacing:0.12em;padding:")
                .append(ctx.mm(1)).append("px 0;font-size:").append(ctx.ptCss(8.5)).append(";\">")
                .append("고도원의 아침편지 · ").append(WidgetHtml.escape(letter.dateText()))
                .append("</div>");
        html.append("<div style=\"border-top:").append(thinRulePx).append("px solid #000;\"></div>");

        html.append("<div style=\"text-align:center;font-weight:bold;padding:")
                .append(ctx.mm(2)).append("px ").append(insetPx).append("px;font-size:")
                .append(ctx.ptCss(14)).append(";\">")
                .append(WidgetHtml.escape(letter.title()))
                .append("</div>");

        html.append("<div style=\"white-space:pre-line;text-align:left;padding:")
                .append(ctx.mm(1)).append("px ").append(insetPx).append("px;font-size:")
                .append(ctx.ptCss(bodyPt)).append(";flex:1 1 auto;\">")
                .append(WidgetHtml.escape(letter.quote()))
                .append("</div>");

        if (!letter.source().isBlank()) {
            html.append("<div style=\"text-align:right;padding:0 ").append(insetPx)
                    .append("px ").append(ctx.mm(2)).append("px;font-size:").append(ctx.ptCss(9.5)).append(";\">")
                    .append(WidgetHtml.escape(letter.source()))
                    .append("</div>");
        }

        if (showComment && !letter.comment().isBlank()) {
            html.append("<div style=\"border-top:").append(dottedRulePx)
                    .append("px dashed #000;margin:0 ").append(insetPx).append("px;\"></div>");
            html.append("<div style=\"white-space:pre-line;padding:")
                    .append(ctx.mm(2)).append("px ").append(insetPx).append("px;font-size:")
                    .append(ctx.ptCss(10)).append(";\">")
                    .append(WidgetHtml.escape(letter.comment()))
                    .append("</div>");
        }

        if (letter.stale()) {
            html.append("<div style=\"text-align:center;padding:0 0 ").append(ctx.mm(1))
                    .append("px;font-size:").append(ctx.ptCss(7)).append(";\">(이전에 받아 둔 편지)</div>");
        }

        html.append("<div style=\"border-top:").append(thickRulePx).append("px solid #000;\"></div>");
        html.append("</div>");
        return html.toString();
    }

    private static boolean readBoolean(Map<String, Object> props, String key, boolean fallback) {
        if (props == null) {
            return fallback;
        }
        Object value = props.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return fallback;
    }

    private static String readString(Map<String, Object> props, String key, String fallback) {
        if (props == null) {
            return fallback;
        }
        Object value = props.get(key);
        if (value instanceof String s && !s.isBlank()) {
            return s.toLowerCase(Locale.ROOT);
        }
        return fallback;
    }
}
