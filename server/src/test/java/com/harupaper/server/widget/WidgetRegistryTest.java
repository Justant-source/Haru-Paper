package com.harupaper.server.widget;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WidgetRegistry의 asset 필드 수집(assetFieldKeys·findAssetReferences·collectAssetIds).
 * 2026-09-19 경계 복구: AssetCleanupScheduler·FormatService가 "image".equals(widget.type())로
 * 직접 하드코딩하던 것을 여기로 옮겼다 — 위젯 종류를 아는 코드는 widget/**뿐이어야 한다
 * (CLAUDE.md 구성요소 경계). asset 필드를 가진 위젯이 2종 이상이어도(필드 키 이름이 달라도)
 * 둘 다 잡혀야 그 전제가 지켜진다는 것을 이 테스트로 증명한다.
 */
@DisplayName("WidgetRegistry - asset 필드 수집")
class WidgetRegistryTest {

    private static Widget stubWidget(String type, PropField... fields) {
        WidgetDescriptor descriptor = new WidgetDescriptor(
                type, type, "설명", "icon", false, true,
                List.of(WidgetSize.fixed(4, 1, "L")), "4x1", List.of(fields));
        return new Widget() {
            @Override
            public WidgetDescriptor descriptor() {
                return descriptor;
            }

            @Override
            public String renderHtml(WidgetInstance instance, WidgetRenderContext ctx) {
                return "";
            }
        };
    }

    @Test
    @DisplayName("assetFieldKeys: kind=ASSET인 필드 key만 돌려준다, 없으면 빈 집합")
    void assetFieldKeysFiltersByKind() {
        Widget imageLike = stubWidget("imageLike",
                PropField.asset("assetId", "이미지", true, null),
                PropField.string("align", "정렬", false, "center", 20, null, null, null));
        Widget textOnly = stubWidget("textOnly",
                PropField.text("text", "본문", true, "", 5000, null, null));
        WidgetRegistry registry = new WidgetRegistry(List.of(imageLike, textOnly));

        assertEquals(Set.of("assetId"), registry.assetFieldKeys("imageLike"));
        assertEquals(Set.of(), registry.assetFieldKeys("textOnly"));
        assertEquals(Set.of(), registry.assetFieldKeys("unknownType"));
    }

    @Test
    @DisplayName("서로 다른 asset 필드 key를 가진 위젯 2종이 섞여 있어도 collectAssetIds가 둘 다 모은다")
    void collectAssetIdsAcrossMultipleAssetWidgetTypes() {
        // "image"만 하드코딩했다면 이 두 번째 위젯(logo, 필드 key도 다름)은 조용히 누락된다.
        Widget photo = stubWidget("photo", PropField.asset("assetId", "사진", true, null));
        Widget brand = stubWidget("brand", PropField.asset("logoId", "로고", true, null));
        WidgetRegistry registry = new WidgetRegistry(List.of(photo, brand));

        List<WidgetInstance> widgets = List.of(
                new WidgetInstance("w1", "photo", "4x1", Map.of("assetId", "asset-1")),
                new WidgetInstance("w2", "brand", "4x1", Map.of("logoId", "asset-2")),
                new WidgetInstance("w3", "photo", "4x1", Map.of("assetId", "asset-1")) // 중복
        );

        Set<String> ids = registry.collectAssetIds(widgets);

        assertEquals(Set.of("asset-1", "asset-2"), ids);
    }

    @Test
    @DisplayName("findAssetReferences: 위젯 인덱스·필드 key와 함께 돌려준다(오류 경로 조립용)")
    void findAssetReferencesReportsIndexAndFieldKey() {
        Widget brand = stubWidget("brand", PropField.asset("logoId", "로고", true, null));
        WidgetRegistry registry = new WidgetRegistry(List.of(brand));

        List<WidgetInstance> widgets = List.of(
                new WidgetInstance("w0", "brand", "4x1", Map.of()), // 값 없음 — 잡히지 않아야 함
                new WidgetInstance("w1", "brand", "4x1", Map.of("logoId", "asset-9"))
        );

        List<WidgetRegistry.AssetReference> refs = registry.findAssetReferences(widgets);

        assertEquals(1, refs.size());
        assertEquals(1, refs.get(0).widgetIndex());
        assertEquals("logoId", refs.get(0).fieldKey());
        assertEquals("asset-9", refs.get(0).assetId());
    }

    @Test
    @DisplayName("props가 null이거나 위젯 목록이 null이면 예외 없이 빈 결과")
    void handlesNullSafely() {
        Widget photo = stubWidget("photo", PropField.asset("assetId", "사진", true, null));
        WidgetRegistry registry = new WidgetRegistry(List.of(photo));

        assertTrue(registry.collectAssetIds(null).isEmpty());
        assertTrue(registry.findAssetReferences(null).isEmpty());

        List<WidgetInstance> widgets = List.of(new WidgetInstance("w1", "photo", "4x1", null));
        assertTrue(registry.collectAssetIds(widgets).isEmpty());
    }
}
