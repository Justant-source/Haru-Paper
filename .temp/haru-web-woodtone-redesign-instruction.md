# Haru Web 우드톤 리디자인 — 구현 지시서

에이전트가 **이 파일만** 보고 `/app/web`을 한 번에 고친다. 합의(Q1–Q20)는 여기 숫자·규칙으로 녹였다. Q번호를 다시 묻지 않는다.

대상 URL: `http://100.81.189.92:18080/`  
참고 UX(읽기 전용): `/home/justant/Data/Green-Forest/frontend`  
화면 동작 원본: `docs/app/screens.md` — **플로우·API·카피는 유지**. 시각·크롬만 바꾼다.

---

## 잠금

| 항목 | 값 |
|---|---|
| 이식 | Green-Forest의 **크롬/컴포넌트 문법**만. 탭 구성·라우트·초록 팔레트·Jua 폰트·나무 로고·피드 그리드는 가져오지 않는다 |
| 스택 | Vite + React + 단일 CSS. Tailwind/새 UI 킷 추가 금지 |
| 레이아웃 | 모바일 우선, 셸 최대폭 **480px**. 그 이상은 가운데 정렬된 같은 폭 |
| 톤 | 연한 종이/우드. CTA만 호두색으로 대비. 측정·다크모드·i18n·이벤트 훅은 이번 작업에 넣지 않는다 |
| 적용 | 한 커밋 단위로 전 화면 통일. “점진적 마이그레이션”으로 화면을 남기지 않는다 |

Green-Forest에서 **가져올 것**

- sticky 헤더 (`bg-white/80` + blur에 해당하는 종이색 반투명)
- 하단 탭: SVG 라인 아이콘 + 작은 라벨, 활성 = 굵은 스트로크 + accent
- 카드: 큰 radius, 연한 테두리, 썸네일+제목+메타+액션
- pill/badge로 상태 표시
- `env(safe-area-inset-bottom)` 패딩
- 헤더 우측의 pill형 주 버튼

Green-Forest에서 **가져오지 말 것**

- `forest-*` 초록 (`#2D8A4E` 등)
- `jua` 디스플레이 폰트, 나무 SVG 워드마크
- `max-w-7xl`, `hover:scale-105`
- 인증으로 탭이 늘었다 줄었다 하는 패턴
- 4:5 그리드 피드 (포맷 목록은 screens.md대로 **한 줄에 카드 1개**)

---

## 완료 정의 (전부 참이어야 끝)

1. `:root`에 아래 **토큰 표의 값이 그대로** 있다. 페이지 JSX에 `#` hex / `rgb()` 색이 없다. 예외: 미리보기 `<img>`의 `src`뿐.
2. 공통 컴포넌트 목록(아래)이 존재하고, 7화면+더보기가 그 컴포넌트와 토큰 클래스만 쓴다.
3. 하단 탭 4개는 SVG+라벨. 이모지 탭 금지. 포맷 편집 라우트에서는 하단 탭을 **숨긴다**.
4. 포맷·예약 삭제와 오버플로 메뉴는 **하단 시트**(screens.md). `window.alert` 없음.
5. 모달은 열릴 때 내부 포커스, Tab 순환, Esc 닫기.
6. 인터랙티브 높이 ≥ 44px. `:focus-visible` 링이 모든 버튼/링크/입력에 보인다.
7. `app/web`에서 `npm run lint`와 `npm run build`가 통과한다.
8. `docs/app/screens.md` 공통절·레이아웃 메모와 `docs/app/web.md` PWA `theme_color`가 이 토큰과 맞다.
9. `index.html`과 Vite PWA manifest의 `theme_color` / `background_color`가 `--bg-page`와 같다.

기능(API 호출, 저장, 미리보기, 예약, 지금 인쇄, 이력 필터, 기기 토글, 설정 저장)은 지금과 동일하게 동작해야 한다. 새 엔드포인트·스키마 변경 없음.

---

## 디자인 토큰 (이 값 그대로)

