# 하루종이 (Haru Paper) — Orange Pi PoC 작업지시서

> **버전** 1.3 (실물 환경 반영)
> **작성일** 2026-09-17 (v1.2: 2026-09-15)
> **범위** 1단계 PoC — Orange Pi Zero 2W + Phomemo M832
> **목표** 서버 스케줄에 따라 매일 아침 자동으로 종이가 출력되는 것을 **1대**로 완전히 검증한다.

**변경 이력**

| 버전 | 요지 |
|---|---|
| 1.0 | 최초 작성 |
| 1.1 | 폴링 주기 서버 지시형 / 중복 방지 서버 이관 / 감지 수단 미확정 항목(U1~U5) 신설 / 스누프 선행 / 개발-운영 구간 분리 |
| 1.2 | USB 프린터 클래스 `GET_PORT_STATUS` 경로 추가 / overlayfs 토큰 휘발 함정 / 다운로드-검증-후-write·write 데드라인 / CUPS를 Pi에서 제거 / heartbeat를 poll에 통합 / 실패를 리그·제품으로 분류 |
| **1.3** | **실물 3대(Orange Pi·Ubuntu 서버·노트북) 환경을 실제로 구축·실험한 결과를 전면 반영한다. ① 연결 방식을 USB 직결 → BT(SPP/RFCOMM 채널 1)로 확정 전환 — V1·V2·V3 실측 결과. ② 4장 "멍청한 파이프(register/poll/stream/ack, 서버 .bin 생성)"는 기각된 설계이므로 실제 채택된 아키텍처(서버=그레이스케일 PNG 렌더, Pi=디더링·인코딩·전송)로 교체. ③ U5(용지 없이 write) 삭제 — CLAUDE.md 절대금지 1 위반. ④ 용지 감지(U1·U2·H4)를 USB `LPGETSTATUS` 경로에서 BT 기반 재조사로 이동 — 운영 경로에 USB가 없어졌다. ⑤ 노트북 WSL을 앞으로 사용하지 않는다 — 고유 역할(usbipd USB 직결)이 없어졌다. BT 실험은 서버, 운영은 Pi, BLE 스누프는 폰이 맡는다. ⑥ 실제로 완료된 것(Pi 개발환경, install.sh, systemd 자동시작, 서버 폴링, V1·V3 통과, V2 전송 계층)과 남은 것(V2 출력물 육안 확인, BT transport 구현, 용지 감지 BT 재조사)을 현재 상태로 명시.** |

본문에서 **(v1.3)** 표시는 이번 판에서 바뀐 곳이다. 실험 기록의 원본은 `~/Data/detox-printer/m832/docs/findings.md`이고, 이 저장소의 하드웨어 상태 요약은 `docs/pi/hardware-verification.md`다. 이 작업지시서는 그 둘을 근거로 하는 **계획 문서**이며, 사실의 원본이 아니다.

---

## 0. 실물 환경 대장 (v1.3 신설) — 지금 우리가 가진 3대

이번 판의 가장 큰 변화다. v1.2까지는 "노트북 WSL + usbipd로 프린터를 USB로 붙여 실험"을 전제했으나, 실물을 구축하면서 다음이 확정됐다.

| 머신 | 사양 (실측) | v1.3 역할 | 프린터 접근 |
|---|---|---|---|
| **Orange Pi Zero 2W** (`haru-pi`, tailnet `100.117.239.83`) | Debian 12 bookworm, 커널 6.1.31-sun50iw9, Python 3.11.2, RAM 981Mi, 스왑 zram 490Mi, 내장 BT UWE5622(UART, `hci0` `UP RUNNING`), SD 32GB | **운영 기기.** `haru-paper-agent`가 systemd로 상시 구동(자동시작·서버 폴링 확인). 프린터를 BT로 붙여 실제 인쇄를 담당한다 | **BT(SPP/RFCOMM)** — 아직 이 Pi에는 M832를 페어링하지 않음 |
| **Ubuntu 서버** (`justant-server2`, tailnet `100.81.189.92`) | Ubuntu 24.04.4, Python 3.12.3, Realtek BT 동글(`0bda:8771`, `hci` `UP`), 서버 백엔드·DB·웹앱 구동, 다른 운영 프로젝트 다수 | **BT 실험실 + 서버 백엔드.** M832가 옆에 있을 때 BT 실험을 진행했다. `~/Data/detox-printer`를 clone해 둠. M832와 **이미 페어링됨**(`Bonded: yes`) | **BT(SPP/RFCOMM), 이미 페어링** |
| **노트북** (Windows 11 + WSL2) | node18, docker, usbipd로 프린터 USB attach 가능 | **(v1.3) 앞으로 사용하지 않는다.** 아래 "왜 배제하는가" 참고 | (배제) |

