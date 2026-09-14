package com.harupaper.server.device;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 스냅샷 해시 계산 (docs/server/api.md 6.1절).
 * 결정적이어야 한다 (같은 내용이면 항상 같은 값).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SnapshotHashCalculator {

    private final ObjectMapper objectMapper;

    /**
     * 현재 스냅샷의 SHA-256 해시를 계산한다.
     *
     * @param schedules 모든 예약 (켜진 것과 꺼진 것 포함)
     * @param renders 스냅샷에 포함될 렌더들
     * @return 소문자 hex 문자열
     */
    public String calculate(List<DeviceDto.ScheduleDto> schedules, List<DeviceDto.RenderDto> renders) {
        try {
            // 1. schedules를 id 오름차순 정렬, 필요한 필드만 추출
            List<DeviceDto.ScheduleHashInput> sortedSchedules = schedules.stream()
                    .map(s -> new DeviceDto.ScheduleHashInput(
                            s.id(),
                            s.formatId(),
                            s.type(),
                            normalizeDaysOfWeek(s.daysOfWeek()),
                            s.time(),
                            s.date(),
                            s.enabled()
                    ))
                    .sorted(Comparator.comparing(s -> s.id()))
                    .collect(Collectors.toList());

            // 2. renders를 (formatId, targetDate, renderId) 오름차순 정렬, 필요한 필드만 추출
            List<DeviceDto.RenderHashInput> sortedRenders = renders.stream()
                    .map(r -> new DeviceDto.RenderHashInput(
                            r.renderId(),
                            r.formatId(),
                            r.targetDate(),
                            r.sha256()
                    ))
                    .sorted(Comparator.comparing((DeviceDto.RenderHashInput r) -> r.formatId())
                            .thenComparing(r -> r.targetDate())
                            .thenComparing(r -> r.renderId()))
                    .collect(Collectors.toList());

            // 3. 구조를 만들고 Jackson으로 직렬화
            // ObjectMapper에서 키 정렬을 활성화하면 순서를 보장한다
            ObjectMapper sortingMapper = new ObjectMapper();
            sortingMapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

            // 입력 객체를 LinkedHashMap으로 만들어서 순서를 보장하면서 JSON 직렬화
            LinkedHashMap<String, Object> input = new LinkedHashMap<>();
            input.put("schedules", sortedSchedules);
            input.put("renders", sortedRenders);

            // JSON 문자열 생성 (공백 없음)
            String json = sortingMapper.writeValueAsString(input);

            // 4. SHA-256 해시 계산
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            String hash = bytesToHex(hashBytes);

            log.debug("Calculated snapshot hash: {} (json length: {})", hash, json.length());
            return hash;
        } catch (Exception e) {
            log.error("Failed to calculate snapshot hash", e);
            throw new RuntimeException("Failed to calculate snapshot hash", e);
        }
    }

    /**
     * 바이트 배열을 16진수 소문자 문자열로 변환한다.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private List<String> normalizeDaysOfWeek(List<String> daysOfWeek) {
        if (daysOfWeek == null) {
            return null;
        }

        java.util.Map<String, Integer> dayOrder = java.util.Map.of(
                "MON", 1,
                "TUE", 2,
                "WED", 3,
                "THU", 4,
                "FRI", 5,
                "SAT", 6,
                "SUN", 7
        );

        return daysOfWeek.stream()
                .map(code -> code == null ? "" : code.trim().toUpperCase())
                .sorted(Comparator.comparingInt(code -> dayOrder.getOrDefault(code, Integer.MAX_VALUE)))
                .collect(Collectors.toList());
    }
}
