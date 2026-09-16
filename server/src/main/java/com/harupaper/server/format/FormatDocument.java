package com.harupaper.server.format;

import java.util.List;

/**
 * 포맷 문서 v2 (원본: docs/server/format-schema.md). assets는 가져오기/내보내기 요청에서만
 * 별도로 다루고, 이 record에는 포함하지 않는다.
 *
 * schemaVersion 2: 블록들을 행(rows)으로 구성하며, 각 행은 1~2개의 슬롯(slots)을 담는다.
 * 지금 저장된 v1 포맷은 FormatDocumentSupport.readDocument()에서 자동 up-convert된다.
 */
public record FormatDocument(
        int schemaVersion,
        FormatMeta meta,
        FormatStyle style,
        List<Row> rows
) {}
