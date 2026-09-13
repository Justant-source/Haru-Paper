# Orange Pi Zero 2W 설치 (M5)

> Pi를 받아서 `haru-paper-agent`가 부팅 시 자동으로 도는 상태까지 만드는 절차.
> 최초 결정: [../init_plan.md](../init_plan.md) 3장·8.1·10절 M5, Q24·Q34.
> 공식 이미지의 세부 절차(파일 이름, 기본 계정, 헤드리스 Wi-Fi 설정 방법 등)는 **아직 확인하지 않았다 [미검증]**. 실제로 해 보고 이 문서를 고친다.

## 1. 보드 사양 (구매 정보)

| 항목 | 값 |
|---|---|
| SoC | Allwinner H618, Cortex-A53 쿼드코어 1.5GHz |
| GPU | Mali G31 MP2 |
| RAM | **LPDDR4 1GB** → Pi에서는 렌더링하지 않는다(서버가 PNG를 그림) |
| 저장 | Micro SD **32GB 사용**, SPI Flash 16MB |
| 무선 | Wi-Fi 5 + BT 5.0 / BLE (UWE5622, BT는 UART 연결) |
| 영상 | Mini HDMI 1개 |
| USB | Type-C USB 2.0 × 2 — **USB0**: 호스트/디바이스 겸용(공식 이미지 기본은 디바이스), 전원 입력 / **USB1**: 호스트 전용 |
| 확장 | 24핀(USB 2.0 ×2, 100M 이더넷, IR, 오디오 등 — 확장보드 필요), 40핀(GPIO/UART/I2C/SPI/PWM) |
| 전원 규격 | **Type-C 5V 2A** |
| RTC | **없음** ([policy.md](policy.md) 4절) |
| 지원 OS | Debian 11/12, Ubuntu 20.04/22.04, Android 12 TV, Orange Pi OS(Arch) |

## 2. 도착 시 확인 체크리스트

- [ ] **전원 어댑터 포함 여부와 사양** — 5V 2A인지 **5V 3A인지**. V4(USB 직결 시험)에는 5V 3A가 필요하다
- [ ] USB-C 케이블 포함 여부
- [ ] **OTG 젠더**(USB-C 수 → USB-A 암) 포함 여부 — 프린터 케이블 종류에 따라 필요
- [ ] 방열판·케이스 포함 여부
- [ ] 지금 프린터를 노트북에 연결한 케이블 종류(C-C 또는 A-C) — Pi 직결 시 필요한 젠더 판단
- [ ] Micro SD 32GB 준비
- [ ] (헤드리스 설정이 안 될 경우 대비) Mini HDMI 케이블·어댑터, USB 키보드(+OTG 젠더) 준비 가능 여부
- [ ] 확인 결과를 이 체크리스트에 적고, 추가 구매가 필요하면 사용자에게 알린다

## 3. 전원·포트 배치

| 포트 | 연결 |
|---|---|
| USB0 | 전원 어댑터 (매뉴얼 권장: USB0 전원 + USB1 장치) [확인됨·매뉴얼] |
| USB1 | BT 방식: 비움(또는 USB BT 동글) / USB 방식: 프린터 |

- **BT 방식(목표)**: 프린터는 자체 5V 2A 충전기에 상시 연결한다. Pi와 전기적으로 분리된다.
- **USB 직결(V4)**: Pi USB1이 프린터에 5V를 공급하고, 프린터는 충전 전류(최대 2A)를 끌어간다. 보드 규격이 5V 2A라 전압 강하·재부팅 위험이 있다 [추정]. **5V 3A 어댑터로만 시험**한다. 전원 문제가 나오면 셀프전원 USB 허브(역전류 방지, 포트당 2A 이상) 구매를 다시 논의한다.
- 어느 방식인지는 [hardware-verification.md](hardware-verification.md) 결정 규칙을 따른다.

## 4. OS 설치 — 공식 Debian 12 서버 이미지

V3(내장 BT 재부팅 20회)에서 실패하면 7절 Armbian으로 바꾼다.

### 4.1 SD카드 굽기 (노트북)

1. Orange Pi 공식 사이트의 Zero 2W 페이지에서 **Debian 12 (Bookworm) 서버 이미지**를 받는다 — 정확한 파일명·커널 버전 [미검증]
2. 압축을 풀고 balenaEtcher 또는 Raspberry Pi Imager의 "사용자 지정 이미지"로 32GB SD에 굽는다 [기본값]
3. 이미지 체크섬이 제공되면 확인한다

### 4.2 첫 접속 (헤드리스 목표)

모니터 없이 설정하는 것이 목표지만, 공식 이미지가 부팅 전에 Wi-Fi·SSH를 미리 넣는 방법을 지원하는지 **[미검증]**이다. 가능한 경로 순서:

1. SD카드 부트 파티션에서 Wi-Fi·SSH 사전 설정이 가능한지 매뉴얼로 확인 → 가능하면 그대로 사용
2. 불가능하면 **Mini HDMI + USB 키보드로 1회만** 로그인해 Wi-Fi(`nmtui` 또는 `nmcli`)를 설정 [미검증: 이미지에 NetworkManager 포함 여부]
3. 또는 40핀 디버그 UART(USB-TTL 어댑터 필요, 추가 구매)

기본 계정·비밀번호는 매뉴얼에서 확인하고 **첫 로그인 직후 바꾼다** [미검증: 기본 계정 이름]. 이후:

