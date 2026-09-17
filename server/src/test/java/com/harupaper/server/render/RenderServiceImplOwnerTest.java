package com.harupaper.server.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.device.PrinterProfile;
import com.harupaper.server.device.PrinterProfileProvider;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * renders.owner_user_id가 채워지는지 검증 (구현 담당 지시서 (1)).
 *
 * 배경: RenderServiceImpl.savRender()가 Render.builder()에 .ownerUserId(...)를 빼먹어서
 * 저장되는 렌더가 항상 owner_user_id=NULL이었다. 렌더는 포맷에서 파생되므로
 * format.getOwnerUserId()를 복사하는 것이 규약(FormatService·ScheduleService·PrintNowController·
 * AssetService·ResultIngestService와 동일한 패턴)이다.
 *
 * 이 값이 계속 NULL이면: (a) DeviceSyncController의 렌더 다운로드 소유권 체크가 무력화되고,
 * (b) AdminService.claimLegacyResources("claim-legacy")를 실행하면 모든 사용자의 렌더가
 * 관리자 소유로 넘어간다.
 */
@DisplayName("RenderServiceImpl - 렌더 소유자(owner_user_id) 백필")
class RenderServiceImplOwnerTest {

    private RenderServiceImpl renderService;
    private FormatRepository formatRepository;
    private RenderRepository renderRepository;
    private PrinterProfileProvider printerProfileProvider;
    private HtmlTemplateBuilder htmlTemplateBuilder;
    private PlaywrightRenderer playwrightRenderer;
    private GrayscaleConverter grayscaleConverter;
    private PbmConverter pbmConverter;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        printerProfileProvider = mock(PrinterProfileProvider.class);
        formatRepository = mock(FormatRepository.class);
        AssetRepository assetRepository = mock(AssetRepository.class);
        renderRepository = mock(RenderRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();
        htmlTemplateBuilder = mock(HtmlTemplateBuilder.class);
        playwrightRenderer = mock(PlaywrightRenderer.class);
        grayscaleConverter = mock(GrayscaleConverter.class);
        pbmConverter = mock(PbmConverter.class);

        renderService = new RenderServiceImpl(
                printerProfileProvider,
                formatRepository,
                assetRepository,
                renderRepository,
                objectMapper,
                htmlTemplateBuilder,
                playwrightRenderer,
                grayscaleConverter,
                pbmConverter
        );
        ReflectionTestUtils.setField(renderService, "filesDir", tempDir.toString());

        when(printerProfileProvider.getCurrentProfile(nullable(String.class))).thenReturn(PrinterProfile.DEFAULT);
        when(htmlTemplateBuilder.buildHtml(any(), any(), any(), any())).thenReturn("<html></html>");
        when(playwrightRenderer.captureScreenshot(any(), anyInt())).thenReturn(fakePngBytes());
        when(grayscaleConverter.convertToGrayscale(any(), anyInt())).thenReturn(fakePngBytes());
        when(pbmConverter.convertToPbm(any())).thenReturn(new byte[]{1, 2, 3});
        when(renderRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("renderForCommand: 저장된 Render.ownerUserId == format.ownerUserId")
    void renderForCommand_copiesOwnerFromFormat() {
        Format format = formatWithOwner("f1", "user-1");
        when(formatRepository.findById("f1")).thenReturn(Optional.of(format));

        renderService.renderForCommand("f1", LocalDate.of(2026, 9, 17));

        Render saved = captureSavedRender();
        assertEquals("user-1", saved.getOwnerUserId());
    }

    @Test
    @DisplayName("renderForScheduled: 저장된 Render.ownerUserId == format.ownerUserId")
    void renderForScheduled_copiesOwnerFromFormat() {
        Format format = formatWithOwner("f2", "user-2");
        when(formatRepository.findById("f2")).thenReturn(Optional.of(format));

        renderService.renderForScheduled("f2", LocalDate.of(2026, 9, 17));

        Render saved = captureSavedRender();
        assertEquals("user-2", saved.getOwnerUserId());
    }

    @Test
    @DisplayName("renderSavedFormatPreview: 저장된 Render.ownerUserId == format.ownerUserId")
    void renderSavedFormatPreview_copiesOwnerFromFormat() {
        Format format = formatWithOwner("f3", "user-3");
        when(formatRepository.findById("f3")).thenReturn(Optional.of(format));
        // 캐시 미스(같은 formatId/date/profileKey 렌더 없음)라서 새로 렌더된다.
        when(renderRepository.findFirstByFormatIdAndTargetDateAndProfileKeyOrderByRenderedAtDesc(
                eq("f3"), any(), any())).thenReturn(Optional.empty());

        renderService.renderSavedFormatPreview("f3", LocalDate.of(2026, 9, 17));

        Render saved = captureSavedRender();
        assertEquals("user-3", saved.getOwnerUserId());
    }

    @Test
    @DisplayName("포맷 owner가 NULL(레거시, claim-legacy 전)이면 렌더도 NULL로 저장된다 — 예외를 던지지 않는다")
    void renderForCommand_withNullFormatOwner_savesNullOwnerWithoutThrowing() {
        Format format = formatWithOwner("f4", null);
        when(formatRepository.findById("f4")).thenReturn(Optional.of(format));

        assertDoesNotThrow(() -> renderService.renderForCommand("f4", LocalDate.of(2026, 9, 17)));

        Render saved = captureSavedRender();
        assertNull(saved.getOwnerUserId());
    }

    private Render captureSavedRender() {
        var captor = org.mockito.ArgumentCaptor.forClass(Render.class);
        verify(renderRepository).save(captor.capture());
        return captor.getValue();
    }

    private Format formatWithOwner(String id, String ownerUserId) {
        Format format = new Format();
        format.setId(id);
        format.setOwnerUserId(ownerUserId);
        format.setName("Test Format");
        format.setSchemaVersion(2);
        format.setBody("{\"schemaVersion\":3,\"meta\":{\"name\":\"t\"},\"style\":null,"
                + "\"widgets\":[{\"id\":\"w1\",\"type\":\"text\",\"size\":\"4xauto\",\"props\":{\"text\":\"t\"}}]}");
        format.setHasDynamicBlocks(false);
        format.setCreatedAt(Instant.now());
        format.setUpdatedAt(Instant.now());
        return format;
    }

    /** PNG 시그니처 이후 width/height 헤더만 채운 가짜 바이트(extractPngDimensions가 직접 읽는 위치). */
    private byte[] fakePngBytes() {
        byte[] bytes = new byte[24];
        int width = 1300;
        int height = 100;
        bytes[16] = (byte) ((width >> 24) & 0xFF);
        bytes[17] = (byte) ((width >> 16) & 0xFF);
        bytes[18] = (byte) ((width >> 8) & 0xFF);
        bytes[19] = (byte) (width & 0xFF);
        bytes[20] = (byte) ((height >> 24) & 0xFF);
        bytes[21] = (byte) ((height >> 16) & 0xFF);
        bytes[22] = (byte) ((height >> 8) & 0xFF);
        bytes[23] = (byte) (height & 0xFF);
        return bytes;
    }
}
