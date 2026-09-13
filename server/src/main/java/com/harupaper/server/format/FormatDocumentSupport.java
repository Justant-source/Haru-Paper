package com.harupaper.server.format;

/**
 * FormatDocument에 대한 순수 계산 헬퍼. 검증 로직은 포함하지 않는다(Format 도메인 서비스가 담당).
 */
public final class FormatDocumentSupport {

    private FormatDocumentSupport() {
    }

    /** weather 블록이 하나라도 있으면 동적 포맷이다(렌더 스케줄러가 occurrence 60분 전 재렌더). */
    public static boolean hasDynamicBlocks(FormatDocument document) {
        if (document == null || document.blocks() == null) {
            return false;
        }
        return document.blocks().stream().anyMatch(b -> "weather".equals(b.type()));
    }
}
