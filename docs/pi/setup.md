# Orange Pi Zero 2W 설치 (M5)

> Pi를 받아서 `haru-paper-agent`가 부팅 시 자동으로 도는 상태까지 만드는 절차.
> 최초 결정: [../init_plan.md](../init_plan.md) 3장·8.1·10절 M5, Q24·Q34. 담당·접속은 [../environment.md](../environment.md).
> **[확인됨·실물, 2026-09-16~17]** 첫 부팅·SSH 접속, 고정 IP·타임존·NTP·Wi-Fi 절전 끄기(4.3~4.4절), 계정 rename·SSH 키·Tailscale(4.5절), `install.sh` 실물 실행으로 `haru-paper-agent`가 서버를 폴링하고 재부팅 후 자동 복구(5·6·9절)까지 전부 완료. overlayfs(7.1절)는 개발 구간이라 의도적으로 미룸.
> **프린터는 Pi에 물리적으로 연결돼 있지 않다.** Pi와 M832는 각자 USB-C 충전기로 전원만 받고 **BT로만** 연결한다(V1·V2·V3 통과로 확정, [hardware-verification.md](hardware-verification.md)) — USB 직결(V4)은 대상 제외.
> **같은 날 M5 이후 커밋 3개(재시도·명령 실행기·시계 게이트 등, [agent.md](agent.md) 11절)는 아직 Pi에 배포되지 않았다 [미검증]** — 5.4절.

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

## 2. 도착 시 확인 — 완료됨

**[확인됨·실물]** 5V 3A 어댑터·USB-C 케이블·Micro SD 32GB 확보, 헤드리스 설정 성공(모니터·키보드 불필요). V4(USB 직결)는 사용자가 BT 전용으로 확정해 대상 제외됐으므로 OTG 젠더는 불필요해졌다.

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

### 4.2 첫 접속 — 헤드리스 [확인됨·실물, 2026-09-16]

공식 이미지는 모니터 없이 SSH만으로 설정 가능하다. SSH가 기본 활성화돼 있고, 기본 계정 **`orangepi`**(uid 1000, `sudo` 그룹)로 이미지 기본 비밀번호만으로 바로 쉘이 열렸다(첫 로그인 마법사·강제 비밀번호 변경 없음) — **비밀번호 값은 이 공개 저장소에 적지 않는다** [절대 금지 4]. `sudo`는 비밀번호를 요구한다(NOPASSWD 아님).

헤드리스 Wi-Fi가 필요하면 이미지의 `/boot/orangepi_first_run.txt.template`을 SD카드에 `/boot/orangepi_first_run.txt`로 복사하고 `FR_net_wifi_ssid`·`FR_net_wifi_key`·`FR_net_wifi_countrycode='KR'`을 채운다(고정 IP는 `FR_net_use_static=1` 등). 네트워크 관리 주체는 **NetworkManager**다(4.4절 nmcli 명령의 대상).

> **각주 (당시 노트북에서 수행, 이후 서버 세션으로 담당 이관)**: SD카드 굽기(`dd` + SHA256 대조 + `e2fsck`)와 첫 SSH 로그인은 노트북에서 했다. `orangepi_first_run.txt` 주입은 `dd` 직후 VFS 마운트 충돌이 있어 `debugfs -w`로 직접 썼다. 이후 계정을 **`orangepi` → `justant`로 rename**, SSH 키(`~/.ssh/haru_pi_key`) 등록, Tailscale 연결까지 노트북에서 끝냈다(4.5절 — 값은 그대로 남기고 절차만 요약). 이 시점 이후 Pi 작업은 서버 세션이 `ssh haru-pi`로 이어받는다([environment.md](../environment.md)).

### 4.3 기본 설정

