# 포맷 스키마 v3(위젯 그리드)

> **이 문서가 포맷 스키마의 원본이다.** 앱(`/app/web` 편집기), 서버 검증기(`FormatValidator`·`WidgetPropsValidator`), 렌더러(`HtmlTemplateBuilder`/`WidgetPageBuilder`)는 모두 이 문서를 따른다.
> 위젯 종류별 표(fields 키·kind·기본값·범위, 데이터 출처, 실패 표시)는 **[`widgets.md`](widgets.md)** 로 옮겼다 — 이 문서는 문서 전체 구조(그리드·루트·widgets[i] 봉투·up-convert·가져오기/내보내기)만 다룬다.
> **v3 도입**: 2026-09-18. 드래그 기반 레이아웃 편집기(v2 행/슬롯 모델, `.temp/04`)를 폐기하고, 안드로이드 홈 화면 위젯처럼 "미리 정해진 크기의 위젯을 4열 그리드에 놓는" 모델로 바꿨다. 결정 원본: `.temp/07-위젯그리드-작업지시서.md`(구현 완료 후 삭제됨 — 이 문서와 [`widgets.md`](widgets.md)로 옮겨졌다).
> 표기: **[확인됨]** 실물 확인 / **[확인됨·코드]** 코드에서 확인 / **[미검증]** 확인 전 / **[기본값]** 따로 묻지 않고 정한 값(바꿔도 됨)

## 1. 개념

- **포맷(Format) = 카드.** 인쇄물 한 장의 설계이자 내용이다. 저장해 두고 여러 예약에서 다시 쓴다.
- **포맷 = 위젯의 순서 목록.** 배치는 서버 코드가 조립하는 **4열 CSS 그리드**가 자동으로 한다(왼쪽→오른쪽, 넘치면 다음 줄, `grid-auto-flow: row dense`라서 앞줄에 남은 빈칸은 뒤에 오는 작은 위젯이 메운다). 사용자는 순서와 크기만 고른다 — 드래그로 자유 배치하지 않는다.
- **위젯(Widget) 1종 = 서버의 `Widget` 구현 1개**(`@Component`). 종류·허용 크기·설정 스키마(`WidgetDescriptor`)는 서버가 `GET /api/widgets`로 내려주고, 앱은 그것만 보고 카탈로그·크기 선택·설정 폼을 **자동 생성**한다 — 새 위젯을 추가해도 앱 코드를 고칠 필요가 없다. 위젯 프레임워크·구현 규칙·위젯별 표는 [`widgets.md`](widgets.md).
- **가져오기(import)** 하면 내 라이브러리에 **새 포맷**이 생긴다(fork). 원본 출처는 `meta.forkedFrom`에 남고, 이후 자유롭게 고친다.
- **허용되는 것은 JSON 위젯 목록 + 화이트리스트 스타일 속성뿐.** 임의 HTML/CSS/JS는 받지 않는다(9절).
- **단위는 mm(길이)와 pt(글자 크기).** 프린터 dpi와 무관하게 정의하고, 렌더할 때 프린터 프로필 dpi로 환산한다([`rendering.md`](rendering.md)).
- `style`은 포맷 전체에만 있다 — v2에 있던 위젯(블록)별 `style`은 없다. 위젯별 여백·정렬 같은 값은 이제 그 위젯의 `props`(각 위젯 표, [`widgets.md`](widgets.md))로 들어간다.

## 2. 그리드 규격 [기본값] — `GridSpec.java`(`server/.../widget/GridSpec.java`)가 원본

```
용지 110mm − 좌우 여백 3mm×2 = 콘텐츠 폭 104mm (300dpi·1300px 프로필에서 1230px)
4열, 칸 간격 2mm   → 1열 24.5mm / 2열 51mm / 3열 77.5mm / 4열 104mm
행 단위 12mm, 간격 2mm → r행 = 12r + 2(r−1) → 1행 12mm / 2행 26mm / 4행 54mm / 6행 82mm
```

