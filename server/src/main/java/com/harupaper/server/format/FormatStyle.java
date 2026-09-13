package com.harupaper.server.format;

public record FormatStyle(
        String fontFamily,
        Double baseFontSizePt,
        Double lineHeight,
        MarginMm marginMm,
        Double blockGapMm,
        String divider
) {
    /** format-schema.md 3.2절 기본값 */
    public static FormatStyle defaults() {
        return new FormatStyle("Pretendard", 11.0, 1.4,
                new MarginMm(3.0, 3.0, 8.0, 3.0), 3.0, "none");
    }
}
