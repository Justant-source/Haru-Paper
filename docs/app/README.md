# docs/app — 앱 문서 인덱스

하루종이 클라이언트(`/app`)의 설계 컨텍스트다. **서버 세션이 담당**한다.

> 표기: **[확인됨]** 실물로 확인 / **[확인됨·코드]** 코드에서 확인 / **[미검증]** 확인 전 / **[추정]** 추론 / **[기본값]** 사용자에게 따로 묻지 않고 정한 값

## 읽는 순서

1. [../init_plan.md](../init_plan.md) — 최초 결정 원본(특히 1·2·6·7·8.3·10절)
2. [../architecture.md](../architecture.md) — 전체 구조, 구성요소 경계, **API 규약 원본**(인증 포함)
3. [../server/format-schema.md](../server/format-schema.md) — **포맷 JSON 스키마 v3(위젯 그리드) 원본**, [../server/widgets.md](../server/widgets.md) — 위젯별 표(편집기가 따라야 함)
4. [web.md](web.md) — 웹앱 스택, 폴더 구조, 인증, PWA, 배포
5. [screens.md](screens.md) — 화면 11개: 표시 요소, 동작, 호출 API, 빈/오류 상태
6. [editor.md](editor.md) — 위젯 그리드 편집기(schemaVersion 3) 구현 상세
7. [native.md](native.md) — `/app/android`, `/app/ios` 예약과 네이티브 기술 후보(미정)

## 한눈에 보기

- **PoC 클라이언트는 웹앱 하나**: React + TypeScript + Vite + PWA, 폰(Android `s21`) 홈 화면에 설치
- **접속**: Tailscale 내부망 전용, `tailscale serve` HTTPS. **M6부터 이메일/비밀번호 세션 로그인이 있다** — tailnet과 별개로 계정별 소유권을 가른다
- **같은 origin**: `/` = 웹앱, `/api` = Spring
- **앱은 프린터를 모른다**: 미리보기는 서버가 렌더한 PNG를 그대로 보여준다(인쇄물과 동일)
- **앱은 위젯 종류를 모른다**: 포맷 편집기는 `GET /api/widgets` 카탈로그만 보고 위젯 추가 목록·설정 폼을 자동 생성한다 — 새 위젯이 서버에 추가돼도 앱 코드를 고칠 필요가 없다
- **네이티브**: 빈 폴더만. 기술은 PoC 이후 결정

## 현재 상태 (2026-09-18)

| 항목 | 상태 |
|---|---|
| 인증 | **구현됨(M6)** — 로그인·가입·계정 화면, 세션 쿠키 + CSRF. 로그인·가입 화면은 첫 요청 전 CSRF 쿠키를 미리 받는다(2026-09-18, 새 브라우저에서 직접 열면 첫 POST가 403이던 버그 수정). `web.md`·`../server/auth.md` |
| 화면 | **11개** — 기존 7개 + 로그인·가입·계정·더보기. `screens.md` |
| 포맷 편집기 | **위젯 그리드 편집기로 재작성됨(2026-09-18, schemaVersion 3)** — 드래그 편집기(dnd-kit) 폐기. 위젯 종류는 서버 카탈로그로 자동 생성. `editor.md` |
| 기기 화면 | 상태 조회 + **토큰 발급·페어링 코드 발급 UI 추가**. 페어링 코드는 Pi가 아직 `pair` 명령을 지원하지 않아 UI 안내문으로 토큰 발급을 대신 쓰라고 안내한다(`pairingCodeWarning`, `screens.md` (6)) |
| 설정 화면 | 날씨 기본 위치 카드 **제거됨**(2026-09-18) — 위치는 날씨 위젯 설정으로 이동. `screens.md` (7) |
| 상태관리·라우팅 | React Query + React Router, 하단 탭 4개(포맷·예약·지금 인쇄·더보기) |
| 테스트 | **테스트 프레임워크 없음**[확인됨·코드] — `package.json`에 vitest/jest 등이 없다. 검증은 `npm run build`(`tsc -b && vite build`)와 `npm run lint`(`oxlint`)뿐이다. 2026-09-18: 위젯 그리드 편집기 포함 둘 다 통과 |
| 운영 배포 | **아직 안 됨** — 로컬 임시 스택 검증만 완료[확인됨, 2026-09-18]. 실물 인쇄는 [미검증] |
