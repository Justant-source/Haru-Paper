package com.harupaper.server.history;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.device.Result;
import com.harupaper.server.device.ResultRepository;
import com.harupaper.server.format.FormatRepository;
import com.harupaper.server.render.Render;
import com.harupaper.server.render.RenderRepository;
import com.harupaper.server.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * GET /api/history/{resultId}/render.png — 이력에서 그날 실제로 나간 렌더 이미지를 보는 경로
 * (Phomemo UX 대조 필수 3종 중 3번째, docs/server/api.md 5절). preview.png(지금 다시 렌더)와
 * 달리 그 실행 시점 파일을 그대로 내려준다.
 *
 * DeviceSyncControllerOwnershipTest와 같은 함정에 주의한다: "파일 없음"과 "소유권 불일치"가
 * 같은 NotFoundException으로 보이므로, 소유권을 시험하는 케이스는 항상 파일을 실제로 만들어 둔다.
 */
@DisplayName("HistoryController - GET /{resultId}/render.png")
class HistoryRenderTest {

    private static final String OWNER = "user-A";
    private static final String OTHER = "user-B";

    private ResultRepository resultRepository;
    private RenderRepository renderRepository;
    private HistoryController controller;
    private UserPrincipal principal;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        resultRepository = mock(ResultRepository.class);
        FormatRepository formatRepository = mock(FormatRepository.class);
        renderRepository = mock(RenderRepository.class);
        controller = new HistoryController(resultRepository, formatRepository, renderRepository,
                tempDir.toString(), false);
        principal = principalFor(OWNER);
    }

    @Test
    @DisplayName("소유자면 200 + ETag + private 캐시 헤더")
    void ownerGetsRender() throws Exception {
        Result result = resultOwnedBy("res1", OWNER, "r1");
        when(resultRepository.findById("res1")).thenReturn(Optional.of(result));
        Render render = renderOwnedBy("r1", OWNER);
        when(renderRepository.findById("r1")).thenReturn(Optional.of(render));
        writeRenderFile("renders/r1.png", "fake-png-bytes");

        ResponseEntity<Resource> response = controller.getResultRender("res1", principal);

        assertEquals(200, response.getStatusCode().value());
        assertEquals("\"deadbeef\"", response.getHeaders().getETag());
        assertEquals("private, max-age=31536000", response.getHeaders().getCacheControl());
    }

    @Test
    @DisplayName("타인의 resultId면 404 — 존재 여부를 알려주지 않는다")
    void otherUsersResultIsNotFound() {
        Result result = resultOwnedBy("res2", OTHER, "r2");
        when(resultRepository.findById("res2")).thenReturn(Optional.of(result));

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> controller.getResultRender("res2", principal));
        assertEquals("Result not found: res2", ex.getMessage());
    }

    @Test
    @DisplayName("resultId 자체가 없으면 404")
    void missingResultIsNotFound() {
        when(resultRepository.findById("missing")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> controller.getResultRender("missing", principal));
    }

    @Test
    @DisplayName("renderId가 없으면(dry_run·missed 등) 404")
    void resultWithoutRenderIdIsNotFound() {
        Result result = resultOwnedBy("res3", OWNER, null);
        when(resultRepository.findById("res3")).thenReturn(Optional.of(result));

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> controller.getResultRender("res3", principal));
        assertEquals("Render not found for result: res3", ex.getMessage());
    }

    @Test
    @DisplayName("렌더 행 자체가 정리돼 없으면(RenderCleanupScheduler) 404")
    void renderRowDeletedIsNotFound() {
        Result result = resultOwnedBy("res4", OWNER, "r4");
        when(resultRepository.findById("res4")).thenReturn(Optional.of(result));
        when(renderRepository.findById("r4")).thenReturn(Optional.empty());

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> controller.getResultRender("res4", principal));
        assertEquals("Render not found: r4", ex.getMessage());
    }

    @Test
    @DisplayName("렌더 행은 있는데 파일이 없으면 404")
    void renderFileMissingIsNotFound() {
        Result result = resultOwnedBy("res5", OWNER, "r5");
        when(resultRepository.findById("res5")).thenReturn(Optional.of(result));
        when(renderRepository.findById("r5")).thenReturn(Optional.of(renderOwnedBy("r5", OWNER)));
        // 파일을 만들지 않는다 — 소유권은 통과하지만 파일이 없는 경우를 재현한다.

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> controller.getResultRender("res5", principal));
        assertEquals("Render file not found: r5", ex.getMessage());
    }

    @Test
    @DisplayName("렌더 소유자 NULL(claim-legacy 전 레거시)은 strict=false면 허용")
    void nullRenderOwnerAllowedWhenNotStrict() throws Exception {
        Result result = resultOwnedBy("res6", OWNER, "r6");
        when(resultRepository.findById("res6")).thenReturn(Optional.of(result));
        when(renderRepository.findById("r6")).thenReturn(Optional.of(renderOwnedBy("r6", null)));
        writeRenderFile("renders/r6.png", "fake-png-bytes");

        ResponseEntity<Resource> response = controller.getResultRender("res6", principal);

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("렌더 소유자 NULL은 strict=true면 404")
    void nullRenderOwnerRejectedWhenStrict() throws Exception {
        FormatRepository formatRepository = mock(FormatRepository.class);
        HistoryController strictController = new HistoryController(resultRepository, formatRepository,
                renderRepository, tempDir.toString(), true);
        Result result = resultOwnedBy("res7", OWNER, "r7");
        when(resultRepository.findById("res7")).thenReturn(Optional.of(result));
        when(renderRepository.findById("r7")).thenReturn(Optional.of(renderOwnedBy("r7", null)));
        // strict 아래에서도 404 원인이 소유권(NULL)임을 증명하려면 파일이 실제로 있어야 한다
        // (DeviceSyncControllerOwnershipTest와 같은 함정).
        writeRenderFile("renders/r7.png", "fake-png-bytes");

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> strictController.getResultRender("res7", principal));
        assertEquals("Render not found: r7", ex.getMessage());
    }

    @Test
    @DisplayName("getHistory의 renderAvailable: renderId 없음/렌더 행 정리됨/살아있음을 정확히 구분한다")
    void renderAvailableReflectsRenderRowExistence() {
        Result withRender = resultOwnedBy("res8", OWNER, "r8");
        Result cleanedUp = resultOwnedBy("res9", OWNER, "r9");
        Result noRender = resultOwnedBy("res10", OWNER, null);
        when(resultRepository.findAllByOwnerUserIdOrderByExecutedAtDesc(OWNER))
                .thenReturn(List.of(withRender, cleanedUp, noRender));
        // r9는 정리돼(RenderCleanupScheduler) 이제 없다 — findAllById가 r8만 돌려준다.
        when(renderRepository.findAllById(org.mockito.ArgumentMatchers.<Iterable<String>>any()))
                .thenReturn(List.of(renderOwnedBy("r8", OWNER)));

        ResponseEntity<List<HistoryResponseDto>> response = controller.getHistory(50, null, principal);

        List<HistoryResponseDto> body = response.getBody();
        assertTrue(findByResultId(body, "res8").renderAvailable());
        assertFalse(findByResultId(body, "res9").renderAvailable());
        assertFalse(findByResultId(body, "res10").renderAvailable());
    }

    private HistoryResponseDto findByResultId(List<HistoryResponseDto> body, String resultId) {
        return body.stream().filter(dto -> dto.resultId().equals(resultId)).findFirst()
                .orElseThrow(() -> new AssertionError("no dto for " + resultId));
    }

    private void writeRenderFile(String relativePath, String content) throws Exception {
        Path filePath = tempDir.resolve(relativePath);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
    }

    private UserPrincipal principalFor(String userId) {
        User user = User.builder()
                .id(userId)
                .email(userId + "@example.com")
                .passwordHash("irrelevant")
                .handle(userId)
                .displayName(userId)
                .role("user")
                .status("active")
                .mustChangePassword(false)
                .build();
        return new UserPrincipal(user);
    }

    private Result resultOwnedBy(String resultId, String ownerUserId, String renderId) {
        return Result.builder()
                .id(resultId)
                .ownerUserId(ownerUserId)
                .deviceId("device-A")
                .formatId("f1")
                .renderId(renderId)
                .status("printed")
                .executedAt(Instant.now())
                .build();
    }

    private Render renderOwnedBy(String renderId, String ownerUserId) {
        Render render = new Render();
        render.setId(renderId);
        render.setOwnerUserId(ownerUserId);
        render.setFormatId("f1");
        render.setTargetDate(LocalDate.of(2026, 9, 19));
        render.setProfileKey("m832-300-110-1300");
        render.setWidthPx(1300);
        render.setHeightPx(100);
        render.setSha256("deadbeef");
        render.setPath("renders/" + renderId + ".png");
        render.setKind("command");
        render.setFormatUpdatedAt(Instant.now());
        render.setRenderedAt(Instant.now());
        return render;
    }
}
