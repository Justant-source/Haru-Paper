package com.harupaper.server.widget;

/**
 * 위젯 그리드 규격 [기본값, 2026-09-18]. 110mm 연속 롤 기준으로 정했다.
 *
 * <pre>
 *   용지 110mm − 좌우 여백 3mm×2 = 콘텐츠 폭 104mm
 *   4열, 칸 사이 간격 2mm  → 1열 24.5mm / 2열 51mm / 3열 77.5mm / 4열 104mm
 *   행 단위 12mm, 간격 2mm → 1행 12mm / 2행 26mm / 4행 54mm / 6행 82mm  (r행 = 12r + 2(r−1))
 * </pre>
 *
 * 즉 "2x4" 위젯은 약 51×54mm(거의 정사각), "4x4"는 104×54mm(약 2:1)다.
 * 이 값은 프린터 상수가 아니다 — 폭(px)은 항상 프린터 프로필(printableWidthPx, dpi)에서 계산한다.
 */
public final class GridSpec {

    /** 그리드 열 수. 안드로이드 홈 화면처럼 4열 고정 */
    public static final int COLUMNS = 4;
    /** 행 1칸의 높이(mm). 본문 11pt 두 줄(≈10.9mm)이 들어가는 최소 단위로 잡았다 */
    public static final double ROW_UNIT_MM = 12.0;
    /** 칸 사이 간격(mm). 가로·세로 같다 */
    public static final double GAP_MM = 2.0;
    /** 위젯 한 포맷당 최대 개수 */
    public static final int MAX_WIDGETS = 20;

    private GridSpec() {
    }

    public static int mmToPx(double mm, int dpi) {
        return (int) Math.round(mm * dpi / 25.4);
    }

    public static double ptToPx(double pt, int dpi) {
        return pt * dpi / 72.0;
    }

    public static int gapPx(int dpi) {
        return mmToPx(GAP_MM, dpi);
    }

    public static int rowUnitPx(int dpi) {
        return mmToPx(ROW_UNIT_MM, dpi);
    }

    /** cols칸짜리 위젯 박스의 폭(px). contentWidthPx = printableWidthPx − 좌우 여백 */
    public static int boxWidthPx(int cols, int contentWidthPx, int dpi) {
        int gap = gapPx(dpi);
        double colWidth = (contentWidthPx - gap * (COLUMNS - 1)) / (double) COLUMNS;
        return (int) Math.floor(colWidth * cols + gap * (cols - 1));
    }

    /** rows칸짜리 위젯 박스의 높이(px) */
    public static int boxHeightPx(int rows, int dpi) {
        return rowUnitPx(dpi) * rows + gapPx(dpi) * (rows - 1);
    }
}
