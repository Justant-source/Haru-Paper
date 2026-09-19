# 위젯 그리드 편집기 (`app/web/src/widget-editor/`)

> 담당: 서버 세션 / 포맷 스키마 v3(위젯 그리드) 편집기. **2026-09-18에 드래그 편집기(v2 행/슬롯, dnd-kit, `app/web/src/format-editor/`)를 폐기하고 대체했다** — 원본 계획은 `.temp/07-위젯그리드-작업지시서.md`(구현 완료 후 삭제됨, 이 문서로 옮겨졌다). 옛 드래그 편집기 계획(`.temp/04`)도 삭제됨.
> 표기: **[확인됨·코드]** 실제 코드에서 확인 / **[미검증]** 코드는 있으나 브라우저 실사 전

## 1. 데이터 모델 — schemaVersion 3

원본: `app/web/src/types/format.ts`, `app/web/src/types/widget.ts`. 서버 스키마([`../server/format-schema.md`](../server/format-schema.md), [`../server/widgets.md`](../server/widgets.md))와 필드명을 그대로 맞춘다.

```
FormatDocument { schemaVersion: 3, meta, style?, widgets: WidgetInstance[] }
WidgetInstance { id, type: string, size: string, props: Record<string, unknown> }
```

- 위젯 종류·허용 크기·`props` 스키마는 **코드에 하드코딩하지 않는다** — `GET /api/widgets`가 돌려주는 `WidgetCatalog`(`{grid, widgets: WidgetDescriptor[]}`)만 보고 카탈로그·크기 선택·설정 폼을 그린다. 새 위젯이 서버에 추가돼도 이 앱 코드는 고칠 필요가 없다.
- `WidgetDescriptor.fields[]`(`PropField`)의 `kind`(`string`/`text`/`integer`/`boolean`/`enum`/`koreaLocation`/`asset`)가 설정 폼 입력기 종류를 정한다(6절).
- `newWidgetInstance(descriptor, overrides?)` — descriptor의 기본 크기·기본 props로 새 위젯 인스턴스를 만든다(`types/widget.ts`).
- `missingRequiredFields(descriptor, widget)` — 필수인데 비어 있는 필드 목록. 위젯을 추가한 직후 또는 저장 전에 이 목록이 비어 있는지 확인해 설정 시트를 열지 판단한다.

## 2. 컴포넌트 경계 (`app/web/src/widget-editor/`)

