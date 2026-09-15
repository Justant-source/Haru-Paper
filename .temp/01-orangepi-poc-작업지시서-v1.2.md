# 하루종이 (Haru Paper) — Orange Pi PoC 작업지시서

> **버전** 1.2 (2차 리뷰 반영)
> **작성일** 2026-09-15
> **범위** 1단계 PoC — Orange Pi Zero 2W + Phomemo M832
> **목표** 서버 스케줄에 따라 매일 아침 자동으로 종이가 출력되는 것을 **1대**로 완전히 검증한다.

**변경 이력**

| 버전 | 요지 |
|---|---|
| 1.0 | 최초 작성 |
| 1.1 | 폴링 주기 서버 지시형 / 중복 방지 서버 이관 / 감지 수단 미확정 항목(U1~U5) 신설 / 스누프 선행 / 개발-운영 구간 분리 |
| **1.2** | **① 용지 없음 감지에 USB 프린터 클래스 표준 `GET_PORT_STATUS` 경로 추가 ② overlayfs에서 디바이스 토큰이 휘발되어 매 부팅 재등록되는 함정 수정 ③ 다운로드-검증-후-write, write 데드라인 추가 ④ CUPS를 Pi에서 제거 ⑤ heartbeat를 poll에 통합 ⑥ v1.1의 기술적 오류 4건 정정(udev 규칙, cupsfilter 옵션, time-sync.target, pyusb 스니펫) ⑦ 실패를 "테스트 리그 문제 / 제품 문제"로 구분 ⑧ 30일 운영 중 병행 작업 명시** |

본문에서 **(v1.2)** 표시는 이번 판에서 바뀐 곳이다.

---

## 0. 확정된 사실 (재검증 불필요)

이 항목들은 이미 확인되었으므로 다시 테스트하지 않는다.

| 항목 | 값 | 확인 경로 |
|---|---|---|
| USB VID:PID | `0483:5740` | 직접 리버스 엔지니어링 |
| USB 인터페이스 클래스 | `7/1/2` (표준 프린터 클래스, 양방향) | 동일 |
| 데이터 엔드포인트 | Bulk OUT `0x02` | 동일 |
| 명령 계열 | ESC/POS 파생, `1F 11 xx` 패밀리 (M04와 동일) | 동일 |
| 래스터 명령 | `GS v 0` (1D 76 30) | 동일 |
| 잡 종료 | `ESC d` (1B 64) 피드 | 동일 |
| 110mm 롤 | 라인당 **163 byte = 1299 dot** | 동일 |
| 농도/발열 제어 명령 | **스트림에 없음** | 동일 |
| 상태 조회 응답 | **읽지 않아도 정상 인쇄됨** | 동일 |
| USB 페이싱/청킹 | **불필요** | 동일 |
| 해상도 | 300 dpi | 매뉴얼 |
| 전원 입력 | **DC 5V 2A 고정. 고속충전(PD/QC) 미지원** | Phomemo KB |
| 절전 모드 | 앱에서 **"자동 종료 설정 안함"** 선택 가능 | 실측 |
| 절전 설정 영속성 | **재부팅 후에도 유지됨** | 실측 |
| 절전 공장 기본값 | **1시간** | 실측 |
| 상시 급전 시 기상 유지 | **10시간 이상 통과** | 실측 |
| 배터리 잔량 | 기기 내부에서 관리, 앱(BLE)에서 조회 가능 | 실측 |
| 꺼진 상태에서 VBUS 인가 | **켜지지 않음** (충전만 됨) | 매뉴얼 LED 표 |

### 확정되지 않은 사실 — 이번 PoC에서 반드시 답을 내야 하는 것

| # | 미확정 항목 | 왜 중요한가 |
|---|---|---|
| U1 | **용지 없음을 호스트 쪽에서 감지할 수 있는가** | 7장 실패 처리의 전제. **(v1.2)** 3.3의 `GET_PORT_STATUS`가 1순위 경로 |
| U2 | **헤드 과열을 감지할 수 있는가** | 동일. `GET_PORT_STATUS`의 Not-Error 비트가 반응하는지 함께 본다 |
| U3 | 배터리 잔량이 USB 경로로 나오는가 | 5장 |
| U4 | 절전 설정 명령을 USB로 보낼 수 있는가 | 6장 |
| U5 | 용지 소진 상태에서 write하면 어떤 일이 생기는가 (write가 블록되나, 유실되나, 용지 교체 후 밀린 것이 쏟아지나) | 재시도 정책의 입력값. **(v1.2)** write 데드라인(4.3) 설계의 근거 |

> U1·U2가 끝까지 "감지 불가"로 남으면, 실패 처리는 "사용자가 앱에서 '오늘 안 나왔어요'를 누르는" 사후 보고형으로 설계를 바꿔야 한다. 이 판정 자체가 PoC 산출물이다.

### 테스트 대상 기기 대장

| # | 기기번호 | MAC | 펌웨어 | 역할 |
|---|---|---|---|---|
| 1 | Q253E6831170035 | C5:0D:F7:B7:B2:A1 | 2.1.5 | **1호기 — 30일 운영 전용. 미지 벤더 명령 실험 금지** (표준 클래스 요청인 `GET_PORT_STATUS`는 허용) |
| 2 | | | | **실험기 — `1F 11` 스윕 등 리스크 있는 실험 전담** |
| 3 | | | | 예비 |

