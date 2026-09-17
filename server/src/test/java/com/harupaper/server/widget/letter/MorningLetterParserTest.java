package com.harupaper.server.widget.letter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fixture HTML(같은 구조, 지어낸 문장 — 공개 저장소라 실제 편지 본문은 넣지 않는다)로 {@link MorningLetterParser}를 검증한다.
 * 네트워크를 타지 않는다.
 */
@DisplayName("고도원의 아침편지 파서")
class MorningLetterParserTest {

    private static final Instant FIXED_INSTANT = Instant.parse("2026-09-18T00:00:00Z");

    private static String fixture(String name) {
        try (InputStream in = MorningLetterParserTest.class.getResourceAsStream("/widgets/letter/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("정상 구조: 날짜/제목/인용문/출처/한마디를 모두 뽑는다")
    void parsesNormalLetter() {
        MorningLetter letter = MorningLetterParser.parse(fixture("normal.html"), FIXED_INSTANT);

        assertEquals("2026년 9월 18일", letter.dateText());
        assertEquals("가장 낮은 곳에서 배우는 것", letter.title());
        assertEquals(
                "\"가장 낮은 곳으로 내려가 본 사람만이,\n"
                        + "가장 높은 곳의 풍경을 진짜로 이해할 수 있다.\n"
                        + "바람이 세게 부는 날일수록,\n"
                        + "뿌리 깊은 나무는 오히려 더 단단히 선다.\n"
                        + "그러니 오늘 흔들리고 있다면,\n"
                        + "그것은 넘어지는 중이 아니라 뿌리를 내리는 중이다.\"",
                letter.quote());
        assertEquals("- 이든의《바람이 지나간 자리》중에서 -", letter.source());
        assertEquals(
                "오늘 하루,\n"
                        + "누군가의 낮은 자리를 한 번쯤 들여다보는 마음을 가져보세요.\n"
                        + "그 자리에서 배우는 것이 생각보다 많습니다.\n\n"
                        + "오늘도 많이 웃으세요.",
                letter.comment());
        assertEquals(FIXED_INSTANT, letter.fetchedAt());
        assertFalse(letter.stale());
    }

    @Test
    @DisplayName("출처 줄이 없으면 본문 전체가 quote, source·comment는 빈 문자열")
    void handlesMissingSourceLine() {
        MorningLetter letter = MorningLetterParser.parse(fixture("no-source.html"), FIXED_INSTANT);

        assertEquals("2026년 3월 2일", letter.dateText());
        assertEquals(
                "큰 걸음보다 꾸준한 작은 걸음이 더 멀리 간다.\n"
                        + "오늘 내딛는 한 걸음도 그런 걸음이었으면 좋겠다.\n\n"
                        + "오늘도 많이 웃으세요.",
                letter.quote());
        assertEquals("", letter.source());
        assertEquals("", letter.comment());
    }

    @Test
    @DisplayName("한마디가 없으면 source는 채워지고 comment만 빈 문자열")
    void handlesMissingComment() {
        MorningLetter letter = MorningLetterParser.parse(fixture("no-comment.html"), FIXED_INSTANT);

        assertEquals("고요한 아침은 하루를 준비하는 시간이다.\n그 시간을 소중히 여기는 사람은 하루가 다르다.",
                letter.quote());
        assertEquals("- 노을의《아침의 온도》중에서 -", letter.source());
        assertEquals("", letter.comment());
    }

    @Test
    @DisplayName("연속된 빈 줄은 1개로 줄고 앞뒤 빈 줄은 사라진다")
    void collapsesBlankLines() {
        MorningLetter letter = MorningLetterParser.parse(fixture("blank-lines.html"), FIXED_INSTANT);

        assertEquals("문장에 쉼표가 필요하듯,\n\n삶에도 쉬어가는 순간이 필요하다.", letter.quote());
        assertFalse(letter.quote().contains("\n\n\n"), "연속 빈 줄이 1개로 줄어야 한다");
        assertEquals("잠시 멈추는 것은 뒤처지는 것이 아니다.\n\n오늘도 많이 웃으세요.", letter.comment());
    }

    @Test
    @DisplayName("HTML 엔티티를 실제 문자로 푼다")
    void decodesHtmlEntities() {
        MorningLetter letter = MorningLetterParser.parse(fixture("entities.html"), FIXED_INSTANT);

        assertEquals("\"나\"와 & \"너\"", letter.title());
        assertTrue(letter.quote().contains("나 & 너 사이의 거리는 <마음>이 정한다."));
        assertFalse(letter.quote().contains("&amp;"), "엔티티가 실제 문자로 풀려야 한다");
        assertFalse(letter.quote().contains("&lt;"), "엔티티가 실제 문자로 풀려야 한다");
        assertTrue(letter.comment().startsWith("\"고맙습니다\"라는 말 & 진심을"));
    }

    @Test
    @DisplayName("구조가 바뀌어 제목을 못 찾으면 예외")
    void throwsWhenStructureIsBroken() {
        assertThrows(MorningLetterParseException.class,
                () -> MorningLetterParser.parse(fixture("broken.html"), FIXED_INSTANT));
    }

    @Test
    @DisplayName("빈 응답이면 예외")
    void throwsOnBlankHtml() {
        assertThrows(MorningLetterParseException.class, () -> MorningLetterParser.parse("", FIXED_INSTANT));
        assertThrows(MorningLetterParseException.class, () -> MorningLetterParser.parse(null, FIXED_INSTANT));
    }
}
