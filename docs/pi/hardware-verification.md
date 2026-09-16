# 하드웨어 검증 현황 (V0~V4, H4, H5)

> Haru-Paper 무인 인쇄에 필요한데 **아직 확인되지 않은** 하드웨어 사실 목록과 결정 규칙.
> 실험 자체는 `~/Data/detox-printer`에서 detox-printer 규칙(findings 3줄 기록, 보낸 바이트 `captures/sent/` 저장, 용지 확인 전 래스터 금지)으로 진행한다.
> 여기서는 **결과와 그 결과가 Haru-Paper에 주는 영향**만 관리한다.
>
> - 실험 계획 원본: `~/Data/detox-printer/m832/.temp/PLAN-02.md`
> - 실험 기록 원본: `~/Data/detox-printer/m832/docs/findings.md`
> - 최초 합의: [../init_plan.md](../init_plan.md) 9절

## `.temp/01-orangepi-poc-작업지시서-v1.3.md`와의 관계

이 문서는 오렌지파이 PoC의 OS 설정·배선·하드웨어 검증 절차를 다루는 별도 작업지시서다(2026-09-17 v1.2 → v1.3 개정, 실물 3대 환경·BT 전환·노트북 WSL 배제 반영). 그중 OS 설정(overlayfs, Wi-Fi 안정화, 시계 동기화)과 하드웨어 검증(BT 전송·용지 감지 등) 부분은 이 저장소([setup.md](setup.md), 이 문서)에 계속 반영한다. **v1.3에서 이미 바로잡은 것**: ① 연결 방식을 USB 직결 → BT(SPP/RFCOMM 채널 1)로 전환 ② 서버 연동(옛 4장 "멍청한 파이프": CUPS `.bin` + `register/poll/stream/ack`)은 **기각된 설계**이므로 실제 아키텍처(서버=그레이스케일 PNG 렌더, Pi=디더링·정렬보정·비트패킹·인코딩·전송, `/api/device/{poll,snapshot,renders,results}`)로 교체 ③ U5(용지 없이 write) 삭제 — `CLAUDE.md` 절대금지 1 위반 ④ 용지 감지를 USB `GET_PORT_STATUS` → BT 기반 재조사로 이동. 규약 원본은 [../architecture.md](../architecture.md)다.

### 절별 처리 현황 (2026-09-16, 서버 세션 Pi 개발환경 구축 시점)

| 절 | 제목 | 상태 | 사유 |
|---|---|---|---|
| 0장 | 확정된 사실·미확정 항목(U1~U5)·기기 대장 | 진행됨 | detox-printer findings에 [확인됨]으로 반영, VID:PID·명령·펌웨어 v2.1.5 확인 |
| 1.1 | 하드웨어 결선 | 진행됨(설계만) | [setup.md](setup.md) 3절. **단, 실제로는 USB 직결이 아니라 BT/Wi-Fi 결선으로 확정**(아래 참고) |
| 1.2 | M832 초기 설정 | 하지 않음 | 프린터가 이 세션 범위(Pi)에 없음. 노트북 세션 담당 |
| 1.3 | 용지 조달 | 하지 않음 | 30일 운영 선행 조건, 아직 이름 |
| 2.1 | OS 설정(CUPS 미설치·시계·패키지) | **진행됨·실물** | [setup.md](setup.md) 4.3절, 5절 |
| 2.2 | udev 규칙 | **진행됨·실물** | `install.sh` 5단계로 적용. 단, 운영 경로는 문서의 `usblp`가 아니라 pyusb([transport.md](transport.md)) |
| 2.3~2.4 | USB 점유·Wi-Fi 안정화 | 진행됨·실물 | [setup.md](setup.md) 4.4절, `install.sh` 11단계 |
| 2.5 | overlayfs | 하지 않음(의도적) | "개발 구간 1~2주는 rw" 규칙 — 코드 동결 후 30일 시작 직전에 적용 |
| 3.1~3.4 | USB 디스크립터·피드·GET_PORT_STATUS·.bin 동일성 | 하지 않음 | **프린터가 Pi에 물리적으로 연결되지 않는다**(아래 참고). USB 계열 시험 전부 대상 없음 |
| 4장 | 서버 연동(register/poll/stream/ack) | **기각된 설계** | 위 문단 참고. `docs/architecture.md`가 원본 |
| 5·6장 | 배터리·절전 설정 | 하지 않음 | 폰 BLE 스누프 선행 필요, detox-printer 담당 |
| 7·8장 | 스케줄·30일 운영 설계 | 진행됨(설계) | [policy.md](policy.md), 8절 실패 분류(A/B/C×제품/리그) |
| 9절 Exit Criteria | 11개 항목 | 개별 — 아래 참고 | — |
| 10절 "지금 당장 할 일" | 10개 항목 | 개별 — 아래 참고 | — |

