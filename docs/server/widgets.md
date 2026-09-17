# 위젯 프레임워크·카탈로그

> **위젯을 아는 코드는 `server/src/main/java/com/harupaper/server/widget/**`뿐이다.** 서버의 다른 부분(포맷 검증·렌더 조립)과 앱은
> `WidgetDescriptor`(=`GET /api/widgets` 응답)만 안다 — 위젯 종류를 하드코딩하지 않는다(CLAUDE.md "구성요소 경계"에 준하는 규칙, 2026-09-18 위젯 그리드 도입으로 신설).
> 문서 전체(그리드·포맷 문서 구조)의 원본은 [`format-schema.md`](format-schema.md)다. 이 문서는 **위젯 프레임워크 자체**와 **위젯별 표**만 다룬다.
> 표기: **[확인됨]** 실물 확인 / **[확인됨·코드]** 코드에서 확인 / **[미검증]** 확인 전 / **[기본값]** 따로 묻지 않고 정한 값

## 1. 위젯 프레임워크

패키지: `server/src/main/java/com/harupaper/server/widget/`.

| 클래스 | 역할 |
|---|---|
| `GridSpec` | 그리드 상수(열 4, 행 단위 12mm, 간격 2mm, 최대 위젯 20개) + mm/pt → px 환산. [`format-schema.md`](format-schema.md) 2절 |
| `WidgetSize` | 위젯 1개가 허용하는 크기 하나(`id`, `cols`, `rows`(null=자동 높이), `previewRows`, `label`) |
| `PropField` | 위젯 설정값(props) 한 칸의 스키마(`key`, `kind`, `required`, `defaultValue`, `min`/`max`/`maxLength`/`pattern`, `options` 등). 서버 검증기와 앱 설정 폼이 **둘 다 이것만 보고 동작**한다 |
| `WidgetDescriptor` | 위젯 종류 하나의 명세(`type`, `name`, `description`, `icon`, `dynamic`, `catalog`, `sizes`, `defaultSize`, `fields`). `GET /api/widgets`가 이 record를 그대로 JSON으로 내려준다 |
| `WidgetInstance` | 포맷 문서에 저장되는 위젯 1개(`id`, `type`, `size`, `props`) |
| `WidgetRenderContext` | 위젯 1개를 그릴 때 넘겨주는 값(대상 날짜, 프린터 프로필, 소유자, 고른 크기, 박스 px 크기, mm/pt→px 변환 도우미) |
| `Widget` | 위젯 구현 인터페이스. `descriptor()` + `renderHtml(instance, ctx)` + (선택) `validateProps(...)` |
| `WidgetHtml` | HTML 이스케이프(`escape`), 실패 표시 상자(`errorBox`) 공통 도우미 |
| `WidgetRegistry` | 스프링이 주입한 모든 `Widget` 빈을 모은다. 같은 `type`이 두 번 등록되면 기동 시점에 실패. `hasDynamic(widgets)`로 동적 포맷 여부 판정 |
| `WidgetPageBuilder` | 이미 그려진 위젯 HTML 조각들을 4열 CSS 그리드 한 장으로 조립하는 순수 함수. 실제 렌더(`HtmlTemplateBuilder`)와 위젯 단독 미리보기(`WidgetPreviewHarness`)가 같은 골격을 쓴다 |
| `WidgetPropsValidator` | `descriptor.fields` 기반 props 검증(타입·범위·필수·알 수 없는 키) — [`format-schema.md`](format-schema.md) 3.3절 |
| `WidgetController` | `GET /api/widgets` |

테스트 도구: `server/src/test/java/com/harupaper/server/widget/WidgetPreviewHarness`(위젯을 실제 골격 그대로 PNG로 떠서 눈으로 확인, `build/widget-previews/*.png`에 출력).

### 1.1 `Widget` 구현 규칙 (원본은 `Widget.java` javadoc)

① `renderHtml`은 **서버가 조립한 HTML 조각**만 돌려준다. 외부에서 온 모든 문자열(사용자 입력·스크랩한 본문·API 응답)은 반드시 `WidgetHtml.escape`를 거친다. `<script>`, 외부 URL(`img src`, CSS `url()`)은 금지다 — 렌더러는 JS를 끄고 네트워크를 전부 막는다(CLAUDE.md 절대 금지 6).

