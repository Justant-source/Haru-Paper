package com.harupaper.server.history;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.common.exception.NotFoundException;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.device.Result;
import com.harupaper.server.device.ResultRepository;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatRepository;
import com.harupaper.server.render.Render;
import com.harupaper.server.render.RenderOwnership;
import com.harupaper.server.render.RenderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * M6: HistoryController: 실행 결과 이력 조회 (사용자 인증 필수)
 * 소유권 스코핑: 현재 사용자의 결과만
 * - GET /api/history?limit=50&before=<executedAt ISO>
 * - GET /api/history/{resultId}/render.png (2026-09-19 추가, 3절)
 *
 * 응답: [{ ...Result, formatName, source: "schedule" | "command", renderAvailable }] (최신순)
 *
 * 로직:
 * 1. ResultRepository에서 owner_user_id로 스코핑해서 executedAt desc로 조회
 * 2. before 파라미터가 있으면 그보다 이전 것만 필터
 * 3. limit 적용 (기본 50)
 * 4. 각 항목에 formatName (FormatRepository로 조회, 삭제됐으면 null 허용)
 * 5. source: occurrenceKey 있으면 "schedule", commandId 있으면 "command"
 * 6. renderAvailable: renderId가 있고 그 Render 행이 아직 남아 있으면 true(RenderRepository.findAllById로 한 번에 조회)
 */
@Slf4j
@RestController
@RequestMapping("/api/history")
public class HistoryController {
    private final ResultRepository resultRepository;
    private final FormatRepository formatRepository;
    private final RenderRepository renderRepository;
    private final String filesDir;
    private final boolean ownershipStrict;

    public HistoryController(ResultRepository resultRepository,
                              FormatRepository formatRepository,
                              RenderRepository renderRepository,
                              @Value("${haru.files-dir:/data/haru-files}") String filesDir,
                              @Value("${haru.ownership-strict:false}") boolean ownershipStrict) {
        this.resultRepository = resultRepository;
        this.formatRepository = formatRepository;
        this.renderRepository = renderRepository;
        this.filesDir = filesDir;
        this.ownershipStrict = ownershipStrict;
    }

    /**
     * GET /api/history?limit=50&before=<executedAt ISO>
     * 실행 결과 이력 (최신순, 사용자 소유)
     */
    @GetMapping
    public ResponseEntity<List<HistoryResponseDto>> getHistory(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }

        String userId = principal.userId();
        if (limit < 1) {
            throw new ValidationException("limit must be >= 1", List.of(
                    new ValidationException.FieldError("limit", "must be greater than or equal to 1")
            ));
        }

        List<Result> results;

        if (before != null && !before.isEmpty()) {
            // before 파라미터가 있으면 그보다 이전 것만 (사용자별 스코핑)
            try {
                // ISO-8601 문자열을 Instant로 파싱
                Instant beforeInstant = TimeUtils.parseIso8601(before);
                if (beforeInstant == null) {
                    throw new ValidationException("before must be ISO-8601 timestamp", List.of(
                            new ValidationException.FieldError("before", "must be a valid ISO-8601 timestamp")
                    ));
                }
                results = resultRepository.findAllByOwnerUserIdAndExecutedAtBeforeOrderByExecutedAtDesc(userId, beforeInstant);
            } catch (DateTimeParseException e) {
                throw new ValidationException("before must be ISO-8601 timestamp", List.of(
                        new ValidationException.FieldError("before", "must be a valid ISO-8601 timestamp")
                ));
            }
        } else {
            // 사용자의 전체 결과 조회
            results = resultRepository.findAllByOwnerUserIdOrderByExecutedAtDesc(userId);
        }

        // limit 적용
        if (results.size() > limit) {
            results = results.subList(0, limit);
        }

        // renderAvailable 계산용 — 결과당 findById 대신 한 번에 IN 조회(N+1 방지)
        Set<String> renderIds = new HashSet<>();
        for (Result result : results) {
            if (result.getRenderId() != null) {
                renderIds.add(result.getRenderId());
            }
        }
        Set<String> availableRenderIds = new HashSet<>();
        if (!renderIds.isEmpty()) {
            for (Render render : renderRepository.findAllById(renderIds)) {
                availableRenderIds.add(render.getId());
            }
        }