| 파일 | 역할 |
|---|---|
| `PlacementGrid.tsx` | **서버와 같은 4열 CSS 그리드**(`grid-auto-flow: row dense`)로 위젯을 상자로 그린다. 서버 렌더러(`WidgetPageBuilder`)도 같은 dense 배치를 쓰므로 배치도와 실제 인쇄 결과의 위젯 위치가 일치한다. 상자를 누르면 그 위젯 설정 시트를 연다 |
| `gridMath.ts` | 배치도 치수 계산(`useGridMetrics`) — 컨테이너 실측 폭(`ResizeObserver`)에 맞춰 칸 간격·행 높이를 px로 계산한다. 콘텐츠 폭 104mm은 이 파일 안의 상수(`CONTENT_WIDTH_MM`)다 — 서버가 그리드 규격·기본 여백을 바꾸면 이 값도 같이 고쳐야 한다(`GET /api/widgets`의 `grid`는 `columns`/`rowUnitMm`/`gapMm`/`maxWidgets`만 주고 폭(mm)은 안 준다) |
| `WidgetBox.tsx` | 배치도 상자 1개 — 아이콘·이름·요약(`widgetSummary.ts`), 필수값 누락·검증 오류 표시 |
| `WidgetIcon.tsx` | `descriptor.icon`(`letter`\|`chart`\|`weather`\|`calendar`\|`text`\|`image`) → SVG. 모르는 값이면 기본 아이콘 |
| `widgetSummary.ts` | 배치도 상자·목록 카드에 보일 한 줄 요약을 위젯 `props`에서 뽑는다(예: `AAPL · 14일`, `성남시 분당구`) |
| `WidgetList.tsx` | 위젯 목록(순서 = 인쇄 순서). 카드마다 ▲▼(순서 이동) · 설정 · 삭제 — **드래그 없음** |
| `AddWidgetSheet.tsx` | `+` 위젯 추가 → 바텀시트 카탈로그. `catalog=true`인 위젯만 보여준다(아이콘·이름·설명·크기 칩) |
| `SizeSelector.tsx` | 위젯 설정 시트의 크기 선택(세그먼트/카드, `descriptor.sizes[].label` 표시) |
| `WidgetSettingsSheet.tsx` | 크기 선택 + `descriptor.fields`로 **자동 생성한 폼**. `catalog=false`이거나 카탈로그에 없는 type(이전 버전 포맷의 위젯)은 "이전 버전 위젯" 카드로 보여 주고 삭제·순서 이동만 허용한다 |
| `fields/FieldRenderer.tsx` | `field.kind`에 따라 입력기를 고르는 스위치(6절) |
| `fields/{String,Integer,Boolean,Enum,KoreaLocation,Asset,Unsupported}Field.tsx` | kind별 입력기. `AssetField`는 `field.kind==='asset'`에만 반응하고 `descriptor.type`(위젯 종류)은 보지 않는다(2026-09-19 추가) |
| `fields/LocationPickerSheet.tsx` | `koreaLocation` 필드 전용 — `GET /api/widgets/locations`(285개)를 받아 앱에서 검색(공백 무시·대소문자 무시 부분 일치)·시·도별 묶음으로 보여주고, 고르면 `{label, lat, lon}`을 저장한다 |
| `PreviewSection.tsx` | 서버 실제 렌더(`formatsApi.previewEphemeralBlob`)로 미리보기 — 1초 디바운스. 위젯 0개거나 필수값이 빈 위젯이 있으면 호출하지 않는다. 접기 가능 섹션으로 화면에 항상 보인다 |
| `useWidgetActions.ts` | 위젯 목록을 바꾸는 조작(추가·순서·크기·값 변경·삭제)을 한데 모은 훅. `FormatEditPage`에서 분리해 길이를 줄였다 |
| `fieldErrors.ts` | 서버 422의 `errors[].path`(`widgets[2].props.ticker`)를 **위젯 id 기준**으로 옮긴다(`mapWidgetFieldErrors`). 저장 요청을 보낸 시점의 위젯 id 순서(`submittedIds`)로 index → id 변환 |
| `FormatEditHeader.tsx` / `FormatEditLoadingState.tsx` / `FormatEditSheets.tsx` | 헤더(뒤로·저장 상태·저장 버튼) / 로딩·오류 화면 / 시트 모음(추가·설정·나가기 확인)을 페이지 본문에서 분리 |

`FormatEditPage.tsx`(`app/web/src/pages/`)가 이 조각들을 조립한다 — dnd-kit 기반이던 `LayoutEditor`/`LayoutCanvas`/`WidgetPalette` 같은 단일 reducer 구조는 없다.

## 3. 문서 상태 관리 (`FormatEditPage.tsx`)

- **"로드/템플릿 데이터를 effect로 복사"하지 않는다** — `draft`(사용자가 실제로 편집한 값)만 React state로 들고, 편집 전에는 서버 응답·템플릿을 그대로 파생해서 쓴다: `document = draft ?? (id ? 서버 응답 : 템플릿)`.
- 그래서 로드 완료 시점에 `setState`를 맞출 필요가 없고, 저장 직후 URL이 `/formats/new`에서 `/formats/:id/edit`로 바뀌어도(같은 컴포넌트 인스턴스) `draft`가 이미 서버 응답을 담고 있어 화면이 깜빡이지 않는다.
- 새 포맷은 쿼리 `?template=morning|empty`를 읽어 `buildTemplate()`(`lib/format-templates.ts`)로 초기 위젯 목록을 만든다 — 목록 화면(`FormatListPage`)은 저장하지 않고 이 쿼리로 이동만 시킨다(4절).
- 저장(`handleSave`) → 신규면 `formatsApi.create`, 기존이면 `formatsApi.update` — **자동 저장 없음**, 명시적 저장 버튼만. 저장 성공 시 `draft`를 서버가 돌려준 문서로 갱신하고(`setDirty(false)`), react-query 캐시(`format-detail`, `formats`)도 맞춰 둔다.
- 저장 안 한 변경이 있으면(`dirty`) 나갈 때(뒤로가기 버튼·브라우저 `beforeunload`) 확인 시트를 연다.

## 4. 화면 동작 (위→아래)