| 크기 id | 실제 크기 | 비율 | 쓰임 |
|---|---|---|---|
| `4x1` | 104×12mm | 띠 | 날짜 머리글 |
| `4x2` | 104×26mm | 4:1 | 날씨 한 줄 요약 |
| `2x4` | 51×54mm | ≈1:1 | 날씨·증시 절반 폭(둘을 나란히) |
| `4x4` | 104×54mm | ≈2:1 | 날씨 시간대별 / 증시 넓은 차트 |
| `4x6` | 104×82mm | ≈5:4 | 증시 큰 차트 |
| `4xauto` | 104×내용 길이 | — | 아침편지·텍스트·이미지처럼 길이가 그날그날 다른 콘텐츠. **자동 높이는 항상 4열**(고정 높이 위젯과 한 줄에 섞이면 빈 공간이 생겨서 막았다) |

- 이 값은 프린터 상수가 아니다 — 실제 폭(px)은 항상 프린터 프로필(`printableWidthPx`, `dpi`)에서 계산한다(`GridSpec.mmToPx`).
- 고정 크기 위젯은 내용이 넘치면 잘린다(`contain: size; overflow: hidden`, `WidgetPageBuilder`의 `.w-fixed`). 옆 위젯 때문에 그 행의 높이가 늘어날 수는 있으므로 위젯 루트는 항상 `width:100%; height:100%`.
- `GET /api/widgets` 응답의 `grid` 필드가 `{columns, rowUnitMm, gapMm, maxWidgets}`로 이 값을 그대로 내려준다(6절). 앱 배치도(`widget-editor/gridMath.ts`)는 콘텐츠 폭 104mm을 자기 쪽에도 상수로 갖고 있다(서버가 폭·여백을 내려주지 않으므로) — 서버가 그리드 규격이나 기본 여백을 바꾸면 그 상수도 같이 바꿔야 한다.

## 3. 포맷 문서 v3

```json
{
  "schemaVersion": 3,
  "meta": { "name": "아침 브리핑", "author": "", "description": "", "forkedFrom": null },
  "style": { "fontFamily": "Pretendard", "baseFontSizePt": 11, "lineHeight": 1.4,
             "marginMm": { "top": 3, "right": 3, "bottom": 8, "left": 3 }, "blockGapMm": 3, "divider": "none" },
  "widgets": [
    { "id": "uuid-1", "type": "dateHeader",    "size": "4x1",    "props": { "pattern": "YYYY년 M월 D일 dddd" } },
    { "id": "uuid-2", "type": "morningLetter", "size": "4xauto", "props": { "showComment": true, "fontSize": "normal" } },
    { "id": "uuid-3", "type": "weather",       "size": "2x4",    "props": { "location": { "label": "경기도 성남시 분당구", "lat": 37.3827, "lon": 127.1189 } } },
    { "id": "uuid-4", "type": "stockChart",    "size": "2x4",    "props": { "ticker": "AAPL", "days": 14 } }
  ]
}
```

`assets`는 **내보내기 파일에만** 들어간다(7절).

### 3.1 루트 필드

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `schemaVersion` | integer | 필수 | **쓰기(생성·수정·편집본 미리보기)는 3만** 허용. 가져오기(import)는 1·2·3 허용(1·2는 검증 전에 v3로 변환, 4절). 4 이상 → 422 |
| `meta` | object | 필수 | v2와 동일 — 3.1.1절 |
| `style` | object | 선택 | 3.1.2절 |
| `widgets` | array | 필수 | **1~20개**(`GridSpec.MAX_WIDGETS`) — 3.2절 |
| 그 밖의 키(`rows`, `blocks` 포함) | — | — | 422. `assets`는 import에서만(7절) |

#### 3.1.1 `meta`

v2와 필드·제약이 동일하다.

