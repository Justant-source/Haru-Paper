package com.harupaper.server.widget.letter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link MorningLetterProvider} 구현. 출처는 고정 호스트 www.godowon.com 하나뿐이다(D5, SSRF 방지 —
 * 사용자 입력이 URL에 들어가지 않는다). 작업지시서 07 4절 "데이터 제공자 공통 규칙" 그대로:
 * RestClient + 연결/읽기 타임아웃 5초, 재시도 1회, 메모리 캐시 TTL 30분, 실패 시 72시간 이내 캐시를 stale로.
 */
@Slf4j
@Service
public class GodowonMorningLetterProvider implements MorningLetterProvider {

    // 사용자 입력이 절대 섞이지 않는 고정 URL. 이 문자열 자체가 유일한 허용 호스트다
    static final String LETTER_URL = "https://www.godowon.com/";
    private static final int HTTP_TIMEOUT_SECONDS = 5; // 작업지시서 07 4절
    private static final int CACHE_TTL_MINUTES = 30; // 작업지시서 07 4절
    // 아침편지·증시는 주말·일요일 휴간이 있어 24시간이 아니라 72시간(작업지시서 07 4절)
    private static final int FALLBACK_CACHE_HOURS = 72;
    // 일반 브라우저 UA — 서버 측에서 비브라우저 요청을 막는 경우를 피한다(작업지시서 07 4절 "상대 서버 예의")
    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final int MAX_ATTEMPTS = 2; // 최초 시도 + 재시도 1회

    private final RestClient restClient;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    public GodowonMorningLetterProvider() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public MorningLetter getLatest() {
        Instant now = now();
        CacheEntry cached = cache.get();
        if (cached != null && !cached.isExpired(now, CACHE_TTL_MINUTES)) {
            log.debug("고도원의 아침편지 캐시 적중");
            return cached.letter;
        }

        Exception lastFailure = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                String html = fetchHtml();
                MorningLetter letter = MorningLetterParser.parse(html, now);
                cache.set(new CacheEntry(letter, now));
                return letter;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("고도원의 아침편지 조회 실패 (시도 {}/{}): {}", attempt + 1, MAX_ATTEMPTS, e.getMessage());
            }
        }

        if (cached != null && !cached.isExpiredForFallback(now, FALLBACK_CACHE_HOURS)) {
            log.info("고도원의 아침편지 stale 캐시 사용 (fetchedAt={})", cached.letter.fetchedAt());
            MorningLetter stale = cached.letter;
            return new MorningLetter(stale.dateText(), stale.title(), stale.quote(), stale.source(),
                    stale.comment(), stale.fetchedAt(), true);
        }

        throw new MorningLetterProviderException("고도원의 아침편지를 가져오지 못했습니다", lastFailure);
    }

    /**
     * 실제 HTTP GET. 단위 테스트는 이 메서드를 오버라이드해 네트워크를 타지 않고 고정 fixture를 돌려준다
     * (작업지시서 07 4절 "단위 테스트는 네트워크를 타지 않는다").
     */
    protected String fetchHtml() {
        try {
            byte[] body = restClient.get()
                    .uri(LETTER_URL)
                    .header(HttpHeaders.USER_AGENT, USER_AGENT)
                    .retrieve()
                    .body(byte[].class);
            if (body == null || body.length == 0) {
                throw new MorningLetterProviderException("빈 응답", null);
            }
            // 실측(2026-09-18) Content-Type: text/html; charset=UTF-8 — 명시적으로 UTF-8로 디코드
            return new String(body, StandardCharsets.UTF_8);
        } catch (RestClientException e) {
            throw new MorningLetterProviderException("godowon.com 요청 실패: " + e.getMessage(), e);
        }
    }

    /** 캐시 TTL 판정에 쓰는 현재 시각. 테스트가 오버라이드해 시간 경과를 흉내 낸다. */
    protected Instant now() {
        return Instant.now();
    }

    /** 설정 변경·운영 조작용. 기존 WeatherProvider.clearCache() 패턴과 동일 */
    public void clearCache() {
        cache.set(null);
        log.info("고도원의 아침편지 캐시 비움");
    }

    private record CacheEntry(MorningLetter letter, Instant cachedAt) {
        boolean isExpired(Instant now, int ttlMinutes) {
            return Duration.between(cachedAt, now).toMinutes() >= ttlMinutes;
        }

        boolean isExpiredForFallback(Instant now, int fallbackHours) {
            return Duration.between(cachedAt, now).toHours() >= fallbackHours;
        }
    }
}
