# 하루종이 (Haru Paper) — Orange Pi PoC 작업지시서

> **버전** 1.4 (V2 최종 통과 반영·정리판)
> **작성일** 2026-09-17
> **범위** 1단계 PoC — Orange Pi Zero 2W + Phomemo M832
> **목표** 서버 스케줄에 따라 매일 아침 자동으로 종이가 출력되는 것을 **1대**로 완전히 검증한다.

**변경 이력**

| 버전 | 요지 |
|---|---|
| 1.0 | 최초 작성 |
| 1.1 | 폴링 주기 서버 지시형 / 중복 방지 서버 이관 / 미확정 항목(U1~U5) 신설 / 스누프 선행 / 개발-운영 구간 분리 |
| 1.2 | USB 프린터 클래스 `GET_PORT_STATUS` 경로 / overlayfs 토큰 휘발 함정 / 다운로드-검증-후-write·write 데드라인 / CUPS 제거 / heartbeat를 poll에 통합 / 실패를 리그·제품으로 분류 |
| 1.3 | 실물 3대 환경 반영. 연결 방식 USB → BT(SPP/RFCOMM 채널 1). 4장 "멍청한 파이프"를 실제 아키텍처로 교체. U5 삭제. 용지 감지를 BT 재조사로. 노트북 WSL 배제 |
| **1.4** | **V2 최종 통과(BT 체커보드 2장 육안 확인) → transport `bt` 확정. 서버 1-bpp(`.pbm`) 출력 승인. 프린터 1대 제약(추가 구매 없음) 명시. 과거 판 비교 서술 삭제, 현재 상태 문서로 정리** |

실험 기록의 원본은 `~/Data/detox-printer/m832/docs/findings.md`, 하드웨어 상태 요약은 [`docs/pi/hardware-verification.md`](../docs/pi/hardware-verification.md), API 규약 원본은 [`docs/architecture.md`](../docs/architecture.md)다. 이 문서는 그것들을 근거로 하는 **계획 문서**이며, 사실의 원본이 아니다. 2단계(ESP32) 계획은 `.temp/02-esp32-디바이스-계획서-v1.4.md`.

---

## 0. 실물 환경 대장

| 머신 | 사양 (실측) | 역할 | 프린터 접근 |
|---|---|---|---|
| **Orange Pi Zero 2W** (`haru-pi`, tailnet `100.117.239.83`) | Debian 12 bookworm, 커널 6.1.31-sun50iw9, Python 3.11.2, RAM 981Mi, 스왑 zram 490Mi, 내장 BT UWE5622(UART, `hci0` `UP RUNNING`), SD 32GB | **운영 기기.** `haru-paper-agent` systemd 상시 구동(자동시작·서버 폴링 확인됨) | **BT(SPP/RFCOMM)** — M832 페어링 아직 안 함 |
| **Ubuntu 서버** (`justant-server2`, tailnet `100.81.189.92`) | Ubuntu 24.04.4, Python 3.12.3, Realtek BT 동글(`0bda:8771`), 서버 백엔드·DB·웹앱, 다른 운영 프로젝트 다수 | **BT 실험실 + 서버 백엔드.** `~/Data/detox-printer` clone됨, M832와 **페어링됨**(`Bonded: yes`) | BT(SPP/RFCOMM), 이미 페어링 |
| **노트북** (Windows 11 + WSL2) | node18, docker, usbipd | **사용하지 않는다** | — |

노트북 WSL의 유일한 고유 역할은 usbipd로 M832를 USB로 붙이는 것이었는데, 디스크립터는 이미 확보됐고(detox-printer `device-descriptor.md`) 운영 경로에 USB가 없으며, BT 실험은 실제 BT 하드웨어와 페어링을 가진 서버가 더 낫다. USB 재검증이 필요해지면 서버에 M832를 직접 꽂는다.

