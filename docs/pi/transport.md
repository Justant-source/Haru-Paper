# 전송 계층 (`/pi/transport`)

> 드라이버가 만든 바이트를 프린터로 보내고, 필요하면 응답을 읽는 계층. 바이트의 의미는 모른다.
> 선택은 `HARU_TRANSPORT=usb | bt`. 최종 선택은 [hardware-verification.md](hardware-verification.md)의 V1~V4 결정 규칙을 따른다 — **`bt` 확정(V1·V2·V3 통과, 2026-09-17)**.
> 최초 결정: [../init_plan.md](../init_plan.md) 8.1, Q12·Q22. 코드: `usb`는 M1에서 작성됨(`pi/transport/usb.py`), `bt`는 V2 통과로 M5에서 작성됐다(3절 확정값대로, `pi/transport/bt.py`).

## 1. 인터페이스 초안 [기본값]

| 메서드 | 설명 |
|---|---|
| `open()` | 장치 연결. 실패 시 원인이 드러나는 예외(장치 없음, 권한, 페어링 안 됨 등) |
| `write(data, timeout_ms)` | 전부 보낼 때까지 청크로 나눠 전송. 부분 전송·타임아웃은 **예외로 올린다(삼키지 않음)** |
| `read(max_bytes, timeout_ms)` | 응답 읽기. 타임아웃은 "응답 없음"으로 구분해 돌려준다(오류와 구분) |
| `close()` | 해제. 컨텍스트 매니저(`with`)로 쓰게 한다 |

- 모든 I/O에 타임아웃을 명시한다.
- 청크 크기·청크 간격은 transport별 설정값으로 둔다(USB와 BT가 다르다).
- 흐름 제어(H5)가 필요해지면 드라이버가 `write` 사이에 상태 조회(`write` + `read`)를 끼울 수 있어야 한다. 그래서 `write`는 한 번에 전체를 보내는 방식 하나로 고정하지 않고, 드라이버가 청크 단위로 호출할 수도 있게 설계한다 [기본값].
- 한 번에 한 작업만 연결을 쓴다. agent가 인쇄를 순서대로 실행하므로 동시 접근은 없다([agent.md](agent.md)).

## 2. USB (`transport/usb`) — M1

detox-printer에서 실물 검증된 값을 그대로 쓴다. 근거는 [printer-m832.md](printer-m832.md) 2.1절.

| 항목 | 값 | 상태 |
|---|---|---|
| 라이브러리 | pyusb (libusb-1.0) | [확인됨·실물] |
| 장치 | VID:PID `0483:5740`, Interface 0 | [확인됨] |
| 엔드포인트 | OUT `0x02`, IN `0x81` (wMaxPacketSize 64) | [확인됨] |
| 순서 | (활성 시) detach_kernel_driver → set_configuration → claim_interface → write → release_interface → dispose_resources | [확인됨·실물] |
| 청크 | 4096바이트 | [확인됨·실물] |
| write 타임아웃(청크당) | 5000ms | [확인됨·실물] |
| write 전체 데드라인 | 60초 (청크 합산) | [기본값] — 청크별 타임아웃은 통과해도 프린터가 중간에 소비를 멈추면(용지 걸림 등) 총 시간이 쌓여 폴링 스레드를 오래 막을 수 있다. `.temp/01-orangepi-poc-작업지시서-v1.4.md` 4장 아이디어를 이 프로젝트의 pyusb 기반 transport(usblp 아님)에 맞게 적용 |
| read | 전송 후 64바이트 읽기는 무응답이었음 | [확인됨·무응답] |

### 주의

- **노트북 배제 — 과거 기록**: usbipd(WSL attach 끊김)·CUPS 충돌은 노트북 WSL에서 USB 개발할 때 겪은 문제였다. 노트북은 더 이상 이 저장소를 담당하지 않는다([environment.md](../environment.md)). Pi에는 usbipd·CUPS 둘 다 해당 없음.
- **권한 (Pi)**: root가 아닌 서비스 사용자가 장치에 접근할 수 있도록 `0483:5740` udev 규칙을 `install.sh`가 설치한다 [기본값] ([setup.md](setup.md)).
- **전원 (Pi, V4)**: Pi USB1(호스트 전용)에 직결하면 Pi가 프린터에 5V를 공급하고 프린터 충전 전류(최대 2A)를 끌어간다. 보드 전원 규격은 5V 2A라 전압 강하·재부팅 위험이 있다 [추정]. V4는 5V 3A 어댑터로 시험한다.

