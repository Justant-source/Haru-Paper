package com.harupaper.server.render;

import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.device.PrinterProfile;
import com.harupaper.server.device.PrinterProfileProvider;
import com.harupaper.server.format.FormatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RenderCleanupScheduler - "아무 기기나 하나" 폴백 버그 수정 검증(구현 담당 지시서).
 *
 * 배경: 이 스케줄러는 포맷마다 "현재 profileKey"의 최신 렌더 1개를 항상 남기고 나머지는
 * 나이 기준으로 지운다. 예전 코드는 printerProfileProvider.getCurrentProfile()(인자 없음,
 * "아무 기기나 하나" 폴백)로 프로필 하나를 구해 모든 포맷에 똑같이 적용했다 — 사용자가
 * 여럿이고 기기 프로필이 다르면, 자기 프로필과 다른 사용자의 렌더는 "현재 프로필과 일치하는
 * 최신 렌더"를 못 찾아 보호받지 못하고 나이만으로 삭제될 수 있었다.
 *
 * 고친 코드는 렌더 하나하나를 그 렌더의 소유자(Render.ownerUserId) 기기 프로필과 비교한다.
 */
@DisplayName("RenderCleanupScheduler - 소유자별 프로필 기준 정리(멀티유저)")
class RenderCleanupSchedulerTest {

    private RenderRepository renderRepository;
    private FormatRepository formatRepository;
    private PrinterProfileProvider printerProfileProvider;
    private RenderCleanupScheduler scheduler;

    private static final PrinterProfile PROFILE_A = new PrinterProfile("m832", 300, 110, 1300);
    private static final PrinterProfile PROFILE_B = new PrinterProfile("m832", 203, 80, 576);

    @BeforeEach
    void setUp() {
        renderRepository = mock(RenderRepository.class);
        formatRepository = mock(FormatRepository.class);
        printerProfileProvider = mock(PrinterProfileProvider.class);
        scheduler = new RenderCleanupScheduler(renderRepository, formatRepository, printerProfileProvider);

        // 기본: kind=scheduled 목록은 비워 둔다 (preview 전용 테스트에서 영향받지 않도록)
        when(renderRepository.findAllByKind("scheduled")).thenReturn(List.of());
        when(renderRepository.findAllByKind("preview")).thenReturn(List.of());
    }

    @Test
    @DisplayName("사용자 2명: 각자 자기 기기 프로필 기준으로 최신 렌더가 보호된다 (다른 사용자 렌더를 오판하지 않는다)")
    void multiUser_eachOwnersLatestRenderProtectedByItsOwnProfile() {
        when(printerProfileProvider.getCurrentProfile("user-a")).thenReturn(PROFILE_A);
        when(printerProfileProvider.getCurrentProfile("user-b")).thenReturn(PROFILE_B);

        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);  // 24시간 cutoff보다 오래됨

        // user-a의 포맷 fa: 현재 프로필과 일치하는 렌더 1개 → 나이와 무관하게 유지돼야 한다
        Render aCurrent = previewRender("ra-current", "fa", "user-a", PROFILE_A.profileKey(), old);
        // user-b의 포맷 fb: 현재 프로필과 일치하는 렌더 1개 → 마찬가지로 유지돼야 한다.
        // 예전 버그라면 "현재 프로필"이 전역 하나(예: user-a의 프로필)였으므로 이 렌더는
        // 결코 일치하지 않아 삭제됐을 것이다.
        Render bCurrent = previewRender("rb-current", "fb", "user-b", PROFILE_B.profileKey(), old);
        // user-a의 포맷 fa: 예전 프로필로 만들어진, 더 이상 현재 프로필과 안 맞는 낡은 렌더 → 삭제돼야 한다
        Render aStale = previewRender("ra-stale", "fa", "user-a", "m832-300-110-999-old", old);

        when(renderRepository.findAllByKind("preview")).thenReturn(List.of(aCurrent, bCurrent, aStale));

        scheduler.cleanupRenders();

