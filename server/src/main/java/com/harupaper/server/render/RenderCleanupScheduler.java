package com.harupaper.server.render;

import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.device.PrinterProfileProvider;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 렌더 파일 정리 (docs/server/rendering.md 5절).
 * 매일 03:30 KST 실행:
 * - kind=preview: 24시간 지난 것 삭제
 * - kind=command: 명령이 done/expired가 된 후 24시간 지난 것 삭제
 * - kind=scheduled: targetDate < 오늘 - 7일 삭제
 * - 단, 포맷마다 "현재 profileKey"의 최신 렌더 1개는 항상 유지
 *
 * 멀티유저 주의(2026-09-17 수정): "현재 profileKey"는 기기 전체에서 공통인 값이 아니다.
 * 사용자마다 기기(프로필)가 다를 수 있으므로, 렌더 하나하나에 대해 "그 렌더가 만들어질 때의
 * 소유자(Render.ownerUserId)가 지금 쓰는 기기 프로필"을 기준으로 판단한다 — RenderScheduler가
 * 기기별로 순회하며 소유자별 프로필을 쓰는 것과 같은 원칙이다. owner_user_id가 NULL인 레거시
 * 렌더(claim-legacy 전)는 PrinterProfileProviderImpl이 DEFAULT로 폴백해 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RenderCleanupScheduler {

    private final RenderRepository renderRepository;
    private final FormatRepository formatRepository;
    private final PrinterProfileProvider printerProfileProvider;

    private static final String FILES_DIR = "/data/haru-files";

    @Scheduled(cron = "0 30 3 * * *", zone = "Asia/Seoul")  // 매일 03:30 KST
    @Transactional
    public void cleanupRenders() {
        log.info("RenderCleanupScheduler: starting cleanup");

        try {
            LocalDate today = TimeUtils.todayInKST();
            Instant now = Instant.now();

            // 소유자(ownerUserId, null 포함)별 현재 profileKey 캐시 — 실행 1회당 기기 수만큼만 조회한다.
            Map<String, String> profileKeyByOwner = new HashMap<>();

            // 1. kind=preview: 24시간 지난 것 삭제
            cleanupPreviewRenders(now, profileKeyByOwner);

            // 2. kind=command: done/expired인 것 중 24시간 지난 것 삭제
            // (현재 구현에서는 스킵 — Command 도메인과의 상호작용이 필요)
            // cleanupCommandRenders(now);

            // 3. kind=scheduled: targetDate < 오늘 - 7일 삭제
            cleanupScheduledRenders(today, profileKeyByOwner);

            log.info("RenderCleanupScheduler: cleanup completed");

        } catch (Exception e) {
            log.error("RenderCleanupScheduler error", e);
        }
    }

    /**
     * preview 렌더 정리: 24시간 지난 것 삭제
     * 단, 포맷마다 "현재 profileKey"의 최신 렌더 1개는 유지 — "현재"는 그 렌더 소유자 기준(멀티유저)
     */
    private void cleanupPreviewRenders(Instant now, Map<String, String> profileKeyByOwner) {
        List<Render> previewRenders = renderRepository.findAllByKind("preview");
        Instant cutoff = now.minusSeconds(24 * 3600);  // 24시간 전

        // 포맷별로 그룹화
        Map<String, List<Render>> byFormat = new HashMap<>();
        for (Render render : previewRenders) {
            byFormat.computeIfAbsent(render.getFormatId(), k -> new java.util.ArrayList<>()).add(render);
        }

        for (String formatId : byFormat.keySet()) {
            List<Render> renders = byFormat.get(formatId);

            // "현재" 프로필과 일치하는 최신 렌더 찾기 — 각 렌더는 자기 소유자의 기기 프로필과 비교한다.
            Optional<Render> latestCurrent = renders.stream()
                    .filter(r -> currentProfileKeyFor(r, profileKeyByOwner).equals(r.getProfileKey()))
                    .max(Comparator.comparing(Render::getRenderedAt));

            for (Render render : renders) {
                // 최신 렌더는 유지
                if (latestCurrent.isPresent() && render.getId().equals(latestCurrent.get().getId())) {
                    continue;
                }

                // 24시간 지난 것 삭제
                if (render.getRenderedAt().isBefore(cutoff)) {
                    deleteRender(render);
                }
            }
        }
    }

    /**
     * scheduled 렌더 정리: targetDate < 오늘 - 7일인 것 삭제
     * 단, 포맷마다 "현재 profileKey"의 최신 렌더 1개는 유지 — "현재"는 그 렌더 소유자 기준(멀티유저)
     */
    private void cleanupScheduledRenders(LocalDate today, Map<String, String> profileKeyByOwner) {
        List<Render> scheduledRenders = renderRepository.findAllByKind("scheduled");
        LocalDate cutoffDate = today.minusDays(7);

        // 포맷별로 그룹화
        Map<String, List<Render>> byFormat = new HashMap<>();
        for (Render render : scheduledRenders) {
            byFormat.computeIfAbsent(render.getFormatId(), k -> new java.util.ArrayList<>()).add(render);
        }

        for (String formatId : byFormat.keySet()) {
            List<Render> renders = byFormat.get(formatId);

            // "현재" 프로필과 일치하는 최신 렌더 찾기 — 각 렌더는 자기 소유자의 기기 프로필과 비교한다.
            Optional<Render> latestCurrent = renders.stream()
                    .filter(r -> currentProfileKeyFor(r, profileKeyByOwner).equals(r.getProfileKey()))
                    .max(Comparator.comparing(Render::getRenderedAt));

            for (Render render : renders) {
                // 최신 렌더는 유지
                if (latestCurrent.isPresent() && render.getId().equals(latestCurrent.get().getId())) {
                    continue;
                }

                // targetDate < 7일 전 = 삭제
                if (render.getTargetDate().isBefore(cutoffDate)) {
                    deleteRender(render);
                }
            }
        }
    }

    /**
     * 렌더 r의 "현재 profileKey"를 구한다: r을 만들 당시의 소유자(ownerUserId, NULL 가능)가
     * 지금 쓰는 기기 프로필. ownerUserId(문자열 또는 "NULL 대체 키")별로 캐시해 요청 수를 줄인다.
     *
     * 소유자를 모르는 레거시 렌더(ownerUserId=NULL, claim-legacy 전)는 PrinterProfileProviderImpl이
     * DEFAULT로 폴백하므로, 그 렌더의 profileKey가 DEFAULT.profileKey()와 같을 때만 "최신 유지"
     * 대상이 된다. 다른 사용자의 기기 프로필과 잘못 비교되는 일은 없다.
     */
    private String currentProfileKeyFor(Render render, Map<String, String> profileKeyByOwner) {
        String ownerUserId = render.getOwnerUserId();
        String cacheKey = ownerUserId == null ? "" : ownerUserId;
        return profileKeyByOwner.computeIfAbsent(cacheKey,
                k -> printerProfileProvider.getCurrentProfile(ownerUserId).profileKey());
    }

    /**
     * 렌더 삭제: 파일 + DB 행
     */
    private void deleteRender(Render render) {
        try {
            // 파일 삭제
            Path filePath = Paths.get(FILES_DIR).resolve(render.getPath());
            if (Files.exists(filePath)) {
                Files.delete(filePath);
                log.debug("Deleted render file: {}", render.getPath());
            } else {
                log.warn("Render file not found (orphaned): {}", render.getPath());
            }

            // PBM 파일도 있으면 같이 삭제 (docs/architecture.md 3.4)
            if (render.getPbmPath() != null) {
                Path pbmFilePath = Paths.get(FILES_DIR).resolve(render.getPbmPath());
                if (Files.exists(pbmFilePath)) {
                    Files.delete(pbmFilePath);
                    log.debug("Deleted render PBM file: {}", render.getPbmPath());
                }
            }

            // DB 행 삭제
            renderRepository.deleteById(render.getId());
            log.debug("Deleted render record: {}", render.getId());

        } catch (IOException e) {
            log.error("Failed to delete render file: {}", render.getPath(), e);
        } catch (Exception e) {
            log.error("Failed to delete render: {}", render.getId(), e);
        }
    }
}
