package com.harupaper.server.format;

/** importedAt은 ISO-8601 +09:00 문자열(TimeUtils.toIso8601 결과)로 둔다. */
public record ForkedFrom(
        String name,
        String author,
        int schemaVersion,
        String importedAt
) {}
