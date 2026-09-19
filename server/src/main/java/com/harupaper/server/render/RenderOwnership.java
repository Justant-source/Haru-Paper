package com.harupaper.server.render;

import com.harupaper.server.common.exception.NotFoundException;
import lombok.extern.slf4j.Slf4j;

/**
 * 렌더 접근 소유권 검사 — DeviceSyncController(Pi 렌더 다운로드)와 HistoryController(이력 렌더
 * 이미지)가 같은 규칙을 쓰도록 2026-09-19에 뽑았다. 규칙은 원래 DeviceSyncController.assertOwnership이었다:
 * 소유자 NULL이면 ownership-strict일 때만 거부(레거시, V4 백필/claim-legacy 전), 불일치는 항상 거부.
 * 오류는 항상 404다(403이 아니라) — 존재 여부를 노출하지 않는다.
 */
@Slf4j
public final class RenderOwnership {

    private RenderOwnership() {
    }

    /**
     * @param render          검사 대상 렌더(null이면 즉시 404 — 호출부가 findById 없이도 안전하게 쓸 수 있게)
     * @param renderId        오류 메시지·로그용 id
     * @param expectedOwnerId 접근을 요청하는 주체(기기 소유자 또는 세션 사용자)의 user id
     * @param strict          {@code haru.ownership-strict}
     * @param context         로그에 남길 호출부 식별자(예: "device=xxx", "history resultId=xxx")
     */
    public static void assertAccessible(Render render, String renderId, String expectedOwnerId,
                                         boolean strict, String context) {
        if (render == null) {
            throw new NotFoundException("Render not found: " + renderId);
        }
        String ownerUserId = render.getOwnerUserId();
        if (ownerUserId == null) {
            if (strict) {
                log.error("RENDER_OWNER_NULL render={} {} — owner_user_id가 NULL이다. " +
                        "V4 백필/claim-legacy가 끝난 뒤라면 버그다", renderId, context);
                throw new NotFoundException("Render not found: " + renderId);
            }
            log.warn("Render {} has no owner_user_id (V4 백필 전 레거시) — strict=false라 허용, {}",
                    renderId, context);
            return;
        }
        if (!ownerUserId.equals(expectedOwnerId)) {
            throw new NotFoundException("Render not found: " + renderId);
        }
    }
}
