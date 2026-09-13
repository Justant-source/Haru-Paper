package com.harupaper.server.format;

import java.util.List;

/**
 * 포맷 문서 v1 (원본: docs/server/format-schema.md). assets는 가져오기/내보내기 요청에서만
 * 별도로 다루고, 이 record에는 포함하지 않는다.
 */
public record FormatDocument(
        int schemaVersion,
        FormatMeta meta,
        FormatStyle style,
        List<Block> blocks
) {}
