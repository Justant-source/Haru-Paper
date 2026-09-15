# Orange Pi Zero 2W 설치 (M5)

> Pi를 받아서 `haru-paper-agent`가 부팅 시 자동으로 도는 상태까지 만드는 절차.
> 최초 결정: [../init_plan.md](../init_plan.md) 3장·8.1·10절 M5, Q24·Q34.
> 공식 이미지의 파일명·기본 계정·헤드리스 Wi-Fi 설정 방법은 이미지를 직접 내려받아 확인했다(4.1~4.2절). **다만 아직 실제 Orange Pi에서 부팅해 본 적은 없다** — 그 이후 단계는 [미검증]으로 남아 있다.

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

### 4.1 이미지 (확정) — `Orangepizero2w_1.0.2_debian_bookworm_server_linux6.1.31`

[확인됨·이미지 직접 확인] 실물 부팅은 아직 안 함. 경로: orangepi.org → service-and-support 페이지 → Google Drive "Debian" 폴더 → "Linux6.1 kernel version image" 하위 폴더. 같은 폴더에 Bullseye·desktop/xfce 변형도 있으나 이 프로젝트는 **server + bookworm**을 쓴다.

| 항목 | 값 |
|---|---|
| 압축 파일 | `Orangepizero2w_1.0.2_debian_bookworm_server_linux6.1.31.7z` |
| 이미지 파일 | `Orangepizero2w_1.0.2_debian_bookworm_server_linux6.1.31.img` (2,571,108,352 byte) |
| SHA256 | `66c6f55b383ba1e927e6765c4843ff8976924bf128cbadf53ce0416d5523a4af` (압축 안에 동봉된 `.sha` 파일과 대조해 일치 확인) |
| 빌드 | Debian 12(bookworm), 커널 6.1.31, orangepi-build 커밋 `82f9e56`, VERSION 1.0.2, arm64, 2024-07-11 (이미지 안 `/etc/orangepi-release`·`/etc/orangepi.txt` 확인) |
| 파티션 구조 | ext4 파티션 1개뿐 — 별도 FAT 부트 파티션 없음(Allwinner 계열 관행대로 부트로더는 파티션 앞 raw 섹터에 있음). 루트 파티션은 축소된 채(~2.4GB) 출하되고 **첫 부팅 시 SD카드 전체로 자동 확장**된다(`orangepi-resize-filesystem` 서비스 확인됨) |

굽기: 이미지 체크섬을 먼저 확인한 뒤 `dd`(또는 balenaEtcher·Raspberry Pi Imager "사용자 지정 이미지")로 32GB SD에 쓴다. **쓰기 후 원본 이미지와 SHA256을 다시 대조해 바이트 단위로 검증한다** — 이번 세션에서는 노트북 WSL2에서 카드리더를 `usbipd-win`으로 통과시켜 `dd`로 굽고, 원본과 해시를 대조해 일치를 확인했으며, `e2fsck -n -f`로 파일시스템도 깨끗함을 확인했다.

### 4.2 첫 접속 — 헤드리스 (확정)

[확인됨·이미지 직접 확인] 공식 이미지는 모니터 없이도 설정 가능하다.

