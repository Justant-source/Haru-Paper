package com.harupaper.server.widget.stock;

import com.harupaper.server.widget.GridSpec;
import com.harupaper.server.widget.WidgetHtml;
import com.harupaper.server.widget.WidgetRenderContext;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * {@link StockChartWidget}의 실제 그리기 로직(.temp/07-위젯그리드-작업지시서.md 5.2절 "그리기").
 * 순수 함수다 — 네트워크·캐시를 모르고 {@link StockSeries}만 받아 HTML(헤더) + 인라인 SVG(캔들스틱)를
 * 만든다. 위젯 클래스에서 분리한 이유는 단위 테스트가 조회 성공 경로를 provider 없이 바로 찌를 수 있게 하기 위해서다.
 */
final class StockChartRenderer {

    private static final String BLACK = "#000";
    private static final String WHITE = "#fff";
    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("M/d", Locale.KOREA);
    // 캔들 폭은 칸 폭의 60~70%(.temp/07 5.2절)
    private static final double CANDLE_WIDTH_RATIO = 0.65;

    private StockChartRenderer() {
    }

    static String render(StockSeries series, WidgetRenderContext ctx) {
        boolean narrow = ctx.size().cols() < GridSpec.COLUMNS; // 지금 정의된 크기 중 2x4만 좁다
        int boxW = ctx.boxWidthPx();
        int boxH = ctx.boxHeightPx() != null ? ctx.boxHeightPx() : GridSpec.boxHeightPx(4, ctx.profile().dpi());

        List<Candle> candles = series.candles();
        Candle last = candles.get(candles.size() - 1);
        boolean hasChange = series.previousClose() != null && series.previousClose() != 0;

        String header = renderHeader(series, last, narrow, ctx);
        int headerH = estimateHeaderHeightPx(narrow, hasChange, ctx);
        int chartH = Math.max(boxH - headerH, ctx.mm(20));

        String chartBody = renderChartSvg(candles, boxW, chartH, narrow, ctx);

        return "<div style=\"width:100%;height:100%;overflow:hidden;display:flex;flex-direction:column;"
                + "font-family:inherit;color:#000;\">"
                + header
                + "<svg viewBox=\"0 0 " + boxW + " " + chartH + "\" width=\"100%\" height=\"" + chartH
                + "px\" style=\"display:block;flex:0 0 auto;\">" + chartBody + "</svg>"
                + "</div>";
    }

    private static String renderHeader(StockSeries series, Candle last, boolean narrow, WidgetRenderContext ctx) {
        double tickerPt = narrow ? 12 : 13;
        double pricePt = narrow ? 12 : 15;
        double companyPt = 8.5;
        double changePt = narrow ? 9.5 : 10.5;
        double datePt = 8;
        double smallPt = 7.5; // "(이전 시세)" — CLAUDE.md 최소 7pt를 지킨다

        Double prevClose = series.previousClose();
        Double changeAbs = null;
        Double changePct = null;
        if (prevClose != null && prevClose != 0) {
            changeAbs = last.close() - prevClose;
            changePct = changeAbs / prevClose * 100.0;
        }

        StringBuilder h = new StringBuilder();
        h.append("<div style=\"padding:").append(ctx.mm(1)).append("px ").append(ctx.mm(1.5))
                .append("px 0 ").append(ctx.mm(1.5)).append("px;min-width:0;\">");

        // 1행: 티커 / 마지막 종가 + 통화 — CSS grid로 오른쪽(가격)은 항상 제 폭만큼, 왼쪽(티커)은
        // 남는 폭만 쓰게 강제한다(minmax(0,1fr)). flexbox의 자동 shrink는 회사명이 길 때 가격이
        // 박스 밖으로 밀려 잘리는 문제가 있어(실측) grid로 바꿨다
        h.append("<div style=\"display:grid;grid-template-columns:minmax(0,1fr) auto;")
                .append("align-items:baseline;gap:").append(ctx.mm(1)).append("px;\">");
        h.append("<span style=\"font-weight:bold;font-size:").append(ctx.ptCss(tickerPt))
                .append(";white-space:nowrap;overflow:hidden;text-overflow:ellipsis;\">")
                .append(WidgetHtml.escape(series.symbol())).append("</span>");
        h.append("<span style=\"font-weight:bold;font-size:").append(ctx.ptCss(pricePt))
                .append(";white-space:nowrap;\">").append(formatPrice(last.close()))
                .append(" <span style=\"font-weight:normal;font-size:").append(ctx.ptCss(datePt)).append(";\">")
                .append(WidgetHtml.escape(series.currency())).append("</span></span>");
        h.append("</div>");

        // 1-2행: 회사명(넓은 크기만) — 가격과 같은 줄에 두면 가격 쪽이 밀려 잘리는 문제가 있어
        // 따로 한 줄을 쓰고 전체 폭 기준으로 말줄임한다(.temp/07 5.2절)
        if (!narrow) {
            h.append("<div style=\"font-size:").append(ctx.ptCss(companyPt))
                    .append(";white-space:nowrap;overflow:hidden;text-overflow:ellipsis;\">")
                    .append(WidgetHtml.escape(series.shortName())).append("</div>");
        }

        // 2행: 등락(삼각형 + 값)
        if (changeAbs != null) {
            String sign = changeAbs > 0 ? "+" : (changeAbs < 0 ? "−" : "");
            String absText = sign + formatPrice(Math.abs(changeAbs));
            String pctSign = changePct >= 0 ? "+" : "−";
            String pctText = pctSign + String.format(Locale.US, "%.2f%%", Math.abs(changePct));
            h.append("<div style=\"display:flex;align-items:center;gap:").append(ctx.mm(0.8))
                    .append("px;font-size:").append(ctx.ptCss(changePt)).append(";\">")
                    .append(triangleSvg(changeAbs, ctx.pt(changePt)))
                    .append("<span>").append(absText).append(" (").append(pctText).append(")</span></div>");
        }

        // 3행: 마지막 봉 날짜 (+ stale 표시)
        h.append("<div style=\"font-size:").append(ctx.ptCss(datePt)).append(";white-space:nowrap;\">")
                .append(MONTH_DAY.format(last.date())).append(" 종가");
        if (series.stale()) {
            h.append(" <span style=\"font-size:").append(ctx.ptCss(smallPt)).append(";\">(이전 시세)</span>");
        }
        h.append("</div>");

        h.append("</div>");
        return h.toString();
    }

