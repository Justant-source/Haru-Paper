package com.harupaper.server.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * M6: 설정 서비스 + WeatherLocationProvider 구현.
 *
 * 사용자별 설정을 읽고, 없으면 환경변수 기본값을 사용한다.
 * 전역 WeatherLocationProvider.getCurrent()는 임의의(또는 가장 최근의) 사용자 설정을 가져온다.
 * (M7 이후 완전히 사용자별로 분리)
 *
 * docs/server/weather.md, docs/server/data-model.md 참고.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements WeatherLocationProvider {

    private static final String WEATHER_LOCATION_KEY = "weather.location";

    private final SettingsRepository settingsRepository;
    private final ObjectMapper objectMapper;

    @Value("${haru.weather.lat:37.5663}")
    private double defaultLat;

    @Value("${haru.weather.lon:126.9779}")
    private double defaultLon;

    /**
     * 현재 설정된 날씨 위치를 반환한다.
     * 사용자별 설정이 없으면 환경변수 기본값으로 폴백한다.
     *
     * TODO(M7 이후): 사용자별 날씨 위치로 완전히 분리 - 현재는 인증 컨텍스트 없이는 특정 사용자 선택 불가
     */
    @Override
    public WeatherLocation getCurrent() {
        // M6에서는 단일 사용자(PoC) 기준이지만, 마이그레이션 후 데이터는 여러 사용자의 설정이 섞여 있다.
        // TODO(M7 이후): 요청 컨텍스트에서 현재 사용자를 얻어 getWeatherLocation(userId)를 호출
        // 지금은 환경변수 기본값만 사용한다.

        try {
            // 폴백: 환경변수 기본값
            return new WeatherLocation(defaultLat, defaultLon, "서울시청");
        } catch (Exception e) {
            log.warn("Failed to get weather location, using hardcoded fallback", e);
            return new WeatherLocation(defaultLat, defaultLon, "서울시청");
        }
    }

    /**
     * 특정 사용자의 날씨 위치 설정을 조회한다.
     * 없으면 환경변수 기본값을 반환한다.
     */
    public WeatherLocation getWeatherLocation(String userId) {
        return settingsRepository.findByUserIdAndSettingKey(userId, WEATHER_LOCATION_KEY)
                .map(this::parseWeatherLocation)
                .orElseGet(() -> {
                    // 폴백: 환경변수 기본값
                    WeatherLocation fallback = new WeatherLocation(defaultLat, defaultLon, "서울시청");
                    log.debug("Weather location setting not found for user {}, using fallback: {}", userId, fallback);
                    return fallback;
                });
    }

    /**
     * 특정 사용자의 날씨 위치 설정을 저장한다.
     */
    public void saveWeatherLocation(String userId, WeatherLocation location) {
        saveSetting(userId, WEATHER_LOCATION_KEY, location);
        log.info("Weather location saved for user {}: {}", userId, location);
    }

    /**
     * 특정 키로 사용자 설정을 저장한다 (내부용).
     */
    private void saveSetting(String userId, String key, Object value) throws RuntimeException {
        try {
            String jsonValue = objectMapper.writeValueAsString(value);
            Settings setting = Settings.builder()
                    .userId(userId)
                    .settingKey(key)
                    .value(jsonValue)
                    .updatedAt(Instant.now())
                    .build();
            settingsRepository.save(setting);
        } catch (Exception e) {
            log.error("Failed to serialize setting {} for user {}: {}", key, userId, e.getMessage(), e);
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
            log.error("Failed to parse weather location setting for user {}", setting.getUserId(), e);
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