| 필드 | 타입 | 필수 | 기본값 | 제약 |
|---|---|---|---|---|
| `name` | string | 필수 | — | 1~100자 |
| `author` | string | 선택 | `""` | 0~100자 |
| `description` | string | 선택 | `""` | 0~500자 |
| `forkedFrom` | object \| null | 선택 | `null` | **서버가 가져오기 때만 채운다.** 클라이언트가 생성·수정 요청에 넣은 값은 무시하고 기존 값을 유지 [기본값] |

`forkedFrom` 형태(변경 없음):

```json
{ "name": "원본 포맷 이름", "author": "원작자", "schemaVersion": 3, "importedAt": "2026-09-18T07:00:00+09:00" }
```

#### 3.1.2 `style` (전체 스타일)

| 필드 | 타입 | 기본값 | 허용값·제약 | v3에서 |
|---|---|---|---|---|
| `fontFamily` | enum | `"Pretendard"` | `"Pretendard"`, `"Noto Sans KR"` | 그대로 씀 |
| `baseFontSizePt` | number | `11` | 6~48 | 그대로 씀. `WidgetRenderContext.baseFontSizePt`로 위젯에 전달, 각 위젯이 상대 크기를 잡는 기준 |
| `lineHeight` | number | `1.4` | 1.0~3.0 (배수) | 그대로 씀 |
| `marginMm` | object | `{top:3, right:3, bottom:8, left:3}` | 각 0~20 | 그대로 씀. 콘텐츠 폭(2절) 계산에 쓰임 |
| `blockGapMm` | number | `3` | 0~30 | **받되 무시**(v3 그리드 간격은 `GridSpec.GAP_MM = 2mm` 고정) — 범위 자체는 계속 검증한다(호환 필드라고 아무 값이나 통과시키지는 않는다) |
| `divider` | enum | `"none"` | `"none"`, `"line"`, `"dashed"` | **받되 무시**(v3에는 위젯 사이 공통 구분선이 없다 — 필요한 위젯은 스스로 그린다, 예: `morningLetter`) |

`style` 전체가 없거나 일부 필드가 없으면 기본값으로 채운다(`FormatStyle.withDefaults`).

### 3.2 `widgets[i]`

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `id` | string | 필수 | 1~64자, 문서 안에서 유일 |
| `type` | string | 필수 | `WidgetRegistry`에 등록된 type. 모르는 값 → 422. `catalog=false` 위젯도 허용(호환용, [`widgets.md`](widgets.md) 3절) |
| `size` | string | 필수 | 그 위젯 `descriptor.sizes[].id` 중 하나. 아니면 422 |
| `props` | object | 필수(빈 객체 가능) | 키는 `descriptor.fields[].key`뿐 — 모르는 키 → 422. 값은 kind별 규칙(3.3절) + `required`. 통과하면 `Widget.validateProps()`가 위젯 고유 규칙을 추가 검증 |
| 그 밖의 키 | — | — | 422 |

### 3.3 `props` 값 검증 (`WidgetPropsValidator`)

kind별 검증 규칙(각 kind의 필드 정의는 `PropField.java` javadoc이 원본):

| kind | 값 형태 | 검증 |
|---|---|---|
| `string` | 문자열 | `maxLength`, `pattern`(정규식, **전체 일치**) |
| `text` | 문자열(여러 줄) | `maxLength`만(`pattern`은 적용 안 함) |
| `integer` | 정수(Number이고 소수부 없음) | `min`~`max` |
| `boolean` | true/false | — |
| `enum` | 문자열 | `options[].value` 중 하나 |
| `koreaLocation` | `{label, lat, lon}` | `label`: 1~50자, `lat`: 33.0~39.0, `lon`: 124.0~132.0(대한민국 범위), 그 밖의 키 거부 |
| `asset` | 문자열(에셋 id) | `AssetRepository.existsById` |

