# 웹앱 (`/app/web`)

> 담당: 서버 세션 / 마일스톤: **M3** / 기준: [../init_plan.md](../init_plan.md) 8.3절
> 표기: **[기본값]** = 따로 묻지 않고 정한 값(M3에서 바꿔도 됨), **[미검증]** = 확인 전

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
| UI 라이브러리 | 없음 또는 가벼운 것 [기본값] | 모바일 우선. 무거운 컴포넌트 킷은 피한다. M3에서 확정 |
| 블록 순서 변경 | 드래그 라이브러리 또는 위/아래 버튼 [기본값] | 모바일 터치에서 안정적인 쪽. M3에서 확정 |

## 3. 접속 경로와 origin

- **Tailscale 내부망 전용**(Q11). 폰 `s21`은 이미 tailnet에 있다.
- HTTPS는 서버의 `tailscale serve`가 제공한다: `https://justant-server2.tail2b65d1.ts.net`
  - `tailscale serve` 적용은 M2에서 **사용자 승인 후** 진행한다([../server/deploy.md](../server/deploy.md)).
- **같은 origin**: `/` = 웹앱, `/api` = Spring. CORS 설정이 필요 없다.
- **인증 없음**: tailnet이 인증 역할. 앱에는 로그인 화면·토큰이 없다.
  - Pi용 기기 토큰(`HARU_DEVICE_TOKEN`)은 `/api/device/poll` 등 **Pi 전용 API**에만 쓰이며 앱은 다루지 않는다.
- **개발 시** [기본값]: `vite dev`의 proxy로 `/api`를 로컬 또는 서버의 Spring으로 넘긴다. 코드에서 API 주소를 하드코딩하지 않고 항상 상대 경로 `/api/...`를 쓴다.

## 4. PWA 요건

| 요건 | 내용 |
|---|---|
| Secure context | HTTPS 필수 → `tailscale serve`로 해결. `http://100.x.x.x`로는 service worker가 등록되지 않는다 |
| manifest | `name: 하루종이`, `short_name: 하루종이`, `lang: ko`, `display: standalone`, 아이콘 192/512px, `start_url: /` [기본값] |
| service worker | 앱 셸(정적 파일)만 캐시 [기본값]. **`/api/*` 응답은 캐시하지 않는다**(예약·이력·기기 상태는 항상 최신이어야 함) |
| 설치 확인 | Android `s21` Chrome에서 "홈 화면에 추가" → 독립 창으로 실행 |
| 오프라인 | 폰이 tailnet에 못 붙으면 API 오류 화면을 보여줄 뿐, 앱 자체 오프라인 편집은 PoC 범위 밖 |

## 5. 폴더 구조 초안 [기본값]

M3에서 Vite 프로젝트를 만들 때의 출발점이다. 확정은 M3.

```
app/web/
├── index.html
├── package.json
├── vite.config.ts            # PWA 플러그인, dev proxy(/api)
├── public/
│   └── icons/                # PWA 아이콘
└── src/
    ├── main.tsx
    ├── App.tsx               # 라우터, 하단 탭 내비게이션
    ├── api/                  # architecture.md 규약을 그대로 옮긴 fetch 클라이언트 + 타입
    │   ├── client.ts         # 공통 fetch, application/problem+json 오류 변환
    │   ├── formats.ts
    │   ├── assets.ts
    │   ├── schedules.ts
    │   ├── printNow.ts
    │   ├── history.ts
    │   ├── device.ts
    │   └── settings.ts
    ├── types/                # Format, Schedule, Result, Device 등 (format-schema.md·architecture.md 기준)
    ├── pages/                # 화면 7개 (screens.md 번호와 1:1)
    │   ├── FormatListPage.tsx
    │   ├── FormatEditPage.tsx
    │   ├── ScheduleListPage.tsx   # 목록 + 편집 시트
    │   ├── PrintNowPage.tsx
    │   ├── HistoryPage.tsx
    │   ├── DevicePage.tsx
    │   └── SettingsPage.tsx
    ├── format-editor/        # 블록 편집기: 블록 목록, 블록별 폼, 스타일 폼, 변수 삽입, 미리보기
    │   ├── blocks/           # text, image, dateHeader, weather 블록 폼
    │   ├── StyleForm.tsx     # 화이트리스트 스타일 속성만
    │   └── Preview.tsx       # 편집 중 문서를 POST /api/formats/preview(1초 디바운스)로 렌더해 표시
    ├── components/           # 공용 UI (버튼, 시트, 빈 상태, 오류 배너)
    └── lib/                  # 날짜(KST) 포맷, 상태 라벨 등
```

## 6. API 클라이언트

