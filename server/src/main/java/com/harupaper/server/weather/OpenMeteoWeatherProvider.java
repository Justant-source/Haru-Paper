package com.harupaper.server.weather;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open-Meteo Forecast API 구현. {@link WeatherProvider}(기존, 일 단위)와 {@link ForecastProvider}
 * (신규, 일+시간대별)를 같은 캐시로 함께 구현한다 — 두 API 모두 daily+hourly를 한 번에 받아 오는
 * 같은 HTTP 호출 결과에서 파생되므로 캐시를 나눌 이유가 없다({@link #clearCache()}가 그래서
 * 새 예보 캐시도 같이 비운다: 캐시가 하나뿐이다).
 *
 * 메모리 캐시 30분 + 폴백 캐시 24시간. HTTP 타임아웃 5초, 재시도 1회.
 * (docs/server/weather.md 1절, 5절 / .temp/07-위젯그리드-작업지시서.md 5.3절)
 */
@Slf4j
@Service
public class OpenMeteoWeatherProvider implements WeatherProvider, ForecastProvider {

    private static final String API_BASE_URL = "https://api.open-meteo.com/v1/forecast";
    private static final int HTTP_TIMEOUT_SECONDS = 5;
    private static final int CACHE_TTL_MINUTES = 30;
    private static final int FALLBACK_CACHE_HOURS = 24;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    // 캐시: (latRounded,lonRounded,date) -> (DayForecast, cacheTime). getDaily()는 여기서 파생시킨다
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final RestClient restClient;

    public OpenMeteoWeatherProvider() {
        this(buildDefaultRestClient());
    }

    /** 테스트에서 MockRestServiceServer로 바인딩한 RestClient를 넣기 위한 생성자. Spring은 위 no-arg 생성자를 쓴다. */
    OpenMeteoWeatherProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient buildDefaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public DailyWeather getDaily(double lat, double lon, LocalDate targetDate) {
        DayForecast forecast = fetch(lat, lon, targetDate);
        return new DailyWeather(forecast.date(), forecast.tempMin(), forecast.tempMax(), forecast.precipProb(),
                forecast.skyText(), forecast.fetchedAt(), forecast.stale() ? "open-meteo-stale" : "open-meteo");
    }

    @Override
    public DayForecast getForecast(double lat, double lon, LocalDate targetDate) {
        return fetch(lat, lon, targetDate);
    }

    private DayForecast fetch(double lat, double lon, LocalDate targetDate) {
        String cacheKey = buildCacheKey(lat, lon, targetDate);

        // 30분 이내의 정상 캐시 확인
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(CACHE_TTL_MINUTES)) {
            log.debug("Weather cache hit: {}", cacheKey);
            return cached.forecast;
        }

        Exception lastFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return fetchFromApi(lat, lon, targetDate, cacheKey);
            } catch (Exception e) {
                lastFailure = e;
                log.warn("Failed to fetch weather from API (attempt {}): {}", attempt + 1, e.getMessage());
            }
        }

        if (cached != null && !cached.isExpiredForFallback(FALLBACK_CACHE_HOURS)) {
            log.info("Using fallback cache for weather: {} (age: {}min)", cacheKey,
                    (System.currentTimeMillis() - cached.cacheTime) / 60000);
            return cached.forecast.withStale(true);
        }

        throw new WeatherProviderException("Failed to fetch weather after retry and no valid cache", lastFailure);
    }

    private DayForecast fetchFromApi(double lat, double lon, LocalDate targetDate, String cacheKey) {
        try {
            ApiResponse response = restClient.get()
                    .uri(buildApiUrl(lat, lon))
                    .retrieve()
                    .body(ApiResponse.class);

            if (response == null || response.daily == null || response.daily.time == null) {
                throw new WeatherProviderException("Invalid API response: missing daily data");
            }

            int dateIndex = findDateIndex(response.daily.time, targetDate);
            if (dateIndex < 0) {
                throw new WeatherProviderException("Target date not in forecast range");
            }

            Integer tempMin = response.daily.temperature2mMin != null && response.daily.temperature2mMin[dateIndex] != null
                    ? Math.round(response.daily.temperature2mMin[dateIndex])
                    : null;
            Integer tempMax = response.daily.temperature2mMax != null && response.daily.temperature2mMax[dateIndex] != null
                    ? Math.round(response.daily.temperature2mMax[dateIndex])
                    : null;
            Integer precipProb = response.daily.precipitationProbabilityMax != null && response.daily.precipitationProbabilityMax[dateIndex] != null
                    ? Math.round(response.daily.precipitationProbabilityMax[dateIndex])
                    : null;
            // weather_code가 null인 응답은 실측된 적 없지만(문서 미확인), 방어적으로 -1(미확인 코드)로 떨어뜨린다
            int weatherCode = response.daily.weatherCode != null && response.daily.weatherCode[dateIndex] != null
                    ? response.daily.weatherCode[dateIndex]
                    : -1;
            String skyText = WeatherCodes.skyText(weatherCode);

            List<HourPoint> hours = extractHours(response.hourly, targetDate);

            Instant now = Instant.now();
            DayForecast forecast = new DayForecast(targetDate, tempMin, tempMax, precipProb, weatherCode, skyText,
                    hours, now, false);

            cache.put(cacheKey, new CacheEntry(forecast, System.currentTimeMillis()));
            log.debug("Weather cached: {}", cacheKey);

            return forecast;
        } catch (RestClientException e) {
            throw new WeatherProviderException("API call failed: " + e.getMessage(), e);
        }
    }

    /** hourly.time(예: "2026-09-18T06:00")에서 targetDate와 같은 날짜의 시각만 추린다 */
    private List<HourPoint> extractHours(HourlyData hourly, LocalDate targetDate) {
        List<HourPoint> hours = new ArrayList<>();
        if (hourly == null || hourly.time == null) {
            return hours;
        }
        String targetStr = targetDate.format(DATE_FORMATTER);
        for (int i = 0; i < hourly.time.length; i++) {
            String t = hourly.time[i];
            // "YYYY-MM-DDTHH:MM" 형식 — 날짜 앞부분만 비교
            if (t == null || !t.startsWith(targetStr) || t.length() < 16) {
                continue;
            }
            int hour;
            try {
                hour = Integer.parseInt(t.substring(11, 13));
            } catch (NumberFormatException e) {
                continue;
            }
            Integer temp = hourly.temperature2m != null && i < hourly.temperature2m.length && hourly.temperature2m[i] != null
                    ? Math.round(hourly.temperature2m[i])
                    : null;
            Integer precipProb = hourly.precipitationProbability != null && i < hourly.precipitationProbability.length
                    && hourly.precipitationProbability[i] != null
                    ? Math.round(hourly.precipitationProbability[i])
                    : null;
            int weatherCode = hourly.weatherCode != null && i < hourly.weatherCode.length && hourly.weatherCode[i] != null
                    ? hourly.weatherCode[i]
                    : -1;
            hours.add(new HourPoint(hour, temp, precipProb, weatherCode));
        }
        return hours;
    }

    private String buildApiUrl(double lat, double lon) {
        return String.format(
                "%s?latitude=%.4f&longitude=%.4f"
                        + "&daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code"
                        + "&hourly=temperature_2m,precipitation_probability,weather_code"
                        + "&timezone=Asia/Seoul&forecast_days=3",
                API_BASE_URL, lat, lon);
    }

    private String buildCacheKey(double lat, double lon, LocalDate date) {
        // 소수 4자리로 반올림
        double latRounded = Math.round(lat * 10000.0) / 10000.0;
        double lonRounded = Math.round(lon * 10000.0) / 10000.0;
        return String.format("%.4f,%.4f,%s", latRounded, lonRounded, date);
    }

    private int findDateIndex(String[] times, LocalDate targetDate) {
        String targetStr = targetDate.format(DATE_FORMATTER);
        for (int i = 0; i < times.length; i++) {
            if (times[i].startsWith(targetStr)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 날씨 캐시를 명시적으로 비운다. 설정 변경 시 호출된다. daily(getDaily)·예보(getForecast) 캐시가
     * 하나로 합쳐져 있으므로 이 한 번으로 둘 다 비워진다.
     */
    public void clearCache() {
        cache.clear();
        log.info("Weather cache cleared");
    }

    private static class CacheEntry {
        final DayForecast forecast;
        final long cacheTime;

        CacheEntry(DayForecast forecast, long cacheTime) {
            this.forecast = forecast;
            this.cacheTime = cacheTime;
        }

        boolean isExpired(int ttlMinutes) {
            long ageMs = System.currentTimeMillis() - cacheTime;
            return ageMs > (long) ttlMinutes * 60 * 1000;
        }

        boolean isExpiredForFallback(int fallbackHours) {
            long ageMs = System.currentTimeMillis() - cacheTime;
            return ageMs > (long) fallbackHours * 60 * 60 * 1000;
        }
    }

    // Open-Meteo API 응답 역직렬화용 클래스들
    @lombok.Data
    public static class ApiResponse {
        public DailyData daily;
        public HourlyData hourly;
    }

    @lombok.Data
    public static class DailyData {
        public String[] time;

        @com.fasterxml.jackson.annotation.JsonProperty("temperature_2m_min")
        public Float[] temperature2mMin;

        @com.fasterxml.jackson.annotation.JsonProperty("temperature_2m_max")
        public Float[] temperature2mMax;

        @com.fasterxml.jackson.annotation.JsonProperty("precipitation_probability_max")
        public Float[] precipitationProbabilityMax;

        @com.fasterxml.jackson.annotation.JsonProperty("weather_code")
        public Integer[] weatherCode;
    }

    @lombok.Data
    public static class HourlyData {
        public String[] time;

        @com.fasterxml.jackson.annotation.JsonProperty("temperature_2m")
        public Float[] temperature2m;

        @com.fasterxml.jackson.annotation.JsonProperty("precipitation_probability")
        public Integer[] precipitationProbability;

        @com.fasterxml.jackson.annotation.JsonProperty("weather_code")
        public Integer[] weatherCode;
    }

    /**
     * 날씨 조회 실패 예외.
     */
    public static class WeatherProviderException extends RuntimeException {
        public WeatherProviderException(String message) {
            super(message);
        }

        public WeatherProviderException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
