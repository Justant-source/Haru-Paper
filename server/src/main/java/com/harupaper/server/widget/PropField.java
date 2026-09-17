package com.harupaper.server.widget;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

/**
 * 위젯 설정값(props) 한 칸의 스키마. 서버 검증기와 앱 설정 폼이 둘 다 이것만 보고 동작한다 —
 * 새 위젯을 추가해도 앱 코드를 고칠 필요가 없게 하려는 것이다.
 *
 * kind별 값 형태:
 * <ul>
 *   <li>STRING  : 문자열 한 줄. maxLength, pattern(정규식, 전체 일치) 적용</li>
 *   <li>TEXT    : 여러 줄 문자열. maxLength 적용</li>
 *   <li>INTEGER : 정수. min~max</li>
 *   <li>BOOLEAN : true/false</li>
 *   <li>ENUM    : options[].value 중 하나(문자열)</li>
 *   <li>KOREA_LOCATION : {"label": String(1~50자), "lat": 33.0~39.0, "lon": 124.0~132.0} — 대한민국 범위</li>
 *   <li>ASSET   : 업로드된 에셋 id(문자열). 존재 여부를 검증한다</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PropField(
        String key,
        String label,
        Kind kind,
        boolean required,
        Object defaultValue,
        Integer min,
        Integer max,
        Integer maxLength,
        String pattern,
        String placeholder,
        String help,
        List<Option> options
) {

    public enum Kind {
        STRING("string"), TEXT("text"), INTEGER("integer"), BOOLEAN("boolean"),
        ENUM("enum"), KOREA_LOCATION("koreaLocation"), ASSET("asset");

        private final String json;

        Kind(String json) {
            this.json = json;
        }

        @JsonValue
        public String json() {
            return json;
        }
    }

    public record Option(String value, String label) {
    }

    // 대한민국 범위 [기본값]: 마라도(33.1N)~고성(38.6N), 백령도(124.6E)~독도(131.9E)를 덮는 사각형
    public static final double KOREA_LAT_MIN = 33.0;
    public static final double KOREA_LAT_MAX = 39.0;
    public static final double KOREA_LON_MIN = 124.0;
    public static final double KOREA_LON_MAX = 132.0;

    public static PropField string(String key, String label, boolean required, String defaultValue,
                                   int maxLength, String pattern, String placeholder, String help) {
        return new PropField(key, label, Kind.STRING, required, defaultValue, null, null, maxLength,
                pattern, placeholder, help, null);
    }

    public static PropField text(String key, String label, boolean required, String defaultValue,
                                 int maxLength, String placeholder, String help) {
        return new PropField(key, label, Kind.TEXT, required, defaultValue, null, null, maxLength,
                null, placeholder, help, null);
    }

    public static PropField integer(String key, String label, boolean required, Integer defaultValue,
                                    int min, int max, String help) {
        return new PropField(key, label, Kind.INTEGER, required, defaultValue, min, max, null,
                null, null, help, null);
    }

    public static PropField bool(String key, String label, boolean defaultValue, String help) {
        return new PropField(key, label, Kind.BOOLEAN, false, defaultValue, null, null, null,
                null, null, help, null);
    }

    public static PropField enumOf(String key, String label, boolean required, String defaultValue,
                                   List<Option> options, String help) {
        return new PropField(key, label, Kind.ENUM, required, defaultValue, null, null, null,
                null, null, help, List.copyOf(options));
    }

    public static PropField koreaLocation(String key, String label, boolean required, Object defaultValue, String help) {
        return new PropField(key, label, Kind.KOREA_LOCATION, required, defaultValue, null, null, null,
                null, null, help, null);
    }

    public static PropField asset(String key, String label, boolean required, String help) {
        return new PropField(key, label, Kind.ASSET, required, null, null, null, null,
                null, null, help, null);
    }
}