| 항목 | 방법 |
|---|---|
| 시간대 | `timedatectl set-timezone Asia/Seoul` — [확인됨·실물] 적용 후 재부팅해도 유지됨 (2026-09-16) |
| NTP | `timedatectl`의 `NTP service: active`, `System clock synchronized: yes` 확인 — [확인됨·실물] 이미지 기본 상태로 이미 켜져 있었고, 재부팅 직후에는 `synchronized: no`였다가 약 15초 내 `yes`로 바뀜 |
| NTP 동기화 대기 | `sudo systemctl enable systemd-time-wait-sync.service` — [확인됨·실물] 이미지 기본값은 **disabled**였다. 활성화함(2026-09-16). 이게 없으면 `time-sync.target`은 실제 동기화와 무관하게 즉시 도달해 systemd unit의 `After=time-sync.target`(6절)이 무의미해진다 |
| Tailscale | 공식 설치 스크립트로 설치 → `tailscale up` → 표시되는 URL로 **서버(`justant-server2`)·노트북·폰이 이미 들어 있는 같은 tailnet**에 등록. 노드 이름 `haru-pi` — [확인됨·실물, 2026-09-16] 상세는 4.5절 |
| 서버 접근 확인 | `curl https://justant-server2.tail2b65d1.ts.net/api/health` (M2 이후) |
| Python | Debian 12 기본 Python 3.11 + `python3-venv` |

### 4.3.1 고정 IP [확인됨·실물, 2026-09-16]

집 공유기(192.168.45.0/24) DHCP가 임대해 준 주소를 그대로 고정했다 — 새 주소를 고르지 않은 이유는 공유기 DHCP 풀과 충돌할 가능성을 낮추기 위함이다.

| 항목 | 값 |
|---|---|
| IP | `192.168.45.28/24` |
| 게이트웨이 | `192.168.45.1` |
| DNS | `210.220.163.82`, `219.250.36.130` (공유기가 내려준 값 그대로 고정) |
| NetworkManager 연결 이름 | `Orange Pi wireless 2.4G` (`nmcli -t -f NAME,TYPE,DEVICE con show`로 확인) |

적용 명령:

```bash
sudo nmcli con mod "Orange Pi wireless 2.4G" \
  ipv4.method manual \
  ipv4.addresses 192.168.45.28/24 \
  ipv4.gateway 192.168.45.1 \
  ipv4.dns "210.220.163.82 219.250.36.130" \
  connection.autoconnect yes \
  connection.autoconnect-retries 0
sudo nmcli con up "Orange Pi wireless 2.4G"
```

재부팅 검증: `sudo reboot` 후 SSH로 `192.168.45.28`에 재접속되고 `ip -4 addr show wlan0`에 `dynamic` 표시 없이 같은 주소가 뜨는 것을 확인함.

**주의**: 공유기 DHCP 설정에서 이 주소를 별도로 예약(reservation)해 두지 않았다 — Pi가 꺼진 사이에 공유기가 `.28`을 다른 기기에 내줄 가능성은 이론상 남아 있다 [미검증]. 충돌이 의심되면 공유기 관리 페이지에서 `192.168.45.28`을 Pi의 MAC으로 예약하는 것을 검토한다.

### 4.4 Wi-Fi 안정화

상시 기기의 실패 1순위는 프린터가 아니라 Wi-Fi다(전송 방식이 `bt`든 `usb`든 폴링은 항상 Wi-Fi를 탄다). 온보드 UWE5622의 장기 안정성은 8절과 마찬가지로 **[미검증]**이므로, 절전을 꺼서 실패 원인을 최소한 하나 줄인다.

```bash
nmcli connection modify "Orange Pi wireless 2.4G" 802-11-wireless.powersave 2   # 2 = disable
nmcli connection modify "Orange Pi wireless 2.4G" connection.autoconnect yes connection.autoconnect-retries 0
```