### 왜 노트북 WSL을 배제하는가 (v1.3)

노트북 WSL의 **유일한 고유 능력은 usbipd로 M832를 USB로 붙여 쓰는 것**이었다(PLAN-01의 lsusb 덤프, USB 재생 인쇄, USB 상태 조회). 그런데:

1. **USB 디스크립터 덤프는 이미 끝났다** — detox-printer `m832/docs/device-descriptor.md`, findings "1단계"에 `0483:5740`, Printer 클래스 7/1/2, BULK OUT 0x02/IN 0x81, wMaxPacketSize 64가 [확인됨]으로 있다. 다시 뜰 이유가 없다.
2. **운영 경로에 USB가 없다** — Pi와 M832는 각각 독립 충전기로 전원만 받고 **BT로만** 잇기로 확정했다(사용자, 2026-09-16). USB 직결(V4)은 대상에서 제외됐다.
3. **BT 실험은 서버가 더 낫다** — 서버에 실제 BT 하드웨어가 있고 M832와 이미 페어링돼 있으며, `AF_BLUETOOTH`/`BTPROTO_RFCOMM`을 지원하는 파이썬이 있다. WSL에는 BT가 아예 없다(Windows에만 있음).
4. **usbipd는 불안정했다** — WSL2 NAT 유휴 타임아웃으로 USB attach가 반복적으로 끊겼다(findings "usbipd 연결 끊김" 3건). `networkingMode=mirrored` 조치의 해소 여부도 [미검증]이다. 굳이 안고 갈 이유가 없다.

**결론**: 앞으로 노트북 WSL에서 하는 작업은 없다. **혹시라도 USB 재검증이 필요해지면**(예: BT가 끝내 안 되어 폴백) 노트북이 아니라 **서버에 M832를 USB로 직접 꽂거나 Pi USB1을 쓴다.** `/pi` 코드는 어차피 Linux(Pi)에서 도는 것이 원본이고, Windows 네이티브 파이썬은 쓰지 않는다.

> **담당 세션 주의**: `CLAUDE.md`의 "노트북 세션 → `/pi`·`/docs/pi`" 규칙은 프린터가 노트북에 USB로 붙어 있다는 옛 전제로 만든 것이다. 실물에서는 프린터가 서버 옆(BT 실험)·Pi 옆(운영)에 있으므로, `/pi`·`/docs/pi` 작업은 사용자 승인 하에 서버 세션이 이어서 하고 있다. 담당 규칙을 정식으로 바꾸려면 사용자가 `CLAUDE.md`를 고쳐야 한다.

---

## 1. 확정된 사실 (재검증 불필요)

detox-printer(USB 리버싱)와 이번 BT 실험에서 [확인됨]이 된 것들. **USB 관련 사실은 여전히 유효**하지만, 운영 transport는 이제 USB가 아니라 BT다.

### 1.1 M832 프로토콜·전기 (USB 리버싱 + 매뉴얼)

| 항목 | 값 | 확인 |
|---|---|---|
| USB VID:PID | `0483:5740` | [확인됨] findings 1단계 |
| USB 인터페이스 클래스 | `7/1/2` (표준 프린터 클래스, 양방향) | [확인됨] |
| 데이터 엔드포인트 | BULK OUT `0x02` / BULK IN `0x81`, 둘 다 wMaxPacketSize 64 | [확인됨] |
| 초기화·헤더 | `1F 11 0B`(연속용지) + `1F 11 35 00`(압축 Off) + `1D 76 30 00 xL xH yL yH` | [확인됨·실물] |
| 래스터 폭 | **110mm 연속 롤 = 163 byte/line (1304 dot)** | [확인됨·실물] |
| 비트 극성 | 1 = 검정, MSB-first, 반전 없음 | [확인됨·실물] |
| 꼬리 (110mm) | `1B 64 01` + `1B 64 02` + `1F 11 11`(findpaper) = 9 byte | [확인됨·실물] |
| 좌우 정렬 보정 | `--h-offset-mm 2.0` → 좌우 여백 대칭 약 1mm | [확인됨·실물] |
| 농도/발열 제어 명령 | 스트림에 없음(기본 농도로 인쇄) | [확인됨·필터출력] |
| 해상도 | 300 × 300 dpi (정사각 dot) | [확인됨·실물] 원이 정원으로 인쇄 |
| 전원 입력 | DC 5V 2A 고정, PD/QC 미지원 | [확인됨·Phomemo KB] |
| 절전(자동 종료) | 앱에서 "안함" 선택 가능, 재부팅 후 유지, 공장 기본 1시간 | [확인됨·실측] |
| 상시 급전 시 기상 유지 | 8시간 이상 통과(V1) | [확인됨·실물] |
| MAC / 펌웨어 | `C5:0D:F7:B7:B2:A1` / 2.1.5 (기기 대장 1호기) | [확인됨·실물] |

