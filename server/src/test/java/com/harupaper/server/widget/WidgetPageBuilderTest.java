package com.harupaper.server.widget;

import com.harupaper.server.device.PrinterProfile;
import com.harupaper.server.format.FormatStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("위젯 그리드 규격·페이지 골격")
class WidgetPageBuilderTest {

    @Test
    @DisplayName("기본 프로필(300dpi, 1300px)에서 박스 크기가 문서의 mm 값과 맞는다")
    void boxSizesMatchDocumentedMillimetres() {
        PrinterProfile profile = PrinterProfile.DEFAULT;
        int content = WidgetPageBuilder.contentWidthPx(FormatStyle.defaults(), profile);
        // 1300px − 3mm(35px)×2 = 1230px ≈ 104mm
        assertEquals(1230, content);
        assertEquals(1230, GridSpec.boxWidthPx(4, content, profile.dpi()));
        // 2열 ≈ 51mm ≈ 603px
        assertEquals(603, GridSpec.boxWidthPx(2, content, profile.dpi()));
        // 4행 = 12×4 + 2×3 = 54mm
        assertEquals(GridSpec.mmToPx(12, 300) * 4 + GridSpec.mmToPx(2, 300) * 3,
                GridSpec.boxHeightPx(4, profile.dpi()));
    }

    @Test
    @DisplayName("고정 크기는 w-fixed + rows만큼 span, 자동 높이는 span 1")
    void cellsGetGridSpans() {
        String html = WidgetPageBuilder.build(null, PrinterProfile.DEFAULT, List.of(
                new WidgetPageBuilder.Cell(WidgetSize.fixed(2, 4, "절반"), "<p>A</p>"),
                new WidgetPageBuilder.Cell(WidgetSize.auto(8, "전체"), "<p>B</p>")));
        assertTrue(html.contains("class=\"w w-fixed\" style=\"grid-column: span 2; grid-row: span 4;\""));
        assertTrue(html.contains("class=\"w\" style=\"grid-column: span 4; grid-row: span 1;\""));
        assertTrue(html.contains("grid-auto-flow: row dense"));
    }
}