> **펌웨어 버전은 반드시 기록한다.** 버전이 다르면 프로토콜이 달라질 수 있으므로, PoC 기기는 전부 동일 버전으로 맞춘다.
> 미지의 벤더 커맨드를 순회 전송하는 실험은 부작용을 모른다. 기기가 1대뿐인 동안은 **스윕을 하지 말고** 스누프 캡처(5.5)까지만 진행한다.

---

## 1. 하드웨어 셋업

### 1.1 결선

```
벽 콘센트
   └─ 5V 3A USB 어댑터 ──→ Orange Pi Zero 2W (전원)

벽 콘센트
   └─ 5V 2A USB 어댑터 ──→ M832 (전원 / 상시 연결)

Orange Pi USB 포트 ──(USB-A to USB-C 데이터 케이블)──→ M832
```

- **1단계에서는 전원을 합치지 않는다.** 전원 통합은 2단계(ESP32) 설계 사항이다.
- M832에 연결하는 어댑터는 반드시 **5V 2A**. PD 어댑터를 쓰지 말 것.
- 케이블은 **데이터 지원** 제품이어야 한다.

### 1.2 M832 초기 설정 (기기마다 1회)

1. Phomemo 앱 연결
2. 기기 관리 → 절전 모드 → **"자동 종료 설정 안함"** 선택
3. 용지 감지 차단 → **OFF 유지**
4. 기기번호·MAC·펌웨어 버전을 대장에 기록
5. 전원 껐다 켜서 설정 유지 확인

### 1.3 용지 조달 확인 — 30일 테스트 선행 조건

- 30일 × 1장 + 보존성 3장 + 개발 중 시험 인쇄 분량의 110mm 롤을 **테스트 시작 전에** 확보한다

---

## 2. OS 설정

### 2.1 기본 — **(v1.2) Pi에는 CUPS를 설치하지 않는다**

```bash
# Armbian 또는 Orange Pi OS (Debian 계열) 설치 후
sudo apt update && sudo apt upgrade -y
sudo apt install -y python3-usb python3-bleak python3-requests git
sudo timedatectl set-timezone Asia/Seoul
```

- **(v1.2)** v1.1까지는 CUPS를 "임시 검증 도구"로 Pi에 설치했으나 제거한다. CUPS 드라이버는 이미 데스크톱에서 검증된 상태이고, .bin 생성은 서버가 하며, Pi가 할 일은 `usblp` write뿐이다. CUPS(및 함께 깔리는 `cups-browsed`)가 USB 장치를 점유하는 문제 자체를 없앤다. **Pi 이미지 = usblp + 파이썬 에이전트.** 이것이 2단계 ESP32 펌웨어의 역할과 정확히 같다
- 시계가 스케줄에 영향을 주지 않는다. 스케줄은 서버가 판단하고 기기는 폴링만 한다. 기기 시계가 필요한 진짜 이유는 **HTTPS 인증서 검증**이다
- **Zero 2W에는 RTC가 없다.** 정전 후 재부팅하면 NTP 동기화 전까지 시계가 과거에 머물러 TLS가 실패한다. 대응:

```bash
# (v1.2 정정) time-sync.target은 이 서비스를 켜야 "실제 동기화 완료" 시점에 도달한다.
# 이걸 안 켜면 target이 동기화와 무관하게 즉시 도달해 After= 지정이 무의미해진다.
sudo systemctl enable systemd-time-wait-sync.service
```

  - 에이전트 unit: `After=network-online.target time-sync.target`, `Wants=network-online.target time-sync.target`
  - 백스톱: 그래도 TLS 실패 시 에이전트는 죽지 않고 백오프 재시도

### 2.2 USB 권한 — **(v1.2 정정) 규칙 2개 + 안정적 장치 이름**

v1.1의 규칙은 `SUBSYSTEM=="usb"`만 있어 **`/dev/bus/usb/...`(pyusb 경로)에만 적용되고 `/dev/usb/lp0`(usblp, 운영 경로)에는 적용되지 않았다.** 또 `MODE=0666`이면 `GROUP`은 무의미하다. 그리고 `/dev/usb/lp0`은 재연결·다른 프린터 연결 시 `lp1`이 될 수 있으므로 **심볼릭 링크로 고정**한다.

```bash
# /etc/udev/rules.d/99-phomemo.rules

# 운영 경로: usblp 문자 장치. 에이전트는 /dev/haru-printer 만 사용한다
SUBSYSTEM=="usbmisc", KERNEL=="lp*", ATTRS{idVendor}=="0483", ATTRS{idProduct}=="5740", \
  MODE="0660", GROUP="lp", SYMLINK+="haru-printer"

# 실험 경로: pyusb 가 여는 raw 장치
SUBSYSTEM=="usb", ATTRS{idVendor}=="0483", ATTRS{idProduct}=="5740", MODE="0660", GROUP="lp"
```

```bash
sudo udevadm control --reload-rules && sudo udevadm trigger
sudo usermod -aG lp $USER
ls -l /dev/haru-printer      # → /dev/usb/lp0 을 가리켜야 한다
```

### 2.3 USB 장치 점유 주체 정리

