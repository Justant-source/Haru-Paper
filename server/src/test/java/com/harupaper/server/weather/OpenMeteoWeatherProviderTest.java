package com.harupaper.server.weather;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;

/**
 * OpenMeteoWeatherProvider: daily+hourly 파싱, 캐시 TTL·stale 폴백, clearCache가 둘 다 비우는지.
 * (.temp/07-위젯그리드-작업지시서.md 5.3절, docs/server/weather.md) — 네트워크를 타지 않는다.
 * fixture: src/test/resources/widgets/weather/open-meteo-response.json (지어낸 수치, 실제 API 응답 아님)
 */
@DisplayName("OpenMeteoWeatherProvider: daily+hourly 파싱·캐시")
class OpenMeteoWeatherProviderTest {

    private static final double LAT = 37.3827; // 성남시 분당구(테스트용 임의 좌표)
    private static final double LON = 127.1189;
    private static final LocalDate TARGET_DATE = LocalDate.of(2026, 9, 18);

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private String fixtureJson;

    @BeforeEach
    void setUp() throws Exception {
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        fixtureJson = new ClassPathResource("widgets/weather/open-meteo-response.json")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private OpenMeteoWeatherProvider newProvider() throws Exception {
        RestClient client = restClientBuilder.build();
        var ctor = OpenMeteoWeatherProvider.class.getDeclaredConstructor(RestClient.class);
        ctor.setAccessible(true);
        return ctor.newInstance(client);
    }

    @Test
    @DisplayName("daily+hourly를 한 번에 파싱한다 — 대상 날짜의 시간만 추린다")
    void parsesDailyAndHourlyForTargetDate() throws Exception {
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(fixtureJson, MediaType.APPLICATION_JSON));

        OpenMeteoWeatherProvider provider = newProvider();
        DayForecast forecast = provider.getForecast(LAT, LON, TARGET_DATE);

        assertThat(forecast.date()).isEqualTo(TARGET_DATE);
        assertThat(forecast.tempMin()).isEqualTo(18); // round(18.2)
        assertThat(forecast.tempMax()).isEqualTo(28); // round(27.6)
        assertThat(forecast.precipProb()).isEqualTo(30);
        assertThat(forecast.weatherCode()).isEqualTo(2);
        assertThat(forecast.skyText()).isEqualTo("구름 조금");
        assertThat(forecast.stale()).isFalse();

        // 09-19T00:00 시각은 대상 날짜(09-18)가 아니므로 제외돼야 한다
        assertThat(forecast.hours()).extracting(HourPoint::hour)
                .containsExactly(0, 6, 9, 12, 15, 18, 21);

        Optional<HourPoint> h9 = forecast.hours().stream().filter(h -> h.hour() == 9).findFirst();
        assertThat(h9).isPresent();
        assertThat(h9.get().temp()).isEqualTo(22); // round(22.1)
        assertThat(h9.get().precipProb()).isNull(); // fixture의 null 값

        Optional<HourPoint> h15 = forecast.hours().stream().filter(h -> h.hour() == 15).findFirst();
        assertThat(h15).isPresent();
        assertThat(h15.get().temp()).isNull(); // fixture의 null 값
        assertThat(h15.get().precipProb()).isEqualTo(50);
        assertThat(h15.get().weatherCode()).isEqualTo(63);

        mockServer.verify();
    }

    @Test
    @DisplayName("getDaily()는 같은 호출의 daily 부분만 돌려준다(공개 시그니처 유지)")
    void getDailyDerivesFromSameFetch() throws Exception {
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(fixtureJson, MediaType.APPLICATION_JSON));

        OpenMeteoWeatherProvider provider = newProvider();
        DailyWeather daily = provider.getDaily(LAT, LON, TARGET_DATE);