> **담당 세션**: `CLAUDE.md`의 "노트북 세션 → `/pi`·`/docs/pi`" 규칙은 프린터가 노트북에 USB로 붙어 있던 전제다. 실물에서는 서버 세션이 사용자 승인 하에 `/pi`·`/docs/pi`를 이어서 작업한다. 규칙을 바꾸려면 사용자가 `CLAUDE.md`를 고친다.

---

## 1. 확정된 사실 (재검증 불필요)

### 1.1 M832 프로토콜·전기

| 항목 | 값 | 확인 |
|---|---|---|
| USB VID:PID / 클래스 | `0483:5740`, `7/1/2`(표준 프린터 클래스, 양방향) | [확인됨] |
| USB 엔드포인트 | BULK OUT `0x02` / IN `0x81`, wMaxPacketSize 64 | [확인됨] |
| 헤더 | `1F 11 0B`(연속용지) + `1F 11 35 00`(압축 Off) + `1D 76 30 00 xL xH yL yH` | [확인됨·실물] |
| 래스터 폭 | **110mm 연속 롤 = 163 byte/line (1304 dot)** | [확인됨·실물] |
| 비트 극성 | 1 = 검정, MSB-first, 반전 없음 | [확인됨·실물] |
| 꼬리 (110mm) | `1B 64 01` + `1B 64 02` + `1F 11 11` = 9 byte | [확인됨·실물] |
| 좌우 정렬 보정 | `h-offset 2.0mm`(24dot) → 좌우 여백 대칭 약 1mm | [확인됨·실물] |
| 농도 명령 | 스트림에 없음(기본 농도) | [확인됨·필터출력] |
| 해상도 | 300 × 300 dpi(정사각 dot) | [확인됨·실물] |
| 전원 | DC 5V 2A 고정, PD/QC 미지원 | [확인됨·Phomemo KB] |
| 절전 | 앱에서 "자동 종료 안함" 가능, 재부팅 후 유지, 공장 기본 1시간 | [확인됨·실측] |
| 상시 급전 시 기상 유지 | 8시간 이상(V1) | [확인됨·실물] |
| MAC / 펌웨어 | `C5:0D:F7:B7:B2:A1` / 2.1.5 | [확인됨·실물] |

### 1.2 M832 Bluetooth — V2 통과

원본: findings "V2" 5건(서비스 탐색 → 페어링 → SPP 채널 → RFCOMM 전송 → 출력물 육안 확인), `m832/src/09_bt_l2cap_replay.py`·`10_bt_rfcomm_replay.py`.

| 항목 | 값 | 확인 |
|---|---|---|
| 광고 서비스 | **SPP**(`JL_SPP`, 0x1101) + **HCRP**(`HCR Print`, 0x1126) | [확인됨·실물 SDP] |
| SPP 채널 | **RFCOMM 채널 1** | [확인됨·실물] |
| HCRP | L2CAP PSM 4107 — 페어링 후에도 `connect()` `EPERM`(root 포함), 원인 미해결 | [확인됨·실패] |
| 페어링 | Just Works(PIN 없음), `bluetoothctl pair` | [확인됨·실물] |
| 전송 (SPP/RFCOMM ch1) | 검증된 체커보드 106,300 byte 전송, 프린터 응답 11 byte | [확인됨·실물] |
| **출력물** | **체커보드 2장, 왜곡·반전 없음 — 사용자 육안 확인(2026-09-17)** | **[확인됨·실물]** |
| 프린터 응답 | `1a 3e 00 00 1a 3b 04 19 00 05 00` — 의미 | [미검증] |

> **transport 확정**: V1·V2·V3 모두 통과 → 결정 규칙 1번 → **`HARU_TRANSPORT=bt`**, SPP/RFCOMM 채널 1. HCRP는 쓰지 않는다.

### 1.3 실물 인프라

