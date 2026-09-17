package com.harupaper.server.widget.letter;

import java.time.Instant;

/**
 * 고도원의 아침편지 한 편.
 *
 * @param dateText  "2026년 9월 18일" 형식. 원본의 "오늘의 아침편지" 꼬리는 제거한 상태
 * @param title     제목 ({@code h3.mainLetterTit})
 * @param quote     인용문 본문. 줄바꿈은 "\n"으로, 연속 빈 줄은 1개로 줄인 상태
 * @param source    출처 줄(예: "- 아무개의《책 이름》중에서 -"). 없으면 빈 문자열
 * @param comment   고도원의 한마디. 첫 줄의 "* " 접두사는 제거한 상태. 없으면 빈 문자열
 * @param fetchedAt 이 값을 실제로 가져온(또는 캐시에 넣은) 시각
 * @param stale     true면 최신 조회에 실패해 캐시에 남아 있던 직전 값이라는 뜻
 */
public record MorningLetter(
        String dateText,
        String title,
        String quote,
        String source,
        String comment,
        Instant fetchedAt,
        boolean stale
) {
}
