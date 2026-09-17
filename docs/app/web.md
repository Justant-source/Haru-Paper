# 웹앱 (`/app/web`)

> 담당: 서버 세션 / 마일스톤: M3(스캐폴드) + M6(인증) + M7 일부(드래그 편집기) / 기준: [../init_plan.md](../init_plan.md) 8.3절, `.temp/03` M6
> 표기: **[확인됨·코드]** = 코드에서 확인 / **[기본값]** = 따로 묻지 않고 정한 값 / **[미검증]** = 확인 전

## 1. 역할

폰에서 다음을 한다. 프린터는 모른다.

- 포맷(=카드) 만들기·편집·가져오기·내보내기
- 서버가 렌더한 미리보기 PNG 보기(인쇄물과 동일한 이미지)
- 예약(반복/일회성) 만들기·켜기/끄기
- 지금 인쇄 명령 보내기
- 실행 이력, Pi·프린터 상태 보기
- 날씨 기본 위치 설정

## 2. 스택

| 항목 | 선택 | 비고 |
|---|---|---|
| 언어 | TypeScript | 확정(Q16) |
| UI | React | 확정(Q16) |
| 빌드 | Vite | 확정(Q16) |
| PWA | manifest + service worker | 확정(Q16). 플러그인은 `vite-plugin-pwa` [기본값] |
| 라우팅 | React Router [기본값] | M3에서 확정 |
| 서버 상태 | TanStack Query [기본값] | 폴링·캐시·재시도가 필요한 이력/기기 화면에 적합. M3에서 확정 |
| 로컬 UI 상태 | React 기본(useState/useReducer) [기본값] | 포맷 편집기가 복잡해지면 Zustand 검토. M3에서 확정 |
| UI 라이브러리 | 없음(가벼운 CSS) [기본값] | 모바일 우선. 무거운 컴포넌트 킷은 피한다 |
| 블록 순서 변경 | 드래그 라이브러리 또는 위/아래 버튼 [기본값] | 모바일 터치에서 안정적인 쪽. M3에서 확정 |

## 3. 접속 경로와 origin

- **Tailscale 내부망 전용**(Q11). 폰 `s21`은 이미 tailnet에 있다.
- HTTPS는 서버의 `tailscale serve`가 제공한다: `https://justant-server2.tail2b65d1.ts.net`
  - `tailscale serve` 적용은 M2에서 **사용자 승인 후** 진행한다([../server/deploy.md](../server/deploy.md)).
- **같은 origin**: `/` = 웹앱, `/api` = Spring. CORS 설정이 필요 없다.
- **인증: 이메일/비밀번호 세션 로그인(M6)** [확인됨·코드]. `tailnet`은 네트워크 접근만 걸러줄 뿐, 계정·소유권 경계는 서버 세션 인증이 담당한다. 상세 규약은 [../server/auth.md](../server/auth.md).
  - 인증되지 않은 사용자는 `/login`으로 리다이렉트된다(`App.tsx`의 `RequireAuth` 게이트, `useAuthGuard` 훅) [확인됨·코드]. `/login`·`/signup`은 게이트 밖. `RequireAuth`는 `<Outlet/>`을 그리는 레이아웃 컴포넌트로 구현돼 있다 — 함수 컴포넌트가 `<Route>` 엘리먼트를 반환해서 `element={<X/>}`로 끼워 넣으면 React Router가 `<Route>`는 `<Routes>` 바로 아래 자식만 허용하므로 렌더 시점에 예외를 던지고 화면이 통째로 빈 채로 남는다(2026-09-16 실사용 중 발견, `App.tsx` 주석) [확인됨·코드].
  - `apiClient`(`api/client.ts`)는 모든 요청에 `credentials: 'include'`를 강제하고, `POST/PUT/PATCH/DELETE`에는 쿠키에서 읽은 CSRF 토큰을 `X-XSRF-TOKEN` 헤더로 자동 첨부한다 [확인됨·코드] — 화면 코드는 CSRF를 신경 쓸 필요가 없다.
  - Pi용 기기 토큰은 여전히 Pi 전용 API(`/api/device/poll` 등)의 인증 방식이지만, **발급 UI는 앱에 있다** — `DevicePage`에서 `POST /api/devices/me/token`(1회 표시)·`POST /api/devices/pairing-codes`(페어링 코드 발급, 만료 카운트다운)를 호출한다.
