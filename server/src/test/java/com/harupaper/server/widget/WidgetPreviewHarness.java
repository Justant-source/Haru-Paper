package com.harupaper.server.widget;

import com.harupaper.server.device.PrinterProfile;
import com.harupaper.server.format.FormatStyle;
import com.harupaper.server.render.PlaywrightRenderer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;

/**
 * 위젯을 실제 렌더 골격(WidgetPageBuilder) 그대로 PNG로 떠 보는 개발용 도구.
 * 단위 테스트에서 호출하면 build/widget-previews/{name}.png 가 생긴다 — 눈으로 확인하는 용도이고,
 * 합격/불합격을 판정하지는 않는다. Chromium을 띄우므로 느리다(수 초).
 *
 * 사용 예:
 * <pre>
 *   WidgetSize size = widget.descriptor().size("2x4");
 *   var ctx = WidgetPreviewHarness.context(size, LocalDate.of(2026, 9, 18));
 *   String inner = widget.renderHtml(new WidgetInstance("w1", "stockChart", "2x4", props), ctx);
 *   WidgetPreviewHarness.renderToPng("stock-2x4", List.of(new WidgetPageBuilder.Cell(size, inner)));
 * </pre>
 */
public final class WidgetPreviewHarness {

    public static final PrinterProfile PROFILE = PrinterProfile.DEFAULT;
    public static final FormatStyle STYLE = FormatStyle.defaults();

    private WidgetPreviewHarness() {
    }

    /** 기본 프로필·기본 스타일 기준의 렌더 컨텍스트 */
    public static WidgetRenderContext context(WidgetSize size, LocalDate targetDate) {
        int contentWidth = WidgetPageBuilder.contentWidthPx(STYLE, PROFILE);
        int boxWidth = GridSpec.boxWidthPx(size.cols(), contentWidth, PROFILE.dpi());
        Integer boxHeight = size.isAutoHeight() ? null : GridSpec.boxHeightPx(size.rows(), PROFILE.dpi());
        return new WidgetRenderContext(targetDate, PROFILE, "preview-user", size, boxWidth, boxHeight,
                STYLE.baseFontSizePt());
    }

    /** 셀들을 한 장으로 조립해 PNG로 저장하고 경로를 돌려준다 */
    public static Path renderToPng(String name, List<WidgetPageBuilder.Cell> cells) throws IOException {
        String html = WidgetPageBuilder.build(STYLE, PROFILE, cells);
        Path dir = Paths.get("build", "widget-previews");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".html"), html);

        PlaywrightRenderer renderer = new PlaywrightRenderer();
        renderer.init();
        try {
            byte[] png = renderer.captureScreenshot(html, PROFILE.printableWidthPx());
            Path out = dir.resolve(name + ".png");
            Files.write(out, png);
            return out;
        } finally {
            renderer.destroy();
        }
    }
}