- `required=true`인데 값이 없거나(`null`) 빈 문자열이면 오류.
- 오류 경로는 `widgets[2].props.ticker` 형식([`api.md`](api.md) 4절 예시).
- 필드 하나하나의 타입·범위 검증이 전부 통과했을 때만 `Widget.validateProps()`(위젯 필드 사이 관계처럼 스키마로 표현 못 하는 규칙)를 추가로 부른다.

## 4. Up-Convert (v1·v2 → v3)

2026-09-18부터 저장되는 모든 포맷은 schemaVersion 3이다. 그 이전에 저장된 v1(`blocks[]`)·v2(`rows[].slots[].block`) 포맷을 **읽을 때** 자동으로 v3로 변환한다(`FormatDocumentSupport.readDocument()`).

### 4.1 변환 규칙

| 원본 | v3 결과 |
|---|---|
| v1 `blocks[]` / v2 `rows[].slots[].block` | 문서 순서대로 펼쳐 위젯 1개씩(2슬롯 행도 그냥 차례로). `id`는 새 uuid — **v2는 slot id를 재사용**한다(이미 문서 안에서 유일한 id가 있었으므로) |
| `text` | `{type:"text", size:"4xauto", props:{text, align?, fontSizePt?, bold?}}` — 블록 `style`의 `align`·`fontSizePt`(정수로 반올림)·`bold`를 props로 옮긴다. `marginTopMm`/`marginBottomMm`는 버린다 |
| `dateHeader` | `{type:"dateHeader", size:"4x1", props:{pattern?, align?, fontSizePt?, bold?}}` |
| `image` | `{type:"image", size:"4xauto", props:{assetId?, widthPercent?, align?}}` |
| `weather` | `{type:"weather", size:"4x2", props:{location:{label:"서울",lat:37.5665,lon:126.9780}}}` — **옛 블록에는 위치가 없었다**(항상 서울시청 좌표로 렌더되던 버그, [확인됨·코드] — `WeatherLocationProvider.getCurrent()`가 사용자 설정을 무시하고 항상 서울시청을 돌려주고 있었다). up-convert도 같은 값을 넣어 결과가 바뀌지 않게 한다. 옛 `fields`(표시 항목 체크박스)는 버린다 |
| 알 수 없는 블록 type | 버린다(로그 WARN) |
| 빈 결과(위젯 0개) | `text` 위젯 1개(`props.text=""`)를 넣어 "1개 이상" 규칙을 지킨다 |

### 4.2 실행 시점

- **조회(GET)·렌더·미리보기**: `FormatService`, `RenderScheduler`, `RenderServiceImpl.doRender` 등 저장된 `body`를 읽는 모든 경로가 `FormatDocumentSupport.readDocument()`를 거친다.
  **`RenderServiceImpl.doRender`가 한때 `objectMapper.readValue(body, FormatDocument.class)`로 직접 읽던 버그가 있었다** — v1·v2 저장본의 예약 렌더가 전부 실패했을 것이다. v3 전환과 함께 `readDocument()`로 고쳤다[확인됨·코드].
- **내보내기**: `exportFormat()` — 내보낸 파일은 항상 v3.
- **가져오기(import)**: `FormatDocumentSupport.upConvertRawImportIfNeeded()`가 검증(schemaVersion==3만 허용) **전에** raw JSON(Map)을 v3로 올려 변환한다. `assets` 키는 보존한다.
- **검증(쓰기)**: 생성·수정·편집본 미리보기는 항상 v3만 허용한다.

### 4.3 일괄 마이그레이션 없음 [기본값]

DB에 저장된 `formats.body`는 원래 형태(v1 또는 v2 JSON 문자열) 그대로 남아 있다. up-convert는 read-time에만 일어나므로 DB 변경이 없다. 처음 읽힌 뒤 다시 저장되면 그 시점에 v3로 직렬화된다([`data-model.md`](data-model.md) `formats.schema_version`).

## 5. 이전 버전 요약(역사 참고용)