- 노트북 SSH 공개키를 등록하고 비밀번호 로그인을 끈다 [기본값]
- 호스트명 `haru-pi` [기본값]
- `apt update && apt full-upgrade`

### 4.3 기본 설정

| 항목 | 방법 |
|---|---|
| 시간대 | `timedatectl set-timezone Asia/Seoul` |
| NTP | `timedatectl`의 `NTP service: active`, `System clock synchronized: yes` 확인 (이미지 기본 NTP 데몬 종류는 [미검증]) |
| Tailscale | 공식 설치 스크립트로 설치 → `tailscale up` → 표시되는 URL로 **서버(`justant-server2`)·노트북·폰이 이미 들어 있는 같은 tailnet**에 등록(GitHub 계정과 tailnet 로그인 계정은 다를 수 있으니 기존 기기와 같은 tailnet인지 확인). 노드 이름 `haru-pi` [기본값] |
| 서버 접근 확인 | `curl https://justant-server2.tail2b65d1.ts.net/api/health` (M2 이후) |
| Python | Debian 12 기본 Python 3.11 + `python3-venv` |

## 5. `install.sh` (M5에서 작성, 아직 없음)

`/pi/deploy/install.sh`. **여러 번 실행해도 안전(idempotent)**해야 한다. 할 일:

1. apt 패키지: `git`, `python3-venv`, `python3-pip`, `libusb-1.0-0`, `bluez` (BT 방식일 때) [기본값]
2. 서비스 사용자 `haru` 생성(없을 때만), `plugdev`·`bluetooth` 그룹 추가 [기본값]
3. 저장소: `/opt/haru-paper`에 `git clone`(없을 때) 또는 `git pull --ff-only`(있을 때) [기본값]
4. venv 생성(`/opt/haru-paper/pi/.venv`)과 의존성 설치(Pillow 12.3.0 고정 — aarch64·Python 3.11용 설치 가능 여부 [미검증])
5. udev 규칙: `0483:5740`을 서비스 사용자가 열 수 있게
6. `pi/.env`가 없으면 `.env.example`을 복사하고 **토큰 입력이 필요하다고 안내 후 종료**(있으면 절대 덮어쓰지 않음)
7. systemd unit 설치·`daemon-reload`·`enable --now`
8. journald 크기 제한 설정
9. 시간대 확인
10. 마지막에 `systemctl status haru-paper-agent`와 첫 폴링 로그 확인 방법을 출력

배포(PoC): Pi에서 `cd /opt/haru-paper && git pull --ff-only && sudo systemctl restart haru-paper-agent` (또는 `install.sh` 재실행).

## 6. systemd unit 개요 [기본값, M5에서 작성]

```ini
[Unit]
Description=Haru-Paper print agent
Wants=network-online.target
After=network-online.target time-sync.target

[Service]
User=haru
WorkingDirectory=/opt/haru-paper/pi
EnvironmentFile=/opt/haru-paper/pi/.env
ExecStart=/opt/haru-paper/pi/.venv/bin/python -m agent
StateDirectory=haru-paper
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
```

- **`Requires=network-online.target`을 쓰지 않는다** — 인터넷이 없어도 에이전트는 시작해서 캐시로 인쇄해야 한다.
- `StateDirectory=haru-paper` → `/var/lib/haru-paper` ([agent.md](agent.md) 4절)
- 시계 미동기 상태에서도 서비스는 뜨고, 인쇄만 보류한다([policy.md](policy.md) 4절)

## 7. SD카드 보호

| 항목 | 설정 [기본값] |
|---|---|
| journald | `/etc/systemd/journald.conf`에 `SystemMaxUse=50M` |
| 보낸 바이트 | 30일 순환 (`HARU_SENT_RETENTION_DAYS`) |
| PNG 캐시 | 참조 안 되는 오래된 렌더 정리 (M4에서 기준 결정) |
| 에이전트 쓰기 | `kv.last_tick_at` 갱신 간격을 너무 짧게 하지 않음 |
| 스왑 | RAM 1GB — 이미지 기본 zram/스왑 설정 확인 후 SD 스왑은 쓰지 않는 방향 [미검증: 기본 설정] |

## 8. V3 실패 시 — Armbian 전환

- Armbian(커뮤니티 지원, 커널 6.18)은 `uwe5622-allwinner` 확장으로 BT를 자동 설정한다(`sprdbt_tty` 모듈, `aw859a-bluetooth.service`) [확인됨·Armbian 빌드 소스].
- 단, Armbian에서도 "부팅 후 BT가 자주 안 뜬다"는 미해결 보고가 있다 [확인됨·사용자 보고]. Armbian에서도 V3를 똑같이 재부팅 20회로 시험한다.
- DietPi는 6.18 커널에서 Wi-Fi 다운로드 중 커널 oops 보고가 있어 후보에서 제외한다.
- Armbian에서도 실패하면 BT를 포기하고 USB 직결(V4)로 간다.

## 9. M5 통과 조건 (init_plan 10절)

- 재부팅 후 `haru-paper-agent`가 **자동 시작**
- 서버 폴링 정상 (서버 앱의 기기 화면에 마지막 폴링 시각 표시)
- V3·V4 결과로 **연결 방식 결정** ([hardware-verification.md](hardware-verification.md))
- 결정된 transport로 **실물 인쇄 1회**

M5 이후 PoC 완료 시험(WAN 차단 상태 07:00 인쇄 + 복구 후 이력, 3일 연속)으로 간다.
