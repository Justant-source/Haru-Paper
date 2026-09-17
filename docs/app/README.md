# docs/app — 앱 문서 인덱스

하루종이 클라이언트(`/app`)의 설계 컨텍스트다. **서버 세션이 담당**한다.

> 표기: **[확인됨]** 실물로 확인 / **[확인됨·코드]** 코드에서 확인 / **[미검증]** 확인 전 / **[추정]** 추론 / **[기본값]** 사용자에게 따로 묻지 않고 정한 값

## 읽는 순서

1. [../init_plan.md](../init_plan.md) — 최초 결정 원본(특히 1·2·6·7·8.3·10절)
2. [../architecture.md](../architecture.md) — 전체 구조, 구성요소 경계, **API 규약 원본**(인증 포함)
3. [../server/format-schema.md](../server/format-schema.md) — **포맷 JSON 스키마 v2(행/슬롯) 원본**(편집기가 따라야 함)
4. [web.md](web.md) — 웹앱 스택, 폴더 구조, 인증, PWA, 배포
5. [screens.md](screens.md) — 화면 11개: 표시 요소, 동작, 호출 API, 빈/오류 상태
6. [editor.md](editor.md) — 드래그 편집기(schemaVersion 2, dnd-kit) 구현 상세
7. [native.md](native.md) — `/app/android`, `/app/ios` 예약과 네이티브 기술 후보(미정)

## 한눈에 보기

- **PoC 클라이언트는 웹앱 하나**: React + TypeScript + Vite + PWA, 폰(Android `s21`) 홈 화면에 설치
- **접속**: Tailscale 내부망 전용, `tailscale serve` HTTPS. **M6부터 이메일/비밀번호 세션 로그인이 있다** — tailnet과 별개로 계정별 소유권을 가른다
- **같은 origin**: `/` = 웹앱, `/api` = Spring
- **앱은 프린터를 모른다**: 미리보기는 서버가 렌더한 PNG를 그대로 보여준다(인쇄물과 동일)
- **네이티브**: 빈 폴더만. 기술은 PoC 이후 결정

## 현재 상태 (2026-09-17)

| 항목 | 상태 |
|---|---|
| 인증 | **구현됨(M6)** — 로그인·가입·계정 화면, 세션 쿠키 + CSRF. `web.md`·`../server/auth.md` |
| 화면 | **11개** — 기존 7개 + 로그인·가입·계정·더보기. `screens.md` |
| 포맷 편집기 | **드래그 편집기로 재작성됨(schemaVersion 2, 행/슬롯, dnd-kit)**. `editor.md` |
| 기기 화면 | 상태 조회 + **토큰 발급·페어링 코드 발급 UI 추가**. 페어링 코드는 Pi가 아직 `pair` 명령을 지원하지 않아 UI 안내문으로 토큰 발급을 대신 쓰라고 안내한다(`pairingCodeWarning`, `screens.md` (6)) |
| 상태관리·라우팅 | React Query + React Router, 하단 탭 4개(포맷·예약·지금 인쇄·더보기) |
| 테스트 | **테스트 프레임워크 없음**[확인됨·코드] — `package.json`에 vitest/jest 등이 없다. 검증은 `npm run build`(`tsc -b && vite build`)와 `npm run lint`(`oxlint`)뿐이다. 2026-09-17 재확인: 빌드 0 에러, lint 경고 7개(오류 0) |
