package com.harupaper.server.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 설정 서비스 + WeatherLocationProvider 구현.
 * 위치 설정을 읽고 기본값(환경변수)이 없으면 초기화한다.
 * (docs/server/weather.md 2절, docs/server/data-model.md 3절)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements WeatherLocationProvider, ApplicationRunner {

    private static final String WEATHER_LOCATION_KEY = "weather.location";

    private final SettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;

    @Value("${haru.weather.lat:37.5663}")
    private double defaultLat;

    @Value("${haru.weather.lon:126.9779}")
    private double defaultLon;

    /**
     * 애플리케이션 시작 시 설정을 초기화한다.
     * 위치 설정이 없으면 환경변수 기본값으로 만든다.
     */
    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!settingsRepository.existsById(WEATHER_LOCATION_KEY)) {
            WeatherLocation defaultLocation = new WeatherLocation(defaultLat, defaultLon, "서울시청");
            saveSetting(WEATHER_LOCATION_KEY, defaultLocation);
            log.info("Initialized weather location setting: {}", defaultLocation);
        }
    }

    /**
     * 현재 설정된 날씨 위치를 반환한다.
     */
    @Override
    public WeatherLocation getCurrent() {
        return settingsRepository.findById(WEATHER_LOCATION_KEY)
                .map(this::parseWeatherLocation)
                .orElseGet(() -> {
                    // 폴백: 설정이 없으면 환경변수 기본값 반환 (run() 미실행 상황 대비)
                    WeatherLocation fallback = new WeatherLocation(defaultLat, defaultLon, "서울시청");
                    log.warn("Weather location setting not found, using fallback: {}", fallback);
                    return fallback;
                });
    }

    /**
     * 날씨 위치 설정을 저장한다.
     */
    public void saveWeatherLocation(WeatherLocation location) {
        saveSetting(WEATHER_LOCATION_KEY, location);
        log.info("Weather location saved: {}", location);
    }

    /**
     * 특정 키로 설정을 저장한다 (내부용).
     */
    private void saveSetting(String key, Object value) throws RuntimeException {
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            Settings setting = Settings.builder()
                    .settingKey(key)
                    .value(jsonValue)
                    .updatedAt(Instant.now())
                    .build();
            settingsRepository.save(setting);
        } catch (Exception e) {
            log.error("Failed to serialize setting: {}", key, e);
            throw new SettingsException("Failed to save setting", e);
        }
    }

    /**
     * JSON 문자열을 WeatherLocation으로 역직렬화한다.
     */
    private WeatherLocation parseWeatherLocation(Settings setting) {
        try {
            return objectMapper.readValue(setting.getValue(), WeatherLocation.class);
        } catch (Exception e) {
            log.error("Failed to parse weather location setting", e);
            throw new SettingsException("Failed to parse weather location", e);
        }
    }

    /**
     * 설정 조회/저장 실패 예외.
     */
    public static class SettingsException extends RuntimeException {
        public SettingsException(String message) {
            super(message);
        }

        public SettingsException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