### 1.2 M832 Bluetooth (v1.3 신설 — 이번 실험의 핵심 성과)

detox-printer findings "V2" 4건, `m832/src/09_bt_l2cap_replay.py`·`10_bt_rfcomm_replay.py`가 원본이다.

| 항목 | 값 | 확인 |
|---|---|---|
| 광고 서비스 | **SPP**(`JL_SPP`, 0x1101) + **HCRP**(`HCR Print`, 0x1126) 둘 다 | [확인됨·실물 SDP] |
| SPP 채널 | **RFCOMM 채널 1** | [확인됨·실물 `sdptool search SP`] |
| HCRP | L2CAP **PSM 4107** | [확인됨·실물 SDP] |
| 페어링 | Just Works(PIN 없음), `bluetoothctl pair`로 성공, `Bonded: yes` | [확인됨·실물] |
| **전송 계층 (SPP/RFCOMM ch1)** | 검증된 체커보드 106,300 byte를 오류 없이 전송, 프린터 응답 11 byte 수신 | **[확인됨·실물]** |
| 프린터 응답(전송 후) | `1a 3e 00 00 1a 3b 04 19 00 05 00` (의미 미해독) | [미검증] 관찰값 |
| HCRP(L2CAP PSM 4107) 연결 | 페어링 후에도 `connect()`가 `EPERM`으로 거부됨(root로도) | [확인됨·실패, 원인 미해결] |
| **BT 출력물** | 체커보드가 왜곡·반전 없이 실제로 인쇄됐는지 | **[미검증]** — 전송 시점에 프린터를 육안으로 볼 수 없었음. 확인 없이 같은 바이트를 2회 전송함 |

> **transport 결정**: SPP/RFCOMM 채널 1이 USB bulk-out과 같은 원시 바이트를 그대로 받는다. HCRP는 EPERM으로 막혀 후순위다. **`pi/transport/bt`는 SPP/RFCOMM 채널 1로 구현하는 것으로 방향이 잡혔고, V2 출력물 육안 확인이 끝나면 [확인됨]으로 확정한다.**

### 1.3 실물 인프라 (이번 세션에서 확정)

| 항목 | 값 | 확인 |
|---|---|---|
| Pi OS | Debian 12 bookworm, 커널 6.1.31, Python 3.11.2 | [확인됨·실물] |
| Pi 내장 BT | UWE5622, `hci0` `UP RUNNING`, **재부팅 20/20 생존(V3)** | [확인됨·실물] |
| Pi 스왑 | zram(압축 메모리), SD 스왑 아님 → SD 수명 영향 없음 | [확인됨·실물] |
| Pi 에이전트 | `haru-paper-agent` systemd, 재부팅 자동시작 + 서버 폴링 200 | [확인됨·실물] |
| Pi 접속 | `ssh haru-pi`(서버 `~/.ssh/config`, 무비밀번호), Pi `justant` 계정 NOPASSWD sudo | [확인됨·실물] |
| 기기 토큰 | M6부터 기기별 DB 해시(단일 서버 토큰 아님). 웹앱 `POST /api/devices/me/token`으로 1회 발급 | [확인됨·실물] |

### 확정되지 않은 사실 — 이번 PoC에서 답을 내야 하는 것 (v1.3 개정)

| # | 미확정 항목 | v1.3 변경점 |
|---|---|---|
| U1 | **용지 없음을 호스트(Pi)에서 감지할 수 있는가** | **경로가 바뀌었다.** USB `LPGETSTATUS`(v1.2 1순위)는 운영에 USB가 없어 해당 없음. **BT 기반으로 재조사**: ① 전송 후 11 byte 응답(1.2)의 용지 상태별 변화 ② findpaper `1F 11 11` 직후 RFCOMM read ③ 폰이 쓰는 BLE 상태 조회. 전부 **상태 조회만**(래스터 금지) |
| U2 | **헤드 과열을 감지할 수 있는가** | 동일. BT 응답 바이트에 과열 비트가 있는지 U1과 같은 세션에서 본다 |
| U3 | 배터리 잔량을 Pi에서 조회할 수 있는가 | USB 경로 없음 → **BLE**(폰 앱이 쓰는 경로)로 조사. 5장 |
| U4 | 절전(자동 종료 안함) 설정을 Pi에서 보낼 수 있는가 | 동일하게 **BLE 또는 RFCOMM**으로. 6장 |
| ~~U5~~ | ~~용지 소진 상태에서 write하면 무슨 일이 생기나~~ | **(v1.3) 삭제.** CLAUDE.md 절대금지 1("용지 없는 상태에서 래스터를 보내 반응을 보는 시험 금지")과 정면 충돌. 이 실험은 하지 않는다 |

