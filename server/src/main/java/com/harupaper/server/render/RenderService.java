package com.harupaper.server.render;

import com.harupaper.server.format.FormatDocument;

import java.time.LocalDate;

/**
 * Rendering 에이전트가 @Service로 구현한다(Playwright+Chromium, docs/server/rendering.md).
 * 다른 도메인(Format/Schedule)은 이 인터페이스만 보고 호출하고 구현 세부는 모른다.
 */
public interface RenderService {

    /**
     * 저장된 포맷을 현재 프린터 프로필로 렌더한다. kind=preview 행을 만들고,
     * 같은 (포맷 updated_at, date, profileKey) 렌더가 10분 안에 있으면 재사용한다.
     */
    RenderResult renderSavedFormatPreview(String formatId, LocalDate targetDate);

    /** 저장하지 않은 편집본을 렌더한다. DB 행·파일을 남기지 않고 PNG 바이트만 돌려준다. */
    byte[] renderEphemeral(FormatDocument document, LocalDate targetDate);

    /** "지금 인쇄" 전용: kind=command로 즉시 렌더해 렌더 행을 만든다. */
    RenderResult renderForCommand(String formatId, LocalDate targetDate);
}
