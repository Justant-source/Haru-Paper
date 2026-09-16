# 전송 계층 (`/pi/transport`)

> 드라이버가 만든 바이트를 프린터로 보내고, 필요하면 응답을 읽는 계층. 바이트의 의미는 모른다.
> 선택은 `HARU_TRANSPORT=usb | bt`. 최종 선택은 [hardware-verification.md](hardware-verification.md)의 V1~V4 결정 규칙을 따른다.
> 최초 결정: [../init_plan.md](../init_plan.md) 8.1, Q12·Q22. 코드는 M1(`usb`)·M5 전후(`bt`)에 작성(현재 없음).

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
| write 전체 데드라인 | 60초 (청크 합산) | [기본값] — 청크별 타임아웃은 통과해도 프린터가 중간에 소비를 멈추면(용지 걸림 등) 총 시간이 쌓여 폴링 스레드를 오래 막을 수 있다. `.temp/01-orangepi-poc-작업지시서-v1.3.md` 4.2절 아이디어를 이 프로젝트의 pyusb 기반 transport(usblp 아님)에 맞게 적용 |
| read | 전송 후 64바이트 읽기는 무응답이었음 | [확인됨·무응답] |

### 주의

- **usbipd (노트북 WSL 한정)**: 인터페이스 release·dispose 직후나 유휴 시간이 지나면 WSL attach가 끊기는 현상이 반복됐다(`dmesg`의 `vhci_hcd: connection closed`). `.wslconfig`에 `networkingMode=mirrored`를 적용했지만 해소 여부는 [미검증]. 노트북에서 연속 전송할 때는 매번 `lsusb`로 확인하고, 없으면 Windows에서 `usbipd attach --wsl --busid 4-4`. Pi에는 usbipd가 없으므로 해당 없음.
- **CUPS 충돌 (노트북)**: CUPS usb 백엔드가 장치를 잡고 있으면 pyusb claim이 충돌한다. `systemctl stop cups`는 소켓 활성화로 다시 켜질 수 있으므로 전송 직전에 `systemctl is-active cups`를 확인한다. Pi에는 CUPS를 설치하지 않는다.
- **권한 (Pi)**: root가 아닌 서비스 사용자가 장치에 접근할 수 있도록 `0483:5740` udev 규칙을 `install.sh`가 설치한다 [기본값] ([setup.md](setup.md)).
- **전원 (Pi, V4)**: Pi USB1(호스트 전용)에 직결하면 Pi가 프린터에 5V를 공급하고 프린터 충전 전류(최대 2A)를 끌어간다. 보드 전원 규격은 5V 2A라 전압 강하·재부팅 위험이 있다 [추정]. V4는 5V 3A 어댑터로 시험한다.

## 3. Bluetooth (`transport/bt`) — V2 결과로 확정

**M832에서 Bluetooth로 인쇄해 본 적이 없다.** 아래는 전부 같은 계열 기종(M04S/M04AS/M834) 자료 기반 [추정]이며, detox-printer PLAN-02 V2(Windows 파이썬)로 M832에서 먼저 확인한다.

| 항목 | 후보 | 상태 | 출처 |
|---|---|---|---|
| 방식 1: Classic SPP | RFCOMM 채널 1 | [추정] | vivier/phomemo-tools 백엔드(M04 계열), cure-honey/PhomemoA4(M834) |
| 방식 2: BLE GATT | 서비스 `0000ff00-…`, write `ff02`, notify `ff01`/`ff03` | [추정] | vivier/phomemo-tools issue #27, phomymo(M04 계열) |
| M832 지원 여부 | Classic·BLE 둘 다 가능성 | [추정] | FCC ID 2ASRB-M832에 Classic·BLE 등급 모두 존재 |
| 바이트 스트림 | USB와 같은 `1F 11 …` + `1D 76 30 00 …` | [추정] | 계열 기종 사례 |
| 청크·간격 | 256바이트 / 20ms 로 보수적으로 시작 | [추정] | vivier 백엔드(M04: 수신 버퍼가 작고 흐름 제어 없음 → 한꺼번에 보내면 끝부분 유실) |
| 연결 유지 | 인쇄가 끝날 때까지 연결을 끊지 않음 | [추정] | vivier 백엔드 |

### Pi(Linux) 구현 후보 [기본값, V2 이후 확정]

- SPP: Python 표준 `socket.AF_BLUETOOTH` + `BTPROTO_RFCOMM` (BlueZ). 페어링은 `bluetoothctl`로 1회
- BLE: `bleak`(BlueZ D-Bus)
- 서비스 사용자를 `bluetooth` 그룹에 넣는다
- 주소는 `HARU_BT_ADDRESS`

### 주의

- **Pi 내장 BT 안정성**: Orange Pi Zero 2W 내장 칩(UWE5622)은 Armbian에서 "부팅 후 BT가 자주 안 뜬다"는 미해결 보고가 있다 [확인됨·사용자 보고]. 공식 Debian 12 이미지에서의 안정성은 V3(재부팅 20회)로 확인한다. 실패하면 Armbian 시험 또는 USB 직결로 간다. USB BT 동글은 추가 구매가 필요하다.
- **재연결**: 프린터 전원이 꺼졌다 켜지거나 BT 스택이 재시작되면 `open()`이 실패할 수 있다. agent는 이를 `skipped_printer_offline`으로 기록하고 유예 시간 안에서 재시도한다([policy.md](policy.md)).
- **자동 꺼짐**: 충전기만 연결된 상태에서 프린터가 꺼지는지 모른다(V1). 꺼진다면 BT 방식은 쓸 수 없다.

## 4. V2 결과가 나오면 고칠 것

1. 3절 표의 상태를 [확인됨] 또는 [확인됨·실패]로 바꾸고 detox-printer findings 항목 제목을 적는다.
2. 방식(SPP/BLE), 주소 형식, 청크·간격, 연결 유지 여부를 확정값으로 적는다.
3. `pi/.env.example`의 `HARU_TRANSPORT`, `HARU_BT_ADDRESS` 설명을 고친다.
4. [hardware-verification.md](hardware-verification.md) 현황표를 고친다.
