package com.harupaper.server.widget.basic;

import com.harupaper.server.asset.Asset;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetPreviewHarness;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ImageWidget - 업로드 이미지")
class ImageWidgetTest {

    private static final LocalDate DATE = LocalDate.of(2026, 9, 18);

    @TempDir
    Path tempDir;

    private WidgetRenderContext ctx(ImageWidget widget) {
        WidgetSize size = widget.descriptor().size("4xauto");
        return WidgetPreviewHarness.context(size, DATE);
    }

    @Test
    @DisplayName("에셋을 찾으면 data: URI로 내장한다")
    void embedsAssetAsDataUri() throws Exception {
        AssetRepository assetRepository = mock(AssetRepository.class);
        Files.write(tempDir.resolve("a1.png"), new byte[]{1, 2, 3, 4});
        Asset asset = Asset.builder().id("a1").contentType("image/png").path("a1.png").build();
        when(assetRepository.findById("a1")).thenReturn(Optional.of(asset));

        ImageWidget widget = new ImageWidget(assetRepository, tempDir.toString());
        WidgetInstance instance = new WidgetInstance("w1", "image", "4xauto", Map.of("assetId", "a1"));

        String html = widget.renderHtml(instance, ctx(widget));

        assertTrue(html.contains("data:image/png;base64,"));
    }

    @Test
    @DisplayName("widthPercent props가 img의 width로 들어간다")
    void widthPercentAppliesToImg() throws Exception {
        AssetRepository assetRepository = mock(AssetRepository.class);
        Files.write(tempDir.resolve("a1.png"), new byte[]{1, 2, 3, 4});
        Asset asset = Asset.builder().id("a1").contentType("image/png").path("a1.png").build();
        when(assetRepository.findById("a1")).thenReturn(Optional.of(asset));

        ImageWidget widget = new ImageWidget(assetRepository, tempDir.toString());
        WidgetInstance instance = new WidgetInstance("w1", "image", "4xauto",
                Map.of("assetId", "a1", "widthPercent", 60));

        String html = widget.renderHtml(instance, ctx(widget));

        assertTrue(html.contains("width:60%"));
    }

    @Test
    @DisplayName("에셋을 못 찾으면 errorBox를 그린다 — 인쇄 전체를 막지 않는다")
    void missingAssetBecomesErrorBox() {
        AssetRepository assetRepository = mock(AssetRepository.class);
        when(assetRepository.findById("missing")).thenReturn(Optional.empty());

        ImageWidget widget = new ImageWidget(assetRepository, tempDir.toString());
        WidgetInstance instance = new WidgetInstance("w1", "image", "4xauto", Map.of("assetId", "missing"));

        String html = widget.renderHtml(instance, ctx(widget));

        assertTrue(html.contains("이미지를 가져오지 못했습니다"));
    }

    @Test
    @DisplayName("assetId가 없으면(옛 데이터 손상 등) errorBox를 그린다")
    void missingAssetIdBecomesErrorBox() {
        AssetRepository assetRepository = mock(AssetRepository.class);
        ImageWidget widget = new ImageWidget(assetRepository, tempDir.toString());
        WidgetInstance instance = new WidgetInstance("w1", "image", "4xauto", Map.of());

        String html = widget.renderHtml(instance, ctx(widget));

        assertTrue(html.contains("이미지를 가져오지 못했습니다"));
    }
}
