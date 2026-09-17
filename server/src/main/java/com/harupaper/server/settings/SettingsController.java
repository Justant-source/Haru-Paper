package com.harupaper.server.settings;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.render.RenderScanTrigger;
import com.harupaper.server.weather.OpenMeteoWeatherProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * M6: 설정 API 컨트롤러 (사용자 인증 필수).
 *
 * GET /api/settings → 현재 사용자의 날씨 위치 조회
 * PUT /api/settings → 현재 사용자의 날씨 위치 설정 + 캐시 무효화
 *
 * docs/server/api.md "설정" 절, docs/server/weather.md 참고.
 */
@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class SettingsController {

    private final SettingsServiceImpl settingsService;
    private final OpenMeteoWeatherProvider weatherProvider;
    private final RenderScanTrigger renderScanTrigger;

    /**
     * GET /api/settings
     * 현재 사용자의 설정 조회
     * 응답: {weather: {lat, lon, label}}
     */
    @GetMapping
    public ResponseEntity<SettingsResponse> getSettings(
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        WeatherLocation location = settingsService.getWeatherLocation(userId);
        SettingsResponse response = new SettingsResponse(
                new WeatherSettingsDto(location.lat(), location.lon(), location.label())
        );
        return ResponseEntity.ok(response);
    }

    /**
     * PUT /api/settings
     * 현재 사용자의 설정 변경
     * 요청: {weather: {lat, lon, label}}
     * lat: -90~90, lon: -180~180, label: 1~50자
     * 성공 시 날씨 캐시 무효화.
     */
    @PutMapping
    public ResponseEntity<SettingsResponse> updateSettings(
            @RequestBody SettingsRequest request,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();

        // 입력 검증
        List<ValidationException.FieldError> errors = new ArrayList<>();

        if (request.weather == null) {
            errors.add(new ValidationException.FieldError("weather", "weather is required"));
        } else {
            // lat 검증: -90 ~ 90
            if (request.weather.lat < -90 || request.weather.lat > 90) {
                errors.add(new ValidationException.FieldError("weather.lat", "must be between -90 and 90"));
            }

            // lon 검증: -180 ~ 180
            if (request.weather.lon < -180 || request.weather.lon > 180) {
                errors.add(new ValidationException.FieldError("weather.lon", "must be between -180 and 180"));
            }

            // label 검증: 1~50자
            if (request.weather.label == null || request.weather.label.isEmpty()) {
                errors.add(new ValidationException.FieldError("weather.label", "label is required"));
            } else if (request.weather.label.length() < 1 || request.weather.label.length() > 50) {
                errors.add(new ValidationException.FieldError("weather.label", "must be 1-50 characters"));
            }
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Settings validation failed", errors);
        }

        // 저장
        WeatherLocation newLocation = new WeatherLocation(
                request.weather.lat,
                request.weather.lon,
                request.weather.label
        );
        settingsService.saveWeatherLocation(userId, newLocation);

        // 날씨 캐시 무효화
        weatherProvider.clearCache();
        renderScanTrigger.requestScan(userId);
        log.info("Weather cache invalidated after location change for user {}", userId);

        // 응답
        SettingsResponse response = new SettingsResponse(
                new WeatherSettingsDto(newLocation.lat(), newLocation.lon(), newLocation.label())
        );
        return ResponseEntity.ok(response);
    }

    // DTO classes
    public static class SettingsRequest {
        public WeatherSettingsDto weather;

        public SettingsRequest() {
        }
    }

    public static class SettingsResponse {
        public WeatherSettingsDto weather;

        public SettingsResponse(WeatherSettingsDto weather) {
            this.weather = weather;
        }
    }

    public static class WeatherSettingsDto {
        public double lat;
        public double lon;
        public String label;

        public WeatherSettingsDto() {
        }

        public WeatherSettingsDto(double lat, double lon, String label) {
            this.lat = lat;
            this.lon = lon;
            this.label = label;
        }
    }
}
