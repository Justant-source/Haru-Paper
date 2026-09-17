package com.harupaper.server.widget.basic;

import com.harupaper.server.widget.PropField;

import java.util.List;
import java.util.Map;

/** 기본 위젯 3종(dateHeader·text·image)이 공유하는 props 추출·정렬 도우미. */
final class BasicWidgetSupport {

    static final List<PropField.Option> ALIGN_OPTIONS = List.of(
            new PropField.Option("left", "왼쪽"),
            new PropField.Option("center", "가운데"),
            new PropField.Option("right", "오른쪽")
    );

    private BasicWidgetSupport() {
    }

    static String stringProp(Map<String, Object> props, String key, String defaultValue) {
        Object value = props.get(key);
        return (value instanceof String s && !s.isEmpty()) ? s : defaultValue;
    }

    static int intProp(Map<String, Object> props, String key, int defaultValue) {
        Object value = props.get(key);
        return (value instanceof Number n) ? n.intValue() : defaultValue;
    }

    static boolean boolProp(Map<String, Object> props, String key, boolean defaultValue) {
        Object value = props.get(key);
        return (value instanceof Boolean b) ? b : defaultValue;
    }

    /** text-align과 짝을 맞추는 flex justify-content 값 */
    static String justifyContent(String align) {
        return switch (align) {
            case "left" -> "flex-start";
            case "right" -> "flex-end";
            default -> "center";
        };
    }
}