② 외부 데이터 조회가 실패해도 **예외를 던지지 않는다**. 캐시된 직전 값이 있으면 그것으로 그리고, 없으면 `WidgetHtml.errorBox`로 "가져오지 못했습니다"를 그린다. 인쇄 전체를 막지 않는다.

③ 감열 프린터는 흑백 1비트다. 회색·그라데이션을 쓰지 말고 검정(`#000`)/흰색만 쓴다. 선 굵기는 3px 이상(300dpi에서 0.25mm), 글자는 7pt 이상.

④ 고정 크기(`size.rows != null`)면 내용이 `boxWidthPx × boxHeightPx` 안에 들어가야 한다(넘치면 잘린다). 루트 요소는 `width:100%; height:100%`로 잡는다 — 옆 위젯 때문에 박스가 더 커질 수 있다.

⑤ 외부로 나가는 URL은 **코드에 고정된 호스트뿐**이다. 사용자 입력을 URL 호스트·경로에 그대로 넣지 않는다(정규식으로 검증한 값만 허용).

### 1.2 데이터 제공자 공통 규칙 [기본값]

동적 위젯(`morningLetter`, `stockChart`, `weather`)이 외부 데이터를 조회할 때 따르는 공통 패턴:

- HTTP: Spring `RestClient` + `SimpleClientHttpRequestFactory`, 연결·읽기 타임아웃 각 5초, 재시도 1회.
- 메모리 캐시 TTL **30분**(증시는 15분), 실패 시 **24시간**(아침편지·증시는 72시간 — 주말·일요일 휴간) 이내의 직전 값을 "stale"로 돌려준다. 서버 재기동 시 캐시가 비는 것은 PoC에서 수용한다.
- `User-Agent`는 일반 브라우저 문자열을 고정으로 보낸다(상대 서버가 비브라우저 요청을 막는 경우를 피하기 위해서 — "상대 서버 예의").
- 제공자는 인터페이스 뒤에 둔다(테스트에서 가짜로 교체). 단위 테스트는 **네트워크를 타지 않는다** — 저장해 둔 고정 응답(fixture)만 쓴다. fixture에 실제 저작물 본문을 넣지 않는다(공개 저장소) — 같은 HTML 구조의 지어낸 문장을 쓴다.

### 1.3 새 위젯 추가 절차

앱 코드를 고칠 필요가 없다 — 카탈로그·크기 선택·설정 폼이 `WidgetDescriptor`에서 자동 생성되기 때문이다.

1. `server/src/main/java/com/harupaper/server/widget/<새패키지>/`에 `Widget` 구현 클래스를 만들고 `@Component`를 붙인다. `descriptor()`에 `type`(한 번 정하면 바꾸지 않는다)·`name`·`description`·`icon`·`dynamic`·`catalog`·`sizes`·`defaultSize`·`fields`를 채운다.
2. 외부 데이터가 필요하면 위 1.2절 패턴을 따르는 제공자 인터페이스 + 구현을 같은 패키지에 둔다.
3. `renderHtml`을 구현한다(1.1절 규칙을 지킨다).
4. 단위 테스트(위젯 로직) + `WidgetPreviewHarness`로 미리보기 PNG를 실제로 눈으로 확인한다.
5. [`format-schema.md`](format-schema.md) 5절 요약을 갱신할 필요는 없다(위젯 목록은 이 문서 2·5절에서만 관리) — **이 문서(`widgets.md`)에 위젯 표를 추가**한다.
6. 앱은 다음 `GET /api/widgets` 응답부터 새 위젯을 카탈로그에 자동으로 보여준다. 앱 코드 수정 불필요.

## 2. `GET /api/widgets` 응답 형태