**(v1.2)** CUPS를 제거했으므로 주체는 둘로 준다.

| 주체 | 용도 | 규칙 |
|---|---|---|
| usblp (`/dev/haru-printer`) | **운영 경로.** 에이전트가 .bin을 write, `LPGETSTATUS`로 상태 조회 | 상시 사용 |
| pyusb | `1F 11` 응답 읽기 등 실험용 | 사용 전 `detach_kernel_driver` 필수. 실험 후 재부팅 또는 `usblp` 재바인드. **운영기에서는 실험하지 않는다** |

### 2.4 Wi-Fi 안정화

상시 기기의 실패 1순위는 프린터가 아니라 Wi-Fi다.

```bash
# 전원 절약 끄기 (NetworkManager 예시; Armbian 버전에 따라 netplan일 수 있음)
nmcli connection modify <SSID> 802-11-wireless.powersave 2   # 2 = disable
nmcli connection modify <SSID> connection.autoconnect yes connection.autoconnect-retries 0
```

- 에이전트 unit에 `Restart=always`, `RestartSec=10`
- 가능하면 systemd hardware watchdog 활성화
- **(v1.2) 이 Wi-Fi는 제품에 안 들어간다.** Zero 2W의 온보드 Wi-Fi/BT(UWE5622)는 벤더 out-of-tree 드라이버이고 커뮤니티에 안정성 이슈 보고가 있다. 30일 중 Wi-Fi 기인 실패는 **"테스트 리그 문제"로 분류**하며 2단계 ESP32와 무관하다(8장 분류 기준 참고). 반복되면 USB Wi-Fi 동글로 교체하고 30일을 리셋하지 않는다(리그 교체는 리셋 사유가 아님)

### 2.5 read-only 루트파일시스템 — 적용 시점과 **(v1.2) 영속 데이터 함정**

- **개발 구간(≈1~2주)**: rw 상태로 개발
- **구성 동결 시점**: overlayfs 적용 → 강제 전원 차단 10회 부팅 검증 → **그 후 30일 카운트 시작**

```bash
sudo armbian-config   # System → Overlayfs
```

**(v1.2) overlayfs를 켜기 전에 반드시 하부 레이어에 있어야 하는 것들:**

| 항목 | 이유 |
|---|---|
| **디바이스 토큰 파일** | overlay 위에서 `register`를 호출하면 토큰이 tmpfs에 저장돼 재부팅마다 사라진다 → **매 부팅 새 기기로 등록되는 사고.** 등록은 rw 구간에서 1회만 하고, 토큰 파일이 하부 레이어에 있는지 확인 후 overlay를 켠다. (2단계 ESP32에서는 NVS에 저장 — 같은 원칙) |
| Wi-Fi 자격증명 | 동일. NetworkManager 설정은 보통 하부에 있으나 확인한다 |
| 파이썬 패키지 | `pip` 설치분은 overlay 전에. 가능하면 apt 패키지로 통일 |
| udev 규칙, systemd unit | 동일 |

- 로그·상태는 처음부터 "로컬에 안 남는다"고 가정한다. 서버 전송이 원본. **(v1.2)** 네트워크 단절 중 로그는 메모리 링버퍼(최근 200줄)에 쌓았다가 복구 시 poll에 실어 보낸다
- **"job id 로컬 dedup"은 성립하지 않는다.** 중복 출력 방지는 4.4의 서버 상태머신으로 해결한다

---

## 3. USB 프린터 동작 검증

### 3.1 디스크립터 덤프 (ESP32 개발의 입력값)

```bash
lsusb | grep 0483:5740
sudo lsusb -v -d 0483:5740 > m832-descriptor.txt
lsusb -t   # 속도 확인
```

| 항목 | 값 | 비고 |
|---|---|---|
| bDeviceClass | | 0이면 인터페이스 레벨에서 클래스 선언 |
| bNumConfigurations | | |
| bConfigurationValue | | `usb_host_device_open` 후 설정할 값 |
| bInterfaceNumber | | claim 대상. **pyusb 스니펫(5.3)에 이 값을 쓴다** |
| bAlternateSetting | | |
| bInterfaceClass / SubClass / Protocol | 7 / 1 / 2 | 확인용 |
| Bulk OUT bEndpointAddress | 0x02 | |
| Bulk OUT wMaxPacketSize | | **64면 Full Speed 확정** |
| Bulk IN bEndpointAddress | | 있을 것. 운영에서는 쓰지 않음 |
| bMaxPower | | 프린터가 요구하는 전류 |
| 속도 | Full Speed(12Mbps) 예상 | |

### 3.2 최소 인쇄 테스트 — 눈에 보이는 피드

```bash
ls -l /dev/haru-printer
printf '\x1b\x64\x03' | sudo tee /dev/haru-printer > /dev/null   # ESC d 3 — 용지가 나오면 성공
```

- `/dev/usb/lp*`가 없으면 `sudo modprobe usblp`

### 3.3 **(v1.2 신설) 표준 프린터 클래스 상태 조회 — U1의 1순위 경로**