| 항목 | 값 | 확인 |
|---|---|---|
| Pi OS | Debian 12 bookworm, 커널 6.1.31, Python 3.11.2 | [확인됨·실물] |
| Pi 내장 BT | UWE5622, `hci0` `UP RUNNING`, **재부팅 20/20 생존(V3)** | [확인됨·실물] |
| Pi 스왑 | zram(압축 메모리) — SD 수명 영향 없음 | [확인됨·실물] |
| Pi 에이전트 | `haru-paper-agent` systemd, 재부팅 자동시작 + 서버 폴링 200 | [확인됨·실물] |
| Pi 접속 | `ssh haru-pi`(서버 `~/.ssh/config`, 무비밀번호), `justant` NOPASSWD sudo | [확인됨·실물] |
| 기기 토큰 | 기기별 DB 해시(M6). 웹앱 `POST /api/devices/me/token`으로 1회 발급 | [확인됨·실물] |

### 1.4 미확정 — 이번 PoC에서 답을 내야 하는 것

| # | 항목 | 경로 |
|---|---|---|
| U1 | **용지 없음을 Pi에서 감지할 수 있는가** | BT 상태 조회로 재조사(3.3). **ESP 단계는 USB 유선이므로 표준 클래스 `GET_PORT_STATUS`(paper-empty bit)가 후보로 돌아온다** — 서버에 M832를 USB로 꽂아 detox-printer에서 0원으로 시험(상태 조회만) |
| U2 | 헤드 과열 감지 | U1과 같은 응답 바이트에서 함께 본다 |
| U3 | 배터리 잔량 조회 | BLE(폰 앱 경로), 5장 |
| U4 | 절전 설정 전송 | BLE 또는 RFCOMM, 6장 |

U5(용지 없이 write해 반응 보기)는 `CLAUDE.md` 절대금지 1로 제외한다. U1·U2가 "감지 불가"로 남으면 `manual_flag` 정책(앱 수동 플래그)으로 간다 — 이 판정 자체가 PoC 산출물이다([policy.md](../docs/pi/policy.md)).

### 1.5 기기 대장

| # | 기기번호 | MAC | 펌웨어 | 역할 |
|---|---|---|---|---|
| 1 | Q253E6831170035 | C5:0D:F7:B7:B2:A1 | 2.1.5 | **유일한 기기.** 30일 운영 + 2단계 P1을 순차로 담당. 미지 벤더 명령 스윕 금지 |

혼자 테스트하는 단계에서는 프린터를 추가 구매하지 않는다. 기기가 1대뿐이므로 블라인드 스윕은 하지 않고, 스누프 캡처(5장)와 계열 확인된 명령까지만 한다.

---

## 2. 하드웨어 셋업 (BT 전용)

```
벽 콘센트 ─ 5V 3A 어댑터 ─→ Orange Pi Zero 2W
벽 콘센트 ─ 5V 2A 어댑터 ─→ M832 (상시 연결, PD 금지)
Pi ⇄ M832 : Bluetooth (SPP/RFCOMM 채널 1)  ※ USB 데이터선 없음, V4(USB 직결) 대상 제외
```

**M832 초기 설정 (폰 앱, 1회)**: 절전 모드 → "자동 종료 설정 안함" / 용지 감지 차단 → OFF / 기기번호·MAC·펌웨어 기록 / 전원 껐다 켜서 유지 확인.
**용지**: 30일 × 1장 + 보존성 3장 + 시험 인쇄분의 110mm 롤을 30일 시작 전에 확보.

### 감열지 조달처 조사 결과 [미검증, 2026-09-17 웹 조사만 — 실물 주문·실측 안 함]

M832는 **영수증 규격(절취선 있는 57/80mm)이 아니라 절취선 없는 연속 110mm 롤**을 쓴다. 이 규격은 국내 감열지 시장에서 흔치 않다 — 국내 쇼핑몰(다나와·G마켓·옥션·쿠팡·플레이24 등)에서 검색해도 대부분 POS 영수증(57/80mm)이나 바코드 라벨(75×35, 80×100mm 등) 규격만 나오고, **"연속 110mm, 절취선 없음"을 명시적으로 파는 국내 판매처는 찾지 못했다.**

