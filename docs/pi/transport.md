# 전송 계층 (`/pi/transport`)

> 드라이버가 만든 바이트를 프린터로 보내고, 필요하면 응답을 읽는 계층. 바이트의 의미는 모른다.
> 선택은 `HARU_TRANSPORT=usb | bt`. 최종 선택은 [hardware-verification.md](hardware-verification.md)의 V1~V4 결정 규칙을 따른다 — **`bt` 확정(V1·V2·V3 통과, 2026-09-17)**.
> 최초 결정: [../init_plan.md](../init_plan.md) 8.1, Q12·Q22. 코드: `usb`는 M1에서 작성됨(`pi/transport/usb.py`), `bt`는 V2 통과로 M5에서 작성한다(3절 확정값대로, 아직 없음).

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

### Pi 구현 [확인됨·실물, 2026-09-17] — 페어링·코드·연결 테스트 완료, 실제 인쇄는 미착수

- **Pi ↔ M832 페어링 완료**: `bluetoothctl pair`+`trust`, `Paired: yes`/`Bonded: yes`/`UUID: Serial Port, HCR Print`(서버와 동일). Pi에서 `sdptool search SP`로 RFCOMM 채널 1도 재확인함.
- **`pi/transport/bt.py` 작성 완료**: `open()` = 소켓 생성·connect(실패 시 원인이 드러나는 `TransportError`), `write()` = 4096 청크·청크당 타임아웃(기본 20000ms)·총 데드라인 60초·부분 전송은 계속 이어보냄(소켓 일반 규약)·`send()`가 0을 반환하면 예외, `read()` = 타임아웃/빈 응답이면 `None`, `close()`(idempotent). 상수는 `pi/printer/m832/constants.py`의 `BT_*`에 근거 주석과 함께 추가. 단위 테스트 `pi/tests/test_bt_transport.py`(36개, 전부 socket 모킹, 전체 스위트 117개 통과) — `write()`를 호출하지 않는 순수 연결 테스트 케이스도 포함해 실수로 데이터가 나가지 않는지까지 테스트로 고정해뒀다.
- **실물 연결 테스트(안전 — write() 호출 없음)**: Pi에서 `open()` 직후 바로 `close()`만 실행(래스터·어떤 바이트도 전송 안 함). 페어링 직후 첫 시도는 타임아웃, 이후 성공 — 아래 "주의"의 재연결 항목 참고.
- `pi/.env`(운영, `/opt/haru-paper/pi/.env`): `HARU_TRANSPORT=bt`, `HARU_BT_ADDRESS=C5:0D:F7:B7:B2:A1`로 전환·재시작 완료(poll 200, snapshot 200 확인). **`HARU_PRINTER_DRIVER`는 의도적으로 `fake`로 유지** — 용지를 육안으로 확인하기 전에는 실제 드라이버로 바꾸지 않는다(`CLAUDE.md` 절대금지 1). `m832`로 바꾸고 앱 "지금 인쇄"(용지 확인 체크)로 실물 1회 인쇄·육안 확인하는 것이 M5의 마지막 남은 단계다.
- 서비스 사용자 `haru`는 이미 `bluetooth` 그룹(`install.sh`). **overlayfs 전에 `/var/lib/bluetooth/`가 하부 레이어에 있어야 한다**(아직 미적용, 8절 예정).
- 11바이트 응답은 아직 실제 인쇄를 안 해봐서 Pi에서는 관찰 안 됨(서버 실험값만 있음). H4가 의미를 밝히면 `status()`에 쓴다.

### 주의

- **재연결 [확인됨·실물, 2026-09-17, Pi 한정] — 콜드 ACL 타임아웃**: 페어링 직후처럼 BT ACL 링크가 유휴 상태면 `socket.connect()`가 10초 타임아웃으로 실패한다(2연속 재현). `sudo bluetoothctl connect <MAC>`으로 ACL을 한 번 깨우면(프로필 자체는 `NotAvailable`로 실패해도 무방 — ACL만 올라오면 됨) 그 이후 raw RFCOMM connect가 연속 성공한다(2연속 확인). **`bt.py`의 `open()`은 이 워크어라운드를 넣지 않은, 문서 스펙 그대로의 단순 구현이다** — 실제 운영에서 매번 콜드 상태로 시작해 실패할지, 아니면 하루 1회 폴링·이전 연결 등으로 ACL이 데워져 있어 문제없을지는 [미검증]이다. **사용자 결정 필요**: 이대로 두고 30일 운영에서 재시도(agent의 `skipped_printer_offline` + 유예 재시도)로 흡수되는지 지켜볼지, 아니면 `open()`에 사전 wake 단계(예: `bluetoothctl connect` 서브프로세스 호출)를 추가할지.
- **장시간 유휴 후 연결 수락 여부는 [미검증]** — V1은 전원 유지만 봤다. 30일 운영이 답한다.
- **동시 연결**: 폰 Phomemo 앱이 연결 중이면 Pi가 못 붙을 수 있다. 운영 중에는 앱을 쓰지 않는다.
- Pi 내장 BT(UWE5622) 안정성은 V3로 재부팅 20/20 확인. 주 단위 장기 안정성은 30일 운영에서 본다.

## 4. 남은 것

1. ~~3절 표 상태·확정값 갱신~~ — 완료(2026-09-17).
2. ~~`pi/.env.example`의 `HARU_TRANSPORT`, `HARU_BT_ADDRESS` 설명~~ — 갱신함.
3. ~~[hardware-verification.md](hardware-verification.md) 현황표~~ — 갱신함.
4. ~~`pi/transport/bt.py` 작성 + Pi에서 M832 페어링·`trust` + `.env` 전환~~ — 완료(2026-09-17, 위 내용).
5. **콜드 ACL 재연결 문제 해결 여부 결정** — 위 "주의" 참고, 사용자 결정 대기.
6. **M5 실물 인쇄 1회**('지금 인쇄', 용지 확인 체크, `HARU_PRINTER_DRIVER=m832`로 전환) — 용지를 육안으로 확인할 수 있는 사람이 직접 해야 하는 마지막 단계, 아직 미착수.
