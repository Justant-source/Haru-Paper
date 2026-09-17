# 날씨

> 결정 원본: [`../init_plan.md`](../init_plan.md) Q25(Open-Meteo, 기본 위치 서울시청), 8.2절.
> 캐시·실패 표시·매핑 표는 **[기본값]**. Open-Meteo API 파라미터 이름·약관 세부는 **[미검증]** — M2에서 공식 문서로 확인한다.
> **2026-09-18(위젯 그리드 v3)**: 옛 "날씨 블록"은 `weather` **위젯**이 됐고, 위치는 더 이상 사용자 설정(`/api/settings`)이 아니라 **위젯 설정값**(`props.location`)이다. 표시 형식·크기별 레이아웃은 [`widgets.md`](widgets.md) 3.6절로 옮겼다 — 이 문서는 데이터 출처(Open-Meteo)·API 호출·캐시·WMO 코드 매핑만 다룬다.

## 1. 데이터 출처

- **Open-Meteo Forecast API**: API 키 없음, 비상업 용도 무료.
- 선택 이유: 키 발급 절차 없이 PoC를 바로 진행하기 위해서. 한국 정확도는 기상청이 더 낫다 → 출처를 인터페이스로 분리해 나중에 교체(4절).
- 호출은 **`haru-api`의 Java 코드**가 한다. 렌더러 Chromium은 네트워크를 쓰지 않는다([`rendering.md`](rendering.md)).

요청 예시(실측, `OpenMeteoWeatherProvider.buildApiUrl`) [확인됨·코드]:

```
GET https://api.open-meteo.com/v1/forecast
    ?latitude=37.5663&longitude=126.9779
    &daily=temperature_2m_min,temperature_2m_max,precipitation_probability_max,weather_code
    &hourly=temperature_2m,precipitation_probability,weather_code
    &timezone=Asia/Seoul
    &forecast_days=3
```

- `forecast_days=3`: KST 오늘·내일(렌더 스케줄러의 36시간 창)과 여유 하루.
- `hourly=...`는 **2026-09-18에 추가**됐다 — `weather` 위젯의 `4x4` 크기(06·09·12·15·18·21시 6칸)가 쓴다([`widgets.md`](widgets.md) 3.6절). 응답의 `hourly.time[]`("2026-09-18T06:00" 형식)에서 targetDate와 같은 날짜의 시각만 추려 `HourPoint`로 만든다(`OpenMeteoWeatherProvider.extractHours`).
- 응답의 `daily.time[]`에서 **targetDate와 같은 날짜의 인덱스**를 골라 쓴다(일 단위 요약).
- `DayForecast(date, tempMin, tempMax, precipProb, weatherCode, skyText, hours, fetchedAt, stale)`가 `weather` 위젯이 받는 결과 타입(`ForecastProvider.getForecast`, 새 API). 기존 `WeatherProvider.getDaily()` → `DailyWeather`(일 단위만, `hours` 없음)는 아래 "레거시" 절 용도로 공개 시그니처만 유지된다.

## 2. 위치 — **2026-09-18부터 위젯 설정값**

- **날씨 위치는 더 이상 사용자 설정이 아니다.** `weather` 위젯 인스턴스마다 `props.location = {label, lat, lon}`을 갖고, 값은 [`widgets.md`](widgets.md) 3.6절의 대한민국 시·군·구 목록(`GET /api/widgets/locations`)에서 고른다 — 필수 필드라 기본값이 없다(사용자가 직접 고르지 않으면 저장할 수 없다).
- **바뀐 이유** [확인됨·코드, 2026-09-18 발견]: 옛 `WeatherLocationProvider.getCurrent()`(사용자별 설정 기반)는 실제로는 사용자 설정을 무시하고 **항상 서울시청 좌표**를 돌려주고 있었다 — v1·v2의 `weather` 블록은 렌더 시점과 무관하게 늘 서울 날씨가 나오던 버그였다. 또한 Open-Meteo의 지오코딩은 "분당구"·"강남구"·"서울" 같은 한국 행정구역 이름을 못 찾는다(실측 2026-09-18) — 그래서 위치는 좌표가 미리 채워진 정적 목록에서 고르는 방식으로 바꿨다.
- 같은 포맷 안에 `weather` 위젯을 여러 개(예: `2x4` 두 개로 서로 다른 도시) 넣으면 위치도 각자 다르게 설정할 수 있다.

### 레거시: `GET/PUT /api/settings`, `WeatherLocationProvider`

