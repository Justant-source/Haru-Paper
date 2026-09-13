package com.harupaper.server.format;

public record FormatMeta(
        String name,
        String author,
        String description,
        ForkedFrom forkedFrom
) {}
