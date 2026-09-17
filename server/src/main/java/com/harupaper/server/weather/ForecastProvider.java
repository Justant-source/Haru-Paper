package com.harupaper.server.weather;

import java.time.LocalDate;

/**
 * 일+시간대별 예보 출처. .temp/07-위젯그리드-작업지시서.md 5.3절.
 * 조회 실패 시 예외(OpenMeteoWeatherProvider.WeatherProviderException)를 던지고 호출부(위젯)가
 * 실패 표시로 처리한다 — {@link WeatherProvider}와 같은 규약.
 */
public interface ForecastProvider {
    DayForecast getForecast(double lat, double lon, LocalDate targetDate);
}
