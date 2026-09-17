package com.harupaper.server.widget;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 위젯이 허용하는 크기 하나. id는 "{cols}x{rows}" 또는 "4xauto".
 *
 * rows == null 이면 "자동 높이"(내용 길이만큼 늘어난다). 자동 높이는 항상 4열(전체 폭)이다 —
 * 고정 높이 위젯과 한 줄에 섞이면 빈 공간이 생기기 때문에 아예 막았다.
 *
 * @param previewRows 앱 배치도에서 자동 높이 위젯을 몇 행으로 그릴지(표시용 어림값). 고정 크기면 rows와 같다
 * @param label       앱 크기 선택지에 보일 설명 (예: "절반 폭 · 51×54mm")
 */
public record WidgetSize(String id, int cols, Integer rows, int previewRows, String label) {

    public static WidgetSize fixed(int cols, int rows, String label) {
        if (cols < 1 || cols > GridSpec.COLUMNS || rows < 1) {
            throw new IllegalArgumentException("invalid widget size: " + cols + "x" + rows);
        }
        return new WidgetSize(cols + "x" + rows, cols, rows, rows, label);
    }

    public static WidgetSize auto(int previewRows, String label) {
        return new WidgetSize(GridSpec.COLUMNS + "xauto", GridSpec.COLUMNS, null, previewRows, label);
    }

    @JsonProperty("autoHeight")
    public boolean isAutoHeight() {
        return rows == null;
    }
}
