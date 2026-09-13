# 날씨 블록

> 결정 원본: [`../init_plan.md`](../init_plan.md) Q25(Open-Meteo, 기본 위치 서울시청), 8.2절.
> 캐시·실패 표시·매핑 표는 **[기본값]**. Open-Meteo API 파라미터 이름·약관 세부는 **[미검증]** — M2에서 공식 문서로 확인한다.

## 1. 데이터 출처

- **Open-Meteo Forecast API**: API 키 없음, 비상업 용도 무료.
- 선택 이유: 키 발급 절차 없이 PoC를 바로 진행하기 위해서. 한국 정확도는 기상청이 더 낫다 → 출처를 인터페이스로 분리해 나중에 교체(4절).
- 호출은 **`haru-api`의 Java 코드**가 한다. 렌더러 Chromium은 네트워크를 쓰지 않는다([`rendering.md`](rendering.md)).

요청 예시 [미검증 — 파라미터 이름 M2에서 확인]:

```
GET https://api.open-meteo.com/v1/forecast
    ?latitude=37.5663&longitude=126.9779
    &daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code
    &timezone=Asia%2FSeoul
    &forecast_days=3
```

- `forecast_days=3`: KST 오늘·내일(렌더 스케줄러의 36시간 창)과 여유 하루.
- 응답의 `daily.time[]`에서 **targetDate와 같은 날짜의 인덱스**를 골라 쓴다.

## 2. 위치

- 설정 키 `weather.location` = `{lat, lon, label}`([`data-model.md`](data-model.md) `settings`)
- 기본값: `.env`의 `HARU_WEATHER_LAT=37.5663`, `HARU_WEATHER_LON=126.9779`, 라벨 `서울시청` [기본값]
- 앱 설정 화면에서 `PUT /api/settings`로 변경. 바뀌면 날씨 캐시 무효화 + 동적 포맷 재렌더
- 포맷 `weather.props.location`은 v1에서 `"default"`만 허용([`format-schema.md`](format-schema.md))

## 3. 표시

### 필드

| 블록 `fields` 값 | Open-Meteo `daily` 값 | 표시 예 [기본값] |
|---|---|---|
| `tempMin` | `temperature_2m_min` | `최저 18°` (정수 반올림) |
| `tempMax` | `temperature_2m_max` | `최고 27°` |
| `precipProb` | `precipitation_probability_max` | `강수확률 10%` |
| `sky` | `weather_code`(WMO) | `맑음` (아래 매핑) |

블록 한 줄 예 [기본값]: `서울시청  맑음  최저 18° / 최고 27°  강수확률 10%`
- 위치 라벨을 앞에 붙인다. `fields`에 없는 항목은 뺀다.
- **v1은 텍스트만.** 아이콘·이모지 없음(감열지 흑백, 컬러 이모지 폰트 미포함).

### WMO weather code → 한국어 하늘 상태 (초안) [기본값]

| 코드 | 표시 |
|---|---|
| 0 | 맑음 |
| 1 | 대체로 맑음 |
| 2 | 구름 조금 |
| 3 | 흐림 |
| 45, 48 | 안개 |
| 51, 53, 55 | 이슬비 |
| 56, 57 | 어는 이슬비 |
| 61 | 약한 비 |
| 63 | 비 |
| 65 | 강한 비 |
| 66, 67 | 어는 비 |
| 71 | 약한 눈 |
| 73 | 눈 |
| 75 | 강한 눈 |
| 77 | 싸락눈 |
| 80 | 약한 소나기 |
| 81 | 소나기 |
| 82 | 강한 소나기 |
| 85, 86 | 눈보라 |
| 95 | 천둥번개 |
| 96, 99 | 우박 동반 천둥번개 |
| 그 외 | `날씨 코드 {n}` |

## 4. 인터페이스 분리

```java
public interface WeatherProvider {
    /** targetDate(KST)의 일 단위 예보. 조회 실패 시 예외를 던지고 호출부가 실패 표시로 처리한다. */
    DailyWeather getDaily(double lat, double lon, LocalDate targetDate);
}

public record DailyWeather(
    LocalDate date, Integer tempMin, Integer tempMax, Integer precipProb,
    String skyText, Instant fetchedAt, String source) {}
```

- v1 구현: `OpenMeteoWeatherProvider`
- 나중에: `KmaWeatherProvider`(기상청 단기예보, 공공데이터포털 서비스 키 필요, 위경도 → 격자 좌표 변환 필요) — 설정이나 `.env`로 선택
- 렌더러는 `DailyWeather`만 보고, 출처를 모른다.

## 5. 캐시·타임아웃 [기본값]

- **메모리 캐시 30분**. 키 = (`lat`·`lon` 소수 4자리 반올림, KST 조회 날짜). DB에 저장하지 않는다.
- 캐시 항목의 `fetchedAt`을 렌더 행의 `weather_fetched_at`에 기록.
- HTTP 타임아웃 5초, 실패 시 1회 재시도.
- 실패하면 **24시간 이내의 오래된 캐시 값이 있으면 그것을 쓴다**(그래서 캐시는 만료 후에도 24시간까지 "폴백용"으로 보관).

## 6. 실패 시 표시 [기본값]

렌더 전체를 실패시키지 않는다. 날씨 블록 자리에:

| 상황 | 표시 |
|---|---|
| 조회 실패, 24시간 이내 캐시 있음 | 캐시 값 + `(09-14 06:00 기준)` |
| 조회 실패, 쓸 캐시 없음 | `날씨 정보를 가져오지 못했습니다` |
| targetDate가 응답 범위 밖 | `날씨 정보를 가져오지 못했습니다` |

## 7. 약관·제한 [미검증]

- 비상업 용도 무료, API 키 없음, 일일 호출 수 제한 있음(대략 1만 회 수준으로 알려짐). 데이터 라이선스 표기(CC BY 4.0) 요구 여부 — M2에서 공식 약관 확인.
- 사용자 1명·30분 캐시라 호출량은 하루 수십 회 이하 [추정].
- **상용화하면 Open-Meteo 유료 플랜 또는 기상청 전환을 재검토**한다.
