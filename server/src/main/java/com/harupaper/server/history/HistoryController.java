package com.harupaper.server.history;

import com.harupaper.server.auth.UserPrincipal;
import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.common.exception.ValidationException;
import com.harupaper.server.device.Result;
import com.harupaper.server.device.ResultRepository;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * M6: HistoryController: 실행 결과 이력 조회 (사용자 인증 필수)
 * 소유권 스코핑: 현재 사용자의 결과만
 * - GET /api/history?limit=50&before=<executedAt ISO>
 *
 * 응답: [{ ...Result, formatName, source: "schedule" | "command" }] (최신순)
 *
 * 로직:
 * 1. ResultRepository에서 owner_user_id로 스코핑해서 executedAt desc로 조회
 * 2. before 파라미터가 있으면 그보다 이전 것만 필터
 * 3. limit 적용 (기본 50)
 * 4. 각 항목에 formatName (FormatRepository로 조회, 삭제됐으면 null 허용)
 * 5. source: occurrenceKey 있으면 "schedule", commandId 있으면 "command"
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {
    private final ResultRepository resultRepository;
    private final FormatRepository formatRepository;

    public HistoryController(ResultRepository resultRepository, FormatRepository formatRepository) {
        this.resultRepository = resultRepository;
        this.formatRepository = formatRepository;
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
                    source
            );
            response.add(dto);
        }

        return ResponseEntity.ok(response);
    }
}
