package com.harupaper.server.weather;

/**
 * 시간대별 예보 한 칸(.temp/07-위젯그리드-작업지시서.md 5.3절).
 *
 * @param hour       0~23시(KST, Open-Meteo timezone=Asia/Seoul 응답 기준)
 * @param temp       기온(℃, 반올림). 응답에 없으면 null
 * @param precipProb 강수확률(%). 응답에 없으면 null
 * @param weatherCode WMO 코드. 응답에 없으면 -1(WeatherCodes 매핑에서 "날씨 코드 -1"로 떨어진다)
 */
public record HourPoint(int hour, Integer temp, Integer precipProb, int weatherCode) {
}
