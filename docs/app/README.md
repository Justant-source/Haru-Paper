# docs/app — 앱 문서 인덱스

하루종이 클라이언트(`/app`)의 설계 컨텍스트다. **서버 세션이 담당**하며, M3(웹앱)를 시작하기 전에 아래 순서로 읽는다.

> 표기: **[확인됨]** 실물로 확인 / **[미검증]** 확인 전 / **[추정]** 추론 / **[기본값]** 사용자에게 따로 묻지 않고 정한 값(M3에서 바꿔도 됨)

## 읽는 순서

1. [../init_plan.md](../init_plan.md) — 최초 결정 원본(특히 1·2·6·7·8.3·10절)
2. [../architecture.md](../architecture.md) — 전체 구조, 구성요소 경계, **API 규약 원본**
3. [../server/format-schema.md](../server/format-schema.md) — **포맷 JSON 스키마 원본**(편집기가 따라야 함)
4. [web.md](web.md) — 웹앱 스택, 폴더 구조, PWA, 배포, M3 통과 조건
5. [screens.md](screens.md) — 화면 7개: 표시 요소, 동작, 호출 API, 빈/오류 상태
6. [native.md](native.md) — `/app/android`, `/app/ios` 예약과 네이티브 기술 후보(미정)

## 한눈에 보기

- **PoC 클라이언트는 웹앱 하나**: React + TypeScript + Vite + PWA, 폰(Android `s21`) 홈 화면에 설치
- **접속**: Tailscale 내부망 전용, `tailscale serve` HTTPS. 로그인 없음
- **같은 origin**: `/` = 웹앱, `/api` = Spring
- **앱은 프린터를 모른다**: 미리보기는 서버가 렌더한 PNG를 그대로 보여준다(인쇄물과 동일)
- **네이티브**: 빈 폴더만. 기술은 PoC 이후 결정

## 상태

| 항목 | 상태 |
|---|---|
| `/app/web` 코드 | M0: 없음. M3에서 생성 |
| `/app/android`, `/app/ios` | 빈 폴더(`.gitkeep`) 예약 |
| 상태관리·라우팅·UI 라이브러리 | [기본값] 제안만, M3에서 확정 |