M832는 `7/1/2`, 즉 **표준 USB 프린터 클래스**를 선언한다. 이 클래스에는 벤더 명령과 무관한 표준 컨트롤 요청 **`GET_PORT_STATUS`**(bRequest 0x01)가 정의되어 있고, 응답 1바이트에 **Paper Empty(bit 5) / Selected(bit 4) / Not Error(bit 3)** 플래그가 있다. 리눅스 `usblp`는 이를 `LPGETSTATUS` ioctl로 노출한다.

싸구려 프린터는 이 값을 고정으로 돌려주기도 하므로 **실험이 필요**하지만, 성공하면 U1·U2가 벤더 프로토콜 분석 없이 해결되고, 2단계 ESP32에서도 컨트롤 전송 1회로 구현된다.

```python
# lpstatus.py — 1호기에서 실행해도 안전 (표준 클래스 요청)
import os, fcntl, struct
LPGETSTATUS = 0x060b
fd = os.open('/dev/haru-printer', os.O_RDWR)
buf = bytearray(4)
fcntl.ioctl(fd, LPGETSTATUS, buf)
st = struct.unpack('i', buf)[0] & 0xff
print(f"raw=0x{st:02x} paper_empty={bool(st & 0x20)} selected={bool(st & 0x10)} not_error={bool(st & 0x08)}")
```

**실험 절차**

| 상태 | 기대 | 기록 |
|---|---|---|
| 정상, 용지 있음 | paper_empty=0, not_error=1 | |
| 용지 뺀 상태 | paper_empty=1 ? | |
| 커버 연 상태 | not_error=0 ? | |
| 연속 인쇄 직후(발열) | 변화 있나 | |

- 값이 상태에 따라 **바뀌면 U1 해결.** 에이전트는 인쇄 전에 이 값을 확인하고, 용지 없으면 write하지 않고 바로 실패 보고(U5 리스크 원천 차단)
- 값이 **고정이면** 5장의 스누프 경로(`1F 11` 상태 응답)로 넘어간다

### 3.4 "멍청한 파이프" 핵심 검증 — .bin 동일성 테스트

서버가 만든 .bin을 raw로 밀어 넣은 결과가 CUPS 경로 출력과 동일해야 전체 아키텍처가 성립한다.

1. **서버(또는 개발 머신)에서** CUPS 필터 체인을 헤드리스로 돌려 .bin 생성. **(v1.2 정정)** v1.1의 옵션이 틀렸다. PPD는 소문자 `-p`, 출력 타입은 `printer/<이름>`이어야 드라이버 필터까지 체인이 돈다:
   ```bash
   gunzip -k Phomemo-M832.ppd.gz
   cupsfilter -p Phomemo-M832.ppd -m printer/haru -e \
     -o media=<PPD에 정의된 110mm 롤 이름> sample.png > page.bin
   ```
   - 입력이 PNG면 `cups-filters`가 필요하다(imagetopdf → pdftoraster → 드라이버 필터)
   - 한국어 폰트는 **렌더링 단계**(콘텐츠 → PNG/PDF)의 문제다. 이 단계에서 폰트를 래스터화한 뒤 cupsfilter로 넘기면 필터 체인은 폰트를 모른다
2. Pi에서 raw write:
   ```bash
   cat page.bin > /dev/haru-printer
   ```
3. 같은 원본을 데스크톱 CUPS 큐로 출력한 것과 **출력물 비교**. 바이트 diff는 불필요(cupsfilter가 곧 CUPS 필터 체인이다). 대신 .bin 헤더가 `ESC @ … GS v 0 … ESC d` 순서인지, 크기가 약 570KB인지 기록한다
4. **(v1.2) 서버 워커 컨테이너에서도 동일 결과가 나와야 통과.** 노트북에서 된 것과 컨테이너(cups-filters + 드라이버 설치)에서 된 것은 다른 이야기다. 30일 테스트의 .bin은 컨테이너가 만든 것이어야 한다

> **(v1.2 메모)** 프로토콜이 이미 알려져 있으므로(163 byte/line, `GS v 0`, `ESC d`) 서버에 CUPS를 두지 않고 1-bit 비트맵 → ESC/POS 스트림을 직접 만드는 인코더는 수십 줄이다. PoC는 검증된 드라이버 재사용이 빠르니 cupsfilter로 가되, **.bin 포맷이 계약이고 생성 방법은 서버 내부 구현**이라는 점을 기억한다. 나중에 컨테이너에서 CUPS를 빼고 싶으면 바꾸면 된다.

---

## 4. 서버 연동

### 4.1 원칙 — 디바이스는 "멍청한 파이프"

```
[서버]  콘텐츠 선택 → 렌더링 → CUPS 필터 → 완성된 바이트 스트림(.bin)
[기기]  poll → 잡 있으면 .bin 전체 다운로드 → 검증 → /dev/haru-printer 에 write → ack
```

기기는 **날짜 판단도, 폴링 주기 판단도, 재시도 정책 판단도 하지 않는다.** 전부 서버가 지시한다.

### 4.2 서버가 제공해야 할 API — **(v1.2) heartbeat를 poll에 통합, 4개로 축소**

| 엔드포인트 | 메서드 | 설명 |
|---|---|---|
| `/api/device/register` | POST | 최초 등록, 디바이스 토큰 발급. **rw 구간에서 1회만** (2.5) |
| `/api/device/poll` | **POST** | 요청 본문에 기기 상태(아래) 포함. 응답에 잡(있으면) + `next_poll_seconds` |
| `/api/device/job/{id}/stream` | GET | 완성된 프린터 바이트 스트림(application/octet-stream). `Content-Length` 필수 |
| `/api/device/job/{id}/ack` | POST | `printed` / `download_failed` / `print_failed(reason)` |