> U1·U2가 끝까지 "감지 불가"로 남으면, 실패 처리는 **사후 보고형**(앱의 수동 "용지 장착됨" 플래그 = `manual_flag` 정책)으로 간다. 이 판정 자체가 PoC 산출물이다([policy.md](../docs/pi/policy.md)).

### 테스트 대상 기기 대장

| # | 기기번호 | MAC | 펌웨어 | 역할 |
|---|---|---|---|---|
| 1 | Q253E6831170035 | C5:0D:F7:B7:B2:A1 | 2.1.5 | **1호기 — 30일 운영 전용. 미지 벤더 명령 스윕 금지**(표준·계열 확인된 상태 조회는 허용) |
| 2 | | | | 실험기 — 리스크 있는 실험 전담 |
| 3 | | | | 예비 |

> 미지의 벤더 커맨드를 순회 전송하는 실험은 부작용을 모른다. 기기가 사실상 1대인 동안은 **스윕하지 말고** 스누프 캡처(5장)와 계열 확인된 명령까지만 한다.

---

## 2. 하드웨어 셋업 (v1.3 — BT 전용)

### 2.1 결선 — 데이터선 없음

```
벽 콘센트 ─ 5V 3A 어댑터 ─→ Orange Pi Zero 2W (전원)
벽 콘센트 ─ 5V 2A 어댑터 ─→ M832 (전원, 상시 연결)

Pi ⇄ M832 : Bluetooth (SPP/RFCOMM 채널 1)  ※ USB 데이터선으로 잇지 않는다
```

- **(v1.3) USB-A to USB-C 데이터 케이블 결선을 삭제**했다. Pi와 M832는 전기적으로 분리되고 BT로만 통신한다.
- M832 어댑터는 반드시 5V 2A(PD 금지). Pi 어댑터는 5V 3A 권장.
- V4(USB 직결 전압강하 시험)는 **대상 제외**다(사용자 확정).

### 2.2 M832 초기 설정 (폰 앱, 기기마다 1회)

1. Phomemo 앱 연결(폰)
2. 기기 관리 → 절전 모드 → **"자동 종료 설정 안함"**
3. 용지 감지 차단 → **OFF 유지**
4. 기기번호·MAC·펌웨어 버전을 대장에 기록
5. 전원 껐다 켜서 설정 유지 확인

### 2.3 용지 조달 — 30일 테스트 선행 조건

30일 × 1장 + 보존성 3장 + 개발 중 시험 인쇄분의 110mm 롤을 테스트 시작 전에 확보한다.

---

## 3. 프린터 연결·전송 검증 (v1.3 — BT)

v1.2의 3장(USB 디스크립터·피드·`GET_PORT_STATUS`·.bin 동일성)은 **USB 직결 전제**였다. 운영이 BT로 바뀌었으므로 아래로 대체한다.

### 3.1 페어링 (Pi ↔ M832) — 남은 작업

서버에서는 이미 페어링·전송이 됐다(1.2절). **운영 기기인 Pi에는 아직 M832를 페어링하지 않았다.** 남은 절차:

```bash
# haru-pi 에서
bluetoothctl
> power on
> scan on            # M832 (C5:0D:F7:B7:B2:A1) 확인되면
> pair C5:0D:F7:B7:B2:A1     # Just Works, PIN 없음
> trust C5:0D:F7:B7:B2:A1    # 재부팅 후 자동 신뢰(운영에 필요)
> quit
sdptool search --bdaddr C5:0D:F7:B7:B2:A1 SP   # RFCOMM 채널 1 재확인
```

- 서비스 사용자(`haru`)를 `bluetooth` 그룹에 넣는다(`install.sh`가 처리).
- **overlayfs를 켜기 전에** 페어링 정보(`/var/lib/bluetooth/`)가 하부 레이어에 있어야 재부팅 후에도 유지된다(7.1절 함정 목록에 추가).

### 3.2 전송 검증 — 검증된 바이트를 RFCOMM으로

detox-printer에서 확립한 방식 그대로다. **새 실험은 detox-printer에서**(CLAUDE.md 절대금지 3), [확인됨]이 된 것만 `/pi`로 옮긴다.