1. 헤더: 뒤로 · 저장 상태 · **저장** 버튼(`FormatEditHeader`).
2. 포맷 이름 입력(필수, 비어 있으면 저장 전 422로 잡는다) · 설명 입력(접힌 상태, `<details>`).
3. **배치도**(`PlacementGrid`) — 위젯이 0개면 "아직 위젯이 없습니다" 안내. 아니면 4열 그리드에 위젯 상자를 그린다. 자동 높이(`4xauto`) 위젯은 `descriptor.sizes[].previewRows`행으로 그린다.
4. **위젯 목록**(`WidgetList`, 순서 = 인쇄 순서) — 카드마다 ▲▼로 순서 이동, 설정 시트 열기, 삭제.
5. **＋ 위젯 추가**(`AddWidgetSheet`) — 카탈로그(`catalog=true`)에서 고르면 기본 크기·기본 props로 추가하고(`useWidgetActions.handleAddWidget`), 필수값이 비어 있으면(`missingRequiredFields`) 바로 그 위젯의 설정 시트를 연다.
6. **설정 시트**(`WidgetSettingsSheet`) — 크기 선택 + `descriptor.fields`로 자동 생성한 폼(6절).
7. **미리보기**(`PreviewSection`) — 서버 실제 렌더, 1초 디바운스. 위젯이 0개거나 필수값이 빈 위젯이 있으면 호출하지 않고 안내문("위젯을 추가하면 미리보기가 나타납니다" 등)을 보여준다. 422면 필드 오류를 해당 위젯 카드에 표시(`fieldErrors.ts`).
8. 저장 후(기존 포맷일 때만) **"이 포맷으로 예약하기"**(`/schedules?formatId=<id>`) · **"지금 인쇄"**(`/print-now?formatId=<id>`) 버튼.

## 5. 새 포맷 만들기 흐름 (`FormatListPage` → `FormatEditPage`)

서버가 위젯 1개 이상을 요구하므로, **목록 화면은 아무것도 저장하지 않는다** — 시트에서 템플릿을 고르면 편집 화면으로 쿼리와 함께 이동만 시키고, 첫 저장은 편집 화면이 한다(3절).

| 템플릿 | 조합 | 이동 |
|---|---|---|
| 아침 브리핑(추천) | `dateHeader 4x1` + `morningLetter 4xauto` + `weather 2x4`(위치 비움) + `stockChart 2x4`(`ticker: AAPL`) | `/formats/new?template=morning` |
| 빈 포맷 | 위젯 0개 | `/formats/new?template=empty`(=`/formats/new`) |

`buildTemplate(name, catalog)`(`app/web/src/lib/format-templates.ts`)가 조립 함수다 — 카탈로그에 없는 위젯 type은 조용히 건너뛴다(서버에서 위젯이 빠져도 앱이 깨지지 않게).

## 6. 필드 kind → 입력기 매핑 (`FieldRenderer.tsx`)

| kind | 입력기 | 비고 |
|---|---|---|
| `string` | `<input type="text">`(`StringField`) | `field.pattern`이 `^[A-Z`로 시작하면 입력을 자동 대문자화(예: `stockChart.ticker`) — 소문자는 서버가 422로 거부한다 |
| `text` | `<textarea>`(같은 `StringField`, `field.kind==='text'`) | 여러 줄 |
| `integer` | 숫자 입력(`IntegerField`) | `min`/`max` |
| `boolean` | Toggle(`BooleanField`) | — |
| `enum` | 세그먼트/select(`EnumField`) | `field.options[].label` 표시 |
| `koreaLocation` | 검색 가능한 시·군·구 선택기(`KoreaLocationField` → `LocationPickerSheet`) | `widgetsApi.locations()`(285개)를 앱에서 검색 |
| `asset` | 업로드·미리보기·교체(`AssetField`, 2026-09-19 추가) | `POST /api/assets`로 업로드 → `assetId`를 값으로 저장, `assetsApi.thumbnailUrl(assetId)`로 미리보기(`GET /api/assets/{id}`, 소유자만). 값 없으면 점선 플레이스홀더. 413·415는 서버 응답으로, 10MB·PNG/JPEG 제한은 클라이언트에서도 사전 검사(편의용, 판정 원본은 서버). 값이 없어도 오류 없이 통과하던 이전엔 `image` 위젯이 `catalog=false`였다 — 지금은 `catalog=true`([`server/widgets.md`](../server/widgets.md) 3.3절) |

## 7. 저장 차단 조건·422 매핑

