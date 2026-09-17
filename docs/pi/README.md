# docs/pi — Pi 에이전트 문서 인덱스

`/pi`(Orange Pi Zero 2W 에이전트)에 대한 컨텍스트 문서다. **담당: 서버(`justant-server2`) 세션** —
Pi는 `ssh haru-pi`로 다룬다([../environment.md](../environment.md)). 노트북 WSL은 쓰지 않는다.
전체 구조와 API 규약의 원본은 [../architecture.md](../architecture.md), 최초 결정 기록은 [../init_plan.md](../init_plan.md)다.

> **2026-09-17 현재 상태**: **M5 4개 조건 전부 완료.** Pi(`haru-pi`, tailnet)에서 `haru-paper-agent`가
> 실제 `m832` 드라이버(`HARU_PRINTER_DRIVER=m832`, `HARU_TRANSPORT=bt`)로 상시 구동 중이다. 연결 방식은
> `bt`로 확정(V1·V2·V3 통과) — Pi ↔ M832 페어링 완료, `pi/transport/bt.py` 구현(콜드 ACL 재연결
> 워크어라운드 포함, [transport.md](transport.md) 3절), 텍스트+그레이데이션+체커보드 PNG를 실제
> `M832Printer` 드라이버로 BT 전송해 사용자가 출력물을 육안으로 확인했다([setup.md](setup.md) 9절).
> **단, 이 인쇄는 드라이버·전송 계층을 직접 호출한 것이다 — 앱 "지금 인쇄" → 에이전트 실행기 →
> 서버 결과 업로드로 이어지는 체인은 여전히 [미검증]**([agent.md](agent.md) 11절).
> USB 직결(V4)은 진행하지 않는다 — [hardware-verification.md](hardware-verification.md).
>
> **같은 날 M5 이후 커밋 3개(`623a8dc`·`8b7194b`·`51a6a8d`)가 에이전트 동작을 크게 바꿨다 — [확인됨·코드]일 뿐 아직 Pi에 배포되지 않았고 실물로도 확인되지 않았다 [미검증]**:
> 1. 용지 게이트를 fail-closed로 고침(`status_query`는 H4 미판정이라 **항상** `skipped_no_paper`, `manual_flag`는 `paperState` 없으면 skip), `fake` 무성 폴백 제거, `render["path"]` 버그·기동 시 `attempting` 정리 추가, BT 전송에 20초 타임아웃이 실제로 적용되게 수정(agent.md·policy.md·transport.md·printer-m832.md에 반영).
> 2. 유예 안 재시도(`checking`/`attempting` 상태 분리), "지금 인쇄" 명령 실행기(스케줄러 스레드로 직렬화, `HARU_COMMAND_TTL_SEC` 래스터 직전 재확인), 결과 업로드(`missed` 최초 구현)를 실제로 구현(agent.md 7~9절·policy.md 3절).
> 3. 시계 동기화 게이트(`timedatectl`, fail-closed, sticky), `kv.last_tick_at` 되돌아보기(24시간, 60초 쓰기 간격), 보낸 바이트 순환 삭제(`agent/retention.py`)를 실제로 구현(agent.md 4·7절, policy.md 4·7절).
>
> **운영상 중요한 결론**: `HARU_PAPER_POLICY=unverified`가 유지되는 한 **예약 인쇄는 항상 `dry_run`이고, 무인 인쇄가 실제로 되는 정책은 `manual_flag`뿐**이다(`status_query`는 H4 미판정이라 항상 skip — [policy.md](policy.md) 2절). PoC 완료 기준인 "3일 연속 07:00 실제 인쇄" 시험을 하려면 H4 판정 또는 `manual_flag` 전환 결정이 먼저 필요하다.
>
> 전체 테스트 스위트는 **266개 통과**(`cd pi && .venv/bin/python -m pytest tests/ -q`, 2026-09-17 계측).

표기: **[확인됨]** 실물로 눈으로 확인 / **[확인됨·코드]** 코드에서 확인(실물 동작은 별개) / **[미검증]** 확인 전 / **[추정]** 자료·계열 기종 기반 추론 / **[기본값]** 따로 묻지 않고 정한 값(바꿔도 됨)

## 읽는 순서

| 순서 | 문서 | 내용 | 이 문서가 필요한 마일스톤 |
|---|---|---|---|
| — | [../environment.md](../environment.md) | 머신·접속(Pi SSH, sudo 함정), detox-printer 위치 | 전부, 특히 하드웨어 작업 전 |
| 1 | [hardware-verification.md](hardware-verification.md) | 하드웨어 미확정 사항(V0~V4, H4, H5, R5, CG1)과 결정 규칙. **무엇이 아직 모르는 것인지 먼저 파악** | 전부 |
| 2 | [printer.md](printer.md) | 프린터 공통 인터페이스, 프린터 프로필, 서버 PNG ↔ 드라이버 책임 경계, fake 프린터 | M1, M4 |
| 3 | [printer-m832.md](printer-m832.md) | detox-printer에서 이식할 M832 확정 사실, 이식 대상 함수, **M1 통과 조건** | M1 |
| 4 | [transport.md](transport.md) | USB / Bluetooth 전송 계층 | M1, M5 |
| 5 | [agent.md](agent.md) | 폴링 동기화, 로컬 SQLite, occurrence 스케줄러, 실행 흐름, **M4 통과 조건** | M4 |
| 6 | [policy.md](policy.md) | 현재 운영 정책값(용지 정책, 유예·재시도, 시계, 보관) | M4, M5 |
| 7 | [setup.md](setup.md) | Orange Pi Zero 2W 사양, OS 설치, `install.sh`, systemd, **M5** | M5 |

## 마일스톤별 시작점

- **M1 (드라이버 이식 + USB)**: printer-m832.md → printer.md → transport.md
- **M4 (에이전트)**: agent.md → policy.md → ../architecture.md(API)
- **M5 (Pi 실물 설치)**: setup.md → hardware-verification.md(V3·V4) → transport.md

## 규칙 요약 (detox-printer에서 계승)

- 용지 장착을 확인하기 전에는 래스터를 보내지 않는다(용지 정책은 [policy.md](policy.md)).
- 실물로 눈으로 본 것만 [확인됨]이다. M02 등 다른 기종에서 되니까 될 것이라고 가정하지 않는다.
- 새 하드웨어 사실은 `~/Data/detox-printer`에서 먼저 실험·기록([확인됨])한 뒤에만 `/pi/printer/m832`로 옮긴다.
- 하드웨어 결과가 나오면 [hardware-verification.md](hardware-verification.md)의 표와 이 디렉터리의 관련 문서를 함께 고친다.
