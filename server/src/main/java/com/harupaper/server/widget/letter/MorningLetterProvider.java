package com.harupaper.server.widget.letter;

/**
 * 고도원의 아침편지 최신 값을 돌려준다. 조회 실패 시 예외({@link MorningLetterProviderException})를 던진다 —
 * 직전 캐시로 대신 채우는 것은 구현체(캐시 폴백) 몫이고, 그마저 없을 때만 예외가 올라온다.
 */
public interface MorningLetterProvider {
    MorningLetter getLatest();
}