| 후보 | 유형 | 대략 가격 | 비고 |
|---|---|---|---|
| **Phomemo 공식 스토어**(phomemo.com, `paper-for-m832` 컬렉션) | 해외(제조사 정품) | 3롤(각 3.5~6.5m)에 약 $19 안팎(확인 시점 기준) | M04S/M04AS/M832/M833/M834/M835 공용 명시, 흰색 무점착·점착(스티커) 두 종류. **정확히 110mm 폭·연속롤로 명시돼 있어 규격 일치 가능성이 가장 높다.** 한국 직배송 여부는 [미검증] — 배송대행(한품 등)이 필요할 수 있음 |
| Amazon.com | 해외직구 | 3롤에 $15~20대(확인 시점 기준) | 같은 제조사 호환 표기(M832/M833/M834/M835/M04S/M04AS), 무점착 110mm. 배송대행 필요 |
| 가담몰(gadammall.com) | 국내 | 미확인 | **100mm 감열지**로 확인됨 — 110mm이 아니라 폭이 안 맞을 가능성 높음. 판매자에게 110mm 재고·주문제작 가능 여부 직접 문의 필요 |
| 플레이24(play24.co.kr) "롤라벨-감열지" 카테고리, 씨엘프린텍(clprintec.co.kr) | 국내(라벨지 전문) | 미확인 | 다양한 폭을 취급하나 확인된 상품은 영수증·라벨 규격(75×35, 80×100mm 등)뿐. 110mm 연속 주문제작 가능 여부는 직접 문의 필요 |

**주의**: 108mm·112mm 등 근접 규격은 실제로는 폭이 안 맞아 좌우가 잘리거나 헐거울 수 있다 — **정확히 110mm(허용 오차는 프린터 급지구 실측 후 판단)**인지 확인해야 한다. 위 후보 전부 실물로 주문·실측한 적이 없으므로 **[미검증]**이다 — 소량(1~2롤)부터 주문해 실제 폭·인쇄 품질을 확인한 뒤 30일분을 발주하는 것을 권장한다. 국내 조달이 끝내 안 되면 해외 정품(Phomemo 공식) + 배송대행이 현실적인 대안이다.

---

## 3. 프린터 연결·전송

### 3.1 페어링 (Pi ↔ M832) — 미완

서버에는 페어링돼 있지만 운영 기기 Pi에는 아직 없다.

```bash
# haru-pi 에서
bluetoothctl
> power on
> scan on                      # M832 (C5:0D:F7:B7:B2:A1)
> pair C5:0D:F7:B7:B2:A1       # Just Works
> trust C5:0D:F7:B7:B2:A1      # 재부팅 후 자동 신뢰
> quit
sdptool search --bdaddr C5:0D:F7:B7:B2:A1 SP   # RFCOMM 채널 1 재확인
```

- 서비스 사용자 `haru`는 `bluetooth` 그룹(`install.sh`가 처리).
- overlayfs 전에 `/var/lib/bluetooth/`가 하부 레이어에 있어야 한다(8.2).

### 3.2 `pi/transport/bt` 구현 → M5 실물 인쇄 1회

