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
| [`pi/hardware-verification.md`](pi/hardware-verification.md) | V0~V4·H4·H5 하드웨어 미확정 사항 현황표, transport·용지 정책 결정 규칙 |
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
| [`server/data-model.md`](server/data-model.md) | MariaDB 테이블(Flyway V1·V2), 파일 저장 |
| [`server/format-schema.md`](server/format-schema.md) | **포맷 JSON 스키마 v2(행/슬롯) 원본** |
| [`server/rendering.md`](server/rendering.md) | 포맷 → HTML → Chromium → 그레이스케일 PNG, 샌드박스, 1-bpp(PBM) 예정 |
| [`server/weather.md`](server/weather.md) | 날씨 블록: Open-Meteo, 기본 위치 |
| [`server/deploy.md`](server/deploy.md) | Docker compose, 포트·`tailscale serve`, 백업, `.env` |

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
| M1 | M832 드라이버 이식 + USB transport | 코드 있음(`pi/printer/m832`, `pi/transport/usb.py`). "실물 1회"는 M5로 이월 |
| M2 | 서버(Spring Boot + MariaDB + Flyway) | 완료 |
| M3 | 웹앱(PWA) | 완료 |
| M4 | Pi 에이전트(폴링·스케줄러·대기열) | 코드 있음(`pi/agent/`), Pi에서 상시 구동 중 |
| M5 | Pi 실물 설치, 연결 방식 결정 | **4개 중 3개 완료.** 재부팅 자동시작 [확인됨·실물], 서버 폴링 [확인됨·실물], transport 결정 = `bt` [확인됨·실물]. 남은 것: `pi/transport/bt.py` 작성 → Pi에서 M832 페어링 → 실물 인쇄 1회 |
| M6 | 계정·기기 소유(세션 로그인, 기기별 토큰, 페어링 코드, 관리자) | **구현 완료.** 문서는 `server/auth.md`·`app/web.md`·`app/screens.md`에 반영 |
| M7 | 레이아웃·위젯 엔진 | **부분.** 포맷 스키마 v2(행/슬롯)와 드래그 편집기(dnd-kit)는 구현됨. `haru-widget-runner`(Node 샌드박스)는 **미구현** |
| M8 | 작가·글·구독·피드 | 미착수 |
| M9 | 위젯 에디터·마켓 | 미착수 |
| M10 | 외부 공개 준비 | 미착수 |

PoC 완료 시험(WAN 차단 상태 07:00 인쇄 + 복구 후 이력, 3일 연속)과 30일 연속 운영은 M5 다음 단계다.

### 하드웨어 검증 (V0~V4, H4, H5)

원본은 [`pi/hardware-verification.md`](pi/hardware-verification.md), 실험 기록 원본은 `~/Data/detox-printer/m832/docs/findings.md`.

| ID | 내용 | 상태 |
|---|---|---|
| V0 | 노트북 USB 연결 상태 1시간+ 안 꺼짐 | [관찰·미검증] — V1의 비교 기준일 뿐 |
| V1 | 충전기만 연결해도 켜져 있는가 | **[확인됨·실물]** 통과 — 8시간 뒤에도 켜짐 |
| V2 | M832 BT 지원 + 검증된 바이트 BT 전송 인쇄 | **[확인됨·실물]** 통과 — SPP/RFCOMM 채널 1로 체커보드 2장 정상 인쇄, 사용자 육안 확인 |
| V3 | Pi 내장 BT 재부팅 20회 생존 | **[확인됨·실물]** 통과 — 20/20 |
| V4 | USB 직결 시 Pi 전압강하·재부팅 | **대상 제외** — Pi·M832를 BT/Wi-Fi로만 잇기로 확정, 시험하지 않음 |
| H4 | 상태 조회로 용지 있음/없음 구분 가능한가 | 대기 — BT 상태 조회 재조사(전송 후 11byte 응답, findpaper 직후 read 등) |
| H5 | 빽빽한 텍스트에서 줄 누락 있는가 | 대기 |

**결과**: V1·V2·V3 모두 통과 → **`HARU_TRANSPORT=bt`** 확정(SPP/RFCOMM 채널 1). H4 결과로 용지 정책(`status_query` vs `manual_flag`)이 정해진다. 서버의 1-bpp(PBM) 출력은 2단계(ESP32) 요구로 승인됐으나 **미구현**이다.

**프린터는 1호기 1대뿐**이고 추가 구매 없이 진행한다 — 30일 연속 운영과 2단계(ESP32) 실물 시험은 같은 프린터를 순서대로 쓴다.

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