[확인됨·실물, 2026-09-16] 4.3.1절 고정 IP 적용과 함께 실행함. `nmcli con show "Orange Pi wireless 2.4G"`로 `802-11-wireless.powersave: 2 (disable)`, `connection.autoconnect: yes`, `autoconnect-retries: 0 (forever)` 확인. 장기 안정성(며칠~몇 주 단위 Wi-Fi 끊김 여부)은 여전히 [미검증] — 이번 확인은 설정이 적용/유지된다는 것까지만이다.

- 반복해서 끊기면 8절 Armbian 전환과 별개로 USB Wi-Fi 동글 교체를 검토한다(리그 교체는 30일 리셋 사유가 아니다, [hardware-verification.md](hardware-verification.md))

### 4.5 계정 rename, SSH 키, Tailscale [확인됨·실물, 2026-09-16]

**계정 rename (`orangepi` → `justant`)**: `usermod -l`은 로그인 세션이 열려 있으면 "user busy"로 실패하고, 부그룹(`sudo`/`docker`/`dialout`/`plugdev`/`netdev`) 멤버 목록(`/etc/group`, `/etc/gshadow`)의 사용자명 문자열도 자동으로 안 바뀐다. 지연 실행 스크립트로 세션 종료 후 `pkill -u orangepi` → `usermod -l justant -d /home/justant -m orangepi` → `groupmod -n justant orangepi` → `sed -i 's/\borangepi\b/justant/g' /etc/group /etc/gshadow`(+`/etc/subuid`·`/etc/subgid`) 순서로 처리했다. 결과: `id justant` uid=1000 gid=1000, 그룹 전부 유지, 홈 `/home/justant`로 이동. 비밀번호는 rename으로 안 바뀌므로 이후 `justant`·`root` 비밀번호를 직접 바꿨다 — **값은 이 저장소에 남기지 않는다** [절대 금지 4].

**SSH 키 인증**: `~/.ssh/haru_pi_key`(ed25519)를 만들어 `authorized_keys`에 등록. `ssh -i ~/.ssh/haru_pi_key justant@<IP> 'whoami'` → `justant`, 비밀번호 없이 접속 확인. `sudo`는 여전히 비밀번호 필요(로그인과 sudo 인증은 별개).

**Tailscale**: 공식 설치 스크립트(arm64 `.deb` 1.102.4) → `sudo tailscale up --hostname=haru-pi`(한 번만 백그라운드로 띄우고 로그의 URL로 승인 — 재시도하며 겹쳐 실행하면 URL이 안 뜬다). **함정**: `tailscale up`의 stdout을 파이프(리다이렉트·`ssh` 경유 등)로 받으면 완전 버퍼링돼 로그인 URL이 한참 안 보이거나 아예 안 보일 수 있다. `stdbuf -oL -eL tailscale up ...`처럼 줄 단위 버퍼링을 강제해야 URL이 바로 출력된다 — 재설치나 다른 노드 등록 때 다시 겪을 수 있는 함정이라 기록해 둔다. 결과:

```
tailscale status
100.117.239.83  haru-pi              ...  linux    -
100.81.189.92   justant-server2      ...  linux    -   (온라인)
```

Pi(`haru-pi`, `100.117.239.83`)가 서버(`justant-server2`, `100.81.189.92`)와 같은 tailnet에서 온라인 — 이후 서버 세션이 `ssh haru-pi`로 계속 담당한다([environment.md](../environment.md)).

## 5. `install.sh` — 실물 실행 완료 [확인됨·실물, 2026-09-16]

`pi/deploy/install.sh`, `pi/deploy/haru-paper-agent.service`. 서버 세션이 Tailscale SSH로 `haru-pi`(100.117.239.83)에서 처음 실행했다. 목록 12단계 전부 스크립트에 있는 그대로 동작을 확인했다(아래 5.1 결함 3건을 고친 뒤).

### 5.1 실행 전 고친 결함 3건

스크립트를 그대로 실물에서 돌리자 곧바로 죽었다. 전부 고치고 커밋(`97247ea`, `16f16da`)한 뒤에야 끝까지 통과했다.