1. 서버(또는 Pi)에서 `m832/src/10_bt_rfcomm_replay.py --confirm-paper-loaded`로 검증된 체커보드(`captures/sent/0002.bin`)를 RFCOMM 채널 1로 전송 — **전송 계층은 이미 [확인됨]**(1.2절).
2. **남은 것 = 출력물 육안 확인**: 체커보드가 왜곡·반전 없이 나왔는지, 종이가 몇 장인지. 이게 되면 **V2 최종 통과**.
3. 통과하면 `pi/transport/bt`(SPP/RFCOMM 채널 1)를 구현하고, M1의 바이트 동일성 테스트(체커보드 + 임의 PNG)를 BT 전송으로도 1회 실물 확인한다.

> **CLAUDE.md 절대금지 1**: 용지를 육안으로 확인하기 전에는 래스터를 보내지 않는다. `10_bt_rfcomm_replay.py`는 `--confirm-paper-loaded` 없이는 소켓을 열지 않는다.

### 3.3 용지 감지 — BT 기반 재조사 (v1.2 §3.3 `GET_PORT_STATUS` 대체)

v1.2는 USB 표준 클래스 `GET_PORT_STATUS`(paper-empty bit)를 1순위로 뒀지만, 운영에 USB가 없어 해당 없음이 됐다. **BT에서 용지 유무를 알 수 있는지**를 detox-printer H4로 재조사한다. 후보(전부 **상태 조회만**, 래스터 없음):

| 후보 | 방법 | 근거 |
|---|---|---|
| ① 전송 후 11 byte 응답 | 용지 있음/없음 상태에서 각각 인쇄(용지 있을 때만) 후 응답 바이트 비교 | findings V2 RFCOMM `1a 3e … 05 00` |
| ② findpaper 직후 read | `1F 11 11` 전송 **직후** RFCOMM read(전송 후가 아니라) | USB에선 전송 후 무응답이었으나 BT는 응답이 있었음 |
| ③ 계열 상태 조회 | M835 사례 `A8/A9`(용지), `98/99`(커버)를 RFCOMM으로 | [추정·타 기종], 실험기에서만 |
| ④ BLE 상태 | 폰 앱이 쓰는 상태 조회를 스누프해 BLE로 재현 | 5장 스누프와 같은 세션 |

- 값이 상태에 따라 바뀌면 **H4 통과 → `status_query` 정책**. 안 바뀌면 **`manual_flag`**([policy.md](../docs/pi/policy.md)).
- **용지 없는 상태에서 래스터를 보내 반응을 보는 시험은 하지 않는다**(절대금지 1). 조회 명령만 보낸다.

---

## 4. 서버 연동 (v1.3 — 전면 교체)

> **v1.2 4장은 기각된 설계다.** v1.2는 "서버가 CUPS 필터로 완성된 `.bin`을 만들고 `register/poll/stream/ack`로 배포하는 멍청한 파이프"를 제안했으나, **실제로 구현·운영 중인 아키텍처는 다르다.** 원본은 [`docs/architecture.md`](../docs/architecture.md)이고, 이 절은 그것을 요약·링크할 뿐 다르게 정의하지 않는다.

### 4.1 실제 채택된 경계 (원본: architecture.md 1.1)

```
폰 웹앱(PWA) ─HTTPS(tailscale serve)─ 서버(Docker) ─30초 폴링─ Pi ─BT(RFCOMM)─ M832
```

- **서버**: 포맷·예약 저장, 프린터 프로필 폭으로 **그레이스케일 PNG 렌더**, 날씨, Pi 동기화 API. **m832 프로토콜은 모른다.**
- **Pi**: 폴링·스냅샷·PNG 캐시·예약 로컬 계산, 그리고 **디더링·좌우 정렬 보정·비트 패킹·ESC/POS 인코딩·전송**. m832를 아는 유일한 곳(`pi/printer/m832`).
- v1.2의 "서버가 .bin을 만든다 / Pi는 usblp write만 한다"는 채택되지 않았다. **서버는 PNG까지만, 바이트 조립은 Pi.**