| 버전 | 도입 | 배치 모델 | 위젯(블록) 종류 | 폐기 |
|---|---|---|---|---|
| v1 | M2 | 평평한 `blocks[]` 배열 | `text`, `image`, `dateHeader`, `weather`(위치 없음) | 2026-09-16 |
| v2 | 2026-09-16 | 행(row) 1~30개, 각 행 슬롯 1~2개(`1/1` 또는 `1/2`+`1/2`/`2/3`+`1/3`/`1/3`+`2/3`) | 위와 동일 4종, 블록마다 `style`(align/fontSizePt/bold/marginTopMm/marginBottomMm) | 2026-09-18 |
| v3 | 2026-09-18 | 4열 CSS 그리드, 위젯 목록(순서만) | 확장 가능(`Widget` 구현 @Component 하나로 추가) — 지금 6종, [`widgets.md`](widgets.md) | (현재) |

v2 문서의 세부 규칙(행/슬롯 구조, 슬롯 폭 조합, 블록 스타일 화이트리스트)은 이 문서에는 더 남기지 않는다 — 필요하면 git 이력(`docs/server/format-schema.md`의 이 파일이 v3로 재작성되기 전 버전)을 본다.

## 6. 위젯 카탈로그 API

`GET /api/widgets`가 위젯 종류·크기·설정 스키마를 내려준다. 응답 형태와 위젯별 표는 [`widgets.md`](widgets.md) 2절. 날씨 위젯이 쓰는 대한민국 시·군·구 목록은 `GET /api/widgets/locations`([`widgets.md`](widgets.md) 5.3절, [`api.md`](api.md)).

## 7. 가져오기 / 내보내기

### 내보내기 — `GET /api/formats/{id}/export`

- 저장된 포맷 문서(v3로 변환된 상태)에 `assets`를 붙여 반환한다. `widgets[]`가 참조하는 모든 `assetId`(현재는 `image` 위젯뿐)의 파일을 `data:<mime>;base64,...`로 내장한다.
- `meta.forkedFrom`은 그대로 둔다.
- 파일 이름 [기본값]: `<meta.name>.haru-format.json` (`Content-Disposition`).

### 가져오기 — `POST /api/formats/import`

본문 = 내보낸 JSON(**v1·v2·v3 허용**). 처리 순서:

1. JSON 파싱 실패 → 400
2. `schemaVersion` 확인: 1·2·3만 허용(4 이상 → 422)
3. v1·v2면 검증 전에 v3로 up-convert(4절)
4. **엄격 검증**: 알 수 없는 필드·위젯 type·props 키 → 422(필드 경로 목록 포함)
5. `assets` 검증: `widgets[]`의 모든 `image` 위젯이 참조하는 `assetId`가 `assets`에 없으면 422. 참조되지 않는 항목은 무시
6. 각 data URI 디코드: MIME은 `image/png`, `image/jpeg`만 [기본값], 개당 10MB 이하
7. 에셋마다 **새 `assetId`**로 저장하고 `widgets[]`의 모든 `image` 위젯의 `assetId`를 새 값으로 바꾼다
8. **새 포맷 id** 발급, `meta.forkedFrom = {name, author, schemaVersion(원본 값), importedAt}`(원본 파일 `meta` 기준), `assets` 필드는 저장하지 않음
9. 201 + 새 포맷 반환

`meta.name`은 원본 이름을 그대로 쓴다. 사용자가 나중에 고친다.

### 일반 생성·수정 — `POST /api/formats`, `PUT /api/formats/{id}`

- 3절 검증을 그대로 적용한다. 편집본 미리보기 `POST /api/formats/preview`도 같은 검증을 한다(저장은 안 함).
- `assets` 필드가 있으면 422. 이미지는 먼저 `POST /api/assets`로 올리고 `assetId`로 참조한다.
- 존재하지 않는 `assetId` 참조 → 422.
- **schemaVersion은 반드시 3이어야 한다.**

## 8. 텍스트 변수와 targetDate

