package com.harupaper.server.weather;

import java.time.LocalDate;

/**
 * 날씨 출처 인터페이스 (docs/server/weather.md 4절). v1 구현은 OpenMeteoWeatherProvider.
 * targetDate(KST)의 일 단위 예보. 조회 실패 시 예외를 던지고 호출부가 실패 표시로 처리한다.
 */
public interface WeatherProvider {
    DailyWeather getDaily(double lat, double lon, LocalDate targetDate);
}