## 3. Bluetooth (`transport/bt`) — V2 통과, 확정값

**V2 통과 [확인됨·실물, 2026-09-17]**: 검증된 체커보드 바이트(detox-printer `captures/sent/0002.bin` — USB E-3로 인쇄 확인된 것과 동일)를 SPP/RFCOMM 채널 1로 보내 **같은 출력물이 2장(2회 전송분) 왜곡·반전 없이 인쇄**된 것을 사용자가 육안 확인했다. 실험은 서버(`justant-server2`, Realtek BT)에서 detox-printer 규칙대로 했다 — findings "V2" 5건, `m832/src/10_bt_rfcomm_replay.py`. V1(충전기만 8시간 생존)·V3(Pi 내장 BT 재부팅 20/20)도 통과 → [hardware-verification.md](hardware-verification.md) 결정 규칙 1번대로 **`HARU_TRANSPORT=bt` 확정**.

| 항목 | 확정값 | 상태 | 근거 |
|---|---|---|---|
| 방식 | **Classic SPP, RFCOMM 채널 1** (서비스명 `JL_SPP`, 0x1101) | [확인됨·실물] | findings "V2 — SPP(RFCOMM) 채널 확인" |
| HCRP | L2CAP PSM 4107도 광고하나 `connect()`가 `EPERM`(root·페어링 후에도) — **쓰지 않는다** | [확인됨·실패, 원인 미해결] | findings "V2 — BT 서비스 탐색", `09_bt_l2cap_replay.py` |
| BLE GATT | 미실험 | [추정] (M04 계열 `ff00`/`ff02`) | 필요해지면 detox-printer에서 |
| 주소 | `C5:0D:F7:B7:B2:A1` → `HARU_BT_ADDRESS` | [확인됨·실물] | 기기 대장 1호기, SDP 스캔 |
| 페어링 | `bluetoothctl pair` — Just Works(PIN 없음). 운영 기기는 `trust`까지 | [확인됨·실물] (서버에서) | findings "V2 — 페어링 성공" |
| 바이트 스트림 | USB와 **완전히 동일**(`1F 11 …` 헤더 + 비트맵 + `1B 64 …` 꼬리). 드라이버 상수 변경 없음 | [확인됨·실물] | 같은 파일 → 같은 출력물 |
| 소켓 | Python 표준 `socket.AF_BLUETOOTH` + `SOCK_STREAM` + `BTPROTO_RFCOMM`, `connect((addr, 1))` | [확인됨·실물] | `10_bt_rfcomm_replay.py` |
| 청크 | 4096바이트 연속 `send()`, 흐름 제어·간격 없음. 106,300바이트를 축소 없이 한 번에 | [확인됨·실물] | 같은 스크립트 (MIN_CHUNK=64 폴백 미발동) |
| 타임아웃 | connect 10초, 청크당 write 20초, 응답 read 3초 | [확인됨·실물] (이 값으로 실패 없음) | 같은 스크립트 |
| write 전체 데드라인 | 60초 — USB와 동일 | [기본값] | 2절 |
| 연결 유지 | 전송 시작~완료까지 한 연결, 끝나면 닫는다 | [확인됨·실물] | 같은 스크립트 |
| 전송 후 응답 | **11바이트 `1a 3e 00 00 1a 3b 04 19 00 05 00`** 수신(USB에서는 무응답) | [확인됨·수신], 의미 [미검증] | findings "V2 — RFCOMM… 전송 성공" |
| 계열 자료의 256B/20ms 청크 | 필요 없었다 | [확인됨·실물] | 위 청크 행 |

### 발견된 버그 2건 [고침, 2026-09-17] — `HARU_PRINTER_DRIVER=fake`인 동안 안 드러났던 것들

1. **import 경로 버그**: `transport/usb.py`·`transport/bt.py`가 `from pi.printer.m832.constants import ...`로
   돼 있었다. 이건 저장소 루트가 `sys.path`에 있을 때만(예: `pytest`를 저장소 루트에서 `-m`으로 실행할 때
   우연히) 동작하고, **실제 운영 환경**(`systemd`의 `WorkingDirectory=/opt/haru-paper/pi`, `ExecStart=...
   python -m agent` — `pi/`가 cwd)에서는 `ModuleNotFoundError: No module named 'pi'`로 깨진다. 코드베이스의
   실제 관례(`printer/m832/driver.py` 등)는 `pi.` 접두어 없이 `from printer... / from transport...`다 —
   두 파일을 그 관례로 맞췄다. Pi에서 직접 `cwd=/opt/haru-paper/pi`로 재현·검증함.