- **개발 시** [기본값]: `vite dev`의 proxy로 `/api`를 로컬 또는 서버의 Spring으로 넘긴다. 코드에서 API 주소를 하드코딩하지 않고 항상 상대 경로 `/api/...`를 쓴다.

## 4. PWA 요건

| 요건 | 내용 |
|---|---|
| Secure context | HTTPS 필수 → `tailscale serve`로 해결. `http://100.x.x.x`로는 service worker가 등록되지 않는다 |
| manifest | `name: 하루종이`, `short_name: 하루종이`, `lang: ko`, `display: standalone`, 아이콘 192/512px, `start_url: /`, `theme_color`/`background_color`: `#f4ede3` [기본값] |
| service worker | 앱 셸(정적 파일)만 캐시 [기본값]. **`/api/*` 응답은 캐시하지 않는다**(예약·이력·기기 상태는 항상 최신이어야 함) |
| 설치 확인 | Android `s21` Chrome에서 "홈 화면에 추가" → 독립 창으로 실행 |
| 오프라인 | 폰이 tailnet에 못 붙으면 API 오류 화면을 보여줄 뿐, 앱 자체 오프라인 편집은 PoC 범위 밖 |

## 5. 폴더 구조

M3에서 만든 현재 트리.

```
app/web/
├── index.html
├── package.json
├── vite.config.ts            # PWA 플러그인, dev proxy(/api)
├── public/
│   └── icons/                # PWA 아이콘
└── src/
    ├── main.tsx
    ├── App.tsx               # 라우터(/login·/signup은 게이트 밖, 나머지는 RequireAuth), 하단 탭 레이아웃
    ├── hooks/
    │   └── useAuthGuard.ts   # 세션 상태 조회(GET /api/auth/me) 후 loading/authenticated/unauthenticated
    ├── api/                  # architecture.md·server/auth.md 규약을 그대로 옮긴 fetch 클라이언트 + 타입
    │   ├── client.ts         # 공통 fetch: credentials:'include' 강제, X-XSRF-TOKEN 자동 첨부, problem+json 오류 변환
    │   ├── auth.ts           # signup/login/logout/me
    │   ├── account.ts        # 계정 정보 수정(표시 이름·소개·비밀번호)
    │   ├── devices.ts        # 기기 토큰 발급, 페어링 코드 발급, 내 기기 조회
    │   ├── device.ts         # 기기 상태·용지 정책(Pi 프린터 프로필)
    │   ├── formats.ts, assets.ts, schedules.ts, printNow.ts, history.ts, settings.ts
    ├── types/                # format.ts(schemaVersion 2 rows/slots), auth.ts, devices.ts, device.ts 등
    ├── pages/                # 화면 11개 (screens.md 번호와 1:1)
    │   ├── LoginPage.tsx, SignupPage.tsx     # 인증 게이트 밖
    │   ├── FormatListPage.tsx, FormatEditPage.tsx   # 편집은 드래그 편집기(editor.md)
    │   ├── ScheduleListPage.tsx   # 목록 + 편집 시트
    │   ├── PrintNowPage.tsx
    │   ├── MorePage.tsx      # 이력·기기·계정·설정으로 가는 더보기 메뉴
    │   ├── HistoryPage.tsx
    │   ├── DevicePage.tsx    # 기기 상태 + 토큰·페어링 코드 발급 UI
    │   ├── AccountPage.tsx   # 표시 이름·소개·비밀번호 변경
    │   └── SettingsPage.tsx
    ├── format-editor/        # 드래그 편집기 — 상세는 editor.md
    │   ├── blocks/           # text, image, dateHeader, weather 블록 폼
    │   ├── LayoutEditor.tsx, LayoutCanvas.tsx, WidgetPalette.tsx, SlotContent.tsx
    │   ├── StyleForm.tsx, BlockStyleForm.tsx
    │   └── Preview.tsx       # 편집 중 문서를 POST /api/formats/preview(1초 디바운스)로 렌더해 표시
    ├── components/           # 공용 UI (버튼, 시트, 빈 상태, 오류 배너, 하단 탭 Layout)
    └── lib/                  # date.ts(KST 포맷), theme.ts, analytics.ts(로컬 이벤트 로그), xsrf.ts,
                               # layout-math.ts(mmToPx/ptToPx — 서버 HtmlTemplateBuilder와 동일 공식, editor.md 참고)
```

