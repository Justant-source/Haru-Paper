package com.harupaper.server.device;

import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.render.Render;
import com.harupaper.server.render.RenderRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 렌더 다운로드 IDOR 수정 검증 (구현 담당 지시서 (2), docs/server/auth.md 7절
 * "단건 조회는 소유자가 아니면 404 — 존재 여부를 노출하지 않는다").
 *
 * 이전에는 GET /api/device/renders/{id}.png, .pbm이 폴링한 기기의 소유자를 전혀 확인하지 않고
 * renderId만으로 파일을 내려줬다 — 유효한 기기 토큰 하나면 남의 renderId로 인쇄물 비트맵을 받을 수 있었다.
 */
@DisplayName("DeviceSyncController - 렌더 다운로드 소유권 체크")
class DeviceSyncControllerOwnershipTest {

    private DeviceSyncController controller;
    private RenderRepository renderRepository;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        DeviceSyncService deviceSyncService = mock(DeviceSyncService.class);
        renderRepository = mock(RenderRepository.class);
        controller = new DeviceSyncController(deviceSyncService, renderRepository);
        ReflectionTestUtils.setField(controller, "filesDir", tempDir.toString());
    }

    @Test
    @DisplayName("다른 사용자 소유 렌더를 요청하면 404 (PNG)")
    void getRenderImage_ownerMismatch_returns404() throws Exception {
        Device requestingDevice = deviceOwnedBy("device-A", "user-A");
        Render othersRender = renderOwnedBy("r1", "user-B");
        when(renderRepository.findById("r1")).thenReturn(Optional.of(othersRender));
        // 파일을 **실제로 만들어 둔다**: 그러지 않으면 소유권 체크가 없어도 "파일 없음"으로 같은
        // NotFoundException이 나서 테스트가 무효가 된다(2026-09-17 검토에서 실증된 구멍).
        // 이제 404가 날 수 있는 이유는 소유권 불일치뿐이다.
        writeRenderFile("renders/r1.png", "fake-png-bytes");
        HttpServletRequest request = requestWithDevice(requestingDevice);

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> controller.getRenderImage(request, "r1"));
        assertEquals("Render not found: r1", ex.getMessage());
    }

    @Test
    @DisplayName("다른 사용자 소유 렌더를 요청하면 404 (PBM)")
    void getRenderPbm_ownerMismatch_returns404() throws Exception {
        Device requestingDevice = deviceOwnedBy("device-A", "user-A");
        Render othersRender = renderOwnedBy("r2", "user-B");
        othersRender.setPbmPath("renders/r2.pbm");
        when(renderRepository.findById("r2")).thenReturn(Optional.of(othersRender));
        // PNG 쪽과 같은 이유로 PBM 파일도 실제로 만들어 둔다 — 404의 원인을 소유권으로 좁힌다.
        writeRenderFile("renders/r2.pbm", "fake-pbm-bytes");
        HttpServletRequest request = requestWithDevice(requestingDevice);

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> controller.getRenderPbm(request, "r2"));
        assertEquals("Render not found: r2", ex.getMessage());
    }

    @Test
    @DisplayName("소유자가 같으면 정상적으로 파일을 내려준다 (PNG)")
    void getRenderImage_sameOwner_succeeds() throws Exception {
        Device requestingDevice = deviceOwnedBy("device-A", "user-A");
        Render ownRender = renderOwnedBy("r3", "user-A");
        when(renderRepository.findById("r3")).thenReturn(Optional.of(ownRender));
        writeRenderFile("renders/r3.png", "fake-png-bytes");
        HttpServletRequest request = requestWithDevice(requestingDevice);

        ResponseEntity<org.springframework.core.io.Resource> response =
                controller.getRenderImage(request, "r3");

        assertEquals(200, response.getStatusCode().value());
        // 개인 인쇄물이므로 공유 캐시에 두지 않는다.
        assertEquals("private, max-age=31536000",
                response.getHeaders().getCacheControl());
    }

    @Test
    @DisplayName("owner_user_id가 NULL인 레거시 렌더는 strict=false(기본)면 소유자와 무관하게 허용한다 (운영 중단 방지)")
    void nullOwner_strictFalse_isAllowed() throws Exception {
        Device requestingDevice = deviceOwnedBy("device-A", "user-A");
        Render legacyRender = renderOwnedBy("r4", null);
        when(renderRepository.findById("r4")).thenReturn(Optional.of(legacyRender));
        writeRenderFile("renders/r4.png", "fake-png-bytes");
        HttpServletRequest request = requestWithDevice(requestingDevice);

        ResponseEntity<org.springframework.core.io.Resource> response =
                controller.getRenderImage(request, "r4");

        assertEquals(200, response.getStatusCode().value());
    }

    @Test
    @DisplayName("owner_user_id가 NULL인 렌더는 strict=true면 404 (PNG)")
    void nullOwner_strictTrue_returns404() throws Exception {
        ReflectionTestUtils.setField(controller, "ownershipStrict", true);
        Device requestingDevice = deviceOwnedBy("device-A", "user-A");
        Render legacyRender = renderOwnedBy("r5", null);
        when(renderRepository.findById("r5")).thenReturn(Optional.of(legacyRender));
        // strict 아래에서도 404의 원인이 소유권(NULL)임을 증명하려면 파일이 실제로 있어야 한다 —
        // 그러지 않으면 "파일 없음"으로도 같은 404가 나서 테스트가 무효가 된다(클래스 상단 주석, :53-55와 동일한 함정).
        writeRenderFile("renders/r5.png", "fake-png-bytes");
        HttpServletRequest request = requestWithDevice(requestingDevice);

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> controller.getRenderImage(request, "r5"));
        assertEquals("Render not found: r5", ex.getMessage());
    }

    @Test
    @DisplayName("owner_user_id가 NULL인 렌더는 strict=true면 404 (PBM)")
    void nullOwner_strictTrue_returns404_pbm() throws Exception {
        ReflectionTestUtils.setField(controller, "ownershipStrict", true);
        Device requestingDevice = deviceOwnedBy("device-A", "user-A");
        Render legacyRender = renderOwnedBy("r6", null);
        legacyRender.setPbmPath("renders/r6.pbm");
        when(renderRepository.findById("r6")).thenReturn(Optional.of(legacyRender));
        writeRenderFile("renders/r6.pbm", "fake-pbm-bytes");
        HttpServletRequest request = requestWithDevice(requestingDevice);

        NotFoundException ex = assertThrows(NotFoundException.class,
                () -> controller.getRenderPbm(request, "r6"));
        assertEquals("Render not found: r6", ex.getMessage());
    }

    @Test
    @DisplayName("strict=true여도 소유자가 같으면 정상적으로 내려준다 (정상 경로를 막지 않음을 고정)")
    void sameOwner_strictTrue_succeeds() throws Exception {
        ReflectionTestUtils.setField(controller, "ownershipStrict", true);
        Device requestingDevice = deviceOwnedBy("device-A", "user-A");
        Render ownRender = renderOwnedBy("r7", "user-A");
        when(renderRepository.findById("r7")).thenReturn(Optional.of(ownRender));
        writeRenderFile("renders/r7.png", "fake-png-bytes");
        HttpServletRequest request = requestWithDevice(requestingDevice);

        ResponseEntity<org.springframework.core.io.Resource> response =
                controller.getRenderImage(request, "r7");

        assertEquals(200, response.getStatusCode().value());
    }

    private void writeRenderFile(String relativePath, String content) throws Exception {
        Path filePath = tempDir.resolve(relativePath);
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content);
    }

    private HttpServletRequest requestWithDevice(Device device) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getAttribute(DeviceTokenAuthFilter.DEVICE_ATTRIBUTE)).thenReturn(device);
        return request;
    }

    private Device deviceOwnedBy(String deviceId, String ownerUserId) {
        return Device.builder()
                .id(deviceId)
                .ownerUserId(ownerUserId)
                .name("test-device")
                .tokenHash("hash")
                .tokenIssuedAt(Instant.now())
                .paperStateManual(false)
                .createdAt(Instant.now())
                .build();
    }

    private Render renderOwnedBy(String renderId, String ownerUserId) {
        Render render = new Render();
        render.setId(renderId);
        render.setOwnerUserId(ownerUserId);
        render.setFormatId("f1");
        render.setTargetDate(LocalDate.of(2026, 9, 17));
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
