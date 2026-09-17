package com.harupaper.server.widget.letter;

/**
 * godowon.com의 HTML 구조가 바뀌어 제목·본문·날짜 중 하나를 못 찾았을 때 던진다.
 * {@link GodowonMorningLetterProvider}가 잡아서 재시도/캐시 폴백으로 처리한다 —
 * 위젯 쪽으로는 절대 그대로 전파되지 않는다(Widget 구현 규칙 ②).
 */
public class MorningLetterParseException extends RuntimeException {
    public MorningLetterParseException(String message) {
        super(message);
    }
}