## 6. API 클라이언트

- **규약 원본은 [../architecture.md](../architecture.md)**, 인증 규약은 [../server/auth.md](../server/auth.md). 앱 코드는 거기 적힌 경로·요청·응답을 그대로 따른다. 규약을 바꿔야 하면 원본을 먼저 고친다(공통 파일: 수정 직전 `git pull --ff-only`).
- 앱이 쓰는 경로(전부 **세션 인증 필요**, `/login`·`/signup` 자체만 예외):

| 메서드 | 경로 | 쓰는 화면 |
|---|---|---|
| POST | `/api/auth/signup`, `/api/auth/login`, `/api/auth/logout` | 로그인, 가입, 계정(로그아웃) |
| GET | `/api/auth/me` | 인증 게이트(`useAuthGuard`), 계정 |
| PATCH | `/api/account` | 계정(표시 이름·소개·비밀번호 변경) |
| GET/POST | `/api/formats` | 포맷 목록, 새로 만들기 |
| GET/PUT/DELETE | `/api/formats/{id}` | 포맷 편집, 삭제 |
| POST | `/api/formats/import` | 포맷 목록(가져오기) |
| GET | `/api/formats/{id}/export` | 포맷 목록(내보내기) |
| GET | `/api/formats/{id}/preview.png` | 포맷 목록 썸네일, 지금 인쇄(저장된 포맷 미리보기) |
| POST | `/api/formats/preview` | 포맷 편집(저장 안 된 편집본 미리보기) |
| POST | `/api/assets` | 포맷 편집(이미지 블록 업로드, PNG/JPEG, 최대 10MB) |
| GET | `/api/assets/{assetId}` | 포맷 편집(이미지 블록 썸네일) |
| GET/POST | `/api/schedules` | 예약 목록, 새 예약 |
| PUT/DELETE | `/api/schedules/{id}` | 예약 편집·켜기/끄기, 삭제 |
| POST | `/api/print-now` | 지금 인쇄 |
| GET | `/api/history` | 이력 |
| GET | `/api/device` | 기기, 지금 인쇄(용지 정책 표시), 포맷 편집(프린터 폭 조회) |
| PUT | `/api/device/paper-state` | 기기(수동 "용지 장착됨") |
| POST | `/api/devices/me/token` | 기기(토큰 발급, 1회 표시) |
| POST | `/api/devices/pairing-codes` | 기기(페어링 코드 발급) |
| GET | `/api/devices/me` | 기기(내 기기 이름·연결 여부) |
| GET/PUT | `/api/settings` | 설정 |
| GET | `/api/health` | 공통(연결 확인) |

- 앱은 `/api/device/poll`, `/api/device/snapshot`, `/api/device/renders/*`, `/api/device/results`(Pi 전용, `Authorization: Bearer` 기기 토큰)를 **호출하지 않는다**.

## 7. 포맷 스키마 (v2, 행/슬롯)

- **원본은 [../server/format-schema.md](../server/format-schema.md)**. 편집기 구현 상세는 [editor.md](editor.md). 최상위 타입은 `app/web/src/types/format.ts`(`FormatDocument{schemaVersion:2, meta, style, rows}`).
- 요지:
  - 최상위: `schemaVersion: 2`, `meta`(name, author, description, forkedFrom), `style`(전체 스타일), `rows[]`
  - `rows[].slots[]`: 슬롯 1개(폭 `1/1`) 또는 2개(`1/2`+`1/2` / `2/3`+`1/3` / `1/3`+`2/3`)
  - 블록 4종: `text`, `image`, `dateHeader`, `weather`. 단위 mm/pt. 임의 HTML/CSS/JS 입력란은 **만들지 않는다**
  - 블록 스타일 화이트리스트: `align`, `fontSizePt`, `bold`, `marginTopMm`, `marginBottomMm`
  - `dateHeader.props.pattern` 토큰: `YYYY`/`MM`/`DD`/`M`/`D`/`dddd`/`ddd`(길이 순으로 치환)
  - `assets`(이미지 data URI)는 **내보내기 파일에만** 들어간다. 편집 중에는 `assetId`로 참조