**10절 개별 판정**: 1(lsusb 덤프)·2(ESC d 피드)·3(lpstatus.py)·4(보존성 3장)는 프린터 필요라 이번에 못함. 5(BLE 스누프)는 detox-printer 선행 필요. **6(U5: 용지 없이 write)은 CLAUDE.md 절대금지 1과 정면 충돌해 실행 금지.** 7(서버 잡 상태머신)은 기각된 설계. 8(cupsfilter .bin)은 서버 세션 별도 범위. 9(에이전트 다운로드-검증-write 루프)는 이미 `pi/agent`에 구현돼 있고 이번에 실물로 폴링·스냅샷 다운로드까지 확인함(agent.md 참고). 10(register+토큰+overlayfs+30일)은 토큰 발급까지는 이번에 했고(setup.md 5.2절) overlayfs·30일은 착수 전.

**Pi ↔ M832 결선 확정 [확인됨·사용자, 2026-09-16]**: Pi와 M832는 각각 독립된 USB-C 충전기로 전원만 받고, 데이터 연결은 **BT 또는 Wi-Fi로만** 한다. USB 데이터 케이블로 잇지 않는다. 이에 따라:
- **V4(USB 직결 전압강하 시험)는 이 프로젝트에서 진행하지 않는다** — `HARU_TRANSPORT=usb` 경로는 당분간 불필요
- V1~V3(BT 경로 확인)이 실질적인 목표 경로가 된다. V3(Pi 내장 BT 재부팅 20회 생존)는 프린터 페어링 없이도 시험 가능하나 이번 세션에서는 하지 않음(사용자 선택)
- 이 결정은 [setup.md](setup.md) 3절의 "BT 방식(목표)" 배선과 일치한다

## 현황표 (2026-09-13 기준)

| ID | 내용 | 장소·시점 | PLAN-02 절 | 현재 상태 | 결과가 바꾸는 것 |
|---|---|---|---|---|---|
| V0 | 노트북 USB(데이터 호스트 + 전원) 연결 상태로 1시간 넘게 안 꺼짐 | 사용자 관찰 | findings V0 항목 | **[관찰·미검증]** findings 기록됨. 충전기만 연결한 조건과 같다고 가정 금지 | — (V1의 비교 기준) |
| V1 | 폰 충전기만(데이터 없음) 연결 후 2시간 뒤에도 켜져 있는가 | 사용자, 언제든 (바이트 전송 없음) | §4 | **[확인됨·실물, 2026-09-17] 통과 — 사용자가 8시간 뒤 확인, M832·Pi 둘 다 켜져 있었음(2시간 기준 초과 충족)** | BT 선택 가능 — 통과 |
| V2 | M832 Bluetooth(SPP/BLE) 지원 확인 + 검증된 체커보드 바이트(`captures/sent/0002.bin`) BT 전송 인쇄 | 서버 세션(justant-server2, 이 서버에도 BT 하드웨어가 있어 detox-printer를 clone해 진행), 2026-09-17 | §5 | **부분 확인 — 아래 "V2 실측 기록" 참고. 전송 계층 [확인됨·실물], 출력물 육안 확인은 [미검증]** | `transport/bt` 방식(SPP 확정)·청크·간격 |
| 선행 | usbipd `networkingMode=mirrored` 적용 후 연결 끊김 재발 여부 | 노트북 WSL | §6 | 대기 (mirrored는 적용된 것으로 보이나 끊김 해소는 [미검증]) | H4·H5·M1 실물 전송의 신뢰성 |
| H4 | 상태 조회 명령으로 용지 있음/없음을 구분할 수 있는가 (래스터 없이 조회만) | 노트북 WSL + USB | §7 | 대기 | 용지 정책 `status_query` vs `manual_flag` ([policy.md](policy.md)) |
| H5 | 글자가 빽빽한 110mm 페이지에서 줄이 빠지는가 | 노트북 WSL + USB | §8 | 대기 | 흐름 제어 필요 여부([transport.md](transport.md), [printer-m832.md](printer-m832.md)) |
| V3 | Pi 내장 Bluetooth(UWE5622)가 재부팅 20회 동안 매번 살아나는가 | Orange Pi (M5) | §9 | **[확인됨·실물, 2026-09-16] 통과 — 20/20** | BT 확정 |
| V4 | Pi USB1에 프린터 직결 시 전압 강하·재부팅이 생기는가 (**5V 3A 어댑터 필요**, 보드 규격은 5V 2A) | Orange Pi (M5) | §9 | **대상 제외** — 사용자가 Pi-M832를 BT/Wi-Fi로만 잇기로 확정(위 문단) | — |

### V2 실측 기록 — 전송 [확인됨·실물], 출력물 [미검증], 2026-09-17