- **저장 버튼 비활성화**: 위젯 0개, 또는 필수값이 빈 위젯이 1개 이상 있으면 저장을 막고 이유를 화면에 나열한다(`blockedReasons`).
- **미리보기 차단**: 위와 같은 조건(`canPreview`) — 서버 렌더 호출 자체를 아끼기 위해서다(위젯이 없거나 필수값이 없으면 어차피 422다).
- **422 매핑**: 서버 응답 `errors[].path`가 `widgets[i].props.<key>` 형식이면 저장 요청을 보낸 시점의 위젯 id 순서(`submittedIds`)로 `i` → 위젯 id를 되짚어(`fieldErrors.ts`) 그 위젯 카드·설정 시트에 메시지를 띄운다. `widgets[i]`(size 등 필드 지정 없는 오류)는 `_general`에 담는다. `meta.name` 오류는 이름 입력 칸 옆에 별도 표시.

## 8. CSS·i18n

- 새 CSS는 `app/web/src/index.css`에 위젯 그리드 전용 클래스로 다시 썼다(옛 `.layout-*`, `.widget-palette*` 등 드래그 편집기 규칙은 지웠다). 다크/라이트 테마 변수(`lib/theme.ts`)는 그대로 따른다.
- `@dnd-kit/*` 의존성은 제거됐다(`package.json`).
- 문구는 아직 `useI18n()`의 `t(key)`를 전면 적용하지 않았다 — 이 화면의 새 문구 다수가 한국어 리터럴로 직접 쓰여 있다(예: "위젯 추가", "포맷 이름"). 옛 드래그 편집기 시절의 "하드코딩된 한국어 문자열 없음" 원칙은 이번 재작성에서 전부 지켜지지는 않았다 — i18n 정리는 이 문서 범위 밖(후속 과제).

## 9. 알려진 근사치·한계

- `PlacementGrid`는 위젯 배치(어느 칸에 어떤 크기로 놓이는지)는 서버와 동일 CSS로 정확히 일치하지만, **위젯 안쪽 내용**(글자 굵기·줄바꿈·실제 데이터)은 그리지 않는다 — 상자 안에는 아이콘·이름·요약만 보인다. 인쇄물과 픽셀 단위로 같은 것을 보려면 항상 "미리보기"(서버 PNG)를 본다.
- 위치 검색(`LocationPickerSheet`)은 공백 무시·대소문자 무시 **부분 일치**만 지원한다(초성 검색 등은 없음).
- 자동 대문자화(`StringField`의 `^[A-Z` 패턴 감지)는 `pattern` 문자열의 접두사만 보는 휴리스틱이다 — 새 위젯이 다른 형태의 "대문자 전용" 정규식을 쓰면 이 규칙에 안 걸릴 수 있다(그래도 서버 검증은 항상 정확하다, 앱은 입력 편의일 뿐).

## 10. 검증 상태 (2026-09-18)

- 서버 단위 테스트 195개 통과(미리보기·실측 전용 7개는 기본 스킵), 앱 `npm run build`(`tsc -b && vite build`)·`npm run lint`(`oxlint`) 통과.
- 임시 로컬 스택(임시 MariaDB + jar + vite)에서 Playwright로 앱 화면(390px 폭)을 열어 가입 → 위젯 카탈로그 로드 → "아침 브리핑" 템플릿으로 포맷 생성 → 위치·티커 설정 → 실제 데이터(아침편지·분당구 날씨·AAPL 14봉)로 미리보기 렌더까지 흐름 전체가 통과했다[확인됨, 2026-09-18].
- **2026-09-18 운영 스택에 배포 완료**[확인됨·실물]([`../server/deploy.md`](../server/deploy.md) 5절 절차대로 재빌드·재기동, `e2e-smoke.sh` 65 PASS). 이 화면에서 만든 포맷이 **실제 프린터로 인쇄된 결과**는 아직 [미검증]이다.

## 11. 참고: Phomemo 공식 앱 UX 벤치마킹 (2026-09-19)

Phomemo 공식 Android 앱(`com.quyin.phomemo` 4.1.0)을 구조·흐름 파악 목적으로 디컴파일
분석한 결과에서, 이 편집기와 관련 있는 패턴만 옮겨 적는다. **원본 이미지·코드는 옮기지
않았고**, 전체 분석(레이아웃 995개, 화면 흐름 mermaid, 화면 목록)은 이 저장소 밖
`~/Data/Phomemo-App/`(비공개 로컬 폴더, 타사 앱 리버스엔지니어링 산출물이라 공개
저장소인 이 repo에는 포함하지 않음)의 `layouts-summary.md`·`navigation-flow.md`·
`app-reference-summary.md`에 있다.

