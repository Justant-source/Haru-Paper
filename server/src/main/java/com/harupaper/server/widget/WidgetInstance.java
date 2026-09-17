package com.harupaper.server.widget;

import java.util.Map;

/**
 * 포맷 문서 v3의 위젯 1개 (docs/server/format-schema.md).
 *
 * @param id    문서 안에서 유일한 id(uuid). 앱이 만든다
 * @param type  {@link WidgetDescriptor#type()}
 * @param size  {@link WidgetSize#id()} — 그 위젯이 허용하는 크기 중 하나
 * @param props 설정값. 키는 descriptor.fields의 key뿐이다
 */
public record WidgetInstance(String id, String type, String size, Map<String, Object> props) {
}
