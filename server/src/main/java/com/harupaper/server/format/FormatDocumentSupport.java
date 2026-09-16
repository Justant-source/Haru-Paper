package com.harupaper.server.format;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FormatDocument에 대한 순수 계산 헬퍼. 검증 로직은 포함하지 않는다(Format 도메인 서비스가 담당).
 */
@Slf4j
public final class FormatDocumentSupport {

    private FormatDocumentSupport() {
    }

    /** weather 블록이 하나라도 있으면 동적 포맷이다(렌더 스케줄러가 occurrence 60분 전 재렌더). */
    public static boolean hasDynamicBlocks(FormatDocument document) {
        if (document == null || document.rows() == null) {
            return false;
        }
        return document.rows().stream()
                .flatMap(row -> row.slots().stream())
                .anyMatch(slot -> slot.block() != null && "weather".equals(slot.block().type()));
    }

    /**
     * 저장된 포맷 body(JSON 문자열)를 읽는다.
     * schemaVersion이 1이면 자동으로 v2로 up-convert하고, 2면 그대로 파싱한다.
     *
     * Up-convert (1 → 2): 기존 각 블록을 {id: uuid(), slots: [{id: uuid(), width: "1/1", block: 그 블록}]} 행으로 감싼다.
     *
     * 이 메서드는 읽기 경로(조회, 렌더, 미리보기 등)에서만 사용된다. 쓰기는 항상 v2로 검증한다.
     *
     * @param bodyJson FormatDocument의 JSON 문자열 (format.getBody())
     * @param mapper Jackson ObjectMapper
     * @return FormatDocument (v2), up-convert되었을 수도 있음
     */
    public static FormatDocument readDocument(String bodyJson, ObjectMapper mapper) {
        try {
            // 먼저 Map으로 파싱해서 schemaVersion을 확인
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = mapper.readValue(bodyJson, Map.class);
            Object versionObj = raw.get("schemaVersion");
            int version = (versionObj instanceof Number) ? ((Number) versionObj).intValue() : 0;

            if (version == 1) {
                // v1 → v2 up-convert
                return upConvertFromV1(raw, mapper);
            } else if (version == 2) {
                // v2: 그대로 파싱
                ObjectMapper lenient = mapper.copy();
                lenient.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
                return lenient.convertValue(raw, FormatDocument.class);
            } else {
                log.error("Unsupported schemaVersion: {}", version);
                throw new RuntimeException("Unsupported schemaVersion: " + version);
            }
        } catch (Exception e) {
            log.error("Failed to read format document", e);
            throw new RuntimeException("Failed to read format document", e);
        }
    }

    /**
     * v1 포맷을 v2로 변환한다.
     * 각 블록을 1/1 폭의 단일 슬롯을 가진 행으로 감싼다. 순서는 그대로 유지된다.
     */
    @SuppressWarnings("unchecked")
    private static FormatDocument upConvertFromV1(Map<String, Object> rawV1, ObjectMapper mapper) {
        try {
            ObjectMapper lenient = mapper.copy();
            lenient.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            // v1 구조: schemaVersion, meta, style, blocks
            int version = (rawV1.get("schemaVersion") instanceof Number)
                ? ((Number) rawV1.get("schemaVersion")).intValue()
                : 1;
            FormatMeta meta = lenient.convertValue(rawV1.get("meta"), FormatMeta.class);
            FormatStyle style = rawV1.get("style") != null
                ? lenient.convertValue(rawV1.get("style"), FormatStyle.class)
                : null;

            List<Row> rows = new ArrayList<>();
            Object blocksObj = rawV1.get("blocks");
            if (blocksObj instanceof List<?> blocksList) {
                for (Object blockObj : blocksList) {
                    Block block = lenient.convertValue(blockObj, Block.class);
                    if (block != null) {
                        // 각 블록을 1/1 슬롯으로 감싼 행으로 생성
                        Slot slot = new Slot(UUID.randomUUID().toString(), "1/1", block);
                        Row row = new Row(UUID.randomUUID().toString(), List.of(slot));
                        rows.add(row);
                    }
                }
            }

            return new FormatDocument(2, meta, style, rows);
        } catch (Exception e) {
            log.error("Failed to up-convert v1 format", e);
            throw new RuntimeException("Failed to up-convert v1 format", e);
        }
    }
}
