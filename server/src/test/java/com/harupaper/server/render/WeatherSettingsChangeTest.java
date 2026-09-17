package com.harupaper.server.render;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.settings.SettingsController;
import com.harupaper.server.settings.SettingsServiceImpl;
import com.harupaper.server.user.User;
import com.harupaper.server.weather.OpenMeteoWeatherProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

/**
 * 날씨 설정 변경 시 동적 포맷 재렌더 검증
 * (docs/server/rendering.md 4절, 7절 체크리스트 항목 7)
 *
 * 검증 항목:
 * - PUT /api/settings로 위치가 바뀌면
 * - 날씨 캐시가 무효화되고
 * - RenderScanTrigger.requestScan()이 호출되어 동적 포맷 재렌더가 트리거됨
 *
 * docs/server/api.md 5절:
 * "PUT /api/settings → 날씨 기본 위치 설정 + 캐시 무효화 + 동적 포맷 재렌더 트리거"
 */
@DisplayName("Item 7: 날씨 설정 변경 시 동적 포맷 재렌더")
class WeatherSettingsChangeTest {

    private SettingsController settingsController;
    private SettingsServiceImpl settingsService;
    private OpenMeteoWeatherProvider weatherProvider;
    private RenderScanTrigger renderScanTrigger;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsServiceImpl.class);
        weatherProvider = mock(OpenMeteoWeatherProvider.class);
        renderScanTrigger = mock(RenderScanTrigger.class);

        settingsController = new SettingsController(
                settingsService,
                weatherProvider,
                renderScanTrigger
        );

        User user = User.builder()
                .id("test-user-1")
                .email("test@example.com")
                .passwordHash("irrelevant")
                .handle("tester")
                .displayName("Tester")
                .role("user")
                .status("active")
                .mustChangePassword(false)
                .build();
        principal = new UserPrincipal(user);
    }

    /**
     * 위치가 변경되면:
     * 1. 새 위치가 저장되고
     * 2. 날씨 캐시가 무효화되고
     * 3. RenderScanTrigger.requestScan()이 호출됨
     *
     * 이를 통해 동적 포맷(weather 블록)이 새 위치로 재렌더됨
     */
    @Test
    @DisplayName("Item 7: 날씨 위치 변경 → 캐시 무효화 + 재렌더 트리거")
    void testWeatherLocationChangeTriggersReRender() {
        // 요청 준비: 서울시청 → 부산시청으로 변경
        SettingsController.SettingsRequest request = new SettingsController.SettingsRequest();
        request.weather = new SettingsController.WeatherSettingsDto(35.1043, 129.0325, "부산시청");

        // 컨트롤러 호출
        settingsController.updateSettings(request, principal);

        // 검증:
        // 1. settingsService.saveWeatherLocation()가 호출됨
        verify(settingsService, times(1))
                .saveWeatherLocation(anyString(), any());

        // 2. weatherProvider.clearCache()가 호출됨 (캐시 무효화)
        verify(weatherProvider, times(1))
                .clearCache();

        // 3. renderScanTrigger.requestScan()이 호출됨 (재렌더 트리거)
        verify(renderScanTrigger, times(1))
                .requestScan("test-user-1");
    }

    /**
     * 여러 번 위치 변경: 매번 재렌더 트리거
     */
    @Test
    @DisplayName("Item 7: 반복된 위치 변경 → 매번 재렌더 트리거")
    void testMultipleWeatherChangesEachTriggersReRender() {
        // 1차: 서울
        SettingsController.SettingsRequest request1 = new SettingsController.SettingsRequest();
        request1.weather = new SettingsController.WeatherSettingsDto(37.5663, 126.9779, "서울시청");
        settingsController.updateSettings(request1, principal);

        // 2차: 부산
        SettingsController.SettingsRequest request2 = new SettingsController.SettingsRequest();
        request2.weather = new SettingsController.WeatherSettingsDto(35.1043, 129.0325, "부산시청");
        settingsController.updateSettings(request2, principal);

        // 3차: 대구
        SettingsController.SettingsRequest request3 = new SettingsController.SettingsRequest();
        request3.weather = new SettingsController.WeatherSettingsDto(35.8722, 128.5975, "대구시청");
        settingsController.updateSettings(request3, principal);

        // 검증: 3번 모두 재렌더 트리거됨
        verify(renderScanTrigger, times(3))
                .requestScan("test-user-1");
    }

    /**
     * 위치 변경이 동적 포맷(weather 블록)의 재렌더로 이어지는지 확인
     *
     * 흐름:
     * 1. PUT /api/settings로 위치 변경
     * 2. renderScanTrigger.requestScan() 호출
     * 3. RenderScheduler.scanAndRender() 실행
     * 4. 동적 포맷의 occurrence 60분 전 재렌더 검사 → 날씨 데이터 새로 조회
     * 5. snapshotHash 변경 → Pi poll 응답의 snapshotChanged = true
     * 6. Pi가 새 스냅샷 받음 → 새 날씨 렌더로 인쇄
     */
    @Test
    @DisplayName("Item 7: 위치 변경의 효과 체계도")
    void testWeatherChangeEffectChain() {
        // 이것은 문서화된 흐름 검증
        // 실제 통합 테스트에서는:
        // 1. 동적 포맷(weather) 생성
        // 2. 위치 변경 (PUT /api/settings)
        // 3. RenderScheduler 실행
        // 4. 새 날씨로 재렌더된 Render 확인
        // 5. SnapshotHash 변경 확인
        // 6. Poll 응답의 snapshotChanged = true 확인

        SettingsController.SettingsRequest request = new SettingsController.SettingsRequest();
        request.weather = new SettingsController.WeatherSettingsDto(37.5663, 126.9779, "서울");
        settingsController.updateSettings(request, principal);

        verify(renderScanTrigger).requestScan("test-user-1");
    }
}
