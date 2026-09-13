package com.harupaper.server.history;

import com.harupaper.server.common.time.TimeUtils;
import com.harupaper.server.device.Result;
import com.harupaper.server.device.ResultRepository;
import com.harupaper.server.format.Format;
import com.harupaper.server.format.FormatRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * HistoryController: 실행 결과 이력 조회 (인증 없음 — tailnet이 인증)
 * - GET /api/history?limit=50&before=<executedAt ISO>
 *
 * 응답: [{ ...Result, formatName, source: "schedule" | "command" }] (최신순)
 *
 * 로직:
 * 1. ResultRepository에서 executedAt desc로 조회
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
     * 실행 결과 이력 (최신순)
     */
    @GetMapping
    public ResponseEntity<List<HistoryResponseDto>> getHistory(
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(required = false) String before
    ) {
        List<Result> results;

        if (before != null && !before.isEmpty()) {
            // before 파라미터가 있으면 그보다 이전 것만
            try {
                // ISO-8601 문자열을 Instant로 파싱
                Instant beforeInstant = Instant.parse(before);
                results = resultRepository.findAllByExecutedAtBeforeOrderByExecutedAtDesc(beforeInstant);
            } catch (IllegalArgumentException e) {
                // 파싱 실패하면 빈 목록
                results = new ArrayList<>();
            }
        } else {
            // 전체 조회
            results = resultRepository.findAllByOrderByExecutedAtDesc();
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
            if (result.getOccurrenceKey() != null) {
                source = "schedule";
            } else if (result.getCommandId() != null) {
                source = "command";
            } else {
                source = null; // 이론상 발생하면 안 됨
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
