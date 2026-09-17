package com.harupaper.server.weather;

/**
 * WMO weather_code(Open-Meteo가 쓰는 세계기상기구 현재일기 코드) → 표시값 매핑.
 * 한국어 하늘 상태 텍스트와 아이콘 분류 둘 다 여기 하나에만 둔다(docs/server/weather.md 3절,
 * .temp/07-위젯그리드-작업지시서.md 5.3절 "WMO 코드→8종...매핑은 한 곳에만 둔다").
 *
 * {@link OpenMeteoWeatherProvider}가 {@link #skyText}를, 위젯의 아이콘 선택기(WeatherIcons)가
 * {@link #category}를 쓴다.
 */
public final class WeatherCodes {

    private WeatherCodes() {
    }

    /** 아이콘 8종 분류 (.temp/07 5.3절: 맑음/구름 조금/흐림/안개/비/눈/소나기/뇌우) */
    public enum IconCategory {
        CLEAR, PARTLY_CLOUDY, CLOUDY, FOG, RAIN, SNOW, SHOWER, THUNDERSTORM
    }

    /** WMO 코드 → 한국어 하늘 상태. docs/server/weather.md 3절 표 그대로(기존 OpenMeteoWeatherProvider에서 옮김) */
    public static String skyText(int code) {
        return switch (code) {
            case 0 -> "맑음";
            case 1 -> "대체로 맑음";
            case 2 -> "구름 조금";
            case 3 -> "흐림";
            case 45, 48 -> "안개";
            case 51, 53, 55 -> "이슬비";
            case 56, 57 -> "어는 이슬비";
            case 61 -> "약한 비";
            case 63 -> "비";
            case 65 -> "강한 비";
            case 66, 67 -> "어는 비";
            case 71 -> "약한 눈";
            case 73 -> "눈";
            case 75 -> "강한 눈";
            case 77 -> "싸락눈";
            case 80 -> "약한 소나기";
            case 81 -> "소나기";
            case 82 -> "강한 소나기";
            case 85, 86 -> "눈보라";
            case 95 -> "천둥번개";
            case 96, 99 -> "우박 동반 천둥번개";
            default -> String.format("날씨 코드 %d", code);
        };
    }

    /** WMO 코드 → 아이콘 8종 중 하나. 모르는 코드는 CLOUDY로 안전하게 떨어뜨린다 */
    public static IconCategory category(int code) {
        return switch (code) {
            case 0 -> IconCategory.CLEAR;
            case 1, 2 -> IconCategory.PARTLY_CLOUDY;
            case 3 -> IconCategory.CLOUDY;
            case 45, 48 -> IconCategory.FOG;
            case 51, 53, 55, 56, 57, 61, 63, 65, 66, 67 -> IconCategory.RAIN;
            case 71, 73, 75, 77, 85, 86 -> IconCategory.SNOW;
            case 80, 81, 82 -> IconCategory.SHOWER;
            case 95, 96, 99 -> IconCategory.THUNDERSTORM;
            default -> IconCategory.CLOUDY;
        };
    }
}