    /** ▲▼ 글리프는 글꼴에 따라 빠질 수 있어 SVG 삼각형으로 그린다(.temp/07 5.2절) */
    private static String triangleSvg(double changeAbs, double sizePxD) {
        int size = Math.max(6, (int) Math.round(sizePxD));
        String shape;
        if (changeAbs > 0) {
            shape = "<polygon points=\"5,0 10,10 0,10\" fill=\"" + BLACK + "\"/>";
        } else if (changeAbs < 0) {
            shape = "<polygon points=\"0,0 10,0 5,10\" fill=\"" + BLACK + "\"/>";
        } else {
            shape = "<rect x=\"0\" y=\"4\" width=\"10\" height=\"2\" fill=\"" + BLACK + "\"/>";
        }
        return "<svg width=\"" + size + "\" height=\"" + size + "\" viewBox=\"0 0 10 10\" "
                + "style=\"flex:0 0 auto;\">" + shape + "</svg>";
    }

    /**
     * 헤더가 실제로 차지할 높이(px)를 미리 어림한다. 헤더는 흐르는 HTML이고 차트는 고정 viewBox의
     * SVG라서, 차트 영역을 얼마나 줄일지 렌더 전에 정해야 한다. line-height 1.3배 + 위아래 여백.
     */
    private static int estimateHeaderHeightPx(boolean narrow, boolean hasChangeLine, WidgetRenderContext ctx) {
        double tickerPt = narrow ? 12 : 13;
        double pricePt = narrow ? 12 : 15;
        double companyPt = 8.5;
        double changePt = narrow ? 9.5 : 10.5;
        double datePt = 8;
        double line1Pt = Math.max(tickerPt, pricePt) * 1.3;
        double companyLinePt = narrow ? 0 : companyPt * 1.3; // 회사명은 넓은 크기만 별도 줄
        double line2Pt = hasChangeLine ? changePt * 1.3 : 0;
        double line3Pt = datePt * 1.3;
        double totalPt = line1Pt + companyLinePt + line2Pt + line3Pt;
        return (int) Math.round(ctx.pt(totalPt) + ctx.mm(2.5));
    }

