package com.harupaper.server.widget.weather;

import com.harupaper.server.weather.WeatherCodes;
import com.harupaper.server.weather.WeatherCodes.IconCategory;

import java.util.Locale;

/**
 * WMO 코드 8종 → 흑백 선 SVG 아이콘. 순수 함수(스프링 의존 없음) — WidgetPreviewHarness와
 * WeatherWidget 양쪽에서 그대로 쓴다.
 *
 * viewBox는 항상 "0 0 64 64"로 고정하고, 요청 픽셀 크기(sizePx)에 맞게 stroke-width를
 * 뷰박스 단위로 역산한다: 렌더된 선이 실제로 3px 이상이어야 한다(Widget.java javadoc 규칙 ③) —
 * 1 뷰박스 단위가 화면에서 sizePx/64 px가 되므로, 3px를 얻으려면 3 * 64 / sizePx 뷰박스 단위가 필요하다.
 * 흑/백만 쓴다: fill은 "#000"/"#fff" 둘 중 하나, stroke는 항상 "#000".
 */
public final class WeatherIcons {

    /** 아이콘이 아무리 커져도 선이 너무 가늘어 보이지 않게 잡아두는 뷰박스 단위 하한 [기본값] */
    private static final double MIN_STROKE_VIEWBOX_UNITS = 1.4;
    /** 렌더된 선의 실제 최소 두께(px). Widget.java javadoc ③ "선 굵기는 3px 이상" */
    private static final double MIN_STROKE_PX = 3.0;
    private static final double VIEWBOX_SIZE = 64.0;

    // 구름 하나의 윤곽선(64x64 뷰박스 기준, 폭 55·높이 36 정도의 뭉게구름 실루엣)
    private static final String CLOUD_PATH =
            "M20 46 C13 46 8 41 8 35 C8 29 13 25 19 25 C20 16 28 10 37 10 C46 10 53 16 55 24 "
                    + "C60 25 63 29 63 34 C63 41 57 46 50 46 Z";

    private WeatherIcons() {
    }

    /** 요청 픽셀 크기에서 3px 이상을 보장하는 stroke-width(뷰박스 단위) */
    public static double strokeWidthForSize(int sizePx) {
        if (sizePx <= 0) {
            return MIN_STROKE_VIEWBOX_UNITS;
        }
        double needed = MIN_STROKE_PX * VIEWBOX_SIZE / sizePx;
        return Math.max(needed, MIN_STROKE_VIEWBOX_UNITS);
    }

    /** 코드로 바로 그리고 싶을 때(WeatherCodes.category를 거쳐서) */
    public static String svgForCode(int weatherCode, int sizePx) {
        return svg(WeatherCodes.category(weatherCode), sizePx);
    }