- **SSH가 기본 활성화**되어 있다(`ssh.service`가 `multi-user.target.wants`에 있음, 확인됨). 기본 계정은 **`orangepi`**(uid 1000, `sudo` 그룹). 첫 SSH 로그인 시 Armbian식 설정 마법사(`/usr/lib/orangepi/orangepi-firstlogin`, 확인됨)가 떠서 **비밀번호를 강제로 바꾼 뒤에야** 쉘을 준다 — SSH 세션 안에서 답변만 입력하면 되므로 모니터·키보드는 필요 없다.
- **헤드리스 Wi-Fi 사전 설정**: 이미지에 `/boot/orangepi_first_run.txt.template`이 들어 있다(Armbian/orangepi-build 관행). SD카드를 굽자마자(노트북에서, Pi에 꽂기 전) 이 파일을 `/boot/orangepi_first_run.txt`로 복사하고 아래를 채우면 첫 부팅 때 자동으로 Wi-Fi에 붙는다 — 파일 자체의 안내문으로 확인, **실제 부팅으로 검증된 것은 아직 아님**:

  ```
  FR_general_delete_this_file_after_completion=1   # 적용 후 파일 자동 삭제
  FR_net_change_defaults=1
  FR_net_ethernet_enabled=0
  FR_net_wifi_enabled=1
  FR_net_wifi_ssid='<SSID>'
  FR_net_wifi_key='<비밀번호>'                      # 평문 저장 — 파일 자체 경고문에 명시됨
  FR_net_wifi_countrycode='KR'
  ```

  고정 IP가 필요하면 같은 파일의 `FR_net_use_static=1`과 IP·마스크·게이트웨이·DNS 필드를 쓴다. 네트워크 관리 주체는 **NetworkManager**다(`/etc/network/interfaces`에 "Network is managed by Network manager" 명시, 확인됨) — 4.4절 nmcli 명령의 대상이 맞다는 뜻.
  - **이번 세션 기록**: 위 파일을 실제 SD카드에 썼다(`dd`로 이미지 자체를 쓴 뒤, 검증 읽기 직후 VFS 마운트가 read/write 상태 충돌로 걸려서 — 재현되면 알아둘 만한 특이 증상 — 대신 `debugfs -w`로 `/boot/orangepi_first_run.txt`를 직접 주입하고 다시 읽어 바이트 단위로 확인함). 사용자 홈 Wi-Fi로 자동 접속하도록 채워 넣었다. **카드를 아직 실제 Orange Pi에 꽂아 부팅한 적은 없다.**

- 첫 로그인 이후: 노트북 SSH 공개키를 등록하고 비밀번호 로그인을 끈다 [기본값] / 호스트명은 이미지 기본값 `orangepizero2w`이며 필요하면 바꾼다(기존 계획의 `haru-pi`는 [기본값]으로 유지, 확정 아님) / `apt update && apt full-upgrade`

### 4.2.1 다음 단계 (사용자가 직접 — 이번 세션 범위 밖)

1. SD카드를 Orange Pi Zero 2W에 삽입, 전원 연결
2. 몇 분 대기(첫 부팅 + Wi-Fi 연결 + 파일시스템 확장)
3. 공유기 관리 페이지 또는 `arp-scan`/`nmap`으로 Pi의 IP 확인(호스트명 `orangepizero2w`로 뜰 가능성이 높음, 확정 아님)
4. `ssh orangepi@<IP>` 접속 → 첫 로그인 마법사에서 비밀번호 설정
5. 이후 5절 `install.sh` 절차로 진행

### 4.3 기본 설정

| 항목 | 방법 |
|---|---|
| 시간대 | `timedatectl set-timezone Asia/Seoul` |
| NTP | `timedatectl`의 `NTP service: active`, `System clock synchronized: yes` 확인 (이미지 기본 NTP 데몬 종류는 [미검증]) |
| Tailscale | 공식 설치 스크립트로 설치 → `tailscale up` → 표시되는 URL로 **서버(`justant-server2`)·노트북·폰이 이미 들어 있는 같은 tailnet**에 등록(GitHub 계정과 tailnet 로그인 계정은 다를 수 있으니 기존 기기와 같은 tailnet인지 확인). 노드 이름 `haru-pi` [기본값] |
| 서버 접근 확인 | `curl https://justant-server2.tail2b65d1.ts.net/api/health` (M2 이후) |
| Python | Debian 12 기본 Python 3.11 + `python3-venv` |
| NTP 동기화 대기 | `sudo systemctl enable systemd-time-wait-sync.service` — 이게 없으면 `time-sync.target`은 실제 동기화와 무관하게 즉시 도달해 systemd unit의 `After=time-sync.target`(6절)이 무의미해진다 |

### 4.4 Wi-Fi 안정화

상시 기기의 실패 1순위는 프린터가 아니라 Wi-Fi다(전송 방식이 `bt`든 `usb`든 폴링은 항상 Wi-Fi를 탄다). 온보드 UWE5622의 장기 안정성은 8절과 마찬가지로 **[미검증]**이므로, 절전을 꺼서 실패 원인을 최소한 하나 줄인다.