    private static String renderChartSvg(List<Candle> candles, int boxW, int chartH, boolean narrow,
                                          WidgetRenderContext ctx) {
        int n = candles.size();
        double minLow = candles.stream().mapToDouble(Candle::low).min().orElseThrow();
        double maxHigh = candles.stream().mapToDouble(Candle::high).max().orElseThrow();
        double range = maxHigh - minLow;
        if (range <= 0) {
            // 모든 봉의 고가=저가(범위 0)여도 0으로 나누지 않는다(.temp/07 5.2절)
            double pad = Math.max(Math.abs(maxHigh) * 0.01, 1.0);
            minLow -= pad;
            maxHigh += pad;
            range = maxHigh - minLow;
        }
        double padAmount = range * 0.05; // 위아래 5% 여유(.temp/07 5.2절)
        double yMax = maxHigh + padAmount;
        double yMin = minLow - padAmount;
        double yRange = yMax - yMin;

        double labelPt = narrow ? 8 : 8.5;
        // 가로 눈금 가격: 좁은 폭(2x4)은 최고/최저 2개만, 넓은 폭은 4개(.temp/07 5.2절)
        List<Double> gridPrices = narrow
                ? List.of(maxHigh, minLow)
                : List.of(yMax, yMax - yRange / 3.0, yMax - yRange * 2.0 / 3.0, yMin);
        // 가격 라벨 폭은 가장 긴 라벨 글자 수로 잡는다 — 고정 폭이면 "4,300.5" 같은 긴 값이 박스 밖으로 잘린다.
        // 숫자 한 글자 ≈ 0.62em(고딕 계열 숫자 폭의 넉넉한 어림) + 눈금선과의 간격 1mm + 오른쪽 여유 0.5mm
        int maxLabelChars = gridPrices.stream().mapToInt(p -> formatPrice(p).length()).max().orElse(6);
        int rightMarginPx = (int) Math.ceil(maxLabelChars * ctx.pt(labelPt) * 0.62) + ctx.mm(1.5);
        int bottomMarginPx = (int) Math.round(ctx.pt(labelPt) * 1.8);
        int topMarginPx = (int) Math.round(ctx.pt(labelPt) * 0.6);
        int plotW = Math.max(boxW - rightMarginPx, boxW / 2);
        int plotH = Math.max(chartH - bottomMarginPx - topMarginPx, 10);

        int strokeW = Math.max(3, ctx.mm(0.3)); // 선 굵기 3px 이상(CLAUDE.md, Widget 구현 규칙 ③)

        StringBuilder svg = new StringBuilder();

        // 가로 점선 눈금 + 가격 라벨
        for (double price : gridPrices) {
            double y = topMarginPx + (yMax - price) / yRange * plotH;
            svg.append("<line x1=\"0\" y1=\"").append(fmt(y)).append("\" x2=\"").append(plotW)
                    .append("\" y2=\"").append(fmt(y)).append("\" stroke=\"").append(BLACK)
                    // 눈금선도 3px 이상(구현 규칙 ③) — 가늘면 감열 인쇄에서 사라진다. 대신 점 간격을 넓혀 봉보다 옅게 보이게 한다
                    .append("\" stroke-width=\"").append(strokeW)
                    .append("\" stroke-dasharray=\"").append(strokeW).append(' ').append(strokeW * 4).append("\"/>");
            svg.append("<text x=\"").append(plotW + ctx.mm(1)).append("\" y=\"").append(fmt(y))
                    .append("\" font-size=\"").append(fmt(ctx.pt(labelPt)))
                    .append("\" dominant-baseline=\"middle\" fill=\"").append(BLACK).append("\">")
                    .append(formatPrice(price)).append("</text>");
        }

        // 캔들스틱: 오른 날(종가>=시가)=흰 채움+검은 테두리, 내린 날=검게 채움 + 심지 선(.temp/07 5.2절)
        double colWidth = plotW / (double) n;
        double bodyWidth = Math.max(colWidth * CANDLE_WIDTH_RATIO, strokeW * 2.0);
        for (int i = 0; i < n; i++) {
            Candle c = candles.get(i);
            double cx = colWidth * (i + 0.5);
            double yOpen = topMarginPx + (yMax - c.open()) / yRange * plotH;
            double yClose = topMarginPx + (yMax - c.close()) / yRange * plotH;
            double yHigh = topMarginPx + (yMax - c.high()) / yRange * plotH;
            double yLow = topMarginPx + (yMax - c.low()) / yRange * plotH;
            boolean up = c.close() >= c.open();
            double bodyTop = Math.min(yOpen, yClose);
            double bodyHeight = Math.max(Math.abs(yClose - yOpen), strokeW / 2.0);

            svg.append("<line x1=\"").append(fmt(cx)).append("\" y1=\"").append(fmt(yHigh))
                    .append("\" x2=\"").append(fmt(cx)).append("\" y2=\"").append(fmt(yLow))
                    .append("\" stroke=\"").append(BLACK).append("\" stroke-width=\"").append(strokeW)
                    .append("\"/>");
            svg.append("<rect x=\"").append(fmt(cx - bodyWidth / 2)).append("\" y=\"").append(fmt(bodyTop))
                    .append("\" width=\"").append(fmt(bodyWidth)).append("\" height=\"").append(fmt(bodyHeight))
                    .append("\" fill=\"").append(up ? WHITE : BLACK).append("\" stroke=\"").append(BLACK)
                    .append("\" stroke-width=\"").append(strokeW).append("\"/>");
        }

        // 첫날·마지막 날 날짜(M/D)
        String firstDate = MONTH_DAY.format(candles.get(0).date());
        String lastDate = MONTH_DAY.format(candles.get(n - 1).date());
        double dateY = topMarginPx + plotH + ctx.pt(labelPt) * 0.9 + ctx.mm(0.5);
        svg.append("<text x=\"0\" y=\"").append(fmt(dateY)).append("\" font-size=\"").append(fmt(ctx.pt(labelPt)))
                .append("\" fill=\"").append(BLACK).append("\">").append(firstDate).append("</text>");
        svg.append("<text x=\"").append(plotW).append("\" y=\"").append(fmt(dateY)).append("\" font-size=\"")
                .append(fmt(ctx.pt(labelPt))).append("\" text-anchor=\"end\" fill=\"").append(BLACK).append("\">")
                .append(lastDate).append("</text>");

        return svg.toString();
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    // 1000 이상은 천 단위 구분 + 소수 1자리, 그 미만은 소수 2자리(.temp/07 5.2절)
    private static String formatPrice(double price) {
        if (Math.abs(price) >= 1000) {
            return String.format(Locale.US, "%,.1f", price);
        }
        return String.format(Locale.US, "%.2f", price);
    }
}
