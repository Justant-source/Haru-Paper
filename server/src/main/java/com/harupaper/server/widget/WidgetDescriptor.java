package com.harupaper.server.widget;

import java.util.List;

/**
 * 위젯 종류 하나의 명세. GET /api/widgets 가 이 record를 그대로 JSON으로 내려준다.
 *
 * @param type        포맷 문서에 저장되는 식별자 (예: "morningLetter"). 한 번 정하면 바꾸지 않는다
 * @param name        앱에 보일 이름
 * @param description 앱 카탈로그에 보일 한 줄 설명
 * @param icon        앱 아이콘 키. 앱이 모르는 값이면 기본 아이콘을 쓴다 (letter|chart|weather|calendar|text|image)
 * @param dynamic     true면 외부 데이터에 의존한다 → 포맷이 "동적"이 되어 예약 60분 전에 다시 렌더된다
 * @param catalog     false면 앱 "위젯 추가" 목록에 안 나온다(이전 버전 포맷을 읽기 위한 호환용 위젯)
 * @param sizes       허용 크기. 1개 이상
 * @param defaultSize sizes 중 하나의 id
 * @param fields      설정값 스키마. 없으면 빈 리스트
 */
public record WidgetDescriptor(
        String type,
        String name,
        String description,
        String icon,
        boolean dynamic,
        boolean catalog,
        List<WidgetSize> sizes,
        String defaultSize,
        List<PropField> fields
) {
    public WidgetDescriptor {
        sizes = List.copyOf(sizes);
        fields = fields == null ? List.of() : List.copyOf(fields);
        if (sizes.isEmpty()) {
            throw new IllegalArgumentException("widget " + type + " must have at least one size");
        }
        final String wanted = defaultSize;
        if (sizes.stream().noneMatch(s -> s.id().equals(wanted))) {
            throw new IllegalArgumentException("widget " + type + " defaultSize not in sizes: " + defaultSize);
        }
    }

    public WidgetSize size(String sizeId) {
        return sizes.stream().filter(s -> s.id().equals(sizeId)).findFirst().orElse(null);
    }
}