1. `pi/transport/bt.py`: `socket.AF_BLUETOOTH` + `BTPROTO_RFCOMM`, `(MAC, 1)` 연결. detox-printer `10_bt_rfcomm_replay.py`에서 검증된 순서(연결 → 청크 send → 응답 read → close)를 근거 주석과 함께 이식. 청크·타임아웃·전체 데드라인 60초는 [transport.md](../docs/pi/transport.md) 1절 인터페이스대로.
2. `pi/.env`: `HARU_TRANSPORT=bt`, `HARU_BT_ADDRESS=C5:0D:F7:B7:B2:A1`, `HARU_PRINTER_DRIVER=m832`. `HARU_PAPER_POLICY`는 `unverified` 유지(예약 인쇄는 `dry_run`).
3. **M5 실물 인쇄 1회**: 앱 "지금 인쇄" + `paperConfirmed=true`(용지 육안 확인) → Pi가 BT로 인쇄 → 결과 `printed` 업로드. 보낸 바이트는 `HARU_DATA_DIR/sent/`.
4. 통과하면 [hardware-verification.md](../docs/pi/hardware-verification.md)·[transport.md](../docs/pi/transport.md)·`pi/.env.example`을 갱신한다.

> 절대금지 1: 용지 육안 확인 전 래스터를 보내지 않는다. 새 하드웨어 실험은 detox-printer에서(절대금지 3).

### 3.3 용지 감지(H4) — 상태 조회만

| 후보 | 방법 |
|---|---|
| ① 전송 후 11 byte 응답 | 용지 있음 상태에서 인쇄 후 응답 바이트 기록, 이후 변화 비교 |
| ② findpaper 직후 read | `1F 11 11` 전송 **직후** RFCOMM read(USB에선 무응답, BT는 응답 있었음) |
| ③ 계열 상태 조회 | M835 사례 `A8/A9`(용지), `98/99`(커버)를 RFCOMM으로 [추정·타 기종] |
| ④ BLE 상태 | 폰 앱 스누프 → BLE 재현 |
| ⑤ USB `GET_PORT_STATUS` | 서버에 USB로 꽂아 pyusb `ctrl_transfer`(bRequest 0x01) 1바이트 조회 — ESP 유선 단계용 |

값이 상태에 따라 바뀌면 `status_query`, 안 바뀌면 `manual_flag`([policy.md](../docs/pi/policy.md)). 용지 없는 상태에서 래스터를 보내는 시험은 하지 않는다.

---

## 4. 서버 연동