        assertThat(daily.date()).isEqualTo(TARGET_DATE);
        assertThat(daily.tempMin()).isEqualTo(18);
        assertThat(daily.tempMax()).isEqualTo(28);
        assertThat(daily.precipProb()).isEqualTo(30);
        assertThat(daily.skyText()).isEqualTo("구름 조금");
        assertThat(daily.source()).isEqualTo("open-meteo");
    }

    @Test
    @DisplayName("30분 이내 재조회는 캐시를 쓴다 — HTTP 호출이 한 번만 나간다")
    void reusesCacheWithinTtl() throws Exception {
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(fixtureJson, MediaType.APPLICATION_JSON));

        OpenMeteoWeatherProvider provider = newProvider();
        DayForecast first = provider.getForecast(LAT, LON, TARGET_DATE);
        DayForecast second = provider.getForecast(LAT, LON, TARGET_DATE);

        assertThat(second).isEqualTo(first);
        mockServer.verify(); // 딱 1번만 기대했으므로 2번째 호출이 HTTP를 탔다면 여기서 실패한다
    }

    @Test
    @DisplayName("캐시가 30분 지나 만료돼도 24시간 안이면 재조회 실패 시 stale 값을 돌려준다")
    void fallsBackToStaleCacheWithin24Hours() throws Exception {
        // MockRestServiceServer(SimpleRequestExpectationManager)는 첫 요청이 나간 뒤에는 새 expect()를
        // 등록할 수 없다 — 이 테스트에서 나갈 요청 3개(최초 성공 1 + 재시도 실패 2)를 전부 먼저 등록해 둔다
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(fixtureJson, MediaType.APPLICATION_JSON));
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withServerError());
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withServerError());

        OpenMeteoWeatherProvider provider = newProvider();
        DayForecast first = provider.getForecast(LAT, LON, TARGET_DATE);
        backdateCache(provider, LAT, LON, TARGET_DATE, Duration.ofMinutes(31));

        DayForecast stale = provider.getForecast(LAT, LON, TARGET_DATE);
        assertThat(stale.stale()).isTrue();
        assertThat(stale.tempMax()).isEqualTo(first.tempMax());
        assertThat(stale.fetchedAt()).isEqualTo(first.fetchedAt()); // 최초 조회 시각을 유지
        mockServer.verify();
    }

    @Test
    @DisplayName("24시간 폴백 캐시도 지나면 예외를 던진다")
    void throwsWhenFallbackCacheAlsoExpired() throws Exception {
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(fixtureJson, MediaType.APPLICATION_JSON));
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withServerError());
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withServerError());

        OpenMeteoWeatherProvider provider = newProvider();
        provider.getForecast(LAT, LON, TARGET_DATE);
        backdateCache(provider, LAT, LON, TARGET_DATE, Duration.ofHours(25));

        assertThatThrownBy(() -> provider.getForecast(LAT, LON, TARGET_DATE))
                .isInstanceOf(OpenMeteoWeatherProvider.WeatherProviderException.class);
    }

    @Test
    @DisplayName("clearCache()는 daily·forecast 캐시를 함께 비운다(단일 캐시) — 다음 호출이 다시 HTTP를 탄다")
    void clearCacheInvalidatesBoth() throws Exception {
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(fixtureJson, MediaType.APPLICATION_JSON));
        mockServer.expect(method(org.springframework.http.HttpMethod.GET))
                .andRespond(MockRestResponseCreators.withSuccess(fixtureJson, MediaType.APPLICATION_JSON));

        OpenMeteoWeatherProvider provider = newProvider();
        provider.getDaily(LAT, LON, TARGET_DATE);
        provider.clearCache();

        // 캐시가 비었으니 getForecast()도 새(두 번째로 등록해 둔) HTTP 호출을 탄다
        DayForecast forecast = provider.getForecast(LAT, LON, TARGET_DATE);

        assertThat(forecast.tempMax()).isEqualTo(28);
        mockServer.verify();
    }

    /** 리플렉션으로 캐시 항목의 시각을 과거로 되돌려 TTL 만료를 흉내 낸다(30분 실제로 기다릴 수 없어서) */
    @SuppressWarnings("unchecked")
    private void backdateCache(OpenMeteoWeatherProvider provider, double lat, double lon, LocalDate date,
                                Duration age) throws Exception {
        Field cacheField = OpenMeteoWeatherProvider.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Map<String, Object> cache = (Map<String, Object>) cacheField.get(provider);

        double latRounded = Math.round(lat * 10000.0) / 10000.0;
        double lonRounded = Math.round(lon * 10000.0) / 10000.0;
        String key = String.format(java.util.Locale.ROOT, "%.4f,%.4f,%s", latRounded, lonRounded, date);

        Object entry = cache.get(key);
        assertThat(entry).as("caller must fetch once before backdating").isNotNull();
        Field cacheTimeField = entry.getClass().getDeclaredField("cacheTime");
        cacheTimeField.setAccessible(true);
        cacheTimeField.setLong(entry, System.currentTimeMillis() - age.toMillis());
    }
}