| # | 증상 | 원인 | 고침 |
|---|---|---|---|
| 1 | 4단계(venv 생성)에서 `Permission denied`로 스크립트 전체 종료 | `sudo git clone`이 `/opt/haru-paper`를 root 소유로 만드는데, 그 다음 `python3 -m venv`·`pip install`은 sudo 없이 실행돼 root 소유 디렉터리에 못 씀 | clone 직후 `sudo chown -R haru:haru`로 저장소 소유권을 서비스 계정으로 옮김. 이후 venv·pip·`.env` 복사는 `sudo -u haru`로 통일 |
| 2 | (1을 고친 뒤 재실행에서 새로 발견) 3단계(git pull)에서 `detected dubious ownership in repository` | 저장소가 haru 소유가 됐는데 `git pull`은 여전히 root(sudo)로 실행 — Git 2.35.2+ 보호 기능에 걸림 | `git pull`도 `sudo -u haru`로 통일(clone은 최초 1회뿐이라 root 유지, 이후 pull만 haru) |
| 3 | plugdev·bluetooth 그룹에 `haru`가 안 들어감(USB 접근 실패 위험) | `if sudo usermod ... || [ $? -eq 4 ]`가 `usermod`가 아니라 `||` 좌변 전체의 종료코드를 봄. Debian 12의 실제 실패 코드는 4가 아니라 다름 | 그룹 존재 여부를 `grep`으로 직접 확인 후 무조건 `usermod -a -G`를 시도하는 방식으로 재작성 |

부수 효과: 저장소가 root가 아니라 `haru` 소유가 되면서 `.env`(mode 600)도 `haru` 소유가 돼, `EnvironmentFile=`로 읽는 systemd `User=haru`와 자연히 맞아떨어졌다(별도 권한 조정 불필요).

### 5.2 기기 토큰 발급 — M6부터 절차가 바뀜

M6부터 토큰은 서버 `.env`의 단일 값이 아니라 **기기별로 DB에 해시 저장**된다(`server/.../device/DeviceTokenAuthFilter.java` 주석 확인). `pi/.env.example`은 이미 이 M6 절차대로 갱신돼 있다(6~8행). 기존에 로컬 개발용으로 쓰던 옛 단일 토큰을 그대로 Pi에 넣었더니 `POST /api/device/poll`이 401을 반환한 것을 실제로 확인했다(무효화됨).

**발급 절차 [확인됨·실물]**: 웹앱(`https://justant-server2.tail2b65d1.ts.net`) 로그인 → 기기 메뉴 → "토큰 발급받기"(`POST /api/devices/me/token`) → 응답에 평문 토큰이 **이번 한 번만** 표시됨 → `pi/.env`의 `HARU_DEVICE_TOKEN`에 저장. 재발급하면 이전 토큰은 즉시 무효화된다(기기당 1개).

### 5.3 폴링 확인 [확인됨·실물]

```
POST /api/device/poll HTTP/1.1" 200
GET /api/device/snapshot HTTP/1.1" 200
```

재부팅 전·후 모두 위 로그를 확인했다(9절 M5 통과 조건).

`/pi/deploy/install.sh`. **여러 번 실행해도 안전(idempotent)**해야 한다 — 아래는 전부 실물 실행으로 확인된 스펙이다:

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

### 5.4 2026-09-17 M5 이후 커밋(`623a8dc`·`8b7194b`·`51a6a8d`) 배포 — 아직 안 함 [미검증]

M5 실물 인쇄(9절)는 이 세 커밋 **이전** 코드로 이뤄졌다. 이 문서를 쓰는 시점까지 Pi에 `git pull`을 다시 돌린 적이 없어 **Pi가 지금 이 커밋들 이전 코드로 구동 중일 수 있다**(확인 불가 — [미검증]). 위 "배포(PoC)" 한 줄(`git pull --ff-only && systemctl restart`)을 그대로 다시 돌리면 되지만, 이번엔 다음을 미리 알아 두는 편이 좋다:

- **새 환경변수 `HARU_COMMAND_TTL_SEC`는 필수가 아니다** [확인됨·코드, `pi/agent/config.py`의 `from_env`] — `required_keys` 목록에 없고 `os.environ.get("HARU_COMMAND_TTL_SEC", "600")`로 읽는다. 운영 Pi의 기존 `pi/.env`에 이 키가 없어도 기동이 실패하지 않고 기본값 600초로 동작한다. `pi/.env.example`에는 이미 추가돼 있다(주석 포함) — 넣고 싶으면 운영 `.env`에 같은 줄을 수기로 추가하면 되고, 안 넣어도 무방하다.
- **SQLite 마이그레이션은 기동 시 자동으로, 조용히 실행된다** [확인됨·코드, `pi/agent/storage.py`의 `Storage.__init__` → `_migrate()`] — `PRAGMA table_info`로 `executed_occurrences`·`commands` 테이블의 실제 컬럼을 보고 없는 것만 `ALTER TABLE ... ADD COLUMN`으로 추가한다(기본값 없이 붙이므로 즉시 완료, 테이블 재작성 없음). **기존 `agent.db`의 데이터는 지워지지 않는다** — 기존 행의 새 컬럼은 NULL로 채워지고, 옛 코드가 이 컬럼을 읽지 않으므로 구현을 되돌려도 DB가 깨지지 않는다. 별도 수동 마이그레이션 절차가 필요 없다.
- **동작이 눈에 띄게 달라질 수 있는 지점**: `HARU_PAPER_POLICY`가 운영 Pi에서 `status_query`나 `manual_flag`로 이미 바뀌어 있었다면, 이 배포 이후 `status_query`는 (H4 미판정이므로) 인쇄가 완전히 멈추고 `manual_flag`는 `paperState` 키가 없거나 파싱 실패 시 인쇄가 멈춘다(둘 다 이번 fail-closed 수정의 의도된 동작, [policy.md](policy.md) 2절) — 이전에는 반대로 fail-open이었다. 배포 직후 이 정책값과 실제 인쇄 여부를 확인한다.
- 이 배포 자체와 위 세 항목의 실물 동작은 아직 **[미검증]**이다 — 배포한 뒤 폴링·스케줄러 틱이 예외 없이 도는지 로그로 확인한다([policy.md](policy.md) 4절 "시계 게이트"가 새로 개입하므로, 재부팅 직후 로그에 `NTPSynchronized` 관련 경고가 없는지도 함께 본다).
- 이번 배포에 SSE 깨우기 채널 설정 `HARU_EVENTS_ENABLED`·`HARU_EVENT_READ_TIMEOUT_SEC`도 새로 추가되지만([agent.md](agent.md) 3절), 둘 다 선택 설정이라 **없어도 기존 `.env`로 그대로 기동된다**.

## 6. systemd unit 개요 [확인됨·실물, 2026-09-16]

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
- **실물 재부팅 검증 [확인됨·실물, 2026-09-16]**: `sudo reboot` 후 별도 조작 없이 `haru-paper-agent`가 `active`/`enabled`로 다시 뜨고, 부팅 약 40초 만에 `POST /api/device/poll`이 200을 받는 것을 확인했다(로그 타임스탬프로 대조).

## 7. SD카드 보호

| 항목 | 설정 [기본값] |
|---|---|
| journald | `/etc/systemd/journald.conf`에 `SystemMaxUse=50M` |
| 보낸 바이트 | 30일 순환 (`HARU_SENT_RETENTION_DAYS`) |
| PNG 캐시 | 참조 안 되는 오래된 렌더 정리 (M4에서 기준 결정) |
| 에이전트 쓰기 | `kv.last_tick_at` 갱신 간격을 너무 짧게 하지 않음 |
| 스왑 | **[확인됨·실물, 2026-09-16]** 이미지 기본값이 `/dev/zram0`(502708KB) — SD카드 기반 스왑 파일이 아니라 압축 메모리라 SD 수명에 영향 없음. `free -h` 기준 `RAM 981Mi / swap 490Mi`. 별도 조치 불필요 |

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

