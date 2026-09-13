# 포맷 스키마 v1

> **이 문서가 포맷 스키마의 원본이다.** 앱(`/app/web` 편집기), 서버 검증기, 렌더러는 모두 이 문서를 따른다.
> 출발점은 [`../init_plan.md`](../init_plan.md) 6.1절이고, 필드별 제약·기본값은 M0에서 정한 **[기본값]**이다. M2 구현 중 바꾸면 이 문서를 먼저 고친다.

## 1. 개념

- **포맷(Format) = 카드.** 인쇄물 한 장의 설계이자 내용이다. 저장해 두고 여러 예약에서 다시 쓴다.
- 예약은 포맷을 가리킨다(예약 1개 = 포맷 1개).
- **가져오기(import)** 하면 내 라이브러리에 **새 포맷**이 생긴다(fork). 원본 출처는 `meta.forkedFrom`에 남고, 이후 자유롭게 고친다.
- **블록 쌓기**: `blocks[]`를 위에서 아래로 쌓는다. 용지가 110mm 연속 롤이라 높이는 내용 길이만큼이다.
- **허용되는 것은 JSON 블록 + 화이트리스트 스타일 속성뿐.** 임의 HTML/CSS/JS는 받지 않는다(7절).
- **단위는 mm(길이)와 pt(글자 크기).** 프린터 dpi와 무관하게 정의하고, 렌더할 때 프린터 프로필 dpi로 환산한다([`rendering.md`](rendering.md)).
- `style`(전체)과 `blocks[].style`(개별)을 **분리**한다. 나중에 "내 스타일 입히기"를 스타일 덮어쓰기로 구현하기 위해서다.

## 2. 전체 예시

```json
{
  "schemaVersion": 1,
  "meta": { "name": "아침 브리핑", "author": "justant", "description": "", "forkedFrom": null },
  "style": {
    "fontFamily": "Pretendard", "baseFontSizePt": 11, "lineHeight": 1.4,
    "marginMm": { "top": 3, "right": 3, "bottom": 8, "left": 3 },
    "blockGapMm": 3, "divider": "none"
  },
  "blocks": [
    { "type": "dateHeader", "props": { "pattern": "YYYY년 M월 D일 dddd" }, "style": { "align": "center", "fontSizePt": 16, "bold": true } },
    { "type": "text", "props": { "text": "{{date}} {{weekday}}\n오늘의 할 일" }, "style": { "align": "left" } },
    { "type": "image", "props": { "assetId": "a1b2", "widthPercent": 100 } },
    { "type": "weather", "props": { "location": "default", "fields": ["tempMin", "tempMax", "precipProb", "sky"] } }
  ],
  "assets": { "a1b2": "data:image/png;base64,..." }
}
```

`assets`는 **내보내기 파일에만** 들어간다(5절).

## 3. 루트 필드

| 필드 | 타입 | 필수 | 기본값 | 허용값·제약 |
|---|---|---|---|---|
| `schemaVersion` | integer | 필수 | — | `1` (서버가 아는 최대 버전 이하만 허용) |
| `meta` | object | 필수 | — | 3.1절 |
| `style` | object | 선택 | 3.2절 기본값 | 3.2절 |
| `blocks` | array | 필수 | — | 1~50개 [기본값] |
| `assets` | object | 가져오기 파일에서만 | — | `{assetId: dataURI}`. 일반 생성·수정 요청에 있으면 422 [기본값] |

### 3.1 `meta`

| 필드 | 타입 | 필수 | 기본값 | 제약 |
|---|---|---|---|---|
| `name` | string | 필수 | — | 1~100자 |
| `author` | string | 선택 | `""` | 0~100자 |
| `description` | string | 선택 | `""` | 0~500자 |
| `forkedFrom` | object \| null | 선택 | `null` | **서버가 가져오기 때만 채운다.** 클라이언트가 생성·수정 요청에 넣은 값은 무시하고 기존 값을 유지 [기본값] |

`forkedFrom` 형태 [기본값]:

```json
{ "name": "원본 포맷 이름", "author": "원작자", "schemaVersion": 1, "importedAt": "2026-09-14T07:00:00+09:00" }
```

v1은 **직전 출처 한 단계만** 기록한다. 원본 파일의 `forkedFrom` 체인은 버린다 [기본값].

### 3.2 `style` (전체)