```json
{
  "grid": { "columns": 4, "rowUnitMm": 12, "gapMm": 2, "maxWidgets": 20 },
  "widgets": [
    {
      "type": "dateHeader", "name": "날짜 머리글", "description": "오늘 날짜를 큰 제목으로 표시합니다",
      "icon": "calendar", "dynamic": false, "catalog": true,
      "sizes": [ { "id": "4x1", "cols": 4, "rows": 1, "previewRows": 1, "label": "띠 · 104×12mm", "autoHeight": false } ],
      "defaultSize": "4x1",
      "fields": [
        { "key": "pattern", "label": "날짜 형식", "kind": "string", "required": false, "defaultValue": "YYYY년 M월 D일 dddd", "maxLength": 100, "placeholder": "YYYY년 M월 D일 dddd", "help": "..." },
        { "key": "align", "label": "정렬", "kind": "enum", "required": false, "defaultValue": "center", "options": [{"value":"left","label":"왼쪽"}, ...] },
        { "key": "fontSizePt", "label": "글자 크기(pt)", "kind": "integer", "required": false, "defaultValue": 16, "min": 6, "max": 72 },
        { "key": "bold", "label": "굵게", "kind": "boolean", "required": false, "defaultValue": true }
      ]
    }
  ]
}
```

- `widgets`는 등록 순서(스프링 빈 주입 순서)대로 나온다. `catalog=false`인 위젯도 포함된다(옛 문서 호환 렌더용) — 앱의 "위젯 추가" 목록은 `catalog=true`만 보여준다.
- 인증은 `SecurityConfig`의 기존 `/api/**` 세션 규칙에 맡긴다(`WidgetController`가 별도 인증 검사를 하지 않는다).
- 필드가 `null`이면 응답에서 생략된다(`@JsonInclude(NON_NULL)`).

## 3. 위젯 6종

등록 순서·패키지:

| type | 이름 | 패키지 | catalog | dynamic |
|---|---|---|---|---|
| `dateHeader` | 날짜 머리글 | `widget/basic` | true | false |
| `text` | 텍스트 | `widget/basic` | true | false |
| `image` | 이미지 | `widget/basic` | **false**(호환용, 3.4절) | false |
| `morningLetter` | 고도원의 아침편지 | `widget/letter` | true | **true** |
| `stockChart` | 미국 증시 일봉 | `widget/stock` | true | **true** |
| `weather` | 오늘의 날씨 | `widget/weather`(+`weather/`) | true | **true** |

`dynamic=true`인 세 위젯이 하나라도 포함된 포맷은 **동적 포맷**으로 취급되어 렌더 스케줄러가 예약 약 60분 전에 다시 렌더한다([`rendering.md`](rendering.md) 4절, `WidgetRegistry.hasDynamic`).

### 3.1 `dateHeader` — 날짜 머리글

옛 v2 `dateHeader` 블록을 그대로 옮긴 것. 토큰 치환 규칙(`formatDateHeader`)은 동일하다.

| 크기 | id | 실제 크기 |
|---|---|---|
| 고정 1종 | `4x1` | 104×12mm(띠) |

| props 키 | kind | 필수 | 기본값 | 범위·설명 |
|---|---|---|---|---|
| `pattern` | string | 아니오 | `"YYYY년 M월 D일 dddd"` | 0~100자. 토큰: `YYYY`(2026)·`MM`(09)·`M`(9)·`DD`(05)·`D`(5)·`dddd`(월요일)·`ddd`(월). **긴 토큰부터 치환**한다(짧은 토큰이 먼저 먹지 않도록) |
| `align` | enum | 아니오 | `"center"` | `left`/`center`/`right` |
| `fontSizePt` | integer | 아니오 | `16` | 6~72 |
| `bold` | boolean | 아니오 | `true` | — |

- 데이터 출처: 없음(순수 계산). 실패 표시: 없음(항상 성공).

### 3.2 `text` — 텍스트

옛 v2 `text` 블록을 그대로 옮긴 것. `{{date}}`·`{{weekday}}` 치환과 줄바꿈 유지(`white-space: pre-wrap`)는 동일하다.

| 크기 | id | 실제 크기 |
|---|---|---|
| 자동 높이 1종 | `4xauto`(previewRows 2) | 104mm 폭 |

| props 키 | kind | 필수 | 기본값 | 범위·설명 |
|---|---|---|---|---|
| `text` | text | 아니오(빈 문자열 허용) | `""` | 0~5000자. `{{date}}`·`{{weekday}}` 치환([`format-schema.md`](format-schema.md) 8절) |
| `align` | enum | 아니오 | `"left"` | `left`/`center`/`right` |
| `fontSizePt` | integer | 아니오 | 없음(비우면 포맷 기본값) | 6~72 |
| `bold` | boolean | 아니오 | `false` | — |