    /** 아이콘 8종 중 하나를 sizePx × sizePx로 그린 인라인 SVG 문자열 */
    public static String svg(IconCategory category, int sizePx) {
        double sw = strokeWidthForSize(sizePx);
        String inner = switch (category) {
            case CLEAR -> sun(32, 32, 13, 16, 23, sw, 8);
            case PARTLY_CLOUDY -> partlyCloudy(sw);
            case CLOUDY -> cloudy(sw);
            case FOG -> fog(sw);
            case RAIN -> rain(sw);
            case SNOW -> snow(sw);
            case SHOWER -> shower(sw);
            case THUNDERSTORM -> thunderstorm(sw);
        };
        return String.format(Locale.ROOT,
                "<svg width=\"%d\" height=\"%d\" viewBox=\"0 0 64 64\" xmlns=\"http://www.w3.org/2000/svg\" "
                        + "aria-hidden=\"true\" focusable=\"false\">%s</svg>",
                sizePx, sizePx, inner);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String sun(double cx, double cy, double r, double rayInner, double rayOuter, double sw, int rayCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("<circle cx=\"").append(num(cx)).append("\" cy=\"").append(num(cy)).append("\" r=\"").append(num(r))
                .append("\" fill=\"#fff\" stroke=\"#000\" stroke-width=\"").append(num(sw)).append("\"/>");
        for (int i = 0; i < rayCount; i++) {
            double angle = Math.toRadians(360.0 / rayCount * i);
            double x1 = cx + rayInner * Math.cos(angle);
            double y1 = cy + rayInner * Math.sin(angle);
            double x2 = cx + rayOuter * Math.cos(angle);
            double y2 = cy + rayOuter * Math.sin(angle);
            sb.append("<line x1=\"").append(num(x1)).append("\" y1=\"").append(num(y1))
                    .append("\" x2=\"").append(num(x2)).append("\" y2=\"").append(num(y2))
                    .append("\" stroke=\"#000\" stroke-width=\"").append(num(sw)).append("\" stroke-linecap=\"round\"/>");
        }
        return sb.toString();
    }

    /** 구름 하나(흰 채움+검은 테두리). transform으로 위치·크기를 바꿔가며 재사용한다 */
    private static String cloud(double sw, String transform) {
        String t = transform == null ? "" : " transform=\"" + transform + "\"";
        return "<path d=\"" + CLOUD_PATH + "\" fill=\"#fff\" stroke=\"#000\" stroke-width=\"" + num(sw)
                + "\" stroke-linejoin=\"round\"" + t + "/>";
    }

    /** 구름 하나(검은 채움 — 소나기·뇌우처럼 "짙은 구름"을 나타낼 때) */
    private static String cloudFilled(double sw, String transform) {
        String t = transform == null ? "" : " transform=\"" + transform + "\"";
        return "<path d=\"" + CLOUD_PATH + "\" fill=\"#000\" stroke=\"#000\" stroke-width=\"" + num(sw)
                + "\" stroke-linejoin=\"round\"" + t + "/>";
    }

    private static String partlyCloudy(double sw) {
        // 해(왼쪽 위, 절반만 보이게)는 먼저 그리고, 구름(오른쪽 아래로 옮김)을 위에 덮어 가린다
        StringBuilder sb = new StringBuilder();
        sb.append(sun(22, 20, 9, 12, 17, sw, 8));
        sb.append(cloud(sw, "translate(3,10) scale(0.85)"));
        return sb.toString();
    }

    private static String cloudy(double sw) {
        StringBuilder sb = new StringBuilder();
        // 뒤쪽 작은 구름으로 뭉게뭉게한 부피감을 준다
        sb.append(cloud(sw, "translate(-8,4) scale(0.55)"));
        sb.append(cloud(sw, "translate(2,4) scale(1.02)"));
        return sb.toString();
    }

    private static String fog(double sw) {
        StringBuilder sb = new StringBuilder();
        sb.append(cloud(sw, "translate(8,-4) scale(0.55)"));
        double[] ys = {38, 46, 54};
        double[] insets = {6, 12, 6};
        for (int i = 0; i < ys.length; i++) {
            sb.append("<line x1=\"").append(num(insets[i])).append("\" y1=\"").append(num(ys[i]))
                    .append("\" x2=\"").append(num(64 - insets[i])).append("\" y2=\"").append(num(ys[i]))
                    .append("\" stroke=\"#000\" stroke-width=\"").append(num(sw))
                    .append("\" stroke-linecap=\"round\"/>");
        }
        return sb.toString();
    }

    private static String drops(double sw, double topY, double bottomY, boolean bold) {
        StringBuilder sb = new StringBuilder();
        double[] xs = {22, 32, 42};
        for (double x : xs) {
            sb.append("<line x1=\"").append(num(x)).append("\" y1=\"").append(num(topY))
                    .append("\" x2=\"").append(num(x - 4)).append("\" y2=\"").append(num(bottomY))
                    .append("\" stroke=\"#000\" stroke-width=\"").append(num(bold ? sw * 1.3 : sw))
                    .append("\" stroke-linecap=\"round\"/>");
        }
        return sb.toString();
    }

    private static String rain(double sw) {
        return cloud(sw, null) + drops(sw, 50, 58, false);
    }

    private static String snow(double sw) {
        StringBuilder sb = new StringBuilder();
        sb.append(cloud(sw, null));
        double[] xs = {20, 32, 44};
        for (double x : xs) {
            double y = 55;
            double r = 5;
            // 눈 결정을 十자 두 개(수직/수평 + 대각선 대신 +자 한 번, 단순화)로 표현
            sb.append("<line x1=\"").append(num(x - r)).append("\" y1=\"").append(num(y))
                    .append("\" x2=\"").append(num(x + r)).append("\" y2=\"").append(num(y))
                    .append("\" stroke=\"#000\" stroke-width=\"").append(num(sw)).append("\" stroke-linecap=\"round\"/>");
            sb.append("<line x1=\"").append(num(x)).append("\" y1=\"").append(num(y - r))
                    .append("\" x2=\"").append(num(x)).append("\" y2=\"").append(num(y + r))
                    .append("\" stroke=\"#000\" stroke-width=\"").append(num(sw)).append("\" stroke-linecap=\"round\"/>");
        }
        return sb.toString();
    }

    private static String shower(double sw) {
        return cloudFilled(sw, null) + drops(sw, 48, 61, true);
    }

    private static String thunderstorm(double sw) {
        StringBuilder sb = new StringBuilder();
        sb.append(cloudFilled(sw, null));
        // 번개(지그재그 다각형). 구름 아래 가운데로
        String bolt = "37,42 25,57 32,57 27,63 45,46 36,46";
        sb.append("<polygon points=\"").append(bolt).append("\" fill=\"#fff\" stroke=\"#000\" stroke-width=\"")
                .append(num(sw * 0.7)).append("\" stroke-linejoin=\"round\"/>");
        return sb.toString();
    }
}