- [x] 재부팅 후 `haru-paper-agent`가 **자동 시작** — **[확인됨·실물, 2026-09-16]** 5·6절
- [x] 서버 폴링 정상 — **[확인됨·실물, 2026-09-16]** `POST /api/device/poll` 200, `GET /api/device/snapshot` 200 (재부팅 전후 모두). 다만 "서버 앱의 기기 화면에 마지막 폴링 시각 표시"는 앱 화면으로 직접 보지는 않았다(로그로 확인) — `GET /api/device`는 M6부터 세션 인증이 **규약**이라(무인증 시절 문서가 낡은 것, [`../architecture.md`](../architecture.md) 4.1, [`../server/api.md`](../server/api.md) 4절) 로그인 없이 부르면 401이 정상이다. 5.2절에서 기기 토큰을 발급받아 로그인한 브라우저로 앱 화면을 보면 확인 가능하지만, 이번 세션에서는 아직 그렇게 확인하지 않았다
- [x] **연결 방식 결정 = `bt`** ([hardware-verification.md](hardware-verification.md)) — **[확인됨·실물, 2026-09-17]** V1(충전기만 8시간 생존)·V2(SPP/RFCOMM 채널 1로 체커보드 2장 정상 인쇄, 사용자 육안)·V3(Pi 내장 BT 재부팅 20/20) 모두 통과. V4(USB 직결)는 사용자 결정(2026-09-16)으로 대상 제외
- [x] 결정된 transport로 **실물 인쇄 1회** — **[확인됨·실물, 2026-09-17]** Pi에서 M832 페어링·`trust` 완료 → `pi/transport/bt.py` 작성([transport.md](transport.md) 3절) → `.env`를 `HARU_TRANSPORT=bt`, `HARU_BT_ADDRESS`, `HARU_PRINTER_DRIVER=m832`로 전환·재시작 → **실제 `pi/printer/m832` 드라이버**(`M832Printer`+`BtTransport`, detox-printer 스크립트 아님)로 그레이데이션+텍스트("HARU-PAPER REAL PRINT TEST"+시각)+체커보드가 섞인 PNG(1300×500)를 SPP/RFCOMM 채널 1로 전송(81,850바이트, 사전 육안 용지 확인 후) → **사용자가 출력물을 직접 보고 왜곡·반전 없이 정상 인쇄됐다고 확인함**. 보낸 바이트는 `/var/lib/haru-paper/sent/`에 보관. 이걸로 그동안 [미검증]이던 "텍스트·그레이스케일 콘텐츠 인쇄 품질"도 함께 해소됨([printer-m832.md](printer-m832.md) 6절). **범위 한계**: 이 인쇄는 `M832Printer`+`BtTransport`를 직접 호출한 것이라, 지시서가 요구한 "앱 '지금 인쇄' + `paperConfirmed=true` → 결과 업로드"(에이전트 실행기 → 서버 업로드 체인)를 통한 경로는 아니다 — 드라이버·전송 계층은 실물 검증됐지만 그 체인은 여전히 [미검증]([agent.md](agent.md) 11절)

**현재 상태 요약**: **4개 중 4개 완료 — M5 통과** (단, 위 범위 한계 참고). `HARU_PRINTER_DRIVER=m832`가 Pi의 새 기본 상태로 남는다(더 이상 `fake`로 되돌리지 않음). 프린터는 1호기 1대뿐이라 30일 운영과 2단계(ESP32) 실물 시험은 같은 프린터를 순서대로 쓴다(`.temp/02-esp32-디바이스-계획서-v1.4.md`).

M5 이후 PoC 완료 시험(WAN 차단 상태 07:00 인쇄 + 복구 후 이력, 3일 연속)으로 간다.