```bash
nmcli connection modify <SSID> 802-11-wireless.powersave 2   # 2 = disable
nmcli connection modify <SSID> connection.autoconnect yes connection.autoconnect-retries 0
```

- 반복해서 끊기면 8절 Armbian 전환과 별개로 USB Wi-Fi 동글 교체를 검토한다(리그 교체는 30일 리셋 사유가 아니다, [hardware-verification.md](hardware-verification.md))

## 5. `install.sh` (M5에서 작성, 아직 없음)

`/pi/deploy/install.sh`. **여러 번 실행해도 안전(idempotent)**해야 한다. 할 일:

1. apt 패키지: `git`, `python3-venv`, `python3-pip`, `libusb-1.0-0`, `bluez` (BT 방식일 때) [기본값]
2. 서비스 사용자 `haru` 생성(없을 때만), `plugdev`·`bluetooth` 그룹 추가 [기본값]
3. 저장소: `/opt/haru-paper`에 `git clone`(없을 때) 또는 `git pull --ff-only`(있을 때) [기본값]
4. venv 생성(`/opt/haru-paper/pi/.venv`)과 의존성 설치(Pillow 12.3.0 — aarch64·`cp311` 프리빌트 wheel 존재 확인됨 [확인됨·PyPI, 소스 컴파일 불필요]. 실제 Pi 실물 설치는 아직 [미검증])
5. udev 규칙: `0483:5740`을 서비스 사용자가 열 수 있게
6. `pi/.env`가 없으면 `.env.example`을 복사하고 **토큰 입력이 필요하다고 안내 후 종료**(있으면 절대 덮어쓰지 않음)
7. systemd unit 설치·`daemon-reload`·`enable --now`
8. journald 크기 제한 설정
9. 시간대 확인
10. `systemd-time-wait-sync.service` 활성화 (4.3절 — 이게 없으면 `time-sync.target`이 실제 동기화와 무관하게 즉시 도달함)
11. Wi-Fi 절전 끄기 (4.4절 — 활성 연결 자동 감지, 없으면 경고만 남기고 건너뜀)
12. 마지막에 `systemctl status haru-paper-agent`와 첫 폴링 로그 확인 방법을 출력

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

### 7.1 read-only 루트파일시스템 전환 (30일 시작 조건)

30일 연속 운영([policy.md](policy.md))을 시작하기 전에 SD카드를 읽기 전용으로 돌린다. 순서:

1. **개발 구간(1~2주)**: rw 상태로 개발
2. **구성 동결**: overlayfs 적용 전에 아래가 전부 하부(비-overlay, 비-tmpfs) 레이어에 있는지 확인한다

   | 항목 | 확인 방법 |
   |---|---|
   | `pi/.env`의 `HARU_DEVICE_TOKEN` | 이 저장소는 런타임 등록 API가 없다 — 토큰은 설치 시 수기로 `pi/.env`에 넣는다(5절 6번). overlay를 켜기 전에 이 파일이 실제 SD카드(하부 레이어)에 쓰였는지 확인한다. tmpfs 위에 있으면 **재부팅마다 토큰이 사라져 매번 기기가 오프라인처럼 보인다** |
   | Wi-Fi 자격증명 | NetworkManager 설정 위치 확인(보통 하부 레이어) |
   | 파이썬 패키지 | venv(`pi/.venv`) 설치가 overlay 켜기 전에 끝나 있어야 함 |
   | udev 규칙, systemd unit | 마찬가지로 overlay 켜기 전 설치 완료 |

3. **overlayfs 적용**: `armbian-config` → System → Overlayfs (Armbian 기준, 8절). 공식 Debian 12 이미지의 overlayfs 전환 방법은 **[미검증]**
4. **강제 전원 차단 10회 부팅 검증** — 매 부팅 후 서버 폴링이 정상 도달하는지 확인(토큰이 유지되는지가 핵심)
5. 여기까지 통과해야 30일 카운트를 시작한다

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