원래 계획(작업지시서 v1.2 §5, 이 문서 구버전)은 V2를 "노트북 Windows 네이티브 파이썬"에서 하도록 정해뒀지만,
서버(`justant-server2`)에도 실제 BT 하드웨어(Realtek Bluetooth Radio)가 있고 이 시점에 M832가 서버 옆에
켜져 있어서 서버 세션이 진행했다. **새 하드웨어 실험 절대금지(CLAUDE.md 3번) 준수**: `~/Data/detox-printer`를
이 서버에 clone해서 그 저장소 규칙(findings.md 3줄 기록, 보낸 바이트 `captures/sent/` 저장)대로 진행했다 —
자세한 스크립트·기록은 `~/Data/detox-printer/m832/docs/findings.md`와 `m832/src/09_bt_l2cap_replay.py`,
`m832/src/10_bt_rfcomm_replay.py`.

**M832 BT 프로토콜은 `init_plan.md`의 [추정](같은 계열은 SPP)과 실제로 맞았다 — SPP·HCRP 둘 다 지원**:

| 서비스 | 프로토콜 | 결과 |
|---|---|---|
| HCRP("HCR Print", 0x1126) | L2CAP PSM 4107 | 페어링 전 `connect()`가 `EPERM`으로 거부됨(root로 실행해도 동일 — 링크 레벨 인증 문제로 추정, 미해결) |
| **SPP("JL_SPP", 0x1101)** | **RFCOMM 채널 1** | **페어링 후 연결 성공, 검증된 체커보드 바이트(0002.bin, 106,300byte) 전송 완료, 프린터 응답 11byte 수신(`1a 3e 00 00 1a 3b 04 19 00 05 00`)** |

- 페어링(`bluetoothctl pair`)은 PIN 없이 성공(Just Works로 추정)
- **[확인됨·실물] 전송 계층**: SPP/RFCOMM이 USB bulk-out과 같은 원시 바이트를 그대로 받고, 106,300/106,300byte
  오류 없이 전송됐으며 프린터가 응답까지 보냈다