- 인증은 디바이스별 토큰(Bearer). 1단계에서는 Tailscale 내부망 + 토큰으로 충분
- **(v1.2) heartbeat를 없앤 이유**: 5분 주기 poll이 곧 생존 신호다. 별도 엔드포인트는 요청 수를 두 배로 만들 뿐이고, ESP32에서는 HTTPS 요청 하나가 TLS 핸드셰이크 비용(시간·RAM)이다

**poll 요청 본문**

```json
{
  "fw": "pi-agent-0.3",
  "printer_connected": true,
  "lp_status": 24,
  "battery": 87,
  "wifi_reconnects": 2,
  "agent_restarts": 0,
  "logs": ["...", "..."]
}
```

**poll 응답**

```json
{
  "next_poll_seconds": 4236,
  "job": { "id": "j_20260916_0700", "bytes": 583104, "sha256": "..." }
}
```

- **(v1.2) `next_poll_seconds` 계산 단순화**: v1.1의 "±10분 구간 30초"보다 단순하고 정확한 방법이 있다. **서버가 다음 잡 예정 시각까지 남은 초를 그대로 주되 `clamp(남은 초, 30, 300)`.** 기기는 예정 시각에 정확히 도착하고, 5분 상한 덕에 생존 신호 주기도 유지된다. 사용자가 앱에서 시각을 앞당겨도 지연은 최대 5분
- 기기는 값이 없거나 파싱 실패 시 300초

### 4.3 기기 측 에이전트

```
haru-agent.py (systemd service)
 loop:
   ├─ 상태 수집 (printer_connected, LPGETSTATUS, 배터리, 링버퍼 로그)
   ├─ POST poll
   ├─ job 있으면:
   │    ├─ (v1.2) LPGETSTATUS 로 용지 확인 → 없으면 ack print_failed(paper_empty), write 안 함
   │    ├─ (v1.2) GET stream → 메모리에 전체 수신 → Content-Length·sha256 검증
   │    │       실패 시 ack download_failed (서버가 pending 으로 되돌림). 프린터에는 아무것도 안 감
   │    ├─ (v1.2) write with deadline: O_NONBLOCK + select, 전체 60초 초과 시 중단 → ack print_failed(write_timeout)
   │    └─ ack printed (실패 시 백오프로 30분까지 재시도 — ack 는 멱등)
   └─ sleep(next_poll_seconds)
```

**(v1.2) write 데드라인이 필요한 이유**: `/dev/usb/lp0`에 대한 `write()`는 프린터가 데이터를 받아줄 때까지 **블록**된다. 용지 없음이나 행 상태에서 프린터가 소비를 멈추면 에이전트가 영원히 멈추고, 그러면 poll도 멈춰 서버 입장에서는 기기가 죽은 것처럼 보인다. 570KB는 정상이면 수 초 안에 끝나므로 60초면 넉넉하다.

**(v1.2) 다운로드-검증-후-write가 필요한 이유**: 스트리밍하면서 write하면 다운로드가 중간에 끊겼을 때 **반쪽짜리 래스터가 프린터에 들어간 상태**가 된다. 570KB는 메모리에 다 받아도 아무 부담이 없다(ESP32-S3의 PSRAM에서도 마찬가지).

**필수 방어 로직 (유지)**

- 네트워크 단절 시 지수 백오프 (최대 5분)
- 인쇄 실패 시 **자동 재시도 없음**. 재시도 여부는 서버가 결정(4.4)
- systemd: `Restart=always`, `After=network-online.target time-sync.target`

### 4.4 중복 출력 방지 — 서버 잡 상태머신

```
pending ──(stream 요청)──→ delivered ──ack printed──→ done
   ↑                           │
   └──── ack download_failed ──┘
                               │
                               ├─ ack print_failed(paper_empty) ──→ failed → 운영자 알림
                               ├─ ack print_failed(write_timeout) ──→ failed → 운영자 알림
                               └─ ack 없이 30분 경과 ──→ unknown → 운영자 알림
pending ──(유효기간 경과)──→ expired
```

- `poll`은 `pending`이면서 **예정 시각이 지난** 잡만 반환한다
- **`download_failed`만 자동 복귀.** 프린터에 아무것도 안 갔다는 것이 확실한 유일한 실패이기 때문이다
- **`unknown`은 자동 재전송하지 않는다.** "안 나옴"이 "두 장 나옴"보다 낫다는 정책. (v1.2) 다운로드 검증과 ack 재시도를 넣었으므로 `unknown`은 이제 "write 도중 정전" 같은 진짜 불명 상황에만 남는다
- **(v1.2) 잡 유효기간은 제품 결정 사항**: 07:00 예정 잡이 Wi-Fi 복구로 20:00에 나오는 것이 좋은가? "아침 종이"라는 컨셉상 아닐 가능성이 높다. PoC 기본값 **예정 시각 + 3시간**, 서버 설정으로 조정. 만료된 잡은 앱에 "오늘은 못 나왔어요"로 표시
- PoC에서 "운영자 알림"은 앱이 아니라 **웹훅(Slack/Telegram 등)**. 앱은 아직 없다