| 변수 | 치환값 [기본값] | 적용 대상 |
|---|---|---|
| `{{date}}` | `2026년 9월 14일` | `text` 위젯의 `props.text` |
| `{{weekday}}` | `월요일` | `text` 위젯의 `props.text` |

- 값은 **렌더 대상 날짜(targetDate, KST)** 기준이다.
  - 예약 렌더: occurrence가 속한 KST 날짜
  - 지금 인쇄·미리보기: 요청 시점의 KST 오늘(미리보기는 `?date=`로 지정 가능)
- 모르는 변수(예: `{{time}}`)는 **문자 그대로** 출력한다. 검증 오류가 아니다 [기본값].
- `dateHeader` 위젯의 `props.pattern` 토큰(`YYYY`/`MM`/`DD`/`M`/`D`/`dddd`/`ddd`)은 별도 규칙 — [`widgets.md`](widgets.md) `dateHeader` 절.
- **알려진 한계(PoC 수용)**: Pi가 오래 오프라인이면 마지막으로 받은 렌더를 인쇄하므로 날짜·날씨가 그 렌더 시점 값으로 나온다.

## 9. 보안 — 임의 HTML/CSS/JS를 받지 않는 이유

렌더러는 서버 안의 헤드리스 Chromium이다. 서버는 운영 중인 다른 서비스들과 같은 LAN·tailnet에 있다.

- **SSRF**: 남이 만든 HTML이 `<img src="http://내부주소:포트">`, CSS `url()`, `@import`로 서버 LAN·tailnet 내부 주소(다른 컨테이너의 3000/8080 포트, DB 관리 페이지 등)에 요청을 보낼 수 있다. 응답 내용이 렌더 PNG에 찍혀 밖으로 새어 나갈 수 있다.
- **JS 실행**: 스크립트가 렌더 중 임의 동작을 할 수 있다.

v3도 v1·v2와 같이 다음을 지킨다.

- 포맷에 HTML/CSS/JS를 담는 필드가 없다. 위젯이 만드는 텍스트는 전부 `WidgetHtml.escape`를 거쳐 삽입한다([`widgets.md`](widgets.md) `Widget` 구현 규칙 ①).
- 폰트는 내장 폰트 enum, 이미지는 업로드 에셋만(외부 URL 불가). 위젯이 부르는 외부 API는 서버 코드에 **고정된 호스트**만 허용한다(`godowon.com`, `*.finance.yahoo.com`, `api.open-meteo.com`) — 사용자 입력(예: 증시 티커)은 정규식 검증을 통과한 값만 URL 경로에 들어간다([`widgets.md`](widgets.md) 4절 D5).
- 방어를 한 겹 더: 렌더러는 JS를 끄고 모든 네트워크 요청을 차단한다([`rendering.md`](rendering.md)).

HTML 템플릿 포맷(사용자가 직접 HTML을 쓰는 것)은 **네트워크·JS가 차단된 샌드박스가 검증된 뒤에만** 재검토한다.

## 10. 향후 확장

- **내 스타일 입히기**: 저장해 둔 스타일 객체로 가져온 포맷의 `style`을 덮어쓴다.
- **새 위젯 추가 절차**: 앱을 고치지 않는다 — [`widgets.md`](widgets.md) "새 위젯 추가 절차" 절이 원본이다.
- **스키마 버전을 또 올려야 하는 경우**(그리드 자체의 모양을 바꾸는 등) [기본값]
  1. 이 문서에 절 추가, `schemaVersion` 올리기(예: 4). 서버는 자기가 아는 최대 버전 이하만 받고, 더 새 버전 파일은 422로 거부
  2. 서버 검증기 + 렌더러
  3. 앱(위젯 카탈로그 기반이라면 대체로 무수정)
  4. 이전 버전 문서는 읽을 때 서버 코드에서 새 버전으로 올려 변환(up-convert)
- **공유 갤러리**: PoC 이후. 지금은 JSON 파일 가져오기/내보내기만.