        verify(renderRepository, never()).deleteById("ra-current");
        verify(renderRepository, never()).deleteById("rb-current");
        verify(renderRepository).deleteById("ra-stale");
    }

    @Test
    @DisplayName("기기 1대: 수정 전과 동일하게 동작한다 (현재 프로필 렌더는 유지, 낡은 프로필 렌더는 삭제)")
    void singleDevice_behavesSameAsBefore() {
        when(printerProfileProvider.getCurrentProfile("user-solo")).thenReturn(PROFILE_A);

        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        Render current = previewRender("r-current", "f1", "user-solo", PROFILE_A.profileKey(), old);
        Render stale = previewRender("r-stale", "f1", "user-solo", "old-profile-key", old);
        Render recent = previewRender("r-recent", "f1", "user-solo", "old-profile-key",
                Instant.now().minus(1, ChronoUnit.HOURS));  // 24시간 안 지남 → 프로필 상관없이 유지

        when(renderRepository.findAllByKind("preview")).thenReturn(List.of(current, stale, recent));

        scheduler.cleanupRenders();

        verify(renderRepository, never()).deleteById("r-current");
        verify(renderRepository, never()).deleteById("r-recent");
        verify(renderRepository).deleteById("r-stale");
    }

    @Test
    @DisplayName("기기 0대(페어링 전): DEFAULT 프로필로 폴백하고, DEFAULT와 일치하는 렌더는 유지된다")
    void noDevice_fallsBackToDefaultAndProtectsMatchingRender() {
        when(printerProfileProvider.getCurrentProfile("user-nodevice")).thenReturn(PrinterProfile.DEFAULT);

        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        Render defaultRender = previewRender("r-default", "f1", "user-nodevice",
                PrinterProfile.DEFAULT.profileKey(), old);

        when(renderRepository.findAllByKind("preview")).thenReturn(List.of(defaultRender));

        scheduler.cleanupRenders();

        verify(renderRepository, never()).deleteById("r-default");
    }

    @Test
    @DisplayName("소유자를 모르는 레거시 렌더(ownerUserId=NULL)도 예외 없이 처리되고, DEFAULT와 일치하면 보수적으로 유지한다")
    void legacyNullOwner_doesNotThrowAndIsConservative() {
        when(printerProfileProvider.getCurrentProfile(isNull())).thenReturn(PrinterProfile.DEFAULT);

        Instant old = Instant.now().minus(48, ChronoUnit.HOURS);
        Render legacy = previewRender("r-legacy", "f1", null, PrinterProfile.DEFAULT.profileKey(), old);

        when(renderRepository.findAllByKind("preview")).thenReturn(List.of(legacy));

        assertDoesNotThrow(() -> scheduler.cleanupRenders());

        verify(renderRepository, never()).deleteById("r-legacy");
    }

    @Test
    @DisplayName("kind=scheduled도 소유자별 프로필 기준으로 같은 원칙을 적용한다")
    void scheduledKind_usesOwnerProfileToo() {
        when(printerProfileProvider.getCurrentProfile("user-a")).thenReturn(PROFILE_A);
        when(printerProfileProvider.getCurrentProfile("user-b")).thenReturn(PROFILE_B);

        LocalDate today = todayInKST();
        LocalDate oldDate = today.minusDays(10);  // 7일 cutoff보다 오래됨

        Render aCurrent = scheduledRender("sa-current", "fa", "user-a", PROFILE_A.profileKey(), oldDate);
        Render bCurrent = scheduledRender("sb-current", "fb", "user-b", PROFILE_B.profileKey(), oldDate);

        when(renderRepository.findAllByKind("scheduled")).thenReturn(List.of(aCurrent, bCurrent));

        scheduler.cleanupRenders();

        verify(renderRepository, never()).deleteById("sa-current");
        verify(renderRepository, never()).deleteById("sb-current");
    }

    private LocalDate todayInKST() {
        return TimeUtils.todayInKST();
    }

    private Render previewRender(String id, String formatId, String ownerUserId, String profileKey, Instant renderedAt) {
        return Render.builder()
                .id(id)
                .ownerUserId(ownerUserId)
                .formatId(formatId)
                .targetDate(todayInKST())
                .profileKey(profileKey)
                .widthPx(1300)
                .heightPx(100)
                .sha256("sha")
                .path("renders/" + id + ".png")
                .kind("preview")
                .formatUpdatedAt(Instant.now())
                .renderedAt(renderedAt)
                .build();
    }

    private Render scheduledRender(String id, String formatId, String ownerUserId, String profileKey, LocalDate targetDate) {
        return Render.builder()
                .id(id)
                .ownerUserId(ownerUserId)
                .formatId(formatId)
                .targetDate(targetDate)
                .profileKey(profileKey)
                .widthPx(1300)
                .heightPx(100)
                .sha256("sha")
                .path("renders/" + id + ".png")
                .kind("scheduled")
                .formatUpdatedAt(Instant.now())
                .renderedAt(Instant.now().minus(48, ChronoUnit.HOURS))
                .build();
    }
}
