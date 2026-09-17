package com.harupaper.server.widget;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
}
