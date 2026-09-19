package com.harupaper.server.widget;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 등록된 위젯 목록. 스프링이 모든 {@link Widget} 빈을 주입한다 — 새 위젯은 @Component만 붙이면 된다.
 * 같은 type이 두 번 등록되면 기동 시점에 바로 실패시킨다.
 */
@Component
public class WidgetRegistry {

    private final Map<String, Widget> byType = new LinkedHashMap<>();

    public WidgetRegistry(List<Widget> widgets) {
        for (Widget widget : widgets) {
            String type = widget.descriptor().type();
            if (byType.putIfAbsent(type, widget) != null) {
                throw new IllegalStateException("duplicate widget type: " + type);
            }
        }
    }

    public Optional<Widget> find(String type) {
        return Optional.ofNullable(type == null ? null : byType.get(type));
    }

    public Collection<Widget> all() {
        return byType.values();
    }

    /** 외부 데이터에 의존하는 위젯이 하나라도 있으면 true (= 동적 포맷, 예약 60분 전 재렌더 대상) */
    public boolean hasDynamic(List<WidgetInstance> widgets) {
        if (widgets == null) {
            return false;
        }
        return widgets.stream()
                .map(w -> byType.get(w.type()))
                .anyMatch(w -> w != null && w.descriptor().dynamic());
    }

    /**
     * type의 props 필드 중 kind=ASSET인 key 집합. 위젯을 모르는 코드(에셋 정리·export/import)가
     * "asset 필드를 가진 위젯"을 다루려고 만들었다 — CLAUDE.md 구성요소 경계: 위젯 종류를 아는
     * 코드는 widget/** 뿐이어야 한다. 이전엔 AssetCleanupScheduler·FormatService가
     * "image".equals(widget.type())으로 직접 하드코딩했다(2026-09-19 이전).
     */
    public Set<String> assetFieldKeys(String type) {
        Widget widget = byType.get(type);
        if (widget == null) {
            return Set.of();
        }
        return widget.descriptor().fields().stream()
                .filter(f -> f.kind() == PropField.Kind.ASSET)
                .map(PropField::key)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 위젯 목록 안에서 asset 필드가 실제로 가리키는 값들을, 어느 위젯·어느 필드인지와 함께 모은다. */
    public List<AssetReference> findAssetReferences(List<WidgetInstance> widgets) {
        List<AssetReference> refs = new ArrayList<>();
        if (widgets == null) {
            return refs;
        }
        for (int i = 0; i < widgets.size(); i++) {
            WidgetInstance instance = widgets.get(i);
            if (instance.props() == null) {
                continue;
            }
            for (String key : assetFieldKeys(instance.type())) {
                Object value = instance.props().get(key);
                if (value instanceof String assetId && !assetId.isBlank()) {
                    refs.add(new AssetReference(i, key, assetId));
                }
            }
        }
        return refs;
    }

    /** {@link #findAssetReferences}의 assetId만 중복 없이. */
    public Set<String> collectAssetIds(List<WidgetInstance> widgets) {
        return findAssetReferences(widgets).stream()
                .map(AssetReference::assetId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** widgetIndex: FormatDocument.widgets() 안 위치(오류 경로 widgets[i].props.〈fieldKey〉 조립용). */
    public record AssetReference(int widgetIndex, String fieldKey, String assetId) {
    }
}
