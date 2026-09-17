package com.harupaper.server.widget;

import com.harupaper.server.device.PrinterProfile;

import java.time.LocalDate;

/**
 * 위젯 1개를 그릴 때 넘겨주는 값.
 *
 * @param targetDate     인쇄 대상 날짜(KST). 날씨처럼 날짜에 묶인 위젯이 쓴다
 * @param profile        프린터 프로필(dpi, printableWidthPx). 프린터 프로토콜 상수는 없다
 * @param ownerUserId    포맷 소유자. 레거시 포맷이면 null일 수 있다
 * @param size           이 인스턴스가 고른 크기
 * @param boxWidthPx     위젯 박스 폭(px)
 * @param boxHeightPx    위젯 박스 높이(px). 자동 높이면 null
 * @param baseFontSizePt 포맷 전체 기본 글자 크기(pt). 위젯은 이 값을 기준으로 상대 크기를 잡는다
 */
public record WidgetRenderContext(
        LocalDate targetDate,
        PrinterProfile profile,
        String ownerUserId,
        WidgetSize size,
        int boxWidthPx,
        Integer boxHeightPx,
        double baseFontSizePt
) {
    /** mm → px (반올림) */
    public int mm(double mm) {
        return GridSpec.mmToPx(mm, profile.dpi());
    }

    /** pt → px */
    public double pt(double pt) {
        return GridSpec.ptToPx(pt, profile.dpi());
    }

    /** pt → "NN.Npx" CSS 문자열 */
    public String ptCss(double pt) {
        return String.format(java.util.Locale.ROOT, "%.1fpx", pt(pt));
    }
}