---

## 5. 배터리 잔량 확인 로직

### 5.1 배경

M832는 배터리 잔량을 내부에서 관리하며 Phomemo 앱이 BLE로 조회한다. USB 경로로 나오는지는 미확인(U3).

### 5.2 스누프 캡처를 먼저 한다

앱은 어차피 배터리를 조회한다 → 스누프 로그에 정답 명령이 들어 있을 가능성이 높다. 블라인드 스윕보다 빠르고 안전하며, 절전 설정 캡처(6장)와 같은 세션에서 한 번에 얻는다.

1. 5.5의 스누프 세션에서 **기기 관리 화면 진입 시점**(배터리 % 표시)의 BLE 트래픽 확보
2. 배터리 조회로 추정되는 Write/Read/Notification 페이로드 특정
3. 그 페이로드가 `1F 11 xx` 계열이면 → 동일 바이트를 USB Bulk OUT으로 보내고 Bulk IN을 읽어본다 (BLE 명령이 USB에서도 동작하는 경우가 흔하다)
4. 성공하면 경로 A(USB) 확정, 실패하면 경로 B(BLE) 확정

### 5.3 경로 A — USB 상태 조회 — **(v1.2 정정) 스니펫 완성**

v1.1 스니펫은 `set_configuration` 누락, 인터페이스 번호 하드코딩, `ep_in` 미정의였다.

```python
# 실험기에서만 실행. 운영기(1호기) 금지.
import usb.core, usb.util
VID, PID = 0x0483, 0x5740
IFACE = 0          # ← 3.1 표의 bInterfaceNumber 로 교체
dev = usb.core.find(idVendor=VID, idProduct=PID)
if dev.is_kernel_driver_active(IFACE):
    dev.detach_kernel_driver(IFACE)      # usblp 가 잡고 있으면 read/write 실패
dev.set_configuration()
usb.util.claim_interface(dev, IFACE)

cfg  = dev.get_active_configuration()
intf = cfg[(IFACE, 0)]
ep_out = usb.util.find_descriptor(intf, custom_match=lambda e:
    usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_OUT)
ep_in  = usb.util.find_descriptor(intf, custom_match=lambda e:
    usb.util.endpoint_direction(e.bEndpointAddress) == usb.util.ENDPOINT_IN)

ep_out.write(bytes([0x1f, 0x11, 0x0e]))     # 스누프에서 특정한 조회 명령
try:
    print(bytes(ep_in.read(64, timeout=2000)).hex())
except usb.core.USBTimeoutError:
    print("no response")
```

- 배터리를 다른 수준으로 방전시킨 뒤 반복해 **값이 변하는 바이트**를 찾고, 앱 표시 %와 대조해 스케일 추정
- 스누프로 명령을 특정하지 못했을 때만, **실험기에서** `0x00~0x20` 스윕. 전후로 앱에서 설정 상태를 확인해 부작용 기록
- 실험 후 재부팅 또는 usblp 재바인드

### 5.4 경로 B — BLE GATT

```bash
sudo apt install -y bluez
bluetoothctl
> scan on
> connect C5:0D:F7:B7:B2:A1
> menu gatt
> list-attributes
```

- `gatttool`은 deprecated. 에이전트 코드는 `bleak`로 작성
- 표준 Battery Service(`0x180F`/`0x2A19`)가 있으면 그대로, 없으면 스누프의 handle/UUID와 대조
- BLE는 앱이 연결 중이면 동시 연결이 안 될 수 있다. 앱 연결 해제 상태에서 조회
- **(v1.2) BLE 경로가 채택되면 2단계 비용이 늘어난다는 점을 기록해 둔다.** ESP32-S3에서 Wi-Fi와 BLE는 라디오를 공유하며 코엑시스턴스 설정이 필요하다. 2단계 계획의 "USB 호스트 2~4주"에는 BLE가 포함되어 있지 않다. 1단계 결과에 따라 2단계 일정을 갱신할 것

### 5.5 안드로이드 블루투스 HCI 스누프 로그 (공통 도구)

배터리(5장)와 절전 설정(6장)을 **한 세션에서** 캡처한다.

1. 개발자 옵션 → **Bluetooth HCI 스누프 로그 사용** 켜기
2. 블루투스 껐다 켜기
3. Phomemo 앱에서: ① 기기 관리 화면 진입(배터리 표시) ② 절전 모드 변경 7종 — 각 동작 사이 10초 이상, 수행 시각 메모
4. 버그 리포트 생성 → `btsnoop_hci.log` 추출
5. Wireshark, `btatt` 필터, Write Request / Notification 페이로드 확인

### 5.6 활용

- poll 본문의 `battery`로 전송 → 서버 임계값(20%) 미만이면 알림
- **PoC에서 배터리 제어는 하지 않는다.**

---

## 6. 절전 설정 자동화 로직

### 6.1 문제

- 공장 기본값 **1시간**. 앱 없이는 못 바꾼다
- 공장 초기화나 펌웨어 업데이트로 되돌아가면 **다음 날 아침부터 출력이 멈춘다**

### 6.2 목표

