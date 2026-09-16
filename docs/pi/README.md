# docs/pi — Pi 에이전트 문서 인덱스

`/pi`(Orange Pi Zero 2W 에이전트)에 대한 컨텍스트 문서다. **담당: 노트북 세션.**
전체 구조와 API 규약의 원본은 [../architecture.md](../architecture.md), 최초 결정 기록은 [../init_plan.md](../init_plan.md)다.

> **2026-09-16 현재 상태**: Pi(`haru-pi`, tailnet)에서 `haru-paper-agent`가 systemd로 떠서 실물로
> 서버를 폴링하고 있고, 재부팅 후 자동 복구도 확인됐다(M5 4개 조건 중 2개 완료) — [setup.md](setup.md) 9절.
> 프린터는 아직 Pi에 물리적으로 연결되지 않았다(`HARU_PRINTER_DRIVER=fake`) — Pi와 M832를 BT/Wi-Fi로만
> 잇기로 확정했고, USB 직결(V4)은 진행하지 않는다 — [hardware-verification.md](hardware-verification.md).

표기: **[확인됨]** 실물로 눈으로 확인 / **[미검증]** 확인 전 / **[추정]** 자료·계열 기종 기반 추론 / **[기본값]** 따로 묻지 않고 정한 값(바꿔도 됨)

## 읽는 순서

| 순서 | 문서 | 내용 | 이 문서가 필요한 마일스톤 |
|---|---|---|---|
| 1 | [hardware-verification.md](hardware-verification.md) | 하드웨어 미확정 사항(V0~V4, H4, H5)과 결정 규칙. **무엇이 아직 모르는 것인지 먼저 파악** | 전부 |
| 2 | [printer.md](printer.md) | 프린터 공통 인터페이스, 프린터 프로필, 서버 PNG ↔ 드라이버 책임 경계, fake 프린터 | M1, M4 |
| 3 | [printer-m832.md](printer-m832.md) | detox-printer에서 이식할 M832 확정 사실, 이식 대상 함수, **M1 통과 조건** | M1 |
| 4 | [transport.md](transport.md) | USB / Bluetooth 전송 계층 | M1, M5 |
| 5 | [agent.md](agent.md) | 폴링 동기화, 로컬 SQLite, occurrence 스케줄러, 실행 흐름, **M4 통과 조건** | M4 |
| 6 | [policy.md](policy.md) | 현재 운영 정책값(용지 정책, 유예·재시도, 시계, 보관) | M4, M5 |
| 7 | [setup.md](setup.md) | Orange Pi Zero 2W 사양, 도착 체크리스트, OS 설치, `install.sh`, systemd, **M5** | M5 |

## 마일스톤별 시작점

- **M1 (드라이버 이식 + USB)**: printer-m832.md → printer.md → transport.md
- **M4 (에이전트)**: agent.md → policy.md → ../architecture.md(API)
- **M5 (Pi 실물 설치)**: setup.md → hardware-verification.md(V3·V4) → transport.md

## 규칙 요약 (detox-printer에서 계승)

- 용지 장착을 확인하기 전에는 래스터를 보내지 않는다(용지 정책은 [policy.md](policy.md)).
- 실물로 눈으로 본 것만 [확인됨]이다. M02 등 다른 기종에서 되니까 될 것이라고 가정하지 않는다.
- 새 하드웨어 사실은 `~/Data/detox-printer`에서 먼저 실험·기록([확인됨])한 뒤에만 `/pi/printer/m832`로 옮긴다.
- 하드웨어 결과가 나오면 [hardware-verification.md](hardware-verification.md)의 표와 이 디렉터리의 관련 문서를 함께 고친다.