### 4.2 실제 device API (원본: architecture.md 4.3, 이미 구현·운영 중)

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/device/poll` | 30초마다. `{agentVersion, printerProfile, printerStatus, paperPolicy, snapshotHash}` → `{serverTime, snapshotChanged, commands[], paperState, pollIntervalSec}` |
| GET | `/api/device/snapshot` | `snapshotChanged`일 때. 예약·렌더 목록 |
| GET | `/api/device/renders/{renderId}.png` | PNG 다운로드(sha256 검증) |
| POST | `/api/device/results` | 결과 묶음 업로드(`resultId` 멱등, 명령 완료 보고) |

- 인증: 기기별 토큰 Bearer. **M6부터 서버 단일 토큰이 아니라 기기별 DB 해시**다. 발급은 웹앱 `POST /api/devices/me/token`(평문은 발급 시 1회만 표시).
- v1.2의 좋은 아이디어 중 **살아남아 실제로 반영된 것**: 다운로드-검증-후-write(sha256), write 데드라인(`pi/transport/usb.py`의 60초 총 데드라인 — BT transport에도 동일 적용), heartbeat를 poll에 통합(별도 엔드포인트 없음), `pollIntervalSec` 서버 지시.
- **문서 불일치(기록만)**: architecture.md는 `GET /api/device`를 무인증으로 규정하나 실제로는 401. 서버 코드 또는 문서 갱신 필요 — 이번 범위 밖.

### 4.3 잡·중복 방지

- 중복 출력 방지는 **Pi의 로컬 occurrence 상태머신**(`executed_occurrences`, `final=1`이면 재인쇄 안 함)과 **서버 명령 만료(생성 후 10분)**로 한다([agent.md](../docs/pi/agent.md) 9절). v1.2의 서버 잡 상태머신(pending/delivered/done/failed) 도식은 채택 안 됨.
- "안 나옴 ≥ 두 장 나옴" 정책은 유지: 전송 중 프로세스가 죽으면 자동 재인쇄하지 않고 `failed`로 끝낸다.

---

## 5. 배터리 잔량 확인 (v1.3 — USB 경로 삭제, BLE 우선)

M832는 배터리를 내부에서 관리하고 폰 앱이 **BLE**로 조회한다. 운영에 USB가 없으므로 v1.2의 "경로 A(USB 상태 조회)"는 뺀다.

### 5.1 스누프를 먼저 한다 (폰)

폰 앱은 배터리를 BLE로 조회한다 → 안드로이드 Bluetooth HCI 스누프 로그에 정답 명령이 들어 있다. 블라인드 스윕보다 빠르고 안전하다. 절전 설정(6장)과 **한 세션에서** 캡처한다.

1. 개발자 옵션 → Bluetooth HCI 스누프 로그 ON → BT 껐다 켜기
2. Phomemo 앱: ① 기기 관리 화면 진입(배터리 표시) ② 절전 모드 7종 변경(각 사이 10초+, 시각 메모)
3. 버그 리포트 → `btsnoop_hci.log` 추출 → Wireshark `btatt` 필터로 Write/Notification 페이로드 확인

### 5.2 경로 — BLE GATT (Pi/서버)

```bash
bluetoothctl        # scan on → connect C5:0D:F7:B7:B2:A1 → menu gatt → list-attributes
```

- 표준 Battery Service(`0x180F`/`0x2A19`)가 있으면 그대로. 없으면 스누프의 handle/UUID와 대조. 에이전트 코드는 `bleak`.
- 앱이 BLE로 연결 중이면 동시 연결이 안 될 수 있다 — 앱 연결 해제 상태에서 조회.
- BLE 채택 시 2단계(ESP32) 비용이 는다는 점 기록(Wi-Fi/BLE 라디오 공유). PoC에서 **배터리 제어는 하지 않는다.**
- poll 본문의 `printerStatus`에 잔량을 실어 서버 임계값(20%) 미만이면 웹훅 알림 [기본값].

---

## 6. 절전 설정 자동화 (v1.3 — BLE/RFCOMM)

### 6.1 문제·목표

- 공장 기본 자동 종료 1시간. 공장 초기화·펌웨어 업데이트로 되돌아가면 다음 날 아침부터 출력이 멈춘다.
- **매 인쇄 직전에 "자동 종료 안함" 명령을 1회 전송**해 어떤 경우에도 자동 복구되게 한다(하루 1회 플래시 쓰기는 수명에 무의미).
- 설정 명령 직후 바로 인쇄 스트림을 보냈을 때 출력이 깨지지 않는지 확인한다. 필요하면 명령과 인쇄 사이 대기(예: 500ms).

### 6.2 절차

1. **캡처** — 5.1 스누프로 `1시간 → 안함` 순간의 Write Request 특정. 7종 대조표 작성.
2. **전송 시도** — BLE 또는 RFCOMM으로. 전송 전에 앱을 다른 값으로 맞춰두고 보내야 변화를 관찰할 수 있다.
3. **결과별 대응**

| 결과 | PoC | 양산(2단계) |
|---|---|---|
| BT로 전송 가능 | 에이전트 인쇄 직전에 삽입 | 그대로 채택 |
| 명령 특정 실패 | 손으로 설정 | 출하 전 검사 공정 + 사용자 안내 |

---

## 7. 스케줄 출력·저장소 보호 (v1.3 — 실물 반영)

### 7.1 스케줄

- **스케줄 판단은 서버, 실행은 Pi.** 기기에 cron을 두지 않는다. 서버가 KST로 잡을 만들고 Pi가 로컬 시계로 실행한다([policy.md](../docs/pi/policy.md), [agent.md](../docs/pi/agent.md)).
- 늦은 실행은 유예 30분·60초 재시도, 넘기면 `missed`. 같은 occurrence는 두 번 인쇄 안 함.

### 7.2 실패 처리 (v1.3 — 감지 수단 BT로 갱신)

| 상황 | 감지 수단 | 동작 |
|---|---|---|
| 용지 없음 | **(v1.3)** BT 상태 조회(3.3, H4 통과 시) → 미통과면 앱 수동 플래그(`manual_flag`) | 감지 시 래스터 안 보냄, `skipped_no_paper` 후 유예 내 재시도 |
| 프린터 미연결 | BT `open()` 실패 — 감지 가능 | `skipped_printer_offline`, 유예 내 재연결되면 인쇄 |
| 네트워크 단절 | HTTP 실패 — 감지 가능 | 백오프. 캐시 PNG로 예약 인쇄는 계속 |
| 헤드 과열 | BT 응답 과열 비트(U2, 확인되면) | 확인 후 정의 |
| 전송 행(hang) | write 총 데드라인 60초 초과 | `failed`, 원인 불명이므로 재발행 없음 |

> v1.2의 기기 측 재시도 로직은 그대로 유지 삭제 방침(dumb pipe): 기기는 상태를 보고하고 받을 수 있으면 받을 뿐, 재시도·재발행은 서버·Pi 상태로 관리한다.

### 7.3 저장소(SD) 보호 — 실물 확인됨

- 스왑은 zram(SD 아님) — 별도 조치 불필요 [확인됨·실물].
- 보낸 바이트 `HARU_DATA_DIR/sent/`에 30일 순환. journald 크기 제한(`SystemMaxUse=50M`). 시간대 `Asia/Seoul` 고정.

---

## 8. OS·overlayfs (v1.3 — 대부분 완료, overlay만 남음)

### 8.1 완료된 것 [확인됨·실물]

`install.sh`로 실제 적용·검증했다([setup.md](../docs/pi/setup.md)):

- CUPS 미설치(Pi 이미지 = 파이썬 에이전트 + BT). 패키지: `git python3-venv python3-pip libusb-1.0-0 bluez`.
- 고정 IP(192.168.45.28), Wi-Fi 절전 OFF, `systemd-time-wait-sync` 활성화(RTC 없음 대응), 시간대 KST.
- `haru-paper-agent` systemd 자동시작 + 재부팅 후 서버 폴링 200.
- **install.sh 결함 3건 수정 완료**(그룹 추가 로직, clone 후 `chown haru`, `git pull`을 `sudo -u haru`로 — dubious ownership).

### 8.2 남은 것 — overlayfs (30일 시작 직전)

개발 구간(1~2주)은 rw로 두고, 코드 동결 시 overlay를 켠다. **overlay 켜기 전 하부 레이어에 있어야 하는 것**:

| 항목 | 이유 |
|---|---|
| `pi/.env`의 기기 토큰 | tmpfs에 있으면 재부팅마다 사라져 매번 오프라인처럼 보임 |
| **(v1.3) BT 페어링(`/var/lib/bluetooth/`)** | tmpfs면 재부팅 후 페어링이 풀려 인쇄 불가 |
| Wi-Fi 자격증명, venv, udev·systemd | 동일 |

- overlay 적용 후 강제 전원 차단 10회 부팅 검증(매번 폴링 도달·토큰·페어링 유지 확인) → 통과해야 30일 카운트 시작.
- 공식 Debian 12의 overlay 전환 방법은 [미검증] — Armbian `armbian-config`와 다를 수 있음.

---

## 9. 30일 연속 운영 (v1.3 — 실패 분류 유지)

**시작 조건**: ① 에이전트 동결 ② overlay + 전원 차단 10회 통과(토큰·페어링 유지) ③ 용지 재고 ④ V2 최종 통과(BT 실물 인쇄). 운영 중 프로토콜 중대 변경 시 리셋. 콘텐츠 변경·리그 교체(Wi-Fi/BT 동글)는 리셋 사유 아님.

**실패 분류 2축**(v1.2 유지):

- 축1 복구 성격: A(자동 복구) / B(원인 규명) / C(원인 불명)
- 축2 이전성: **제품 영역**(프린터 프로토콜·BT·용지·스케줄·PNG 파이프라인·감열 보존성 — 2단계로 직접 이전) vs **리그 영역**(Pi Wi-Fi/BT 드라이버·SD·부팅·파이썬 런타임 — ESP32와 무관).
- **C등급 × 제품 영역 = 0건**이 2단계 진입 조건. 성공률도 두 영역을 나눠 집계.

출력물 보존성 테스트(창가/서랍/냉장고 각 1장, 주 1회 동일 조명 촬영)는 **코드 동결을 기다리지 않고** 시작한다.

---

## 10. 완료 기준 (Exit Criteria) — v1.3

- [ ] 동결 구성으로 30일 연속 운영, **제품 영역 자동 출력 성공률 95%+**(A등급 성공 집계)
- [ ] **C등급 × 제품 영역 실패 0건**
- [x] USB 디스크립터 확보 — detox-printer device-descriptor.md [확인됨]
- [x] **BT 전송 계층 확인**(SPP/RFCOMM 채널 1, 106,300 byte 전송) [확인됨·실물]
- [ ] **BT 출력물 육안 확인**(V2 최종 통과) — 남음
- [ ] **`pi/transport/bt` 구현** + 바이트 동일성 테스트를 BT 전송으로 실물 1회
- [ ] 용지 감지 BT 재조사 판정(U1) — "감지 불가"도 유효한 결론(그 경우 `manual_flag` 설계 문서 포함)
- [ ] 배터리 조회 경로 확정(BLE) / 절전 설정 명령 캡처 또는 "손으로 설정" 확정
- [ ] 출력물 30일 보존성 사진
- [ ] overlayfs + 강제 전원 차단 10회 후 매 부팅 동일 토큰·페어링으로 폴링·인쇄 성공
- [x] 2단계(ESP32) 계획 갱신 — BT transport 반영, 칩 S3 → 원본 ESP32(PSRAM), 구매 게이트화 (`.temp/02-esp32-디바이스-계획서-v1.2.md`, 2026-09-17). BLE 필요 여부는 그 문서 G1에서 판정
- [x] Pi 실물 개발환경·자동시작·서버 폴링 [확인됨·실물]
- [x] V1(충전기만 8시간 생존)·V3(BT 재부팅 20/20) [확인됨·실물]

> 95%는 PoC 통과선이지 제품 기준(99.5%+)이 아니다. PoC의 목적은 수치가 아니라 **실패 모드의 전수 목록화**다.

---

## 11. 지금 당장 할 일 (v1.3 — 현재 상태 기준 우선순위)

USB 전제의 v1.2 10장은 대부분 완료됐거나 해당 없음이 됐다. 현재 남은 순서:

**바로**

1. **V2 출력물 육안 확인** — 프린터를 볼 수 있을 때 체커보드 왜곡·반전·매수 확인. 되면 V2 최종 통과 → `docs/pi/hardware-verification.md`·`transport.md` 갱신.
2. **Pi ↔ M832 페어링**(3.1) — 서버가 아니라 운영 기기 Pi에서 `pair` + `trust`.
3. **`pi/transport/bt` 구현**(SPP/RFCOMM 채널 1) — detox-printer `10_bt_rfcomm_replay.py`에서 검증된 순서를 근거 주석과 함께 이식. M1 바이트 동일성 테스트를 BT로 실물 1회.

**폰이 있을 때 (한 세션에 묶어서)**

4. **BLE HCI 스누프**(5.1) — 배터리 + 절전 7종을 한 번에 캡처.
5. **용지 감지 BT 재조사**(3.3, H4) — 상태 조회만. 결과로 용지 정책(`status_query`/`manual_flag`) 확정.

**30일 시작 직전**

6. overlayfs 적용(토큰·페어링 하부 레이어 확인) → 전원 차단 10회 → 30일 시작.

**병행(30일은 기다리는 시간이 아니다)**

7. 2단계(ESP32) 준비 — **부품 구매 없이** 게이트 조건만 진행: ESP-IDF 설치·SPP 예제 빌드, (선택) BLE GATT 0원 실험, 이미지 파이프라인 결정. **ESP32-S3에는 BT Classic이 없어 v1.1의 S3·USB 호스트·인라인 전원 설계는 폐기됐다** — 상세는 `.temp/02-esp32-디바이스-계획서-v1.2.md`.
8. 110mm 감열지 국내 조달처 조사.

> **하드웨어 실험은 전부 `~/Data/detox-printer`에서** 그 저장소 규칙(보낸 바이트 `captures/sent/` 저장, findings.md 3줄 기록)대로 하고, [확인됨]이 된 것만 `/pi/printer/m832`·`/pi/transport`로 옮긴다(CLAUDE.md 절대금지 3). 실물로 눈으로 본 것만 [확인됨]이다(절대금지 2).
