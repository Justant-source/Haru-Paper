package com.harupaper.server.widget.basic;

import com.harupaper.server.asset.Asset;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.widget.PropField;
import com.harupaper.server.widget.Widget;
import com.harupaper.server.widget.WidgetDescriptor;
import com.harupaper.server.widget.WidgetHtml;
import com.harupaper.server.widget.WidgetInstance;
import com.harupaper.server.widget.WidgetRenderContext;
import com.harupaper.server.widget.WidgetSize;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.harupaper.server.widget.basic.BasicWidgetSupport.ALIGN_OPTIONS;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.intProp;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.justifyContent;
import static com.harupaper.server.widget.basic.BasicWidgetSupport.stringProp;

/**
 * 업로드한 이미지를 넣는 위젯(.temp/07-위젯그리드-작업지시서.md 5.4절). 옛 FormatDocument v2의
 * "image" 블록을 그대로 옮긴 것 — data: URI로 내장하는 방식은 동일하다.
 *
 * catalog=true(2026-09-19부터): 앱에 asset kind 필드 편집기(`AssetField.tsx`)가 생겨 "위젯 추가"
 * 목록에 노출한다. 그전에는(catalog=false) 편집기가 없어 이전 버전 포맷을 읽을 때만 렌더했다.
 */
@Slf4j
@Component
public class ImageWidget implements Widget {

    private static final WidgetDescriptor DESCRIPTOR = new WidgetDescriptor(
            "image",
            "이미지",
            "사진이나 그림을 넣습니다",
            "image",
            false,
            true,
            List.of(WidgetSize.auto(4, "자동 높이 · 104mm 폭")),
            "4xauto",
            List.of(
                    PropField.asset("assetId", "이미지", true, "업로드한 이미지를 고릅니다"),
                    PropField.integer("widthPercent", "폭(%)", false, 100, 10, 100, null),
                    PropField.enumOf("align", "정렬", false, "center", ALIGN_OPTIONS, null)
            )
    );

    private final AssetRepository assetRepository;
    private final String filesDir;

    public ImageWidget(AssetRepository assetRepository, @Value("${haru.files-dir}") String filesDir) {
        this.assetRepository = assetRepository;
        this.filesDir = filesDir;
    }

    @Override
    public WidgetDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
        Map<String, Object> props = instance.props() != null ? instance.props() : Map.of();
        Object assetIdObj = props.get("assetId");
        String dataUri = (assetIdObj instanceof String assetId) ? getImageDataUri(assetId) : null;
        if (dataUri == null) {
            return WidgetHtml.errorBox("이미지", "이미지를 가져오지 못했습니다", ctx);
        }

        int widthPercent = intProp(props, "widthPercent", 100);
        String align = stringProp(props, "align", "center");
        String css = "width:100%;height:100%;display:flex;justify-content:" + justifyContent(align)
                + ";align-items:center;overflow:hidden;";

        return "<div style=\"" + css + "\"><img src=\"" + WidgetHtml.escape(dataUri)
                + "\" style=\"width:" + widthPercent + "%;\"></div>";
    }

    /** 이미지 에셋을 data: URI로 변환. 실패해도 예외를 던지지 않고 null을 돌려준다(errorBox로 대체). */
    private String getImageDataUri(String assetId) {
        try {
            Optional<Asset> asset = assetRepository.findById(assetId);
            if (asset.isEmpty()) {
                log.warn("Asset not found: {}", assetId);
                return null;
            }
            Asset a = asset.get();
            byte[] imageData = Files.readAllBytes(Paths.get(filesDir).resolve(a.getPath()));
            String base64 = Base64.getEncoder().encodeToString(imageData);
            return "data:" + a.getContentType() + ";base64," + base64;
        } catch (IOException e) {
            log.error("Failed to load asset: {}", assetId, e);
            return null;
        }
    }
}