`app/web/src/index.css` `:root`에 넣고, 컴포넌트는 이 변수만 참조한다.

```css
:root {
  --bg-page: #f4ede3;
  --bg-surface: #fffbf5;
  --bg-muted: #ede4d6;
  --text-primary: #2c241c;
  --text-secondary: #5f564c;
  --border: #d8cbb8;
  --accent: #4a3728;       /* CTA·활성 탭. 흰 글자 대비 ≥ 4.5:1 */
  --accent-hover: #3b2c20;
  --danger: #b42318;
  --warn: #a65d1a;
  --ok: #3d6b4f;
  --radius-sm: 8px;
  --radius-md: 12px;
  --radius-lg: 16px;
  --space-1: 4px;
  --space-2: 8px;
  --space-3: 12px;
  --space-4: 16px;
  --space-5: 24px;
  --tap: 44px;
  --header-h: 52px;
  --tab-h: 56px;
  --ease: 150ms ease;
  --focus-ring: 2px solid var(--accent);
  --focus-offset: 2px;
}
```

상태 배지 (이력 `status` / 기기 `printerStatus`) — 의미색은 screens.md 표를 유지하고 채도만 위 토큰에 맞춤:

| 의미 | 배경 | 글자 |
|---|---|---|
| 성공 `printed` / `ok` | `#e4efe6` | `var(--ok)` |
| 주의 `missed` `skipped_*` `warn` | `#f6ead8` | `var(--warn)` |
| 실패 `failed` `offline` `error` | `#f8e4e1` | `var(--danger)` |
| 중립 `dry_run` `unknown` | `var(--bg-muted)` | `var(--text-secondary)` |

질감: `body`에 CSS만. 텍스처 이미지 금지.

```css
body {
  background-color: var(--bg-page);
  background-image:
    radial-gradient(rgba(74, 55, 40, 0.04) 0.6px, transparent 0.6px),
    linear-gradient(180deg, #f7f1e8 0%, var(--bg-page) 40%, #efe6d8 100%);
  background-size: 4px 4px, 100% 100%;
}
```

불투명도는 텍스트 대비를 해치지 않는 이 값에서 끝낸다. 더 진한 노이즈 금지.

타이포: **Pretendard를 실제로 로드**한다. 지금은 `index.css`에 이름만 있고 `index.html`에 링크가 없다.

```html
<link rel="stylesheet"
  href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/static/pretendard.min.css" />
```

계층: 화면 제목 20px/700, 섹션 16px/600, 본문 15px/400, 메타 12px/`--text-secondary`. line-height 1.45.

아이콘: `app/web/src/components/icons.tsx`에 라인 SVG(24 viewBox, `currentColor`, stroke 1.5 / 활성 2.5). 탭 4개 + 뒤로 + 더보기(세로점) + 닫기. 본문에 이모지 장식 금지. 이력 상태도 이모지 대신 Badge.

---

## 레이아웃 규칙

`.app-shell`: max-width 480px, `min-height: 100dvh`, `background: var(--bg-surface)`, 바깥은 `--bg-page`.

**하단 탭** (`Layout`): 포맷 `/`, 예약 `/schedules`, 지금 인쇄 `/print-now`, 더보기 `/more`. `fixed` + `safe-area-bottom`. 콘텐츠 `padding-bottom: calc(var(--tab-h) + env(safe-area-inset-bottom))`.

숨김: `formats/new`, `formats/:id/edit` — 편집 전용 크롬(뒤로 + 제목 입력 + 저장). 탭이 미리보기를 가리지 않게 한다.

**페이지 헤더** (`PageHeader`): sticky, `background: color-mix(in srgb, var(--bg-surface) 82%, transparent)`, `backdrop-filter: blur(10px)`, 높이 `--header-h`. 왼쪽 제목, 오른쪽 보조 액션(최대 2). 탭 루트 화면은 모두 이 헤더를 쓴다. 페이지 본문의 중복 `<h1>`은 제거한다.

포맷 목록의 주 CTA는 헤더 오른쪽 **새 포맷**(pill, `--accent`). **가져오기**는 본문 상단 보조 버튼.

