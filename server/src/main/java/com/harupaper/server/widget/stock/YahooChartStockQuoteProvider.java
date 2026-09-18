package com.harupaper.server.widget.stock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Yahoo Finance chart API(키 없음, 비공식) 구현 (.temp/07-위젯그리드-작업지시서.md 5.2절, D7).
 *
 * {@code https://query1.finance.yahoo.com/v8/finance/chart/{SYMBOL}?range=6mo&interval=1d} 를 부르고,
 * 실패하면 {@code query2}로 1회 더 시도한다. 메모리 캐시에는 6개월치 전체를 담아 두고 {@code days}는
 * 꺼낼 때 자른다 — 캐시 적중률을 높이고(같은 심볼을 다른 days로 여러 위젯이 물어도 재조회하지 않는다),
 * previousClose는 항상 "가장 최근 거래일의 전 거래일 종가"가 되게 하기 위해서다.
 */
@Slf4j
@Service
public class YahooChartStockQuoteProvider implements StockQuoteProvider {

    // 위젯 props 정규식과 동일(PropField pattern) — 여기서 다시 검증해 URL에 사용자 입력을 그대로
    // 꽂지 않는다(SSRF 방지, .temp/07 D5·CLAUDE.md 구성요소 경계)
    static final Pattern SYMBOL_PATTERN = Pattern.compile("^[A-Z]{1,5}([.-][A-Z]{1,2})?$");

    private static final String HOST_PRIMARY = "https://query1.finance.yahoo.com";
    private static final String HOST_FALLBACK = "https://query2.finance.yahoo.com";
    private static final String CHART_PATH_FORMAT = "/v8/finance/chart/%s?range=6mo&interval=1d";
    // 실측 2026-09-18(.temp/07 5.2절): UA 없이 부르면 429가 나기 쉬워 일반 브라우저 UA를 고정으로 보낸다
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final String NOT_FOUND_ERROR_CODE = "Not Found";

    private static final int HTTP_TIMEOUT_SECONDS = 5;
    private static final int CACHE_TTL_MINUTES = 15;
    private static final int FALLBACK_CACHE_HOURS = 72;
    private static final ZoneId DEFAULT_EXCHANGE_ZONE = ZoneId.of("America/New_York");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 심볼별 6개월치 전체 캐시. days로 자르는 건 {@link #trim} 몫이다 */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ChartHttpClient httpClient;
    // 캐시 만료·폴백 판단용 시각. 테스트가 15분/72시간을 실제로 기다리지 않고 흉내 낼 수 있게 분리했다
    private final java.util.function.LongSupplier clockMillis;

    public YahooChartStockQuoteProvider() {
        this(defaultHttpClient());
    }

    /** 테스트에서 네트워크 없이 고정 응답을 꽂기 위한 생성자 */
    YahooChartStockQuoteProvider(ChartHttpClient httpClient) {
        this(httpClient, System::currentTimeMillis);
    }

    /** 테스트에서 캐시 TTL·폴백 경과를 흉내 내기 위한 생성자(시각 공급자 주입) */
    YahooChartStockQuoteProvider(ChartHttpClient httpClient, java.util.function.LongSupplier clockMillis) {
        this.httpClient = httpClient;
        this.clockMillis = clockMillis;
    }

    @Override
    public StockSeries getDaily(String symbol, int days) {
        if (symbol == null) {
            throw new StockQuoteException("symbol is null");
        }
        String normalized = symbol.trim().toUpperCase(java.util.Locale.ROOT);
        if (!SYMBOL_PATTERN.matcher(normalized).matches()) {
            // 정규식에 안 맞으면 네트워크를 아예 타지 않는다(절대 금지 없음이지만 SSRF 방지 원칙)
            throw new SymbolNotFoundException(normalized);
        }
        String yahooSymbol = normalized.replace('.', '-');

        long now = clockMillis.getAsLong();
        CacheEntry cached = cache.get(normalized);
        if (cached != null && !cached.isExpired(CACHE_TTL_MINUTES, now)) {
            log.debug("Stock cache hit: {}", normalized);
            return trim(cached.series, days, false);
        }

        try {
            StockSeries fetched = fetchWithFallback(normalized, yahooSymbol);
            cache.put(normalized, new CacheEntry(fetched, now));
            return trim(fetched, days, false);
        } catch (SymbolNotFoundException e) {
            // 없는 티커는 캐시로 가리지 않는다 — 매번 같은 결과여야 한다
            throw e;
        } catch (StockQuoteException e) {
            if (cached != null && !cached.isExpiredForFallback(FALLBACK_CACHE_HOURS, now)) {
                log.warn("Yahoo Finance 조회 실패, {}시간 이내 캐시로 폴백: {} ({})",
                        FALLBACK_CACHE_HOURS, normalized, e.getMessage());
                return trim(cached.series, days, true);
            }
            throw e;
        }
    }

    private StockSeries fetchWithFallback(String symbol, String yahooSymbol) {
        RuntimeException lastFailure = null;
        for (String host : List.of(HOST_PRIMARY, HOST_FALLBACK)) {
            String url = host + String.format(CHART_PATH_FORMAT, yahooSymbol);
            try {
                FetchResult result = httpClient.fetch(url);
                return parseChartResponse(symbol, result);
            } catch (SymbolNotFoundException e) {
                throw e; // 없는 티커는 다른 호스트로 재시도해도 마찬가지다
            } catch (RuntimeException e) {
                lastFailure = e;
                log.warn("Yahoo Finance 호출 실패({}): {}", host, e.getMessage());
            }
        }
        throw new StockQuoteException("Yahoo Finance 조회 실패: " + symbol, lastFailure);
    }

    /** 네트워크 계층과 분리된 순수 파싱 — 단위 테스트가 fixture로 이 메서드까지만 건드린다 */
    StockSeries parseChartResponse(String requestedSymbol, FetchResult result) {
        JsonNode root;
        try {
            root = MAPPER.readTree(result.body());
        } catch (IOException e) {
            throw new StockQuoteException("Yahoo Finance 응답 파싱 실패: " + requestedSymbol, e);
        }
        if (root == null || root.isMissingNode()) {
            throw new StockQuoteException("Yahoo Finance 빈 응답: " + requestedSymbol);
        }
        JsonNode chart = root.path("chart");
        JsonNode errorNode = chart.path("error");
        if (!errorNode.isMissingNode() && !errorNode.isNull()) {
            String code = errorNode.path("code").asText("");
            if (NOT_FOUND_ERROR_CODE.equals(code) || result.statusCode() == 404) {
                throw new SymbolNotFoundException(requestedSymbol);
            }
            throw new StockQuoteException("Yahoo Finance 오류(" + code + "): "
                    + errorNode.path("description").asText(""));
        }
        if (result.statusCode() == 404) {
            throw new SymbolNotFoundException(requestedSymbol);
        }
        if (result.statusCode() != 200) {
            throw new StockQuoteException("Yahoo Finance HTTP " + result.statusCode() + ": " + requestedSymbol);
        }

        JsonNode results = chart.path("result");
        if (!results.isArray() || results.isEmpty()) {
            throw new StockQuoteException("Yahoo Finance 응답에 result 없음: " + requestedSymbol);
        }
        JsonNode r0 = results.get(0);
        JsonNode meta = r0.path("meta");
        String symbol = meta.path("symbol").asText(requestedSymbol);
        String shortName = meta.hasNonNull("shortName") ? meta.path("shortName").asText() : symbol;
        String currency = meta.hasNonNull("currency") ? meta.path("currency").asText() : "USD";
        ZoneId zone = resolveZone(meta.path("exchangeTimezoneName").asText(null));

        JsonNode timestamps = r0.path("timestamp");
        JsonNode quote0 = r0.path("indicators").path("quote").path(0);
        JsonNode opens = quote0.path("open");
        JsonNode highs = quote0.path("high");
        JsonNode lows = quote0.path("low");
        JsonNode closes = quote0.path("close");

        List<Candle> candles = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode o = opens.path(i);
            JsonNode h = highs.path(i);
            JsonNode l = lows.path(i);
            JsonNode c = closes.path(i);
            // o/h/l/c 중 하나라도 null이면 그 봉은 버린다(.temp/07 5.2절)
            if (isNullish(o) || isNullish(h) || isNullish(l) || isNullish(c)) {
                continue;
            }
            long epochSec = timestamps.path(i).asLong();
            LocalDate date = Instant.ofEpochSecond(epochSec).atZone(zone).toLocalDate();
            candles.add(new Candle(date, o.asDouble(), h.asDouble(), l.asDouble(), c.asDouble()));
        }
        if (candles.isEmpty()) {
            throw new StockQuoteException("Yahoo Finance 응답에 유효한 봉이 없음: " + requestedSymbol);
        }

        // clockMillis로 "지금"을 얻는다(테스트가 흉내 낼 수 있게, cache TTL 판정과 같은 방식) —
        // 여기서 Instant.now()를 직접 부르면 "마지막 봉이 확정 종가인지"를 결정론적으로 테스트할 수 없다.
        Instant fetchedAt = Instant.ofEpochMilli(clockMillis.getAsLong());
        boolean lastCandleSettled = isLastCandleSettled(candles, meta, zone, fetchedAt);
        return new StockSeries(symbol, shortName, currency, candles, null, fetchedAt, false, lastCandleSettled);
    }

    /**
     * 마지막 봉이 "확정된 종가"인지 판정한다(2026-09-18 사고 수정 — 장중에 조회한 값을 "종가"로
     * 표시해 사용자가 혼란을 겪었다. 자세한 배경은 {@link StockSeries#lastCandleSettled()} javadoc).
     *
     * 판정 규칙: 마지막 봉의 날짜가 거래소 현지 "오늘"보다 이전이면 무조건 확정이다(이미 지난 날이므로
     * 더 바뀔 수 없다). 오늘 날짜와 같으면 Yahoo 응답의 {@code currentTradingPeriod.regular.end}(그날
     * 정규장 마감 시각, epoch초)와 지금을 비교한다 — 그 시각을 지났으면 확정, 아니면 아직 장중(또는
     * 장 시작 전)이라 값이 계속 바뀔 수 있으므로 미확정으로 본다. 이 필드가 없으면(옛 응답 형태 등)
     * 안전한 쪽(미확정)으로 판단한다 — "종가"라고 잘못 단정하는 것보다 "현재가"라고 보수적으로 표시하는
     * 쪽이 낫다.
     */
    private static boolean isLastCandleSettled(List<Candle> candles, JsonNode meta, ZoneId zone, Instant now) {
        Candle last = candles.get(candles.size() - 1);
        LocalDate todayInZone = LocalDate.ofInstant(now, zone);
        if (last.date().isBefore(todayInZone)) {
            return true;
        }
        long regularEndEpoch = meta.path("currentTradingPeriod").path("regular").path("end").asLong(-1);
        if (regularEndEpoch <= 0) {
            return false;
        }
        return now.getEpochSecond() >= regularEndEpoch;
    }

    private static ZoneId resolveZone(String tzName) {
        if (tzName == null || tzName.isBlank()) {
            return DEFAULT_EXCHANGE_ZONE;
        }
        try {
            return ZoneId.of(tzName);
        } catch (RuntimeException e) {
            log.warn("알 수 없는 exchangeTimezoneName '{}', America/New_York으로 대체", tzName);
            return DEFAULT_EXCHANGE_ZONE;
        }
    }

    private static boolean isNullish(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    /**
     * 캐시된(또는 갓 받아온) 전체 시리즈를 days로 자른다.
     * previousClose는 잘라낸 목록이 아니라 <b>전체</b> 목록의 마지막 두 봉으로 계산한다 —
     * 그래야 어떤 days로 요청하든 등락이 같은 값이 된다.
     */
    private static StockSeries trim(StockSeries full, int days, boolean stale) {
        List<Candle> all = full.candles();
        int size = all.size();
        int take = Math.max(0, Math.min(days, size));
        List<Candle> trimmed = take == 0 ? List.of() : List.copyOf(all.subList(size - take, size));
        Double previousClose = size >= 2 ? all.get(size - 2).close() : null;
        // 마지막 봉은 days로 자르든 말든 항상 같다(끝에서부터 자르므로) — lastCandleSettled는 그대로 옮긴다
        return new StockSeries(full.symbol(), full.shortName(), full.currency(), trimmed, previousClose,
                full.fetchedAt(), stale, full.lastCandleSettled());
    }

    // ---- HTTP 전송 계층 (테스트에서 교체) ----

    /** 상태 코드에 상관없이 응답 바디를 그대로 돌려준다 — 404 + error 바디도 정상 처리하기 위해 */
    interface ChartHttpClient {
        FetchResult fetch(String url);
    }

    record FetchResult(int statusCode, String body) {
    }

    private static ChartHttpClient defaultHttpClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
        factory.setReadTimeout(Duration.ofSeconds(HTTP_TIMEOUT_SECONDS));
        RestClient restClient = RestClient.builder().requestFactory(factory).build();
        return url -> {
            try {
                return restClient.get()
                        .uri(url)
                        .header(HttpHeaders.USER_AGENT, USER_AGENT)
                        .exchange((request, response) -> {
                            String body = response.bodyTo(String.class);
                            return new FetchResult(response.getStatusCode().value(), body == null ? "" : body);
                        });
            } catch (RestClientException e) {
                throw new StockQuoteException("Yahoo Finance 호출 실패: " + url, e);
            }
        };
    }

    private static final class CacheEntry {
        final StockSeries series;
        final long cacheTime;

        CacheEntry(StockSeries series, long cacheTime) {
            this.series = series;
            this.cacheTime = cacheTime;
        }

        boolean isExpired(int ttlMinutes, long nowMillis) {
            return nowMillis - cacheTime > (long) ttlMinutes * 60 * 1000;
        }

        boolean isExpiredForFallback(int fallbackHours, long nowMillis) {
            return nowMillis - cacheTime > (long) fallbackHours * 60 * 60 * 1000;
        }
    }
}
