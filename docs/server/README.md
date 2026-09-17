# docs/server — 서버 컨텍스트

하루종이 백엔드(`/server`)의 컨텍스트 문서다. `justant-server2` 세션이 담당한다.

> 표기: **[확인됨]**/**[확인됨·코드]** 실제 확인 / **[미검증]** 확인 전 / **[추정]** 자료 기반 추론 / **[기본값]** 사용자에게 따로 묻지 않고 정한 값(바꿔도 됨, 바꾸면 이 문서를 고친다)

## 현재 상태 (2026-09-17)

M2(서버 스캐폴드·도메인·API)·M3(웹앱)·M6(계정·기기·소유권)까지 구현 완료. M7(레이아웃·위젯)은 포맷 스키마 v2(행/슬롯) + 드래그 편집기까지만 됐고 `haru-widget-runner`는 **미구현**. M8~M10은 미착수(`.temp/03-플랫폼-작업지시서-v1.0.md` 참고).

## 읽는 순서

1. [`../../CLAUDE.md`](../../CLAUDE.md): 규칙, Git 규칙, 절대 금지
2. [`../init_plan.md`](../init_plan.md): 최초 결정 원본(Q1~Q34), M0~M5 마일스톤
3. [`../architecture.md`](../architecture.md): 전체 구조, 경계, **API 규약 원본**
4. [`deploy.md`](deploy.md): compose, 노출(`tailscale serve`), 백업
5. [`auth.md`](auth.md): **M6** 세션 인증·CSRF·기기 토큰·페어링 코드·관리자 API
6. [`format-schema.md`](format-schema.md): 포맷 스키마 **v2**(행/슬롯) 원본
7. [`data-model.md`](data-model.md): MariaDB 테이블(V1·V2), Flyway, 파일 저장
8. [`api.md`](api.md): 컨트롤러 13개, 인증, 에러 형식, 멱등, snapshotHash, curl 시나리오
9. [`rendering.md`](rendering.md): 포맷 → HTML → Chromium → PNG, 렌더 스케줄러
10. [`weather.md`](weather.md): Open-Meteo, 날씨 블록

## 서버가 맡는 것 / 모르는 것

| 맡는 것 | 모르는 것 |
|---|---|
| 계정·세션·기기 소유권([`auth.md`](auth.md)) | M832 프로토콜(헤더, 래스터, 정렬보정) |
| 포맷·에셋·예약·명령·결과 저장(사용자별) | Pi의 transport(BT 확정, USB는 M1) |
| 프린터 프로필 폭으로 **그레이스케일 PNG**(+1-bpp PBM, 미구현) 렌더 | 용지 감지 방식의 세부 |
| 날씨 조회, 앱 API, Pi 동기화 API(폴링) | 사용자 위젯 스크립트(M7, 미구현 — `/server/runner`가 맡을 예정) |

좌우 정렬 보정, 1304dot 패딩, M832 헤더·꼬리 조립, 전송은 **Pi 드라이버 몫**이다. 서버는 `printerProfile.printableWidthPx` 폭의 그레이스케일 PNG(+ 승인된 1-bpp PBM, 미구현)까지만 만든다.

## M2·M6 통과 기록

M2 통과 조건(`init_plan.md` 10절: compose 기동, `tailscale serve` HTTPS 200, curl 시나리오 통과, 미리보기 폭 일치, 한글 렌더, 백업 1회)은 전부 확인됨 — [`deploy.md`](deploy.md) 7절. M6 통과 조건(로그인 세션 격리, 리소스 소유권 스코핑, 기기별 토큰)은 [`auth.md`](auth.md).
