package com.harupaper.server.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.harupaper.server.asset.AssetRepository;
import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.device.PrinterProfileProvider;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatDocument;
import com.harupaper.server.format.FormatDocumentSupport;
import com.harupaper.server.format.FormatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 렌더링 서비스 구현 (docs/server/rendering.md).
 * 파이프라인: 포맷 조회 → 동적 데이터 → HTML 생성 → Chromium 스크린샷 → 그레이스케일 변환 → 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RenderServiceImpl implements RenderService {

    private final PrinterProfileProvider printerProfileProvider;
    private final FormatRepository formatRepository;
    private final AssetRepository assetRepository;
    private final RenderRepository renderRepository;
    private final ObjectMapper objectMapper;
    private final HtmlTemplateBuilder htmlTemplateBuilder;
    private final PlaywrightRenderer playwrightRenderer;
    private final GrayscaleConverter grayscaleConverter;
    private final PbmConverter pbmConverter;

    @Value("${haru.files-dir:/data/haru-files}")
    private String filesDir;

    /**
     * 저장된 포맷 미리보기 렌더. kind=preview, 같은 (format.updatedAt, date, profileKey) 렌더가
     * 10분 안에 있으면 재사용한다.
     */
    @Override
    @Transactional
    public RenderResult renderSavedFormatPreview(String formatId, LocalDate targetDate) {
        Format format = formatRepository.findById(formatId)
                .orElseThrow(() -> new NotFoundException("Format not found: " + formatId));

        return renderWithCache(format, targetDate, "preview", 10 * 60 * 1000);
    }

    /**
     * 저장하지 않은 편집본 렌더. DB 행·파일을 남기지 않고 PNG 바이트만 반환.
     * ownerUserId 소유 기기의 프로필을 쓴다(멀티유저) — "아무 기기나 하나" 폴백은 없앴다.
     */
    @Override
    public byte[] renderEphemeral(FormatDocument document, LocalDate targetDate, String ownerUserId) {
        var profile = printerProfileProvider.getCurrentProfile(ownerUserId);
        String html = htmlTemplateBuilder.buildHtml(document, targetDate, profile, ownerUserId);
        byte[] pngBytes = playwrightRenderer.captureScreenshot(html, profile.printableWidthPx());
        return grayscaleConverter.convertToGrayscale(pngBytes, profile.printableWidthPx());
    }

    /**
     * "지금 인쇄" 전용: kind=command로 즉시 새로운 렌더를 생성해 행을 저장한다.
     */
    @Override
    @Transactional
    public RenderResult renderForCommand(String formatId, LocalDate targetDate) {
        Format format = formatRepository.findById(formatId)
                .orElseThrow(() -> new NotFoundException("Format not found: " + formatId));

        return doRender(format, targetDate, "command");
    }

    @Override
    @Transactional
    public RenderResult renderForScheduled(String formatId, LocalDate targetDate) {
        Format format = formatRepository.findById(formatId)
                .orElseThrow(() -> new NotFoundException("Format not found: " + formatId));

        return doRender(format, targetDate, "scheduled");
    }

    /**
     * 내부 렌더 메서드. 캐시를 고려하거나 새로 생성한다.
     */
    private RenderResult renderWithCache(Format format, LocalDate targetDate, String kind, long cacheWindowMs) {
        // format 소유자의 기기 프로필을 쓴다(멀티유저) — 여러 기기가 있으면 사용자마다 다를 수 있다.
        var profile = printerProfileProvider.getCurrentProfile(format.getOwnerUserId());

        // 캐시 체크: 같은 (formatId, targetDate, profileKey)이고 cacheWindowMs 안의 렌더
        if ("preview".equals(kind)) {
            var existing = renderRepository.findFirstByFormatIdAndTargetDateAndProfileKeyOrderByRenderedAtDesc(
                    format.getId(), targetDate, profile.profileKey());
            if (existing.isPresent()) {
                Render render = existing.get();
                long ageMillis = Instant.now().toEpochMilli() - render.getRenderedAt().toEpochMilli();
                boolean sameFormatVersion = render.getFormatUpdatedAt().equals(format.getUpdatedAt());
                if (sameFormatVersion && ageMillis < cacheWindowMs) {
                    log.debug("Reusing cached render: {}", render.getId());
                    try {
                        byte[] pngBytes = Files.readAllBytes(Paths.get(filesDir).resolve(render.getPath()));
                        return new RenderResult(render.getId(), pngBytes, render.getSha256(),
                                render.getWidthPx(), render.getHeightPx(), render.getPbmSha256());
                    } catch (IOException e) {
                        log.warn("Failed to read cached render file, will re-render", e);
                    }
                }
            }
        }

        return doRender(format, targetDate, kind);
    }

    /**
     * 실제 렌더 수행: HTML 생성 → Chromium 스크린샷 → 그레이스케일 변환 → 저장
     */
    private RenderResult doRender(Format format, LocalDate targetDate, String kind) {
        // format 소유자의 기기 프로필을 쓴다(멀티유저) — 여러 기기가 있으면 사용자마다 다를 수 있다.
        var profile = printerProfileProvider.getCurrentProfile(format.getOwnerUserId());

        // 포맷 문서 역직렬화. v1·v2 저장본도 자동으로 v3(위젯 그리드)로 up-convert된다 —
        // 여기서 objectMapper.readValue를 직접 쓰면 옛 저장본의 예약 렌더가 전부 실패한다
        // (.temp/07-위젯그리드-작업지시서.md 3.3절 마지막 문단).
        FormatDocument document = FormatDocumentSupport.readDocument(format.getBody(), objectMapper);

        // 1. HTML 생성 (변수 치환, 동적 데이터 조회 포함) — 위에서 구한 profile을 그대로 써서
        // Chromium 스크린샷 폭·CSS px 변환이 서로 다른 프로필을 기준으로 어긋나지 않게 한다.
        String html = htmlTemplateBuilder.buildHtml(document, targetDate, profile, format.getOwnerUserId());

        // 2. Chromium 스크린샷 (PNG 반환)
        byte[] pngBytes = playwrightRenderer.captureScreenshot(html, profile.printableWidthPx());

        // 3. 그레이스케일 변환 + 폭 검증
        byte[] grayscalePng = grayscaleConverter.convertToGrayscale(pngBytes, profile.printableWidthPx());

        // 4. 파일 저장 + 행 커밋
        String renderId = UUID.randomUUID().toString();
        return savRender(renderId, format, targetDate, profile, grayscalePng, kind);
    }

    /**
     * 렌더 결과를 파일로 저장하고 Render 엔티티를 저장한다.
     */
    private RenderResult savRender(String renderId, Format format, LocalDate targetDate,
                                    com.harupaper.server.device.PrinterProfile profile,
                                    byte[] grayscalePng, String kind) {
        // 파일 경로: haru-files/renders/{renderId}.png
        Path renderPath = Paths.get(filesDir, "renders");
        try {
            Files.createDirectories(renderPath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create renders directory", e);
        }

        Path filePath = renderPath.resolve(renderId + ".png");

        // 파일 쓰기
        try {
            Files.write(filePath, grayscalePng);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write render file", e);
        }

        // SHA256 계산
        String sha256 = calculateSha256(grayscalePng);

        // 이미지 크기 추출 (PNG 헤더에서)
        int[] dimensions = extractPngDimensions(grayscalePng);
        int widthPx = dimensions[0];
        int heightPx = dimensions[1];

        // 1-bpp PBM 생성 (docs/architecture.md 3.4, 2026-09-17 승인) — PNG가 원본이고 PBM은 추가 산출물이다.
        // 실패해도 PNG 렌더 자체는 살린다: 2단계 기기가 아직 없으므로 PBM 실패로 전체 렌더를 막지 않는다 [기본값].
        String pbmSha256 = null;
        String pbmRelativePath = null;
        try {
            byte[] pbmBytes = pbmConverter.convertToPbm(grayscalePng);
            Path pbmFilePath = renderPath.resolve(renderId + ".pbm");
            Files.write(pbmFilePath, pbmBytes);
            pbmSha256 = calculateSha256(pbmBytes);
            pbmRelativePath = "renders/" + renderId + ".pbm";
        } catch (Exception e) {
            log.warn("PBM generation failed for render {}, continuing without it", renderId, e);
        }

        // Render 엔티티 저장
        Instant now = Instant.now();
        Render render = Render.builder()
                .id(renderId)
                // 렌더는 포맷에서 파생되므로 렌더 시점 포맷 소유자를 복사한다(이력 보존, Render.ownerUserId 주석).
                // format.getOwnerUserId()가 NULL이면(M6 이전 레거시 포맷, 아직 claim-legacy 전) 렌더도 NULL로 남는다 —
                // V4 백필 마이그레이션이 기존 행을, 이 필드가 이후 생성되는 모든 렌더를 채운다.
                .ownerUserId(format.getOwnerUserId())
                .formatId(format.getId())
                .targetDate(targetDate)
                .profileKey(profile.profileKey())
                .widthPx(widthPx)
                .heightPx(heightPx)
                .sha256(sha256)
                .path("renders/" + renderId + ".png")
                .pbmSha256(pbmSha256)
                .pbmPath(pbmRelativePath)
                .kind(kind)
                .formatUpdatedAt(format.getUpdatedAt())
                .weatherFetchedAt(now)  // 날씨 조회 시각 (현재 구현에서는 현재 시각)
                .renderedAt(now)
                .build();

        renderRepository.save(render);

        log.info("Render saved: id={}, format={}, kind={}, size={}x{}, pbm={}",
                renderId, format.getId(), kind, widthPx, heightPx, pbmSha256 != null);

        return new RenderResult(renderId, grayscalePng, sha256, widthPx, heightPx, pbmSha256);
    }

    /**
     * 바이트 배열의 SHA256 해시 계산
     */
    private String calculateSha256(byte[] data) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * PNG 파일에서 너비와 높이를 추출한다 (PNG 헤더)
     * PNG 시그니처 이후 8-11바이트(너비), 12-15바이트(높이)
     */
    private int[] extractPngDimensions(byte[] pngData) {
        if (pngData.length < 24) {
            throw new RuntimeException("Invalid PNG data: too small");
        }

        int width = ((pngData[16] & 0xFF) << 24) |
                ((pngData[17] & 0xFF) << 16) |
                ((pngData[18] & 0xFF) << 8) |
                (pngData[19] & 0xFF);

        int height = ((pngData[20] & 0xFF) << 24) |
                ((pngData[21] & 0xFF) << 16) |
                ((pngData[22] & 0xFF) << 8) |
                (pngData[23] & 0xFF);

        return new int[]{width, height};
    }
}