- 데이터 출처: 없음. 실패 표시: 없음.

### 3.3 `image` — 이미지

옛 v2 `image` 블록을 그대로 옮긴 것. `data:` URI 내장 방식은 동일하다. **`catalog=false`** — 앱 "위젯 추가" 목록에는 안 뜨지만(이 앱 버전은 이미지 편집 UI가 없다), 이전 버전 포맷을 읽을 때는 계속 렌더할 수 있도록 위젯 자체는 등록돼 있다.

| 크기 | id | 실제 크기 |
|---|---|---|
| 자동 높이 1종 | `4xauto`(previewRows 4) | 104mm 폭 |

| props 키 | kind | 필수 | 기본값 | 범위·설명 |
|---|---|---|---|---|
| `assetId` | asset | 예 | — | 업로드된 이미지 id |
| `widthPercent` | integer | 아니오 | `100` | 10~100 |
| `align` | enum | 아니오 | `"center"` | `left`/`center`/`right` |

- 데이터 출처: `AssetRepository`(로컬 파일). 실패 표시: `errorBox("이미지", "이미지를 가져오지 못했습니다")`(에셋 없음·파일 읽기 실패).

### 3.4 `morningLetter` — 고도원의 아침편지

담당 패키지 `widget/letter/`. "선으로 틀을 잡아 글에 집중하게" 하는 레이아웃.

- **출처**: `https://www.godowon.com/`(UTF-8 [확인됨]). 파서: jsoup.
- **HTML 구조** [확인됨·실측 2026-09-18]:
  ```html
  <span class="letterDate">2026년 9월 18일 오늘의 아침편지</span>
  <div class="mainletterContents">
    <h3 class="mainLetterTit">제목</h3>
    <p> 본문 줄1<br /> 본문 줄2<br /> … <br /><br /> - 아무개의《책 이름》중에서 -<br /><br /><br /> * 고도원의 한마디 줄1<br /> … </p>
  ```
- **파싱**(`MorningLetterParser`): `<br>` → 줄바꿈, 줄별 trim. `^-\s.+\s-$`에 맞는 **첫 줄**이 출처(source). 그 앞 = 인용문(quote), 그 뒤 = 한마디(comment). 출처 줄이 없으면 전부 quote. 연속 빈 줄은 1개로 줄이고 앞뒤 빈 줄 제거. comment 첫 줄의 `* ` 접두사는 제거.
- 결과 record: `MorningLetter(dateText, title, quote, source, comment, fetchedAt, stale)`.

| 크기 | id | 실제 크기 |
|---|---|---|
| 자동 높이 1종 | `4xauto`(previewRows 8) | 104mm 폭 |

| props 키 | kind | 필수 | 기본값 | 범위·설명 |
|---|---|---|---|---|
| `showComment` | boolean | 아니오 | `true` | "고도원의 한마디" 포함 여부 |
| `fontSize` | enum | 아니오 | `"normal"` | `small`(10pt) / `normal`(11pt) / `large`(12.5pt) — 본문 글자 크기 |

레이아웃(2026-09-18 사용자 요구 — "선으로 틀을 잡아 글에 집중하게"):

```
━━━━━━━━━━━━━━━━━━━━━━━━━━  굵은 선(≈0.6mm)
 고도원의 아침편지 · 2026년 9월 18일      작은 글자(8.5pt), 가운데, 자간 넓게
──────────────────────────  가는 선(≈0.25mm)
        제목 (굵게 14pt, 가운데)

 인용문 본문 — 원문 줄바꿈 유지(white-space: pre-line), 왼쪽 정렬, 좌우 안쪽 여백 3mm

                - 아무개의《책 이름》중에서 -   (오른쪽 정렬 9.5pt)
─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─  점선
 한마디 (showComment일 때만, 10pt)
━━━━━━━━━━━━━━━━━━━━━━━━━━  굵은 선
```