| 필드 | 타입 | 기본값 | 허용값·제약 |
|---|---|---|---|
| `fontFamily` | enum | `"Pretendard"` | `"Pretendard"`, `"Noto Sans KR"` (서버 이미지에 내장된 폰트만) |
| `baseFontSizePt` | number | `11` | 6~48 |
| `lineHeight` | number | `1.4` | 1.0~3.0 (배수) |
| `marginMm` | object | `{top:3, right:3, bottom:8, left:3}` | 각 0~20 |
| `blockGapMm` | number | `3` | 0~30 |
| `divider` | enum | `"none"` | `"none"`, `"line"`, `"dashed"` — 블록 사이 구분선 |

`style` 전체가 없거나 일부 필드가 없으면 기본값으로 채운다.

## 4. 블록

### 4.1 공통 구조

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| `type` | enum | 필수 | `"text"`, `"image"`, `"dateHeader"`, `"weather"` |
| `props` | object | 필수 | 타입별(4.3절) |
| `style` | object | 선택 | 블록 스타일 화이트리스트(4.2절) |

### 4.2 블록 스타일 화이트리스트

| 필드 | 타입 | 기본값 | 허용값·제약 |
|---|---|---|---|
| `align` | enum | `"left"` (`dateHeader`는 `"center"`) [기본값] | `"left"`, `"center"`, `"right"` |
| `fontSizePt` | number | 전체 `baseFontSizePt` 상속 | 6~72 |
| `bold` | boolean | `false` | — |
| `marginTopMm` | number | `0` | 0~30 |
| `marginBottomMm` | number | `0` | 0~30 |

이 표에 없는 스타일 키는 **거부(422)** 한다. 색상, 임의 CSS, 폰트 URL은 없다(감열지는 흑백).

### 4.3 타입별 `props`

#### `text`

| 필드 | 타입 | 필수 | 기본값 | 제약 |
|---|---|---|---|---|
| `text` | string | 필수 | — | 0~5000자 [기본값]. 줄바꿈(`\n`) 보존. 텍스트 변수 사용 가능(6절) |

#### `image`

| 필드 | 타입 | 필수 | 기본값 | 제약 |
|---|---|---|---|---|
| `assetId` | string | 필수 | — | 업로드된 에셋 id(`POST /api/assets`). 외부 URL 불가 |
| `widthPercent` | integer | 선택 | `100` | 10~100. 본문 폭(용지 폭 − 좌우 여백) 대비. 가로세로 비율 유지, 정렬은 `style.align` |

흑백 변환(디더링)은 서버가 하지 않는다. 서버는 그레이스케일 PNG까지만 만들고, 디더링은 Pi 드라이버가 한다.

#### `dateHeader`

| 필드 | 타입 | 필수 | 기본값 | 제약 |
|---|---|---|---|---|
| `pattern` | string | 선택 | `"YYYY년 M월 D일 dddd"` | 0~100자. 아래 토큰만 치환, 나머지 문자는 그대로 |

토큰 [기본값]: `YYYY`(2026), `MM`(09), `M`(9), `DD`(05), `D`(5), `dddd`(월요일), `ddd`(월). 날짜는 **targetDate** 기준(6절).

#### `weather`

| 필드 | 타입 | 필수 | 기본값 | 제약 |
|---|---|---|---|---|
| `location` | string | 선택 | `"default"` | v1은 `"default"`만 허용(설정의 날씨 기본 위치) [기본값] |
| `fields` | array | 선택 | 4개 전부 | `"tempMin"`, `"tempMax"`, `"precipProb"`, `"sky"` 중 1개 이상, 중복 불가 |

데이터 출처·표시 형식·실패 시 표시는 [`weather.md`](weather.md). `weather` 블록이 하나라도 있는 포맷은 **동적 포맷**으로 보고, 렌더 스케줄러가 예약 약 60분 전에 다시 렌더한다([`rendering.md`](rendering.md)).

## 5. 가져오기 / 내보내기

### 내보내기 — `GET /api/formats/{id}/export`

- 저장된 포맷 문서에 `assets`를 붙여 반환한다. `blocks`가 참조하는 모든 `assetId`의 파일을 `data:<mime>;base64,...`로 내장한다.
- `meta.forkedFrom`은 그대로 둔다.
- 파일 이름 [기본값]: `<meta.name>.haru-format.json` (`Content-Disposition`).

### 가져오기 — `POST /api/formats/import`

본문 = 내보낸 JSON. 처리 순서:

1. JSON 파싱 실패 → 400
2. `schemaVersion` 확인: 서버가 아는 최대 버전보다 크면 → 422 `unsupported schemaVersion`
3. **엄격 검증**: 알 수 없는 필드·블록 타입·스타일 키 → 422 (필드 경로 목록 포함)
4. `assets` 검증: `blocks`가 참조하는 `assetId`가 `assets`에 없으면 422. 참조되지 않는 항목은 무시
5. 각 data URI 디코드: MIME은 `image/png`, `image/jpeg`만 [기본값], 개당 10MB 이하
6. 에셋마다 **새 `assetId`** 로 저장하고 `blocks`의 `assetId`를 새 값으로 바꾼다
7. **새 포맷 id** 발급, `meta.forkedFrom = {name, author, schemaVersion, importedAt}`(원본 파일 `meta` 기준), `assets` 필드는 저장하지 않음
8. 201 + 새 포맷 반환

`meta.name`은 원본 이름을 그대로 쓴다. 사용자가 나중에 고친다.

### 일반 생성·수정 — `POST /api/formats`, `PUT /api/formats/{id}`

- 3·4절 엄격 검증을 똑같이 적용한다. 편집본 미리보기 `POST /api/formats/preview`도 같은 검증을 한다(저장은 안 함).
- `assets` 필드가 있으면 422. 이미지는 먼저 `POST /api/assets`로 올리고 `assetId`로 참조한다.
- 존재하지 않는 `assetId` 참조 → 422.

## 6. 텍스트 변수와 targetDate

| 변수 | 치환값 [기본값] | 적용 대상 |
|---|---|---|
| `{{date}}` | `2026년 9월 14일` | `text.props.text` |
| `{{weekday}}` | `월요일` | `text.props.text` |

- 값은 **렌더 대상 날짜(targetDate, KST)** 기준이다.
  - 예약 렌더: occurrence가 속한 KST 날짜
  - 지금 인쇄·미리보기: 요청 시점의 KST 오늘(미리보기는 `?date=`로 지정 가능)
- 모르는 변수(예: `{{time}}`)는 **문자 그대로** 출력한다. 검증 오류가 아니다 [기본값].
- v1에는 이스케이프 문법이 없다.
- **알려진 한계(PoC 수용)**: Pi가 오래 오프라인이면 마지막으로 받은 렌더를 인쇄하므로 날짜·날씨가 그 렌더 시점 값으로 나온다.

## 7. 보안 — 임의 HTML/CSS/JS를 받지 않는 이유

렌더러는 서버 안의 헤드리스 Chromium이다. 서버는 운영 중인 다른 서비스들과 같은 LAN·tailnet에 있다.

- **SSRF**: 남이 만든 HTML이 `<img src="http://내부주소:포트">`, CSS `url()`, `@import`로 서버 LAN·tailnet 내부 주소(다른 컨테이너의 3000/8080 포트, DB 관리 페이지 등)에 요청을 보낼 수 있다. 응답 내용이 렌더 PNG에 찍혀 밖으로 새어 나갈 수 있다.
- **JS 실행**: 스크립트가 렌더 중 임의 동작을 할 수 있다.

그래서 v1은 다음을 지킨다.

- 포맷에 HTML/CSS/JS를 담는 필드가 없다. 텍스트는 HTML 이스케이프 후 삽입한다.
- 폰트는 내장 폰트 enum, 이미지는 업로드 에셋만(외부 URL 불가).
- 방어를 한 겹 더: 렌더러는 JS를 끄고 모든 네트워크 요청을 차단한다([`rendering.md`](rendering.md)).

HTML 템플릿 블록은 **네트워크·JS가 차단된 샌드박스가 검증된 뒤에만** 재검토한다.

## 8. 향후 확장

- **내 스타일 입히기**: 저장해 둔 스타일 객체로 가져온 포맷의 `style`(필요하면 블록 `style`)을 덮어쓴다. v1에서 `style`을 분리해 둔 이유다.
- **새 블록 타입·필드 추가 절차** [기본값]
  1. 이 문서에 표 추가
  2. `schemaVersion` 올리기(예: 2). 서버는 자기가 아는 최대 버전 이하만 받고, 더 새 버전 파일은 422로 명확히 거부
  3. 서버 검증기 + 렌더 템플릿 + (필요하면) 동적 데이터 제공자
  4. 앱 편집기
  5. 이전 버전 문서는 읽을 때 서버 코드에서 새 버전으로 올려 변환(up-convert)
- **공유 갤러리**: PoC 이후. 지금은 JSON 파일 가져오기/내보내기만.
- **날씨 위치 지정**: `location`에 좌표·라벨 객체 허용(버전업 필요).
