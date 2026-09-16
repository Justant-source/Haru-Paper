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

    /**
     * 필드별로 null인 값만 기본값으로 채운다. {"style": {}}처럼 객체는 있지만 개별
     * 필드가 비어 있는 입력을 그대로 렌더러에 넘기면 언박싱에서 NPE가 난다 — 저장
     * (FormatService.normalizeDocument)과 즉석 미리보기(HtmlTemplateBuilder.buildHtml)
     * 양쪽이 저장을 거치든 안 거치든 항상 이 메서드를 거쳐야 한다.
     */
    public static FormatStyle withDefaults(FormatStyle given) {
        if (given == null) {
            return defaults();
        }
        FormatStyle defaults = defaults();
        return new FormatStyle(
                given.fontFamily() != null ? given.fontFamily() : defaults.fontFamily(),
                given.baseFontSizePt() != null ? given.baseFontSizePt() : defaults.baseFontSizePt(),
                given.lineHeight() != null ? given.lineHeight() : defaults.lineHeight(),
                given.marginMm() != null ? given.marginMm() : defaults.marginMm(),
                given.blockGapMm() != null ? given.blockGapMm() : defaults.blockGapMm(),
                given.divider() != null ? given.divider() : defaults.divider()
        );
    }
}