**매 인쇄 직전에 "자동 종료 안함" 설정 명령을 1회 전송**하여 어떤 경우에도 자동 복구되게 한다. (하루 1회 플래시 쓰기는 수명에 무의미)

**(v1.2) 검증 항목 추가**: 설정 명령 직후 곧바로 인쇄 스트림을 보냈을 때 출력이 깨지지 않는지 확인한다. 설정 쓰기가 내부 지연이나 버퍼 리셋을 유발할 수 있다. 필요하면 명령과 인쇄 사이에 대기(예: 500ms)를 둔다.

### 6.3 작업 절차

1. **캡처** — 5.5로 확보. `1시간 → 자동 종료 설정 안함` 순간의 Write Request 특정
2. **차분 확인** — 7가지 값 대조표(6.4)
3. **USB 전송 시도** — Bulk OUT `0x02`로 전송. **전송 전에 앱을 다른 값으로 맞춰두고** 보내야 변화를 관찰할 수 있다
4. **결과별 대응**

| 결과 | PoC 10대 | 양산 300대 |
|---|---|---|
| USB로 전송 가능 | 에이전트에 삽입 | 그대로 채택 |
| BLE 전용 | **Pi의 BLE(`bleak`)로 에이전트에서 전송 검증**, 안 되면 손으로 설정 | ESP32-S3 BLE로 전송 (2단계 일정 갱신) |
| 명령 특정 실패 | 손으로 설정 | 출하 전 검사 공정 + 사용자 안내 |

### 6.4 대조표 양식

| 설정값 | 캡처된 페이로드 (hex) | 차이 바이트 |
|---|---|---|
| 10분 | | |
| 30분 | | |
| 1시간 | | |
| 3시간 | | |
| 5시간 | | |
| 10시간 | | |
| 자동 종료 안함 | | |

---

## 7. 스케줄 출력

- **스케줄 판단은 서버가 한다.** 기기는 "지금 출력할 게 있나"만 묻는다
- 기기에 cron을 두지 않는다
- 서버는 사용자 시각(Asia/Seoul) 기준으로 잡을 생성하고, `next_poll_seconds`로 기기를 예정 시각에 불러온다(4.2)
- 밀린 잡은 서버 유효기간 정책이 처리한다(4.4)

### 실패 처리

| 상황 | 감지 수단 | 동작 |
|---|---|---|
| 용지 없음 | **(v1.2)** 1순위 `LPGETSTATUS` paper_empty (3.3) → 2순위 `1F 11` 상태 응답 → 3순위 write 데드라인 초과 | 감지 시 write 없이 `print_failed(paper_empty)` → 알림. 서버는 재발행하지 않음(사용자가 용지 넣고 앱에서 "다시 뽑기") |
| 프린터 미연결 | `/dev/haru-printer` 부재 — **감지 가능** | poll 본문에 보고. 서버는 잡을 `pending`에 유지, 유효기간 내 재연결되면 자연 출력 |
| 네트워크 단절 | HTTP 실패 — **감지 가능** | 백오프. 복구 후는 서버 유효기간 정책 |
| 헤드 과열 | `LPGETSTATUS` not_error 반응 여부 확인(U2) | 감지 시 서버가 10분 후 1회 재발행 |
| **(v1.2)** write 행 | write 데드라인 60초 초과 | `print_failed(write_timeout)` → 알림. 원인 불명이므로 재발행 없음 |

> **(v1.2)** v1.1의 "프린터 미연결 → 5분 간격 재시도, 30분 후 실패" 같은 기기 측 재시도 로직을 전부 삭제했다. 기기는 상태를 보고하고 잡을 받을 수 있으면 받을 뿐, **재시도 여부와 횟수는 서버가 잡 상태로 관리한다.** 이것이 dumb pipe의 일관된 적용이고, ESP32로 옮길 코드도 그만큼 줄어든다.

---

## 8. 30일 연속 운영 테스트

**시작 조건**: ① 에이전트 코드 동결 ② overlayfs 적용 + 전원 차단 10회 부팅 검증 통과(토큰 유지 포함) ③ 용지 재고 확보 ④ .bin이 서버 컨테이너에서 생성됨. **운영 중 에이전트·서버 프로토콜 중대 변경 시 30일 리셋.** 콘텐츠 변경, 테스트 리그(Wi-Fi 동글 등) 교체는 리셋 사유가 아니다.

| 항목 | 기록 방법 |
|---|---|
| 일일 출력 성공/실패 | 서버 잡 상태 |
| 실패 원인 분류 | poll에 실려온 링버퍼 로그 + 서버 로그 |
| 배터리 잔량 추이 | poll 본문 |
| `LPGETSTATUS` 값 추이 | poll 본문 (v1.2) |
| 출력물 변색 속도 | 창가 / 서랍 / 냉장고 위 각 1장, 주 1회 동일 조명·각도 사진 |
| 프린터 발열 | 주 1회 손으로 확인 |
| Wi-Fi 재접속·에이전트 재시작 횟수 | poll 본문 카운터 |
| 누적 용지 소모량 | 수기 |

### 출력물 보존성 테스트 (중요)

코드 동결을 기다릴 필요가 없다 — **오늘 3장 뽑아서 바로 시작.**

