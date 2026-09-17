package com.harupaper.server.widget;

/** 위젯 HTML 조립용 공통 도우미. */
public final class WidgetHtml {

    private WidgetHtml() {
    }

    /** HTML 텍스트·속성값 공용 이스케이프. null이면 빈 문자열 */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /**
     * 데이터 조회 실패 등으로 위젯을 그리지 못할 때 쓰는 공통 표시.
     * 점선 테두리 상자 안에 제목과 사유를 적는다(둘 다 escape한다).
     */
    public static String errorBox(String title, String message, WidgetRenderContext ctx) {
        return "<div style=\"width:100%;height:100%;min-height:" + ctx.mm(10) + "px;"
                + "border:" + Math.max(3, ctx.mm(0.3)) + "px dashed #000;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;"
                + "text-align:center;padding:" + ctx.mm(1.5) + "px;\">"
                + "<div style=\"font-weight:bold;font-size:" + ctx.ptCss(9) + ";\">" + escape(title) + "</div>"
                + "<div style=\"font-size:" + ctx.ptCss(8) + ";\">" + escape(message) + "</div>"
                + "</div>";
    }
}
