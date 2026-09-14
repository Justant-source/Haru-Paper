# /pi — 하루종이 Pi 에이전트

Orange Pi Zero 2W에서 실행되는 Python 에이전트다. 서버에서 예약과 렌더된 용지 PNG를 받아 두었다가,
예약 시각이 되면 프린터(현재 Phomemo M832)로 인쇄한다. 인터넷이 끊겨도 받아 둔 것으로 인쇄한다.

**담당: 노트북 세션** (`~/Data/Haru-Paper`, 프린터가 노트북 USB에 붙어 있음). 서버 세션은 이 디렉터리를 수정하지 않는다.

**m832 프로토콜을 아는 코드는 이 저장소에서 `/pi/printer/m832`뿐이다.** 서버와 앱은 "폭 N px, dpi D의 종이"만 안다.

## 디렉터리

| 경로 | 역할 | 문서 |
|---|---|---|
| `printer/` | 프린터 공통 인터페이스(`profile()`, `status()`, `print_image(png)`) | [docs/pi/printer.md](../docs/pi/printer.md) |
| `printer/m832/` | M832 드라이버 — detox-printer 검증 코드 이식(디더링·정렬보정·패딩·패킹·명령 조립) | [docs/pi/printer-m832.md](../docs/pi/printer-m832.md) |
| `printer/fake/` | 가짜 프린터 — PNG·bin을 파일로만 저장(프린터 없이 개발) | [docs/pi/printer.md](../docs/pi/printer.md) |
| `transport/` | 바이트 전송 계층: `usb`(pyusb) / `bt`(Bluetooth) | [docs/pi/transport.md](../docs/pi/transport.md) |
| `agent/` | 폴링 동기화, PNG 캐시, occurrence 스케줄러, 실행기, 결과 대기열(SQLite) | [docs/pi/agent.md](../docs/pi/agent.md) |
| `deploy/` | `install.sh`, systemd unit | [docs/pi/setup.md](../docs/pi/setup.md) |
| `tests/` | 단위 테스트(바이트 동일성 테스트 포함) | [docs/pi/printer-m832.md](../docs/pi/printer-m832.md) |
| `.env.example` | 환경변수 예시. 실제 값은 `.env`(gitignore) | [docs/pi/agent.md](../docs/pi/agent.md) |

## 현재 상태 (2026-09-14)

**예외적으로 서버 세션이 초기 구현을 했다**(사용자 명시 요청 — 노트북을 계속 켜 둘 수 없어서). 하드웨어(M832, detox-printer)가 없는 서버에서 안전하게 할 수 있는 부분까지만 했고, 나머지는 노트북 세션이 `git fetch`로 받아 이어서 검증한다.

- `printer/m832`: `docs/pi/printer-m832.md` 2절에 이미 [확인됨·실물]로 문서화된 상수·알고리즘을 근거 주석과 함께 포팅. **detox-printer 소스가 이 서버에 없어 직접 복사가 아니라 문서 기반 재작성이다.** 순수 알고리즘 단위 테스트(비트 극성, 헤더/꼬리, 길이 공식)는 통과했지만, **detox-printer의 실제 캡처 바이트(`0002.bin` 등)와의 바이트 단위 동일성 비교, 실물 인쇄는 검증되지 않았다** — 노트북 세션의 M1 절차로 확인 필요.
- `printer/fake`, `transport/usb`(pyusb, 모킹 테스트만 — 장치 없음), `agent/`(폴링·SQLite·스케줄러·실행기): 하드웨어 무관 로직. **실제로 떠 있는 M2 서버(justant-server2)에 붙여 동작 검증함**: 예약 dry_run 기록, 재시작 후 같은 occurrence 중복 실행 안 됨(M4 (a)(d))을 실사로 확인. 오프라인 캐시 실행·복구 후 업로드(M4 (b)(c))는 코드는 있으나 실제 서버 중단 시나리오까지는 검증 못함.
- 통합 중 실사로 발견해 고친 버그: 스냅샷 렌더 필드 `render_id`→`renderId` 등 camelCase 불일치, occurrence 중복 실행 방지 조회가 정시(00:00 등)만 확인해 정시가 아닌 예약 시각(예: 10:31)에서 무한 재실행되던 버그, 렌더 선택에 "오늘 렌더 없으면 최신 렌더로 폴백" 로직 누락.
- `deploy/`(install.sh, systemd unit): 문법 검사만 했다. **이 서버에서 실행하지 않았다** — 운영 중인 다른 프로젝트에 영향을 줄 수 있어서다.
- 다음: **노트북 세션이 M1 절차**(detox-printer 바이트 비교, usbipd 실물 인쇄, printableWidthPx 확정)를 진행한다. [docs/pi/printer-m832.md](../docs/pi/printer-m832.md) 7절.

문서 읽는 순서는 [docs/pi/README.md](../docs/pi/README.md)를 본다.
