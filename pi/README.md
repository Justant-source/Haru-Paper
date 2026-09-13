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

## 현재 상태

- **M0**: 디렉터리 뼈대와 문서만 있다. **코드 없음.**
- 다음: **M1** — `printer/m832` + `transport/usb` 이식, detox-printer `07_print_image.py`와 바이트 동일 검증 ([docs/pi/printer-m832.md](../docs/pi/printer-m832.md))

문서 읽는 순서는 [docs/pi/README.md](../docs/pi/README.md)를 본다.