- 동일 출력물 3장: 창가 / 서랍 / 냉장고 위
- 주 1회 동일 조명·각도로 촬영
- 30일 후 육안 차이가 뚜렷하면 **용지 사양 재검토** 또는 제품 메시지 수정

### 실패 분류 기준 — **(v1.2) 2축으로 확장**

**축 1: 복구 성격**

| 등급 | 정의 |
|---|---|
| A — 자동 복구 | 사용자 개입 없이 유효기간 내 출력됨 |
| B — 원인 규명된 실패 | 출력 안 됐지만 원인·대책이 명확 |
| C — 원인 불명 실패 | 로그로 원인을 못 찾음 |

**축 2: (v1.2) 2단계로 이전되는가**

| 영역 | 예 | 2단계 관련성 |
|---|---|---|
| **제품 영역** | 프린터 프로토콜, 절전/기상 동작, 용지, 서버 스케줄·잡 상태머신, .bin 파이프라인, 감열 보존성 | **직접 이전됨** — 여기서 못 잡으면 ESP32에서도 터진다 |
| **리그 영역** | Pi Wi-Fi 드라이버, SD 카드, Armbian 부팅, 파이썬 런타임 | ESP32와 무관 |

- **C등급 × 제품 영역 = 0건**이 2단계 진입 조건. C등급이라도 리그 영역이면 기록만 하고 진행한다
- 30일 성공률도 두 영역을 나눠 집계한다. "Wi-Fi 드라이버가 죽어서 3번 실패"는 제품 성공률에서 빼고 본다

---

## 9. 완료 기준 (Exit Criteria)

- [ ] 동결된 구성으로 30일 연속 운영, **제품 영역 자동 출력 성공률 95% 이상** (A등급은 성공 집계)
- [ ] **C등급 × 제품 영역 실패 0건**
- [ ] `lsusb -v` 디스크립터 전체 확보 (3.1 표 완성)
- [ ] 서버 **컨테이너**가 생성한 .bin의 raw write 출력이 CUPS 경로와 동일 (3.4)
- [ ] **(v1.2) `GET_PORT_STATUS`(LPGETSTATUS) 반응 여부 판정** — 용지 없음/커버 열림에 반응하는지
- [ ] 배터리 잔량 조회 경로 확정 (USB 또는 BLE)
- [ ] 절전 설정 명령 캡처 완료 또는 "손으로 설정" 확정 — BLE 전용일 경우 Pi BLE 전송 검증 포함
- [ ] U1·U5 판정 완료 ("감지 불가"도 유효한 결론이며, 그 경우 사후 보고형 설계 문서 포함)
- [ ] 출력물 30일 보존성 사진 확보
- [ ] overlayfs 상태에서 강제 전원 차단 10회 후 정상 부팅 **+ 매 부팅 동일 디바이스 토큰으로 poll 성공** (v1.2)
- [ ] **(v1.2) 2단계 일정 갱신 메모**: BLE 필요 여부, `GET_PORT_STATUS` 사용 여부에 따라 ESP32 작업 범위를 재산정한 한 페이지

> 95%는 PoC 통과선이지 제품 기준이 아니다. 상용 기준은 99.5%+ 수준이어야 하며 n=30으로는 그 수준을 말할 수 없다. PoC의 목적은 수치 증명이 아니라 **실패 모드의 전수 목록화**다.

---

## 10. 지금 당장 할 일 (우선순위)

**첫 주**

1. `sudo lsusb -v -d 0483:5740 > m832-descriptor.txt` — 오늘
2. udev 규칙 적용 → `/dev/haru-printer` 확인 → `ESC d` 피드 — 오늘
3. **(v1.2) `lpstatus.py` 실행: 용지 있음/없음/커버 열림 3상태 값 기록 — 오늘.** 10분짜리 실험이고 결과에 따라 5장·7장의 절반이 필요 없어진다
4. **보존성 테스트 3장 출력 + 배치 — 오늘**
5. 블루투스 스누프 캡처 세션 (배터리 + 절전 7종 한 번에)
6. U5 실험: 용지 빼고 `ESC d` write → 블록되나? 용지 넣으면 밀린 게 나오나?

**둘째 주**

7. 서버: 잡 상태머신, `poll`(`next_poll_seconds`), `stream`, `ack`, 웹훅 알림
8. 서버: 렌더링 → cupsfilter → .bin, **컨테이너에서** 3.4 통과
9. 에이전트: 다운로드-검증-write-ack 루프, write 데드라인, 링버퍼 로그
10. register 1회 → 토큰 파일 위치 확인 → overlayfs → 전원 차단 10회 (토큰 유지 확인) → **30일 시작**

**(v1.2) 30일 운영 중 병행 작업** — 30일은 기다리는 시간이 아니다

- ESP32-S3 개발 보드 구매, USB 호스트 예제(`usb_host` 컴포넌트)로 M832 enumerate까지 — 3.1 디스크립터가 있으니 바로 시작 가능
- 같은 .bin을 ESP32에서 Bulk OUT으로 밀어 넣는 최소 실험 (Pi 옆에 실험기 붙여서)
- `GET_PORT_STATUS` 컨트롤 전송을 ESP32에서 재현
- 앱의 출력 시각 설정 UI (서버 스케줄 반영 경로 검증)
- 110mm 감열지 국내 조달처 조사 (미해결 과제 #6)
