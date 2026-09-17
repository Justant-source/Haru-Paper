# docs — 문서 색인·현재 상태

하루종이(Haru-Paper) 전체 문서의 진입점이다. 규칙·경계·절대 금지는 [`../CLAUDE.md`](../CLAUDE.md)가 정본이고, 여기서는 **어떤 문서에 무엇이 있는지**와 **지금 어디까지 됐는지**만 다룬다.

> 표기: **[확인됨]** 실물로 눈으로 확인 / **[미검증]** 확인 전 / **[추정]** 자료·계열 기종 기반 추론 / **[기본값]** 따로 묻지 않고 정한 값(바꿔도 됨)

## 1. 문서 색인

| 문서 | 내용 |
|---|---|
| [`init_plan.md`](init_plan.md) | 최초 합의(Q1~Q34), 인프라 조사, 마일스톤 M0~M5와 통과 조건. **원본 기록 — 절대 고치지 않는다** |
| [`architecture.md`](architecture.md) | 전체 구조, 구성요소 경계, 도메인 모델(포맷 v2), **API 규약 원본**(앱 인증·Pi 동기화), 동기화 흐름, 향후 확장 |
| [`environment.md`](environment.md) | 지금 쓰는 머신 3대, SSH·tailnet 접속, 서버 sudo/TTY 함정, sudoers 규칙, detox-printer 위치 |
| [`pi/README.md`](pi/README.md) | Pi 에이전트·프린터 드라이버 문서 목차. 읽는 순서와 마일스톤별 시작점 안내 |
| [`server/README.md`](server/README.md) | 서버(Spring Boot) 문서 목차 |
| [`app/README.md`](app/README.md) | 웹앱(React PWA) 문서 목차 |
| [`../.temp/`](../.temp/) | **진행 중인 계획서.** 완료된 항목만 이 아래 docs로 옮겨 반영하고, 계획서 자체는 개발이 끝나면 삭제한다 |

### `docs/pi/*` (담당: 서버 세션, `ssh haru-pi`로 원격 작업)

| 문서 | 내용 |
|---|---|
| [`pi/hardware-verification.md`](pi/hardware-verification.md) | V0~V4·H4·H5·R5·CG1 하드웨어 미확정 사항 현황표, transport·용지 정책 결정 규칙 |
| [`pi/transport.md`](pi/transport.md) | `usb`(M1에서 구현됨)·`bt`(V2로 확정, 구현 예정) 전송 계층 |
| [`pi/printer-m832.md`](pi/printer-m832.md) | M832 프로토콜 상수·명령, detox-printer에서 이식한 근거 |
| [`pi/printer.md`](pi/printer.md) | 프린터 공통 인터페이스, 프린터 프로필, fake 드라이버 |
| [`pi/agent.md`](pi/agent.md) | 폴링 동기화, 로컬 SQLite, occurrence 스케줄러, 실행 흐름 |
| [`pi/policy.md`](pi/policy.md) | 용지 정책 3종, 유예·재시도, 시계(RTC 없음), 보관 기간 |
| [`pi/setup.md`](pi/setup.md) | Orange Pi 사양, `install.sh`, systemd, overlayfs, M5 체크리스트 |

### `docs/server/*` (담당: 서버 세션)

| 문서 | 내용 |
|---|---|
| [`server/auth.md`](server/auth.md) | M6: 세션 로그인·가입, 역할, 기기별 토큰·페어링 코드, 소유권 스코핑, 관리자 API |
| [`server/api.md`](server/api.md) | 컨트롤러별 경로·인증, 오류 형식, 멱등, curl 시나리오 |
| [`server/data-model.md`](server/data-model.md) | MariaDB 테이블(Flyway V1~V3 적용됨, V4 파일 작성·미적용), 파일 저장 |
| [`server/format-schema.md`](server/format-schema.md) | **포맷 JSON 스키마 v2(행/슬롯) 원본** |
| [`server/rendering.md`](server/rendering.md) | 포맷 → HTML → Chromium → 그레이스케일 PNG, 샌드박스, 1-bpp(PBM) 구현됨[확인됨·코드] |
| [`server/weather.md`](server/weather.md) | 날씨 블록: Open-Meteo, 기본 위치 |
| [`server/deploy.md`](server/deploy.md) | Docker compose, 포트·`tailscale serve`, 백업, `.env`, 일회성 운영 작업(V4 백필·`claim-legacy`·소유권 엄격 모드) |

### `docs/app/*` (담당: 서버 세션)

| 문서 | 내용 |
|---|---|
| [`app/web.md`](app/web.md) | 웹앱 스택(React+TS+Vite PWA), 인증 흐름, API 클라이언트 |
| [`app/screens.md`](app/screens.md) | 화면 11개: 표시 요소, 동작, 호출 API |
| [`app/editor.md`](app/editor.md) | 드래그 기반 레이아웃 편집기(schemaVersion 2, dnd-kit) |
| [`app/native.md`](app/native.md) | `/app/android`, `/app/ios` 예약, 네이티브 기술 후보(미정) |