---

## 만들 컴포넌트

기존 `EmptyState`, `ErrorBanner`는 토큰으로 재스타일. 아래는 새로 두고 페이지가 이걸 쓴다.

| 파일 | 역할 |
|---|---|
| `components/PageHeader.tsx` | sticky 제목 + 액션 |
| `components/Button.tsx` | `primary` `secondary` `danger` `ghost`. primary = `--accent` 배경 + 흰 글자 |
| `components/Card.tsx` | surface, radius-md, border, padding `--space-3` |
| `components/Badge.tsx` | 위 상태 표 4종 |
| `components/BottomSheet.tsx` | 오버레이 + 하단 패널. 포커스 트랩, Esc, 배경 클릭 닫기, `role="dialog"` `aria-modal` |
| `components/Toggle.tsx` | 예약 on/off. 켜짐 `--ok` |
| `components/icons.tsx` | SVG 세트 |
| `Layout.tsx` | 셸 + 탭 + 편집 화면에서 탭 hide |

`BottomSheet` 하나로 삭제 확인·포맷 오버플로 메뉴·블록 종류 선택을 처리한다. 화면마다 `position:absolute` 드롭다운을 새로 만들지 않는다.

폼: `.field` = 라벨 + 컨트롤 + `.field-error`(서버 `errors[].path` 매핑). 오류는 텍스트로 보여 주고 입력에 `aria-invalid` + `aria-describedby`.

화면당 **채워진 primary 버튼은 하나**. 나머지는 secondary/ghost.

---

## 화면별 — 지금 코드 부채와 바꿀 점

동작은 `docs/app/screens.md`가 원본이다. 아래는 **시각/크롬만**.

### 포맷 목록 `FormatListPage.tsx`

지금: hex 인라인 스타일이 수십 개, `⋯` 드롭다운, `alert()`, 썸네일 60×80.

할 일:

- `PageHeader` 제목 「포맷」, 우측 「새 포맷」
- 가져오기는 목록 위 secondary
- 카드: 썸네일 **80×108**(용지 비율), 이름, 원작자, 수정 시각
- `⋯` → `BottomSheet` (편집 / 내보내기 / 지금 인쇄 / 삭제)
- 삭제 확인도 `BottomSheet`. 예약 중 포맷 409는 시트 안 문구로 (alert 제거)
- 빈 상태: 기존 카피 유지, primary 「새 포맷」

### 포맷 편집 `FormatEditPage.tsx`

- 하단 탭 없음. 상단: 뒤로(목록으로) · 저장 상태 텍스트 · 「저장」 primary
- `편집 | 미리보기` 세그먼트. 활성 = `--accent` 밑줄 2px
- 블록 리스트/폼/스타일은 `Card`
- 선택 블록: `border-color: var(--ok)` 대신 **`--accent`** (초록 선택링은 Green-Forest 잔재)
- 미리보기: `--bg-muted` 위 종이 프레임(`--bg-surface`, 얇은 `--border`, 살짝 그림자)
- 블록 추가는 `BottomSheet` 4종. 떠 있는 드롭다운 제거
- 미저장 이탈 확인은 브라우저 `beforeunload` + 같은 `BottomSheet`

### 예약 `ScheduleListPage.tsx`

- `PageHeader` 「예약」, 본문 하단 또는 헤더 액션 「새 예약」
- 카드: **시각 20px/700** → 요일/날짜 → 포맷명 → 다음 실행(메타)
- 토글은 `Toggle`. 실패 시 롤백 + ErrorBanner (screens.md)
- 생성/편집 폼은 기존 시트 구조를 `BottomSheet`로 옮기고 토큰화
- 안내 문구 2줄은 카드 밖 메타 스타일 유지(카피 변경 금지)

### 지금 인쇄 `PrintNowPage.tsx`