- **데이터 출처·캐시**: `GodowonMorningLetterProvider` — 1.2절 공통 규칙(타임아웃 5초·재시도 1회·캐시 TTL 30분·폴백 72시간).
- **실패 표시**: `errorBox("고도원의 아침편지", "편지를 가져오지 못했습니다")`. 구조가 바뀌어 제목·본문을 못 찾은 것도 실패로 본다(로그 WARN). stale이면 위젯 안에 "(이전에 받아 둔 편지)" 작은 글씨를 덧붙인다.
- **알려진 한계**: godowon.com HTML 구조가 바뀌면 파싱이 깨진다(구조 의존 스크래핑) — 이 경우도 위 실패 표시로 떨어진다, 인쇄 전체를 막지는 않는다.

### 3.5 `stockChart` — 미국 증시 일봉

담당 패키지 `widget/stock/`. 인라인 SVG 캔들스틱 차트.

- **출처**: `https://query1.finance.yahoo.com/v8/finance/chart/{TICKER}?range=6mo&interval=1d`(실패 시 `query2` 1회, 키 없음·비공식 API). 응답의 `timestamp[]`·`indicators.quote[0].{open,high,low,close}[]`(넷 중 하나라도 null인 봉은 버린다)·`meta.{symbol,shortName,currency,exchangeTimezoneName}`을 쓴다. 없는 티커는 `chart.error` 또는 404.
- **인터페이스**: `StockQuoteProvider.getDaily(String symbol, int days)` — 나중에 환율(`KRW=X`) 등으로 넓힐 수 있게 이름을 symbol로 뒀다. **지금은 미국 증시 티커만**.

| 크기 | id | 실제 크기 |
|---|---|---|
| 고정 3종(기본 `2x4`) | `2x4` | 51×54mm |
| | `4x4` | 104×54mm |
| | `4x6` | 104×82mm |

| props 키 | kind | 필수 | 기본값 | 범위·설명 |
|---|---|---|---|---|
| `ticker` | string | 예 | — | 정규식 `^[A-Z]{1,5}([.-][A-Z]{1,2})?$`(예: `AAPL`, `BRK-B`, `BRK.B`). **대문자만**(소문자는 422 — 앱이 입력을 자동 대문자화). 최대 8자. Yahoo 호출 때 `.`은 `-`로 바꾼다 |
| `days` | integer | 아니오 | `14` | 5~60(최근 N거래일) |

- **그리기**: 인라인 SVG. 머리에 티커(굵게)·회사명(넓은 크기만)·마지막 종가(크게, 굵게)+통화·전 거래일 대비(▲/▼/− + 값 + %)·마지막 봉 날짜(`M/D`, 거래소 현지 날짜). 캔들스틱: **오른 날 = 속 빈 상자(흰 채움+검은 테두리), 내린 날 = 검게 채움**, 심지 선. 가로 점선 눈금 2~3개 + 가격 라벨(최고/최저 포함), 아래에 첫날·마지막 날 `M/D`.
- **데이터 출처·캐시**: `YahooChartStockQuoteProvider` — 캐시 TTL **15분**(1.2절과 다름), 실패 시 72시간 폴백. 심볼별 6개월치 전체를 캐시해 두고 `days`는 꺼낼 때 자른다(같은 심볼을 다른 `days`로 여러 위젯이 물어도 재조회하지 않는다). `previousClose`는 항상 전체 목록의 마지막 두 봉 기준(요청 `days`와 무관하게 등락이 같은 값이 되도록).
- **실패 표시**: `errorBox(ticker, "시세를 가져오지 못했습니다")`, 없는 티커면 `"티커를 찾을 수 없습니다"`.
- **알려진 한계** [기본값]: `2x4`에 60봉을 넣으면 봉이 뭉개진다(51mm 해상도 한계). 렌더 시각에 미국 장이 열려 있으면 마지막 봉은 진행 중인 봉이다. 겨울(EST)엔 장 마감이 KST 06:00이라 예약 60분 전 재렌더(보통 06:00경)와 겹칠 수 있다 — 문서에 적고 수용한다.

### 3.6 `weather` — 오늘의 날씨

담당 패키지 `widget/weather/` + `weather/`.

