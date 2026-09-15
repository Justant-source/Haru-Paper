# 하드웨어 검증 현황 (V0~V4, H4, H5)

> Haru-Paper 무인 인쇄에 필요한데 **아직 확인되지 않은** 하드웨어 사실 목록과 결정 규칙.
> 실험 자체는 `~/Data/detox-printer`에서 detox-printer 규칙(findings 3줄 기록, 보낸 바이트 `captures/sent/` 저장, 용지 확인 전 래스터 금지)으로 진행한다.
> 여기서는 **결과와 그 결과가 Haru-Paper에 주는 영향**만 관리한다.
>
> - 실험 계획 원본: `~/Data/detox-printer/m832/.temp/PLAN-02.md`
> - 실험 기록 원본: `~/Data/detox-printer/m832/docs/findings.md`
> - 최초 합의: [../init_plan.md](../init_plan.md) 9절

## `.temp/01-orangepi-poc-작업지시서-v1.2.md`와의 관계

이 문서는 오렌지파이 PoC의 OS 설정·배선·하드웨어 검증 절차를 다루는 별도 작업지시서다. 그중 OS 설정(udev, overlayfs, Wi-Fi 안정화, 시계 동기화)과 하드웨어 검증(용지 감지 등) 부분은 이 저장소([setup.md](setup.md), 이 문서)에 계속 반영한다. 그러나 그 문서의 **서버 연동(4장) 부분은 채택되지 않았다** — 서버가 CUPS 필터 체인으로 완성된 `.bin`을 만들고 `/api/device/register|poll|job/{id}/stream|job/{id}/ack`로 배포하는 "멍청한 파이프" 모델을 제안하지만, 실제로 구현·운영 중인 것은 [../architecture.md](../architecture.md)와 이 저장소 `CLAUDE.md` 구성요소 경계대로 **서버가 그레이스케일 PNG를 렌더**하고 `/api/device/{poll,snapshot,renders,results}`로 제공하며, **디더링·좌우 정렬 보정·비트 패킹은 Pi가**(`pi/printer/m832/image.py`) 직접 한다(server/, docs/server/ 커밋 이력으로 확인). 그 작업지시서의 4장은 사양이 아니라 참고용 대안 설계로만 취급한다.

## 현황표 (2026-09-13 기준)

| ID | 내용 | 장소·시점 | PLAN-02 절 | 현재 상태 | 결과가 바꾸는 것 |
|---|---|---|---|---|---|
| V0 | 노트북 USB(데이터 호스트 + 전원) 연결 상태로 1시간 넘게 안 꺼짐 | 사용자 관찰 | findings V0 항목 | **[관찰·미검증]** findings 기록됨. 충전기만 연결한 조건과 같다고 가정 금지 | — (V1의 비교 기준) |
| V1 | 폰 충전기만(데이터 없음) 연결 후 2시간 뒤에도 켜져 있는가 | 사용자, 언제든 (바이트 전송 없음) | §4 | 대기 | BT 선택 가능 여부 |
| V2 | M832 Bluetooth(SPP/BLE) 지원 확인 + 검증된 체커보드 바이트(`captures/sent/0002.bin`) BT 전송 인쇄 | 노트북 **Windows 네이티브 파이썬**(분석 도구 예외, WSL엔 BT 없음), Pi 도착 전 | §5 | 대기 | `transport/bt` 방식(SPP/BLE)·청크·간격 |
| 선행 | usbipd `networkingMode=mirrored` 적용 후 연결 끊김 재발 여부 | 노트북 WSL | §6 | 대기 (mirrored는 적용된 것으로 보이나 끊김 해소는 [미검증]) | H4·H5·M1 실물 전송의 신뢰성 |
| H4 | 상태 조회 명령으로 용지 있음/없음을 구분할 수 있는가 (래스터 없이 조회만) | 노트북 WSL + USB | §7 | 대기 | 용지 정책 `status_query` vs `manual_flag` ([policy.md](policy.md)) |
| H5 | 글자가 빽빽한 110mm 페이지에서 줄이 빠지는가 | 노트북 WSL + USB | §8 | 대기 | 흐름 제어 필요 여부([transport.md](transport.md), [printer-m832.md](printer-m832.md)) |
| V3 | Pi 내장 Bluetooth(UWE5622)가 재부팅 20회 동안 매번 살아나는가 | Orange Pi (M5) | §9 | Pi 도착 후 | BT 확정 또는 Armbian 시험·USB 전환 |
| V4 | Pi USB1에 프린터 직결 시 전압 강하·재부팅이 생기는가 (**5V 3A 어댑터 필요**, 보드 규격은 5V 2A) | Orange Pi (M5) | §9 | Pi 도착 후 | USB 직결 가능 여부 / 허브 구매 |

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

### H4 1순위 실험 후보 — 표준 프린터 클래스 `GET_PORT_STATUS` [미검증]

`.temp/01-orangepi-poc-작업지시서-v1.2.md` §3.3이 제안한 경로: M832는 `7/1/2`(표준 USB 프린터 클래스)를 선언하므로, Phomemo 벤더 명령과 무관하게 표준 컨트롤 요청 `GET_PORT_STATUS`(bRequest 0x01)가 정의되어 있고 응답 1바이트에 Paper Empty(bit5) / Selected(bit4) / Not Error(bit3) 플래그가 있다. 리눅스에서는 보통 `usblp`가 이를 `LPGETSTATUS` ioctl로 노출하지만, 이 저장소의 실제 transport(`pi/transport/usb.py`)는 `usblp`가 아니라 raw pyusb를 쓰므로, 확인하려면 실험기에서 `usblp`로 바인드해 조회하거나 pyusb `dev.ctrl_transfer(...)`로 같은 표준 컨트롤 요청을 직접 보내야 한다. 값이 상태에 따라 바뀌면 U2(헤드 과열 감지)도 같은 상태 바이트의 Not Error 비트로 같이 해결될 가능성이 있다(U2는 이 문서 표에는 없으나 v1.2 문서 항목). 위 "실험 자체는 `~/Data/detox-printer`에서" 원칙대로, 이 실험은 여기서 하지 않고 detox-printer에서 먼저 시도한다.

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