서버는 **그레이스케일 PNG**(그리고 승인된 **1-bpp `.pbm`**)까지만 만들고, 디더링·정렬보정·비트패킹·ESC/POS 인코딩·전송은 Pi(`pi/printer/m832`)가 한다. "서버가 완성 `.bin`을 만들고 기기는 write만 하는" 초기 설계는 채택되지 않았다. 원본은 [architecture.md](../docs/architecture.md).

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/device/poll` | 30초마다. `{agentVersion, printerProfile, printerStatus, paperPolicy, snapshotHash}` → `{serverTime, snapshotChanged, commands[], paperState, pollIntervalSec}` |
| GET | `/api/device/snapshot` | `snapshotChanged`일 때. 예약·렌더 목록 |
| GET | `/api/device/renders/{renderId}.png` | 그레이스케일 PNG(sha256 검증) |
| GET | `/api/device/renders/{renderId}.pbm` | **1-bpp PBM P4**(폭=프로필 `printableWidthPx`, 1=검정, MSB-first, 서버 Floyd–Steinberg). 2단계(ESP) 요구로 승인됨. h-offset·헤더·꼬리·163바이트 패딩은 기기 몫. Pi 전환은 선택 |
| POST | `/api/device/results` | 결과 묶음 업로드(`resultId` 멱등) |

- 인증: 기기별 토큰 Bearer(M6). 발급은 웹앱 `POST /api/devices/me/token`.
- 중복 방지는 Pi 로컬 occurrence 상태(`final=1`이면 재인쇄 안 함)와 서버 명령 만료(10분). 전송 중 죽으면 자동 재인쇄 없이 `failed` — "안 나옴 ≥ 두 장 나옴".
- 문서 불일치(기록만): `GET /api/device`가 문서와 달리 401 — 이번 범위 밖.

---

## 5. 배터리 잔량 (BLE)

폰 앱이 BLE로 조회한다. **스누프를 먼저 한다**(절전 설정과 한 세션):

1. 안드로이드 개발자 옵션 → Bluetooth HCI 스누프 ON → BT 껐다 켜기
2. Phomemo 앱: ① 기기 관리 화면(배터리 표시) ② 절전 모드 7종 변경(각 10초+, 시각 메모)
3. 버그 리포트 → `btsnoop_hci.log` → Wireshark `btatt` Write/Notification 페이로드

경로는 BLE GATT(`bluetoothctl` → `menu gatt`, 에이전트는 `bleak`). 표준 Battery Service(`0x180F`/`0x2A19`) 있으면 그대로, 없으면 스누프 대조. 앱 연결 해제 상태에서 조회. PoC에서 배터리 제어는 하지 않고 poll의 `printerStatus`로 보고만 [기본값].

---

## 6. 절전 설정 자동 복구

공장 기본 1시간이고 초기화·펌웨어 업데이트로 되돌아가면 다음 날 아침부터 멈춘다. **매 인쇄 직전 "자동 종료 안함" 1회 전송**으로 자동 복구한다. 설정 직후 인쇄가 깨지지 않는지 확인(필요 시 500ms 대기).

| 결과 | 대응 |
|---|---|
| RFCOMM/BLE로 전송 가능 | 에이전트 인쇄 직전 삽입 |
| 명령 특정 실패 | 손으로 설정 + 사용자 안내 |

---

## 7. 스케줄·실패 처리·SD 보호

- **스케줄 원본은 서버, 실행은 Pi**(로컬 시계 KST). 유예 30분·60초 재시도, 넘기면 `missed`. 같은 occurrence 두 번 인쇄 안 함([policy.md](../docs/pi/policy.md), [agent.md](../docs/pi/agent.md)).

| 상황 | 감지 | 동작 |
|---|---|---|
| 용지 없음 | BT 상태 조회(H4 통과 시) / 앱 수동 플래그 | 래스터 안 보냄, `skipped_no_paper`, 유예 내 재시도 |
| 프린터 미연결 | BT `open()` 실패 | `skipped_printer_offline`, 유예 내 재연결 시 인쇄 |
| 네트워크 단절 | HTTP 실패 | 백오프. 캐시로 예약 인쇄 계속 |
| 전송 행 | 데드라인 60초 초과 | `failed`, 재발행 없음 |

- 스왑 zram(SD 아님) [확인됨·실물]. 보낸 바이트 30일 순환, journald `SystemMaxUse=50M`, 시간대 `Asia/Seoul`.

---

## 8. OS·overlayfs

### 8.1 완료 [확인됨·실물]

`install.sh`로 적용·검증([setup.md](../docs/pi/setup.md)): CUPS 미설치, 패키지 `git python3-venv python3-pip libusb-1.0-0 bluez`, 고정 IP 192.168.45.28, Wi-Fi 절전 OFF, `systemd-time-wait-sync` 활성화, KST, `haru-paper-agent` 자동시작 + 재부팅 후 폴링 200. install.sh 결함 3건(그룹 추가, `chown haru`, `git pull`을 `sudo -u haru`로) 수정 완료.

### 8.2 남은 것 — overlayfs (30일 시작 직전)

개발 구간은 rw. 코드 동결 시 overlay를 켜기 전에 하부 레이어에 있어야 하는 것:

| 항목 | 이유 |
|---|---|
| `pi/.env` 기기 토큰 | tmpfs면 재부팅마다 사라짐 |
| **BT 페어링(`/var/lib/bluetooth/`)** | tmpfs면 재부팅 후 페어링 소실 |
| Wi-Fi 자격증명, venv, udev·systemd | 동일 |

overlay 후 강제 전원 차단 10회 부팅(매번 폴링·토큰·페어링 유지 확인) → 통과해야 30일 시작. 공식 Debian 12의 overlay 전환 방법은 [미검증].

---

## 9. 30일 연속 운영

**시작 조건**: 에이전트 동결 / overlay + 전원 차단 10회 / 용지 재고 / M5 실물 인쇄 1회 통과. 프로토콜 중대 변경 시 리셋, 콘텐츠·리그 교체는 리셋 아님. **ESP 부품을 기다리지 않고 준비되는 즉시 시작한다** [기본값].

실패 분류 2축 — 축1 A(자동 복구)/B(원인 규명)/C(원인 불명), 축2 **제품 영역**(프로토콜·BT·용지·스케줄·렌더 파이프라인·보존성, 2단계로 이전) vs **리그 영역**(Pi Wi-Fi/BT 드라이버·SD·부팅·런타임). **C × 제품 = 0건**이 2단계 진입 조건. 성공률은 두 영역을 나눠 집계.

보존성 테스트(창가/서랍/냉장고 각 1장, 주 1회 촬영)는 코드 동결을 기다리지 않고 시작한다.

---

## 10. 완료 기준 (Exit Criteria)

- [x] Pi 실물 개발환경·자동시작·서버 폴링 [확인됨·실물]
- [x] V1(충전기만 8시간)·V3(BT 재부팅 20/20) [확인됨·실물]
- [x] **V2 최종 통과** — BT 전송 + 체커보드 2장 육안 확인 [확인됨·실물, 2026-09-17]
- [x] 2단계(ESP32) 계획 갱신 — `.temp/02-esp32-디바이스-계획서-v1.4.md`
- [x] 서버 1-bpp `.pbm` 출력 승인(규약은 architecture.md)
- [ ] `pi/transport/bt` 구현 + **M5 실물 인쇄 1회**(BT)
- [ ] 용지 감지(U1) 판정 — "감지 불가"도 유효(그 경우 `manual_flag`)
- [ ] 배터리 조회 경로(BLE) / 절전 명령 캡처 또는 "손으로 설정" 확정
- [ ] overlayfs + 전원 차단 10회 후 매 부팅 동일 토큰·페어링으로 폴링·인쇄
- [ ] 30일 연속 운영, **제품 영역 성공률 95%+**, **C × 제품 0건**
- [ ] 출력물 30일 보존성 사진

> 95%는 PoC 통과선이지 제품 기준(99.5%+)이 아니다. PoC의 목적은 **실패 모드의 전수 목록화**다.

---

## 11. 지금 당장 할 일

**바로**

1. **Pi ↔ M832 페어링**(3.1) — 운영 기기에서 `pair` + `trust`.
2. **`pi/transport/bt` 구현 + `.env` 전환 + M5 실물 인쇄 1회**(3.2). 통과 시 docs/pi 갱신.
3. **서버 1-bpp `.pbm` 출력 구현**(서버 세션, architecture.md 규약대로).
4. **H4**: 서버에서 BT 상태 조회 후보 ①~④ + USB `GET_PORT_STATUS`(⑤) 0원 시험 — detox-printer 규칙, 상태 조회만. 결과로 용지 정책 확정.

**폰이 있을 때**

5. BLE HCI 스누프(5장) — 배터리 + 절전 7종 한 번에.

**30일 시작 직전**

6. overlayfs(토큰·페어링 하부 레이어 확인) → 전원 차단 10회 → **30일 시작**.

**병행**

7. ESP32 준비는 `.temp/02-esp32-디바이스-계획서-v1.4.md` 게이트대로 — 부품은 데이터 경로가 확인된 뒤에만. **프린터가 1대뿐이므로 ESP의 프린터 의존 단계는 30일 종료 후** [기본값].
8. 110mm 감열지 국내 조달처 조사.

> 하드웨어 실험은 전부 `~/Data/detox-printer`에서 그 저장소 규칙(보낸 바이트 저장, findings 3줄 기록)대로 하고, [확인됨]이 된 것만 `/pi`로 옮긴다. 실물로 눈으로 본 것만 [확인됨]이다.
