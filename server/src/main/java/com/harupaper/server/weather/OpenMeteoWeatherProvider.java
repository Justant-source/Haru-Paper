package com.harupaper.server.weather;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Open-Meteo Forecast API 구현.
 * 메모리 캐시 30분 + 폴백 캐시 24시간. HTTP 타임아웃 5초, 재시도 1회.
 * (docs/server/weather.md 1절, 5절)
 */
@Slf4j
@Service
public class OpenMeteoWeatherProvider implements WeatherProvider {

    private static final String API_BASE_URL = "https://api.open-meteo.com/v1/forecast";
    private static final int HTTP_TIMEOUT_SECONDS = 5;
    private static final int CACHE_TTL_MINUTES = 30;
    private static final int FALLBACK_CACHE_HOURS = 24;
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    // 캐시: (latRounded,lonRounded,date) -> (DailyWeather, cacheTime)
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final RestClient restClient;

    public OpenMeteoWeatherProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
    }

    @Override
    public DailyWeather getDaily(double lat, double lon, LocalDate targetDate) {
        String cacheKey = buildCacheKey(lat, lon, targetDate);

        // 30분 이내의 정상 캐시 확인
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && !cached.isExpired(CACHE_TTL_MINUTES)) {
            log.debug("Weather cache hit: {}", cacheKey);
            return cached.weather;
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
            DailyWeather stale = cached.weather;
            return new DailyWeather(stale.date(), stale.tempMin(), stale.tempMax(), stale.precipProb(),
                    stale.skyText(), stale.fetchedAt(), "open-meteo-stale");
        }

        throw new WeatherProviderException("Failed to fetch weather after retry and no valid cache", lastFailure);
    }

    private DailyWeather fetchFromApi(double lat, double lon, LocalDate targetDate, String cacheKey) {
        try {
            ApiResponse response = restClient.get()
                    .uri(buildApiUrl(lat, lon))
                    .retrieve()
                    .body(ApiResponse.class);

            if (response == null || response.daily == null || response.daily.time == null) {
                throw new WeatherProviderException("Invalid API response: missing daily data");
            }

            // targetDate와 같은 날짜의 인덱스 찾기
            int dateIndex = findDateIndex(response.daily.time, targetDate);
            if (dateIndex < 0) {
                throw new WeatherProviderException("Target date not in forecast range");
            }

            // 해당 인덱스의 값 추출
            Integer tempMin = response.daily.temperature2mMin != null
                    ? Math.round(response.daily.temperature2mMin[dateIndex])
                    : null;
            Integer tempMax = response.daily.temperature2mMax != null
                    ? Math.round(response.daily.temperature2mMax[dateIndex])
                    : null;
            Integer precipProb = response.daily.precipitationProbabilityMax != null
                    ? Math.round(response.daily.precipitationProbabilityMax[dateIndex])
                    : null;
            String skyText = mapWeatherCode(response.daily.weatherCode[dateIndex]);

            Instant now = Instant.now();
            DailyWeather weather = new DailyWeather(targetDate, tempMin, tempMax, precipProb,
                    skyText, now, "open-meteo");

            // 캐시 저장
            cache.put(cacheKey, new CacheEntry(weather, System.currentTimeMillis()));
            log.debug("Weather cached: {}", cacheKey);

            return weather;
        } catch (RestClientException e) {
            throw new WeatherProviderException("API call failed: " + e.getMessage(), e);
        }
    }

    private String buildApiUrl(double lat, double lon) {
        return String.format("%s?latitude=%.4f&longitude=%.4f&daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code&timezone=Asia/Seoul&forecast_days=3",
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

    private String mapWeatherCode(int code) {
        // docs/server/weather.md 3절 WMO 코드 매핑
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

    /**
     * 날씨 캐시를 명시적으로 비운다. 설정 변경 시 호출된다.
     */
    public void clearCache() {
        cache.clear();
        log.info("Weather cache cleared");
    }

    private static class CacheEntry {
        final DailyWeather weather;
        final long cacheTime;

        CacheEntry(DailyWeather weather, long cacheTime) {
            this.weather = weather;
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