| # | 패턴 | 근거 화면 | 하루종이에 적용한다면 |
|---|---|---|---|
| 1 | **카드 선택 + 상태 기반 CTA** — 연결 방법(BT/WiFi) 카드를 탭해야 "확인" 버튼이 활성화 | `connect_way_activity.xml` | 기기 등록 화면에서 "용지 장착됨" 토글처럼 선행 조건이 있는 액션에 재사용 가능(현재 텍스트 버튼 대신 카드+비활성 상태 패턴 검토) |
| 2 | **문제해결을 다이얼로그로 먼저, 상세는 별도 화면** — 용지 부족 시 모달로 먼저 뜨고 "해결 방법 보기"를 눌러야 4단계 전체화면 안내 | `lack_paper_solution_dialog.xml`→`_activity.xml` | **적용됨**[확인됨·코드, 2026-09-19] — `PrintBlockedBanner.tsx`. 배너(다이얼로그 격)는 가장 위험한 문제 1개만, "해결 방법 보기"를 눌러야 시트(상세)에서 전체 원인·단계·용지 확인형 버튼을 보여준다. `docs/app/screens.md` (1)(3)절 |
| 3 | **기기 정보 화면 = 라벨-값 리스트 + 구분선** — 이름/배터리/버전/일련번호/MAC을 동일 스타일로 나열 | `device_manage_activity.xml` | 하루종이 기기 상세 화면(있다면)에서 Pi 상태·마지막 폴링 시각·프린터 프로필(model/dpi/paperWidthMm)을 같은 패턴으로 |
| 4 | **캔버스 + 하단 도구탭 전환 편집기** — 라벨 편집기가 캔버스는 고정, 도구(텍스트/바코드/프레임/너비/길이/인쇄설정)를 하단 프래그먼트로 전환 | `label_edit_activity.xml`+ 7개 패널 프래그먼트 | **위젯 설정 시트(`WidgetSettingsSheet`, 2절)를 이 패턴(하단 시트 전환)으로 구조화하면 배치도(`PlacementGrid`)를 가리지 않고 도구를 늘릴 수 있음** — 현재는 시트가 열리면 배치도가 뒤로 밀리는데, 캔버스 고정 + 하단 탭 전환 쪽이 대안 |
| 5 | **인쇄 설정에 "농도"(흐리게/적당하게/진하게) 명시적 선택지** | `label_print_panel_fragment.xml` | 감열 인쇄 품질 이슈(H5 줄 누락 등) 대응 UX로, 사용자가 직접 농도를 조절하게 하는 옵션은 하루종이에도 유효한 완화책 후보(단, 농도 제어는 Pi 드라이버가 지원해야 함 — 현재 미구현) |
| 6 | **최초 온보딩 = Fragment 3장 슬라이드**(환영→기기검색→샘플인쇄), "건너뛰기" 상시 노출 | `launch_guide_*_fragment.xml` | 하루종이 최초 실행 시 "계정→기기 페어링→첫 포맷 예약" 온보딩에 그대로 적용 가능한 3단계 구조 |
| 7 | **원격 인쇄를 별도 토글 + 설정 화면 안내 문구로 노출**("언제 어디서나 인쇄") | `setting_activity.xml` | 하루종이는 이미 서버 원본 스케줄 구조라 항상 "원격"이지만, 사용자에게 "인터넷 끊겨도 예약대로 인쇄됨"을 설정 화면에 명시적으로 알리는 문구는 참고할 만함(PoC 완료 기준의 핵심 가치를 UX로 노출) |
| 8 | **홈 화면 3분할 콘텐츠 카드**(문서인쇄/이미지인쇄/스캔) — 파이프라인 목적지가 아니라 콘텐츠 타입으로 홈 진입점을 나눔 | `wide_home_fragment.xml` | 하루종이 홈에서 위젯 종류(날씨/증시/편지 등)가 아니라 "새 포맷 만들기/오늘 예약 확인/기록 보기" 같은 행동 중심 카드로 구성하는 대안 검토 |

이 표는 **아이디어 후보**일 뿐 결정된 설계가 아니다 — 실제 적용 여부는 각 항목을
`.temp/`나 새 작업지시서로 옮겨 검토한 뒤 결정한다.