- API 자체는 그대로 있다(`SettingsController`, `server/.../settings/WeatherLocationProvider.java`). 응답 형태·검증 규칙은 [`api.md`](api.md) 5절 "설정"과 동일.
- **렌더에는 쓰이지 않는다.** 저장은 되지만 어떤 위젯도 이 값을 읽지 않는다. 화면에서도 지워졌다([`app/screens.md`](../app/screens.md) (7) 설정).
- 남겨 둔 이유: 이 API를 바로 지우면 옛 클라이언트·통합 테스트가 깨질 수 있어 당장은 미사용 상태로만 둔다. 정리(엔드포인트 자체 폐기)는 이 변경의 범위 밖이다.

## 3. 표시

크기별 레이아웃(2x4/4x2/4x4)과 텍스트 예시는 [`widgets.md`](widgets.md) 3.6절. 이 절에는 값 매핑만 남긴다.

| 값 | Open-Meteo `daily`/`hourly` 필드 | 표시 예 [기본값] |
|---|---|---|
| 최저/최고 기온 | `temperature_2m_min`/`_max`(일) · `temperature_2m`(시간대별) | `최저 18°`/`최고 27°` (정수 반올림) |
| 강수확률 | `precipitation_probability_max`(일) · `precipitation_probability`(시간대별) | `강수확률 10%` |
| 하늘 상태 | `weather_code`(WMO, 일) | `맑음` (아래 매핑, `WeatherCodes.skyText`) |
| 아이콘 | `weather_code` → `WeatherCodes.category` → 8종 흑백 선 SVG | [`widgets.md`](widgets.md) 3.6절 |

- **텍스트만이던 v1과 달리, `weather` 위젯은 흑백 SVG 아이콘을 그린다**(이모지·컬러 폰트는 여전히 안 쓴다 — 감열지 흑백).

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

/** 2026-09-18 신설 — weather 위젯이 쓰는 일+시간대별 API. */
public interface ForecastProvider {
    DayForecast getForecast(double lat, double lon, LocalDate targetDate);
}

public record DayForecast(
    LocalDate date, Integer tempMin, Integer tempMax, Integer precipProb, int weatherCode,
    String skyText, List<HourPoint> hours, Instant fetchedAt, boolean stale) {}

public record HourPoint(int hour, Integer temp, Integer precipProb, int weatherCode) {}
```

- 구현: `OpenMeteoWeatherProvider`가 `WeatherProvider`·`ForecastProvider` **둘 다** 구현하고 캐시를 공유한다(`clearCache()` 한 번으로 둘 다 비워진다) — 같은 HTTP 호출 결과에서 두 API 모두 파생되므로 캐시를 나눌 이유가 없었다.
- `WeatherProvider`/`DailyWeather`는 `SettingsController` 등 레거시 경로가 계속 쓰므로 공개 시그니처를 유지했다. **실제 렌더(`weather` 위젯)는 `ForecastProvider`/`DayForecast`만 쓴다.**
- 나중에: `KmaWeatherProvider`(기상청 단기예보, 공공데이터포털 서비스 키 필요, 위경도 → 격자 좌표 변환 필요) — 설정이나 `.env`로 선택
- `weather` 위젯은 `DayForecast`만 보고, 출처를 모른다.

## 5. 캐시·타임아웃 [기본값]

- **메모리 캐시 30분**. 키 = (`lat`·`lon` 소수 4자리 반올림, KST 조회 날짜). DB에 저장하지 않는다.
- 캐시 항목의 `fetchedAt`을 렌더 행의 `weather_fetched_at`에 기록.
- HTTP 타임아웃 5초, 실패 시 1회 재시도.
- 실패하면 **24시간 이내의 오래된 캐시 값이 있으면 그것을 쓴다**(그래서 캐시는 만료 후에도 24시간까지 "폴백용"으로 보관).
- 위치가 위젯 설정값으로 바뀌면서 캐시 키의 `(lat, lon)`도 위젯마다 다를 수 있다 — 같은 포맷 안 여러 `weather` 위젯이 다른 도시를 가리키면 캐시 항목도 그만큼 늘어난다(사용자 1명 규모에서는 무시할 만한 수).

## 6. 실패 시 표시 [기본값]

렌더 전체를 실패시키지 않는다. `weather` 위젯 자리에:

| 상황 | 표시 |
|---|---|
| 조회 실패, 24시간 이내 캐시 있음 | 캐시 값 + `(09-14 06:00 기준)` |
| 조회 실패, 쓸 캐시 없음 | `날씨 정보를 가져오지 못했습니다` |
| targetDate가 응답 범위 밖 | `날씨 정보를 가져오지 못했습니다` |

## 7. 약관·제한 [미검증]

- 비상업 용도 무료, API 키 없음, 일일 호출 수 제한 있음(대략 1만 회 수준으로 알려짐). 데이터 라이선스 표기(CC BY 4.0) 요구 여부 — M2에서 공식 약관 확인.
- 사용자 1명·30분 캐시라 호출량은 하루 수십 회 이하 [추정].
- **상용화하면 Open-Meteo 유료 플랜 또는 기상청 전환을 재검토**한다.