- **규약 원본은 [../architecture.md](../architecture.md)**. 앱 코드는 거기 적힌 경로·요청·응답을 그대로 따른다. 규약을 바꿔야 하면 architecture.md를 먼저 고친다(공통 파일: 수정 직전 `git pull --ff-only`).
- 앱이 쓰는 경로(앱용, 인증 없음):

| 메서드 | 경로 | 쓰는 화면 |
|---|---|---|
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
| GET | `/api/device` | 기기, 지금 인쇄(용지 정책 표시) |
| PUT | `/api/device/paper-state` | 기기(수동 "용지 장착됨") |
| GET/PUT | `/api/settings` | 설정 |
| GET | `/api/health` | 공통(연결 확인) |

- 앱은 `/api/device/poll`, `/api/device/snapshot`, `/api/device/renders/*`, `/api/device/results`(Pi 전용)를 **호출하지 않는다**.

## 7. 포맷 스키마

- **원본은 [../server/format-schema.md](../server/format-schema.md)**. 편집기의 블록 종류, 속성, 스타일 화이트리스트, 변수 목록은 거기를 따른다.
- init_plan 6.1 요약(원본과 다르면 원본이 우선):
  - 최상위: `schemaVersion`, `meta`(name, author, description, forkedFrom), `style`, `blocks[]`
  - 첫 블록 4종: `text`, `image`, `dateHeader`, `weather`
  - 단위 mm/pt. 임의 HTML/CSS/JS 입력란은 **만들지 않는다**
  - 블록 스타일 화이트리스트 초안: `align`, `fontSizePt`, `bold`, `marginTopMm`, `marginBottomMm`
  - 텍스트 변수: `{{date}}`, `{{weekday}}`(렌더 대상 날짜 기준)
  - `assets`(이미지 data URI)는 **내보내기 파일에만** 들어간다. 편집 중에는 `assetId`로 참조
- 앱은 스키마 검증을 **서버에 맡긴다** [기본값]: 저장·미리보기 시 서버 422 `application/problem+json` 응답의 `errors[].path`/`errors[].message`를 폼에 표시([../architecture.md](../architecture.md) 4.1). 앱 쪽 검증은 입력 편의(필수값, 숫자 범위) 수준만.

## 8. 빌드와 배포

- `npm run build` → `app/web/dist`
- 서버 compose의 `haru-web`(nginx)가 `dist`를 서빙하고 `/api`를 `haru-api`(Spring)로 프록시한다 — 상세는 [../server/deploy.md](../server/deploy.md).
- 배포는 prod 하나뿐(Q15, dev 없음).
- dist를 git에 커밋할지, 이미지 빌드 단계에서 만들지는 M2/M3에서 deploy.md와 함께 정한다 [기본값: 커밋하지 않고 빌드 단계에서 생성].

## 9. 시간·언어

- 모든 시각은 **KST(`Asia/Seoul`) 고정**. 예약에 시간대 선택 UI 없음.
- UI 언어는 한국어만.

## 10. M3 통과 조건

init_plan 10절 그대로. 통과 전에는 M3 완료로 보고하지 않는다.

1. 폰 `s21` Chrome에서 `https://justant-server2.tail2b65d1.ts.net` 접속 → **PWA 설치**(홈 화면에서 독립 창 실행)
   — **[미검증, 사용자 확인 필요]** 서버 세션은 실제 s21 기기가 없어 확인할 수 없다. `tailscale serve`는
   2026-09-14 적용 확인됨(`docs/server/deploy.md` 3절), HTTPS 응답도 확인됨 — 남은 건 폰에서 "홈 화면에 추가"뿐이다.
2. 포맷 **만들기**(텍스트·날짜 헤더 블록 최소 1개씩) → 저장 — **[확인됨]** Playwright 헤드리스 브라우저로
   `https://justant-server2.tail2b65d1.ts.net`과 `http://127.0.0.1:18080` 양쪽에서 실제 클릭해 확인(2026-09-14)
3. 서버 **미리보기 PNG** 표시(폭 = 프린터 프로필 폭) — **[확인됨]** 1300px 그레이스케일, 한글·날짜 변수·이미지·날씨 전부 정상 렌더 육안 확인
4. **예약** 만들기(반복 또는 일회성) → 목록에 표시, 켜기/끄기 동작 — **[확인됨]**
5. **지금 인쇄** 명령 생성(용지 확인 체크 포함) → **[확인됨]**
6. **가짜 Pi**가 올린 결과가 **이력 화면에 표시** — **[확인됨]** curl로 poll/results 흉내 내 이력 화면 표시 확인

- 5·6단계의 "가짜 Pi"는 curl 등으로 `/api/device/poll` → `/api/device/results`를 흉내 내는 스크립트로 충분하다(실제 Pi 에이전트는 M4).