2. **transport 미변환 버그**: `pi/agent/__main__.py`의 `_load_printer("m832")`가
   `M832Printer(transport=self.config.transport, ...)`처럼 **`HARU_TRANSPORT` 문자열**(`"usb"`/`"bt"`)을
   그대로 넘기고 있었다 — 실제 `UsbTransport()`/`BtTransport()` 객체로 바꾸는 코드가 없었다.
   `HARU_PRINTER_DRIVER=fake`였던 동안은 이 경로를 한 번도 안 타서 드러나지 않았고, `m832`로 바꾸는
   순간 드라이버가 문자열에 `with transport:`를 걸며 즉시 크래시났을 것이다. `_load_transport()`
   메서드를 신설해 `HARU_TRANSPORT`에 맞는 실제 Transport 인스턴스를 만들어 주입하도록 고쳤다
   (`bt`인데 `HARU_BT_ADDRESS`가 비어 있으면 fake로 조용히 안 넘어가고 바로 에러).
3. 재발 방지 테스트 9개 신설(`pi/tests/test_agent_bootstrap.py`), 전체 스위트 133개 통과. `cwd=pi/`로
   실물 venv에서 `_load_printer("m832")`(transport=bt)까지 엔드투엔드로 재현·확인함.

### Pi 구현 [확인됨·실물, 2026-09-17] — 페어링·코드·연결 테스트 완료, 실물 인쇄 1회 완료(한계는 아래 참고)

- **Pi ↔ M832 페어링 완료**: `bluetoothctl pair`+`trust`, `Paired: yes`/`Bonded: yes`/`UUID: Serial Port, HCR Print`(서버와 동일). Pi에서 `sdptool search SP`로 RFCOMM 채널 1도 재확인함.
- **`pi/transport/bt.py` 작성 완료**: `open()` = 소켓 생성·connect(실패 시 원인이 드러나는 `TransportError`), `write()` = 4096 청크·청크당 타임아웃(기본 20000ms)·총 데드라인 60초·부분 전송은 계속 이어보냄(소켓 일반 규약)·`send()`가 0을 반환하면 예외, `read()` = 타임아웃/빈 응답이면 `None`, `close()`(idempotent). 상수는 `pi/printer/m832/constants.py`의 `BT_*`에 근거 주석과 함께 추가. `usb.py`·`bt.py` 둘 다 클래스 속성 `DEFAULT_WRITE_TIMEOUT_MS`를 노출한다(`usb.py`는 `USB_WRITE_TIMEOUT_MS`=5000, `bt.py`는 `BT_WRITE_TIMEOUT_MS`=20000를 그대로 가리킴). 단위 테스트 `pi/tests/test_bt_transport.py`(43개, 전부 socket 모킹) — `write()`를 호출하지 않는 순수 연결 테스트 케이스도 포함해 실수로 데이터가 나가지 않는지까지 테스트로 고정해뒀다. **전체 스위트 166개 통과, `test_bt_transport.py`만 43개 통과(2026-09-17 계측, `cd pi && .venv/bin/python -m pytest tests/ -q` / `tests/test_bt_transport.py -q`).**
- **드라이버가 이제 실제로 이 타임아웃 값을 쓴다(2026-09-17 수정)**: `printer/m832/driver.py`의 `print_image()`가 예전에는 transport 종류와 무관하게 `USB_WRITE_TIMEOUT_MS`(5000ms)를 하드코딩해 넘기고 있었다 — BT로 전송해도 청크당 5초 만에 타임아웃 판정이 날 수 있는 버그였다. 지금은 `transport.DEFAULT_WRITE_TIMEOUT_MS`를 조회해 `transport.write(command, timeout_ms=...)`에 그대로 넘긴다(속성이 없는 transport가 오면 USB 값으로 폴백하며 경고 로그). 즉 **이 절의 BT 확정값 20000ms가 이제 실제 운영 경로(`M832Printer.print_image` → `BtTransport.write`)에서 쓰인다.**
- **실물 연결 테스트(안전 — write() 호출 없음)**: Pi에서 `open()` 직후 바로 `close()`만 실행(래스터·어떤 바이트도 전송 안 함). 페어링 직후 첫 시도는 타임아웃, 이후 성공 — 아래 "주의"의 재연결 항목 참고.
- `pi/.env`(운영, `/opt/haru-paper/pi/.env`): `HARU_TRANSPORT=bt`, `HARU_BT_ADDRESS=C5:0D:F7:B7:B2:A1`, `HARU_PRINTER_DRIVER=m832`로 전환·재시작 완료(poll 200, snapshot 200 확인). **M5 실물 인쇄 1회 완료(2026-09-17)** — `M832Printer`+`BtTransport`로 텍스트+그레이데이션+체커보드 PNG를 BT 전송, 사용자 육안 확인([setup.md](setup.md) 9절). **단, 이 경로는 지시서가 요구한 "앱 '지금 인쇄' + `paperConfirmed=true` → 결과 업로드"가 아니라 드라이버·전송 계층을 직접 호출한 임시 스크립트다** — 드라이버·전송 계층은 실물 검증됐지만, **에이전트 실행기 → 서버 결과 업로드 체인은 여전히 미검증**이다.
- 서비스 사용자 `haru`는 이미 `bluetooth` 그룹(`install.sh`). **overlayfs 전에 `/var/lib/bluetooth/`가 하부 레이어에 있어야 한다**(아직 미적용, 8절 예정).
- 11바이트 응답은 아직 에이전트 실행기 경로로 인쇄를 안 해봐서 그 경로에서는 관찰 안 됨(서버 실험값·임시 스크립트 전송값만 있음). H4가 의미를 밝히면 `status()`에 쓴다.

