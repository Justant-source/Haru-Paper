package com.harupaper.server.widget.letter;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * godowon.com 메인 페이지 HTML → {@link MorningLetter}. 순수 함수(네트워크·스프링 의존 없음) — 단위 테스트가
 * 고정 fixture로만 돈다. HTML 구조는 작업지시서 07 5.1절 2026-09-18 실측 그대로다.
 *
 * <pre>
 * &lt;span class="letterDate"&gt;2026년 9월 18일 오늘의 아침편지&lt;/span&gt;
 * &lt;div class="mainletterContents"&gt;
 *   &lt;h3 class="mainLetterTit"&gt;제목&lt;/h3&gt;
 *   &lt;p&gt;인용문...&lt;br/&gt;...&lt;br/&gt;&lt;br/&gt;- 아무개의《책 이름》중에서 -&lt;br/&gt;&lt;br/&gt;* 한마디...&lt;/p&gt;
 * </pre>
 */
public final class MorningLetterParser {

    // "2026년 9월 18일 오늘의 아침편지" → "2026년 9월 18일"만 남긴다(꼬리 제거는 정규식으로 — 문구가 바뀌어도 앞쪽 날짜만 뽑으면 되게)
    private static final Pattern DATE_PATTERN = Pattern.compile("^(\\d{4}년\\s*\\d{1,2}월\\s*\\d{1,2}일)");
    // 출처 줄: "- 아무개의《책 이름》중에서 -" 형태. 작업지시서 5.1절 규칙 그대로
    private static final Pattern SOURCE_LINE = Pattern.compile("^-\\s.+\\s-$");
    // <br>, <br/>, <br /> 전부 매칭(대소문자 무관)
    private static final Pattern BR_TAG = Pattern.compile("(?i)<br\\s*/?>");
    // 한마디 첫 줄의 "* " 접두사
    private static final Pattern COMMENT_PREFIX = Pattern.compile("^\\*\\s?");

    private MorningLetterParser() {
    }

    public static MorningLetter parse(String html, Instant fetchedAt) {
        if (html == null || html.isBlank()) {
            throw new MorningLetterParseException("빈 응답");
        }
        Document doc = Jsoup.parse(html);

        Element dateSpan = doc.selectFirst(".letterDate");
        if (dateSpan == null || dateSpan.text().isBlank()) {
            throw new MorningLetterParseException(".letterDate 를 찾지 못함");
        }
        String rawDate = dateSpan.text().trim().replaceAll("\\s+", " ");
        Matcher dateMatcher = DATE_PATTERN.matcher(rawDate);
        if (!dateMatcher.find()) {
            throw new MorningLetterParseException("날짜 형식을 인식하지 못함: " + rawDate);
        }
        String dateText = dateMatcher.group(1);

        Element titleEl = doc.selectFirst(".mainletterContents .mainLetterTit");
        if (titleEl == null || titleEl.text().isBlank()) {
            throw new MorningLetterParseException(".mainLetterTit 를 찾지 못함");
        }
        String title = titleEl.text().trim();

        Element bodyEl = doc.selectFirst(".mainletterContents p");
        if (bodyEl == null) {
            throw new MorningLetterParseException(".mainletterContents p 를 찾지 못함");
        }

        List<String> lines = splitLines(bodyEl.html());
        if (lines.isEmpty()) {
            throw new MorningLetterParseException("본문이 비어 있음");
        }

        int sourceIndex = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (SOURCE_LINE.matcher(lines.get(i)).matches()) {
                sourceIndex = i;
                break;
            }
        }

        String quote;
        String source;
        String comment;
        if (sourceIndex < 0) {
            quote = String.join("\n", lines);
            source = "";
            comment = "";
        } else {
            List<String> quoteLines = trimBlankEdges(lines.subList(0, sourceIndex));
            List<String> commentLines = trimBlankEdges(lines.subList(sourceIndex + 1, lines.size()));
            quote = String.join("\n", quoteLines);
            source = lines.get(sourceIndex);
            if (!commentLines.isEmpty()) {
                commentLines = new ArrayList<>(commentLines);
                commentLines.set(0, COMMENT_PREFIX.matcher(commentLines.get(0)).replaceFirst(""));
            }
            comment = String.join("\n", commentLines);
        }

        if (quote.isBlank()) {
            throw new MorningLetterParseException("인용문을 찾지 못함");
        }

        return new MorningLetter(dateText, title, quote, source, comment, fetchedAt, false);
    }

    /**
     * {@code <p>}의 innerHTML을 &lt;br&gt; 기준으로 줄 단위로 쪼개고, 각 줄은 엔티티를 풀고(&amp;amp; → &amp;) trim한다.
     * 그다음 연속된 빈 줄을 1개로 줄이고 앞뒤 빈 줄을 없앤다.
     */
    private static List<String> splitLines(String innerHtml) {
        String[] rawSegments = BR_TAG.split(innerHtml, -1);
        List<String> lines = new ArrayList<>();
        for (String segment : rawSegments) {
            // 조각 안에 남은 인라인 태그(있다면)까지 걷어내고 엔티티만 푼 순수 텍스트로 — jsoup이 해 준다
            String text = Jsoup.parseBodyFragment(segment).body().text().trim();
            lines.add(text);
        }
        return collapseBlankLines(lines);
    }

    private static List<String> collapseBlankLines(List<String> lines) {
        List<String> collapsed = new ArrayList<>();
        for (String line : lines) {
            boolean prevBlank = !collapsed.isEmpty() && collapsed.get(collapsed.size() - 1).isEmpty();
            if (line.isEmpty() && prevBlank) {
                continue;
            }
            collapsed.add(line);
        }
        return trimBlankEdges(collapsed);
    }

    private static List<String> trimBlankEdges(List<String> lines) {
        int start = 0;
        int end = lines.size();
        while (start < end && lines.get(start).isEmpty()) {
            start++;
        }
        while (end > start && lines.get(end - 1).isEmpty()) {
            end--;
        }
        return new ArrayList<>(lines.subList(start, end));
    }
}