- **[미검증] 출력물**: 전송 시점에 사용자가 프린터를 육안으로 확인할 수 없는 위치에 있어, 체커보드가 실제로
  왜곡·반전 없이 인쇄됐는지 아직 확인 못했다. CLAUDE.md 절대금지 2("실제로 확인하지 않은 사실을 [확인됨]으로
  쓰지 않는다")에 따라 이 부분은 확정하지 않는다. **확인 없이 같은 바이트를 두 번 연속 전송**했다 —
  용지가 정상 소모됐다면 2장이 나와 있어야 한다. 다음에 프린터를 볼 수 있을 때 육안 확인 필요
- V2가 최종적으로 "통과"로 확정되려면 이 출력물 확인이 남아있다. `transport/bt`는 SPP/RFCOMM 채널 1로
  구현하는 것으로 사실상 방향이 잡혔다(HCRP는 페어링 요구 등 복잡도가 더 높아 후순위)

### V3 실측 기록 [확인됨·실물, 2026-09-16]

서버 세션이 `haru-pi`를 SSH로 20회 재부팅하며 매 사이클 `sudo hciconfig hci0`로 `UP RUNNING` 여부를 확인했다.
**20/20 통과**, 소요 약 17분(예상 40~60분보다 빠름 — 이 Pi의 부팅+Wi-Fi 재연결이 평균 30초 안팎). 20회차 재부팅 후
`haru-paper-agent`도 정상 기동해 서버 폴링(200)까지 이어지는 것을 같이 확인했다. 이걸로 **V3는 BT 확정**으로
판정한다 — 단, V1·V2가 아직 대기 상태라 "프린터 연결 방식" 최종 결정(아래 "결정 규칙")은 V1·V2까지 나와야
내려진다.

## 결정 규칙

### 프린터 연결 방식 (`HARU_TRANSPORT`)

1. **V1 · V2 · V3 모두 통과 → `bt`** (프린터는 전용 충전기에 상시 연결, Pi와 전기적으로 분리)
2. 하나라도 실패 → **USB 직결(V4)** 시험 → 통과 시 `usb`
3. V4에서 전원 문제(전압 강하·재부팅) → **셀프전원 USB 허브(역전류 방지, 포트당 2A 이상) 구매** 재논의

코드는 결정과 무관하게 `usb`·`bt` 둘 다 같은 인터페이스로 둔다([transport.md](transport.md)). M1은 지금 바로 시험 가능한 `usb`부터 만든다.

### 용지 정책 (`HARU_PAPER_POLICY`)

| H4 결과 | 정책 | 무인(예약) 인쇄 조건 |
|---|---|---|
| 통과 전 (현재) | `unverified` | 무인 인쇄 안 함 — `dry_run` 기록만 |
| 통과 | `status_query` | 인쇄 직전 상태 조회 응답이 "용지 있음"일 때만 |
| 실패 | `manual_flag` | 앱의 수동 "용지 장착됨"이 켜져 있을 때만 |

상세는 [policy.md](policy.md).

### H4 실험 후보 — BT 기반 재조사 [미검증] (v1.3: USB `GET_PORT_STATUS` 경로 폐기)

**v1.2까지의 1순위는 USB 표준 클래스 `GET_PORT_STATUS`**(bRequest 0x01, 응답 1바이트에 Paper Empty bit5 / Not Error bit3)였고, 리눅스 `usblp`의 `LPGETSTATUS` ioctl 또는 pyusb `ctrl_transfer`로 조회하는 방식이었다. 그러나 **Pi와 M832를 USB로 잇지 않기로 확정(2026-09-16)되어 운영 경로에 USB가 없다** — 이 경로는 해당 없음이 됐다. 작업지시서 v1.3 §3.3대로 **BT에서 용지 유무를 알 수 있는지**를 재조사한다(전부 상태 조회만, 래스터 금지):

1. **전송 후 응답 바이트**: RFCOMM 전송 후 프린터가 11바이트를 돌려줬다(`1a 3e 00 00 1a 3b 04 19 00 05 00`, findings V2). 용지 있음/없음 상태별로 이 바이트가 바뀌는지 본다.
2. **findpaper 직후 read**: `1F 11 11`을 전송 **직후**(USB에선 전송 후 무응답이었으나 BT는 응답이 있었음) RFCOMM read.
3. **계열 상태 조회**: M835 사례 `A8/A9`(용지), `98/99`(커버)를 RFCOMM으로 [추정·타 기종, 실험기만].
4. **BLE 상태**: 폰 앱이 쓰는 상태 조회를 스누프해 BLE로 재현.

값이 상태에 따라 바뀌면 U2(헤드 과열)도 같은 응답의 다른 비트로 함께 풀릴 가능성이 있다. "실험 자체는 `~/Data/detox-printer`에서" 원칙대로, 이 실험은 여기서 하지 않고 detox-printer에서 먼저 시도한다.

### 흐름 제어 (H5)

- 줄 누락 없음 → 현재 방식(USB 4096B 청크 연속 전송) 유지
- 줄 누락 있음 → 계열 기종 사례(M835: 1KB씩 보내며 상태 조회로 버퍼 비움 대기, 응답 `0C`) 기반 흐름 제어를 detox-printer에서 먼저 [확인됨]으로 만든 뒤 이식 [추정·타 기종]

## 금지

- **용지 없는 상태에서 래스터를 보내 프린터 반응을 보는 시험은 하지 않는다**(detox-printer 금지 5). H4는 상태 조회 명령만 보낸다.
- 후보 명령(`1F 11 0E` 자동 꺼짐 조회, `1B 4E 07 n` 자동 꺼짐 설정, M835의 상태 조회 계열)은 전부 **다른 기종 자료 기반 [추정]**이다. 추측으로 새 명령을 찾아 보내지 않는다.
- Windows 네이티브 파이썬은 V2 **분석 도구로만** 쓴다. Haru-Paper `/pi` 코드는 Linux(Pi)에서 동작해야 한다.

## 결과가 나오면 갱신하는 절차

1. detox-printer에서 실험 → `m832/docs/findings.md`에 3줄 기록(보낸 것/관찰/결론), 보낸 바이트는 `captures/sent/`에 저장.
2. **detox-printer에서 [확인됨]이 된 항목만** 여기로 가져온다. 이 문서의 현황표 "현재 상태" 칸을 고치고 findings 항목 제목을 적는다.
3. 영향받는 문서를 같은 커밋에서 고친다.
   - V1·V2·V3·V4 → [transport.md](transport.md)(방식·파라미터), [setup.md](setup.md)(전원·배치), `pi/.env.example`의 `HARU_TRANSPORT` 설명
   - H4 → [policy.md](policy.md)(정책 전환), [printer-m832.md](printer-m832.md)(`status()` 구현 근거: 명령·응답 바이트)
   - H5 → [transport.md](transport.md)·[printer-m832.md](printer-m832.md)(흐름 제어)
4. 코드 반영(`/pi/printer/m832`, `/pi/transport`)은 상수 옆에 detox-printer findings 근거 주석을 단다.
5. 연결 방식이나 용지 정책이 확정되면 [../init_plan.md](../init_plan.md) 13절 "열린 항목"은 그대로 두고(최초 기록), [../architecture.md](../architecture.md)에 영향이 있으면 공통 파일 규칙(수정 직전 `git pull --ff-only`)으로 고친다.