- 앱은 스키마 검증을 **서버에 맡긴다** [기본값]: 저장·미리보기 시 서버 422 `application/problem+json` 응답의 `errors[].path`/`errors[].message`를 폼에 표시([../architecture.md](../architecture.md) 4.1). 앱 쪽 검증은 입력 편의(필수값, 숫자 범위) 수준만.

## 8. 빌드와 배포

- **테스트 프레임워크 없음**[확인됨·코드] — `package.json`에 vitest·jest 등 테스트 러너가 없다. 검증은 `npm run build`(`tsc -b && vite build`, 타입 체크 겸함)와 `npm run lint`(`oxlint`)뿐이다. 2026-09-17 재확인: 둘 다 0 에러로 통과(lint 경고 7개 — `react(purity)` `Date.now()` 호출, `react(set-state-in-effect)` 여러 건. 동작에는 영향 없음, 리팩터링은 이 문서 범위 밖).
- `npm run build` → `app/web/dist`
- 서버 compose의 `haru-web`(nginx)가 `dist`를 서빙하고 `/api`를 `haru-api`(Spring)로 프록시한다 — 상세는 [../server/deploy.md](../server/deploy.md).
- 배포는 prod 하나뿐(Q15, dev 없음).
- dist를 git에 커밋할지, 이미지 빌드 단계에서 만들지는 M2/M3에서 deploy.md와 함께 정한다 [기본값: 커밋하지 않고 빌드 단계에서 생성].

## 9. 시간·언어·테마

- 모든 시각은 **KST(`Asia/Seoul`) 고정**. 예약에 시간대 선택 UI 없음.
- UI 문구는 `app/web/src/i18n/` 사전(`ko` 기본, `en` 선택). 설정에서 바꾼다. 폼 도움말·이력 상태 라벨 일부는 아직 한국어 고정.
- 테마는 밝게(우드톤) / 어둡게 / 기기 설정. `localStorage` `haru-theme`.
- 사용 기록(`format_create` / `schedule_save` / `print_now`)은 **이 기기 localStorage만**. 서버·외부로 보내지 않는다.

## 10. M3 통과 조건 (2026-09-14 기록 — M6 인증·드래그 편집기 재작성 이전 상태)

init_plan 10절 그대로. 통과 전에는 M3 완료로 보고하지 않는다. **이후 M6(로그인)와 드래그 편집기 재작성으로 화면 코드가 크게 바뀌었으므로, 아래 확인 시점의 화면과 지금 코드는 다르다** — 현재 화면·API는 `screens.md`·`editor.md`를 본다. 이 절은 M3 자체의 통과 기록으로 남긴다.

1. 폰 `s21` Chrome에서 `https://justant-server2.tail2b65d1.ts.net` 접속 → **PWA 설치**(홈 화면에서 독립 창 실행)
   — **[확인됨, 2026-09-14]** 사용자가 실제 s21에서 설치·구동 확인.
2. 포맷 **만들기**(텍스트·날짜 헤더 블록 최소 1개씩) → 저장 — **[확인됨]** Playwright 헤드리스 브라우저로
   `https://justant-server2.tail2b65d1.ts.net`과 `http://127.0.0.1:18080` 양쪽에서 실제 클릭해 확인(2026-09-14)
3. 서버 **미리보기 PNG** 표시(폭 = 프린터 프로필 폭) — **[확인됨]** 1300px 그레이스케일, 한글·날짜 변수·이미지·날씨 전부 정상 렌더 육안 확인
4. **예약** 만들기(반복 또는 일회성) → 목록에 표시, 켜기/끄기 동작 — **[확인됨]**
5. **지금 인쇄** 명령 생성(용지 확인 체크 포함) → **[확인됨]**
6. **가짜 Pi**가 올린 결과가 **이력 화면에 표시** — **[확인됨]** curl로 poll/results 흉내 내 이력 화면 표시 확인

- 5·6단계의 "가짜 Pi"는 curl 등으로 `/api/device/poll` → `/api/device/results`를 흉내 내는 스크립트로 충분하다(실제 Pi 에이전트는 M4).