## 2. 현재 상태 (2026-09-17)

이 표가 바뀌면 [`../CLAUDE.md`](../CLAUDE.md)의 "현재 상태" 표도 같이 고친다.

### 마일스톤

| | 내용 | 상태 |
|---|---|---|
| M0 | 저장소 뼈대, 문서 | 완료 |
| M1 | M832 드라이버 이식 + USB/BT transport | 코드 있음(`pi/printer/m832`, `pi/transport/{usb,bt}.py`). 실물 인쇄는 M5에서 BT로 완료(텍스트·그레이스케일 포함). detox-printer 기준 바이트 단위 비교(통과 조건 1)만 남음 |
| M2 | 서버(Spring Boot + MariaDB + Flyway) | 완료 |
| M3 | 웹앱(PWA) | 완료 |
| M4 | Pi 에이전트(폴링·스케줄러·대기열) | 코드 있음(`pi/agent/`), Pi에서 상시 구동 중이나 **배포본은 뒤처져 있다**: Pi(`haru-pi`)는 `581899e`(9/17 11:47)까지만 반영됐다(원격보다 12커밋 뒤, 2026-09-17 `ssh haru-pi`로 확인). 그 뒤 **Pi 코드를 바꾼 커밋 3개가 전부 미배포**다 — 용지 게이트 fail-closed·fake 무성 폴백 제거·중복 인쇄 수정(`623a8dc`), 유예 내 재시도·"지금 인쇄" 실행기·결과 업로드(`8b7194b`), 시계 동기화 게이트·`last_tick_at` 되돌아보기·보낸 바이트 순환 삭제(`51a6a8d`). 따라서 **Pi는 지금 옛 fail-open 용지 정책 코드로 돌고 있다** — `HARU_PAPER_POLICY=unverified`라 예약은 `dry_run`으로 끝나 래스터가 나가지 않지만, **배포 전에 정책을 `manual_flag`·`status_query`로 바꾸면 용지 확인 없이 인쇄될 수 있다**(절대금지 1). 세 커밋 모두 [미검증]. 단위 테스트(`pi/tests`)는 266개 전부 통과([`pi/agent.md`](pi/agent.md) 참고) |
| M5 | Pi 실물 설치, 연결 방식 결정 | **완료(4/4).** 재부팅 자동시작·서버 폴링·transport=`bt` 확정에 이어, `pi/transport/bt.py` 구현(콜드 ACL 워크어라운드 포함) + Pi ↔ M832 페어링 + 실물 인쇄 1회(텍스트+그레이데이션+체커보드, 사용자 육안 확인) 전부 [확인됨·실물, 2026-09-17]. `HARU_PRINTER_DRIVER=m832` 상시. **단, 이 인쇄는 `M832Printer`+`BtTransport` 직접 호출로 이뤄졌고, 앱 "지금 인쇄" → 에이전트 실행기 → 서버 결과 업로드로 이어지는 체인은 여전히 [미검증]**([pi/agent.md](pi/agent.md) 11절) |
| M6 | 계정·기기 소유(세션 로그인, 기기별 토큰, 페어링 코드, 관리자) | 구현 완료. 문서는 `server/auth.md`·`app/web.md`·`app/screens.md`에 반영. **단 통과 조건은 미충족** — `.temp/03` 4.5절의 "관리자가 레거시 데이터를 claim"(V4 백필 적용 → `claim-legacy` 실행)이 아직 실행되지 않았다(`server/deploy.md` 7절 런북은 작성됨, 미실행). `CLAUDE.md` "통과 조건을 만족하기 전에 다음 마일스톤으로 넘어가지 않는다" 규칙상 M6은 아직 통과 전이다 |
| M7 | 레이아웃·위젯 엔진 | **부분.** 포맷 스키마 v2(행/슬롯)와 드래그 편집기(dnd-kit)는 구현됨. `haru-widget-runner`(Node 샌드박스)는 **미구현** |
| M8 | 작가·글·구독·피드 | 미착수 |
| M9 | 위젯 에디터·마켓 | 미착수 |
| M10 | 외부 공개 준비 | 미착수 |

PoC 완료 시험(WAN 차단 상태 07:00 인쇄 + 복구 후 이력, 3일 연속)과 30일 연속 운영은 M5 다음 단계다.

### 하드웨어 검증 (V0~V4, H4, H5, R5, CG1)

원본은 [`pi/hardware-verification.md`](pi/hardware-verification.md), 실험 기록 원본은 `~/Data/detox-printer/m832/docs/findings.md`.

