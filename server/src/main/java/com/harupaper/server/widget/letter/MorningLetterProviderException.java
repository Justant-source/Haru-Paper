package com.harupaper.server.widget.letter;

/** 재시도와 캐시 폴백을 모두 소진한 뒤에도 아침편지를 가져오지 못했을 때. */
public class MorningLetterProviderException extends RuntimeException {
    public MorningLetterProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