- **출처**: Open-Meteo forecast API. `daily`(요약) + `hourly=temperature_2m,precipitation_probability,weather_code`(시간대별)를 한 번에 받는다.
- **인터페이스**: `ForecastProvider.getForecast(lat, lon, date)` → `DayForecast(date, tempMin, tempMax, precipProb, weatherCode, skyText, List<HourPoint> hours, fetchedAt, stale)`, `HourPoint(hour, temp, precipProb, weatherCode)`.
  기존 `WeatherProvider.getDaily()`·`DailyWeather`·`OpenMeteoWeatherProvider.clearCache()`의 **공개 시그니처는 유지**된다(`SettingsController`·기존 테스트가 쓴다, 아래 "레거시" 참고). `OpenMeteoWeatherProvider`가 `WeatherProvider`·`ForecastProvider` 둘 다 구현하고 캐시를 공유한다.

| 크기 | id | 실제 크기 | 배치 |
|---|---|---|---|
| 고정 3종(기본 `2x4`) | `2x4` | 51×54mm | 위치 이름 / 큰 아이콘 / 하늘 상태 / `최고 27° · 최저 18°` / `강수확률 30%` |
| | `4x2` | 104×26mm | 가로 한 줄 — 아이콘 \| 위치+하늘 상태 \| 최고/최저 \| 강수확률 |
| | `4x4` | 104×54mm | 왼쪽 1/3 `2x4` 요약 + 오른쪽 2/3 **06·09·12·15·18·21시** 6칸(작은 아이콘·기온·강수확률) |

| props 키 | kind | 필수 | 기본값 | 범위·설명 |
|---|---|---|---|---|
| `location` | koreaLocation | **예**(기본값 없음) | — | `{label, lat, lon}`. 사용자가 직접 고른다 — 아래 위치 목록 |

- **아이콘**: WMO 코드 → 8종(맑음/구름 조금/흐림/안개/비/눈/소나기/뇌우) 흑백 선 SVG(`WeatherIcons`, `WeatherCodes.category`). 선 굵기는 뷰박스 기준 3px 이상.
- **위치 목록**: `server/src/main/resources/widgets/korea-locations.json` — 대한민국 시·도 17개 + 시·군·구(+일반구 포함, 예: 성남시 분당구) 268개 = **285개**. 항목 `{sido, name, lat, lon, manual?}`. 시·도 자체를 고르는 항목은 `name: ""`.
  좌표 출처: **© OpenStreetMap contributors (ODbL)** — `server/scripts/build-korea-locations.py`가 Nominatim(`nominatim.openstreetmap.org/search`, `countrycodes=kr`, **1초에 1건 이하**, 식별 가능한 User-Agent)으로 이름마다 1회 조회해 만들었다. 대한민국 범위(33~39N, 124~132E)로 검증하고, 실패 항목은 `manual: true`로 수동 보완. 스크립트와 결과 JSON을 둘 다 커밋한다. 행정구역이 바뀌면 스크립트의 이름 목록을 고치고 다시 실행한다.
  API: `GET /api/widgets/locations` → `[{sido, name, label, lat, lon}]`(`label = sido + " " + name`, 시·도 자체 항목은 `label = sido`). 인증은 일반 `/api/**` 세션 규칙(`KoreaLocationController`, `widget/weather` 패키지).
- **데이터 출처·캐시**: `OpenMeteoWeatherProvider` — 캐시 TTL 30분, 실패 시 24시간 폴백([`weather.md`](weather.md) 5절). stale이면 "(MM-dd HH:mm 기준)" 작은 글씨를 덧붙인다.
- **실패 표시**: `errorBox(location.label, "날씨 정보를 가져오지 못했습니다")`. `location`이 아예 비어 있으면 `errorBox("오늘의 날씨", "위치가 설정되지 않았습니다")`.
- **레거시**: `WeatherLocationProvider`(`server/.../settings/`)·`SettingsController`의 `GET/PUT /api/settings`는 남아 있지만 **렌더에는 더 이상 쓰이지 않는다** — 이제 위치는 위젯 `props.location`에 들어간다. [`weather.md`](weather.md) 2절 참고.

## 4. 알려진 한계(공통)

- 서버 재기동 시 세 동적 위젯의 메모리 캐시가 전부 비워진다(PoC 수용).
- 옛 v1/v2 저장본이 위젯(블록) 20개를 초과하면 v3로 up-convert된 뒤 `MAX_WIDGETS` 검증에 걸려 **다시 저장할 때 422**가 된다(읽기·렌더는 그대로 된다 — 검증은 쓰기 경로에서만 걸린다).