| ID | 내용 | 상태 |
|---|---|---|
| V0 | 노트북 USB 연결 상태 1시간+ 안 꺼짐 | [관찰·미검증] — V1의 비교 기준일 뿐 |
| V1 | 충전기만 연결해도 켜져 있는가 | **[확인됨·실물]** 통과 — 8시간 뒤에도 켜짐 |
| V2 | M832 BT 지원 + 검증된 바이트 BT 전송 인쇄 | **[확인됨·실물]** 통과 — SPP/RFCOMM 채널 1로 체커보드 2장 정상 인쇄, 사용자 육안 확인 |
| V3 | Pi 내장 BT 재부팅 20회 생존 | **[확인됨·실물]** 통과 — 20/20 |
| V4 | USB 직결 시 Pi 전압강하·재부팅 | **대상 제외** — Pi·M832를 BT/Wi-Fi로만 잇기로 확정, 시험하지 않음 |
| H4 | 상태 조회로 용지 있음/없음 구분 가능한가 | **부분 진행, 2026-09-17** — findpaper 단독 조회 응답이 `1a 06 89`(3바이트)로 5/5 고정 재현됨을 확인. 용지 유무가 이 값에 반영되는지는 아직 미확정 |
| H5 | 빽빽한 텍스트에서 줄 누락 있는가 | 대기 |
| R5 | 재시도마다 전송 전 `printer.status()`로 BT를 다시 여는 것이 M832/BlueZ에 주는 영향 | **대기 [미검증]** — 단발 재연결만 3/3 확인, 유예 30분 동안 60초 간격 반복은 실물 미확인. 30일 운영 중 관찰 |
| CG1 | 인터넷 없는 재부팅에서 시계 동기화 게이트가 실제로 예약·명령을 보류하는가 | **대기 [미검증]** — 단위 테스트로만 확인. **PoC 3일 시험("WAN을 뽑은 상태") 전에 반드시 실물 확인** |

**결과**: V1·V2·V3 모두 통과 → **`HARU_TRANSPORT=bt`** 확정(SPP/RFCOMM 채널 1). H4 결과로 용지 정책(`status_query` vs `manual_flag`)이 정해진다. 서버의 1-bpp(PBM) 출력은 2단계(ESP32) 요구로 승인됐고 **구현됨**[확인됨·코드: `PbmConverter`, `RenderServiceImpl`, `DeviceSyncController.getRenderPbm()`, `V3__render_pbm.sql`] — 실제 배포·기기 연동은 아직 [미검증]이다.

**프린터는 1호기 1대뿐**이고 추가 구매 없이 진행한다 — 30일 연속 운영과 2단계(ESP32) 실물 시험은 같은 프린터를 순서대로 쓴다.

**PoC 3일 연속 시험의 선결 조건**: 현재 `HARU_PAPER_POLICY=unverified`([`pi/policy.md`](pi/policy.md))에서는 예약 인쇄가 항상 `dry_run`으로만 기록되고, `status_query`는 H4가 끝나지 않아 아직 쓸 수 없다 — **예약이 실제로 인쇄되는 정책은 `manual_flag`뿐이다.** PoC 시험을 시작하려면 H4 판정(용지 유무가 상태 조회 응답에 반영되는지)을 마치거나, `manual_flag`로 전환하는 결정이 먼저 필요하다.

## 3. 작업별 읽는 순서

| 지금 하려는 작업 | 먼저 읽을 문서 |
|---|---|
| Pi 코드(드라이버·에이전트·transport) 작업 | [`pi/README.md`](pi/README.md) → 그 안의 읽는 순서표 |
| 서버(Spring Boot) 작업 | [`server/README.md`](server/README.md) → `architecture.md` |
| 앱(React) 작업 | [`app/README.md`](app/README.md) → `architecture.md` → `server/format-schema.md` |
| 하드웨어 실험(BT·용지 감지·배터리 등) | [`pi/hardware-verification.md`](pi/hardware-verification.md) → `~/Data/detox-printer`(그 저장소의 `CLAUDE.md`·`m832/docs/findings.md`) |
| Pi에 SSH로 접속, 서버 sudo/sudoers 다루기 | [`environment.md`](environment.md) |
| 플랫폼(M6~M10) 사양 확인 | `../.temp/03-플랫폼-작업지시서-v1.0.md` |
| ESP32 2단계 계획 확인 | `../.temp/02-esp32-디바이스-계획서-v1.4.md` |
| 오렌지파이 PoC 계획 확인 | `../.temp/01-orangepi-poc-작업지시서-v1.4.md` |

새 문서를 만들 때는 이 색인 표와 [`../CLAUDE.md`](../CLAUDE.md)의 "docs 안내" 표를 같이 갱신한다.