- `PageHeader` 「지금 인쇄」
- 순서: 기기 상태 `Card` → 포맷 선택 → 미리보기 종이 프레임 → 용지 정책 배너 → 확인 체크(타깃 44px) → primary 「지금 인쇄」
- 체크 필수일 때 버튼 disabled + disable-reason 텍스트 (색만으로 금지 표시하지 않음)
- Pi 2분 경고는 `--warn` 배너. 전송은 허용 (screens.md)

### 이력 `HistoryPage.tsx`

- 이모지 상태 제거. `Badge` + 한국어 라벨(`STATUS_LABELS_KO`)
- 필터 3개는 pill. 활성 = `--accent` 배경 흰 글자
- 카드: 배지 · 포맷명 · 시각 · source pill(`예약`/`지금 인쇄`)
- `detail`은 `<details>` 유지, 본문은 모노 12px `--bg-muted`

### 기기 `DevicePage.tsx`

- 인라인 hex 제거
- 카드 3장: Pi 폴링 / 프린터 프로필·상태 / 용지 정책+수동 토글
- 상태 Badge. `manual_flag`가 아닐 때 토글 disabled + 기존 안내 문구

### 설정 `SettingsPage.tsx`

- 인라인 hex 제거
- 카드: 날씨 위치 필드들 / 앱 정보+health
- 위경도 범위 밖이면 저장 disabled + `.field-error`
- 안내 문구 카피 유지

### 더보기 `MorePage.tsx`

- `PageHeader` 「더보기」
- 행 리스트(이력 / 기기 / 설정): 44px+, 우측 셰브론 SVG, 행 구분 `--border`
- 이것도 탭 루트라 하단 탭 유지

---

## 구현 순서 (한 번에, 이 순서)

각 단계 완료 기준이 맞기 전에 다음으로 가지 않는다.

1. **토큰·폰트·PWA 색** — `index.css` `:root` 교체, 구 hex 유틸 삭제. `index.html` Pretendard + `theme-color=#f4ede3`. `vite.config.ts` manifest `theme_color`/`background_color` 동일.
2. **primitives** — Button, Card, Badge, BottomSheet, Toggle, icons, PageHeader, EmptyState/ErrorBanner 재스타일. BottomSheet 포커스 트랩을 이 단계에서 끝낸다.
3. **Layout** — 우드 셸, SVG 탭, 편집 라우트에서 탭 hide, safe-area.
4. **페이지 7+더보기** — 위 화면별 절. 인라인 색/간격 제거.
5. **docs/app** — screens.md 공통 시각(우드톤, 헤더, 편집 시 탭 숨김, 메뉴=하단 시트). web.md PWA 색. `docs/init_plan.md`와 `docs/architecture.md`는 건드리지 않는다.
6. **검증** — `cd app/web && npm run lint && npm run build`. 가능하면 폰/브라우저에서 플로우 5개: 포맷 만들기, 편집 미리보기, 예약, 지금 인쇄 화면 진입, 더보기→이력. UI를 바꿨으면 배포된 `haru-web`을 재빌드해 URL에서 확인한다. **포트/`tailscale serve`는 바꾸지 않는다.**

충돌 시 우선순위: 기능 동일 → 접근성 → 토큰 준수 → 예쁨.

---

## 문서에 적을 시각 메모 (screens.md 공통절에 반영)

- 셸 480px, 배경 `--bg-page`, 카드 `--bg-surface`
- 하단 탭 4개 + 아이콘. 포맷 편집은 하위 화면이라 탭 없음
- 목록 메뉴·삭제 확인은 하단 시트
- PWA `theme_color` `#f4ede3`

---

## 작업 범위

수정해도 되는 곳: `/app/web/**`, `/docs/app/screens.md`, `/docs/app/web.md`

손대지 않는 곳: `/pi`, `/docs/pi`, `/docs/init_plan.md`, `/docs/architecture.md`, 서버 API/렌더러, 포맷 JSON 스키마, 새 측정 코드

하지 않는 것: Tailwind 도입, 다크모드, i18n 파일 분리, 분석 이벤트, Green-Forest 코드 복사, 프린터 프로토콜 상수