### 주의

- **재연결 [확인됨·실물, 2026-09-17, Pi 한정] — 콜드 ACL 타임아웃, 워크어라운드 적용·검증 완료**: 페어링 직후처럼 BT ACL 링크가 유휴 상태면 raw `socket.connect()`가 10초 타임아웃으로 실패한다. **해결**: `open()`이 raw connect 전에 `_wake_acl()`로 `bluetoothctl connect <MAC>`을 먼저 실행해 ACL을 깨운다(SPP 프로파일 자체는 `NotAvailable`로 실패해도 무방 — ACL만 올라오면 됨). **사용자 결정 완료 — 워크어라운드를 코드에 넣었다.** 실물 검증: `bluetoothctl disconnect`로 강제로 콜드 상태를 만든 뒤 새 `open()`을 3회 반복 — **3/3 성공**(0.56s, 0.62s, 1.06s, 전부 10초 타임아웃보다 훨씬 빠름). `bluetoothctl`이 없거나 wake 자체가 실패해도(타임아웃·OSError) 무시하고 raw connect를 그대로 시도하는 폴백 구조라 이 워크어라운드가 상황을 악화시키지 않는다.
- **장시간 유휴 후 연결 수락 여부는 [미검증]** — V1은 전원 유지만 봤다. 30일 운영이 답한다.
- **동시 연결**: 폰 Phomemo 앱이 연결 중이면 Pi가 못 붙을 수 있다. 운영 중에는 앱을 쓰지 않는다.
- Pi 내장 BT(UWE5622) 안정성은 V3로 재부팅 20/20 확인. 주 단위 장기 안정성은 30일 운영에서 본다.

## 4. 남은 것

1. ~~3절 표 상태·확정값 갱신~~ — 완료(2026-09-17).
2. ~~`pi/.env.example`의 `HARU_TRANSPORT`, `HARU_BT_ADDRESS` 설명~~ — 갱신함.
3. ~~[hardware-verification.md](hardware-verification.md) 현황표~~ — 갱신함.
4. ~~`pi/transport/bt.py` 작성 + Pi에서 M832 페어링·`trust` + `.env` 전환~~ — 완료(2026-09-17, 위 내용).
5. ~~콜드 ACL 재연결 문제 해결 여부 결정~~ — 완료(2026-09-17). 워크어라운드를 `open()`에 반영, 강제 disconnect 재현 시험 3/3 성공.
6. ~~M5 실물 인쇄 1회~~ — **완료(2026-09-17)**. `M832Printer`+`BtTransport`로 텍스트+그레이데이션+체커보드 PNG 전송, 사용자 육안 확인. `HARU_PRINTER_DRIVER=m832`가 Pi의 새 기본값(더 이상 `fake`로 되돌리지 않음). M5 4/4 완료 — [setup.md](setup.md) 9절. **단, 이 인쇄는 앱 "지금 인쇄" + `paperConfirmed=true` → 결과 업로드 경로가 아니라 드라이버·전송 계층을 직접 호출한 것이다 — 에이전트 실행기 → 서버 결과 업로드 체인은 여전히 미검증([agent.md](agent.md) 11절).**
