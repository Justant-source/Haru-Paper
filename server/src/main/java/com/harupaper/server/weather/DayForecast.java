package com.harupaper.server.weather;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 하루치 예보(일 단위 요약 + 시간대별). .temp/07-위젯그리드-작업지시서.md 5.3절 원본.
 * weather 위젯이 쓰는 새 API({@link ForecastProvider}) 결과 타입 — 기존 {@link DailyWeather}는
 * SettingsController 등 공개 시그니처 유지를 위해 그대로 둔다.
 *
 * @param date        대상 날짜(KST)
 * @param tempMin     최저기온(℃, 반올림). 응답 범위 밖이면 null
 * @param tempMax     최고기온(℃, 반올림)
 * @param precipProb  강수확률(%, 일 최대값)
 * @param weatherCode 대표 WMO 코드(그 날의 daily.weather_code)
 * @param skyText     {@link WeatherCodes#skyText}로 만든 한국어 하늘 상태
 * @param hours       시간대별 값(있는 시간만). 빈 리스트일 수 있다
 * @param fetchedAt   이 값을 실제로 API에서 받아온 시각(캐시라도 최초 조회 시각 유지)
 * @param stale       true면 조회 실패 후 24시간 폴백 캐시에서 돌려준 값
 */
public record DayForecast(
        LocalDate date,
        Integer tempMin,
        Integer tempMax,
        Integer precipProb,
        int weatherCode,
        String skyText,
        List<HourPoint> hours,
        Instant fetchedAt,
        boolean stale
) {
    public DayForecast {
        hours = hours == null ? List.of() : List.copyOf(hours);
    }

    /** stale 플래그만 바꾼 복사본(24시간 폴백 캐시에서 돌려줄 때 씀) */
    public DayForecast withStale(boolean newStale) {
        return new DayForecast(date, tempMin, tempMax, precipProb, weatherCode, skyText, hours, fetchedAt, newStale);
    }
}