        // DTO로 변환
        List<HistoryResponseDto> response = new ArrayList<>();
        for (Result result : results) {
            // formatName 조회 (포맷 삭제됐으면 null)
            String formatName = null;
            if (result.getFormatId() != null) {
                Optional<Format> format = formatRepository.findById(result.getFormatId());
                if (format.isPresent()) {
                    formatName = format.get().getName();
                }
            }

            // source 판단
            String source;
            boolean hasOccurrence = result.getOccurrenceKey() != null && !result.getOccurrenceKey().isBlank();
            boolean hasCommand = result.getCommandId() != null && !result.getCommandId().isBlank();
            if (hasOccurrence) {
                source = "schedule";
            } else if (hasCommand) {
                source = "command";
            } else {
                source = null;
            }

            HistoryResponseDto dto = new HistoryResponseDto(
                    result.getId(),
                    result.getOccurrenceKey(),
                    result.getCommandId(),
                    result.getFormatId(),
                    formatName,
                    result.getRenderId(),
                    result.getStatus(),
                    result.getDetail(),
                    result.getScheduledAt() != null ? TimeUtils.toIso8601(result.getScheduledAt()) : null,
                    TimeUtils.toIso8601(result.getExecutedAt()),
                    source,
                    result.getRenderId() != null && availableRenderIds.contains(result.getRenderId())
            );
            response.add(dto);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/history/{resultId}/render.png — 그날 실제로 나간(또는 만들어진) 렌더 이미지.
     * preview.png(포맷 상세)와 다르다 — 이건 "지금 다시 렌더"가 아니라 그 실행 시점에 저장된
     * 파일 그대로다. 동적 위젯(morningLetter·weather·stockChart)이 있으면 preview.png는 매일
     * 값이 바뀌므로 이력의 의미가 사라진다.
     *
     * 소유권: Result.ownerUserId로 1차 검사(다른 사용자 것이거나 없으면 404 — 존재 노출 안 함),
     * Render.ownerUserId로 2차 검사(RenderOwnership, DeviceSyncController와 같은 규칙).
     */
    @GetMapping(value = "/{resultId}/render.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<Resource> getResultRender(
            @PathVariable String resultId,
            @AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(401).build();
        }
        String userId = principal.userId();

        Result result = resultRepository.findById(resultId)
                .orElseThrow(() -> new NotFoundException("Result not found: " + resultId));
        if (!userId.equals(result.getOwnerUserId())) {
            // NULL도 여기서 걸러진다 — 이력 목록 자체가 owner_user_id로 스코핑되므로(위 getHistory),
            // NULL 소유 Result는 애초에 어느 사용자의 목록에도 나타나지 않는다. 즉 이 분기에 오는
            // 것은 항상 "존재는 하되 내 것이 아닌 resultId를 직접 넣어 본" 경우다.
            throw new NotFoundException("Result not found: " + resultId);
        }
        if (result.getRenderId() == null) {
            // dry_run·missed·skipped_* 는 렌더 자체가 없다.
            throw new NotFoundException("Render not found for result: " + resultId);
        }

        Render render = renderRepository.findById(result.getRenderId()).orElse(null);
        RenderOwnership.assertAccessible(render, result.getRenderId(), userId, ownershipStrict,
                "history resultId=" + resultId);

        Path filePath = Paths.get(filesDir, render.getPath());
        File file = filePath.toFile();
        if (!file.exists()) {
            throw new NotFoundException("Render file not found: " + result.getRenderId());
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, "\"" + render.getSha256() + "\"")
                // 개인 인쇄물이므로 공유 캐시(CDN·프록시)에는 두지 않는다 — DeviceSyncController의
                // 렌더 다운로드와 같은 이유·같은 값.
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=31536000")
                .body(resource);
    }
}
