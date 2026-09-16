# Orange Pi Zero 2W 설치 (M5)

> Pi를 받아서 `haru-paper-agent`가 부팅 시 자동으로 도는 상태까지 만드는 절차.
> 최초 결정: [../init_plan.md](../init_plan.md) 3장·8.1·10절 M5, Q24·Q34.
> 공식 이미지의 파일명·기본 계정·헤드리스 Wi-Fi 설정 방법은 이미지를 직접 내려받아 확인했다(4.1~4.2절).
> **2026-09-16 실물 Orange Pi Zero 2W 첫 부팅·SSH 접속 완료.** 계정 `orangepi`, 이미지 기본 비밀번호로 그대로 접속됨(마법사 강제 변경 없음, 4.2절 — 비밀번호 값은 공개 저장소에 적지 않음), 고정 IP·타임존·NTP 대기·Wi-Fi 절전 끄기까지 적용하고 재부팅으로 유지 확인(4.3~4.4절). 이후 계정을 `justant`로 rename, SSH 키 인증 전환, Tailscale 연결까지 완료(4.5절).
> **2026-09-16 서버 세션(`justant-server2`)에서 `install.sh` 실물 실행 완료.** Tailscale로 Pi에 SSH가 닿는 것을 이용해, 노트북이 아니라 서버 세션이 이번 1회에 한해 사용자 승인을 받고 `/pi`·`/docs/pi`를 작업했다(평소에는 노트북 세션 담당, `CLAUDE.md` 참고). `haru-paper-agent`가 systemd로 떠서 실제로 서버를 폴링하고, **재부팅 후에도 자동으로 복구되는 것까지 확인**했다 — 5·6·9절 참고. overlayfs(7.1절)는 아직 적용 전(개발 구간이라 의도적으로 미룸).
> **프린터는 Pi에 물리적으로 연결돼 있지 않다.** Pi와 M832는 각자 별도 USB-C 충전기로 전원만 받고, 연결은 앞으로 BT 또는 Wi-Fi로만 한다(사용자 확인, 2026-09-16) — 3장의 USB 직결 시험(V4)과 3.1~3.4의 프린터 실물 시험은 이번에 하지 않았다.

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

- **SSH가 기본 활성화**되어 있다(`ssh.service`가 `multi-user.target.wants`에 있음, 확인됨). 기본 계정은 **`orangepi`**(uid 1000, `sudo` 그룹). 이미지 안에 Armbian식 첫 로그인 마법사(`/usr/lib/orangepi/orangepi-firstlogin`)가 존재하는 것은 확인했으나, **실제 첫 SSH 로그인(2026-09-16, 실물 Orange Pi Zero 2W, IP 192.168.45.28)에서는 마법사가 뜨지 않았고 비밀번호 강제 변경도 없었다** [확인됨·실물]. 이미지 기본 비밀번호로 바로 쉘이 열렸다 — **비밀번호 값 자체는 이 공개 저장소에 적지 않는다** [절대 금지 4].
- 이후 계정을 **`orangepi` → `justant`로 rename**하고 비밀번호도 사용자가 직접 변경했다 (4.5절). 계정/비밀번호 관리는 이 문서에 값을 남기지 않고, 접속은 노트북 WSL의 SSH 키(`~/.ssh/haru_pi_key`, 4.5절)로 한다.
- `sudo`는 비밀번호를 요구한다(NOPASSWD 아님, 확인됨) — 이후 `install.sh`가 비대화식으로 sudo를 써야 한다면 이 점을 고려해야 한다.
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
4. `ssh orangepi@<IP>` 접속 → 이미지 기본 비밀번호로 로그인 (실제로는 마법사 없이 바로 됐다, 4.2절)
5. 이후 5절 `install.sh` 절차로 진행

**2026-09-16 실제로 위 1~4까지 완료함.** 이후 계정을 `justant`로 rename하고 SSH 키 인증으로 전환했다 — 4.5절 참고. 아래 4.3~4.4절의 "적용 명령"들은 원래 로그인 계정 `orangepi`로 실행한 것이고, 4.5절 이후로는 계정명이 `justant`로 바뀐 상태에서 이어졌다.

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

**계정 rename (`orangepi` → `justant`)**

사용자가 로그인 계정을 `orangepi`에서 `justant`로 바꾸고 싶어해서, 로그인 세션이 열려 있는 상태에서 곧바로 `usermod -l`을 하면 "user busy"로 실패하는 문제를 피하려고 지연 실행 스크립트를 썼다:

1. 현재 SSH 세션(orangepi로 접속된 상태)에서 `sudo`로 백그라운드 스크립트를 걸어두고 세션을 끝낸다.
2. 그 스크립트가 몇 초 대기 → `pkill -u orangepi`로 남은 프로세스 정리 → `usermod -l justant -d /home/justant -m orangepi` → `groupmod -n justant orangepi` 순서로 재시도(최대 15회, 2초 간격) 실행.
3. **`usermod -l`은 부그룹(secondary group) 멤버 목록(`/etc/group`, `/etc/gshadow`)의 사용자명 문자열을 자동으로 안 바꿔준다** — `sudo`, `docker`, `dialout`, `plugdev`, `netdev` 등 멤버 목록에 `orangepi` 문자열이 그대로 남아 있어서 rename 후 sudo가 끊길 뻔했다. `groupmod -n`으로 기본 그룹(gid 1000)을 rename한 뒤 `sed -i 's/\borangepi\b/justant/g' /etc/group /etc/gshadow`로 나머지 부그룹 멤버 목록을 정리했다. `/etc/subuid`, `/etc/subgid`(docker rootless 매핑)도 같은 이유로 `orangepi:` → `justant:`로 고쳤다.
4. 결과 확인: `id justant` → uid=1000, gid=1000, `sudo`/`docker`/`plugdev`/`netdev` 등 그룹 전부 유지. `getent passwd orangepi` → 계정 없음(정상). 홈 디렉터리 `/home/justant`로 이동됨.
5. 비밀번호는 rename으로 바뀌지 않는다(같은 해시가 새 계정명으로 옮겨감) — 이후 사용자가 직접 `passwd`로 `justant` 비밀번호를 바꿨고, `root` 비밀번호도 `sudo passwd root`로 직접 바꿨다. **두 비밀번호 값 모두 이 저장소에는 남기지 않는다** [절대 금지 4, 공개 저장소].
6. GECOS 주석 필드(`getent passwd justant`의 `orangepi,,,` 부분)는 rename 후에도 옛 이름이 남아 있다 — 화면상 코멘트일 뿐 기능에는 영향 없음, 필요하면 `chfn`으로 정리 가능 [미검증·선택사항, 정리 안 함].

**SSH 키 인증 전환**

비밀번호를 대화·커밋에 남기지 않기 위해, 노트북 WSL에서 전용 키를 만들어 등록했다:

- 키: `~/.ssh/haru_pi_key` (ed25519, 노트북 WSL에만 있음, 저장소에 커밋 안 됨)
- 등록: 사용자가 새 `justant` 비밀번호로 직접 `ssh justant@192.168.45.28 '... >> ~/.ssh/authorized_keys'`를 실행해 공개키를 추가 — **비밀번호는 이 대화 세션에도, 어떤 명령 실행 기록에도 남기지 않았다.**
- 확인: `ssh -i ~/.ssh/haru_pi_key justant@192.168.45.28 'whoami'` → `justant`, 비밀번호 없이 접속됨.
- `sudo`는 여전히 비밀번호를 요구한다(이 전환은 SSH 로그인만 키 기반으로 바꾼 것이고, sudo 인증은 별개다).

**Tailscale**

```bash
curl -fsSL https://tailscale.com/install.sh | sh   # apt로 tailscale + tailscale-archive-keyring 설치
sudo tailscale up --hostname=haru-pi
```

- 설치 자체는 문제없이 끝남(arm64 `.deb`, 버전 1.102.4).
- `tailscale up`을 여러 번 겹쳐 실행하면(재시도하며 timeout으로 죽이는 식) 매번 이전 로그인 시도가 취소되고 URL이 안 나온다 — **한 번만 백그라운드로 띄우고 죽이지 않은 채 로그로 URL이 뜨길 기다려야 한다.** stdout이 파이프로 나갈 때 완전 버퍼링되는 문제도 있어서 `stdbuf -oL -eL`로 줄 단위 버퍼링을 강제했다.
- 사용자가 브라우저에서 로그인 URL을 열어 승인 → 연결 확인됨:

  ```
  tailscale status
  100.117.239.83  haru-pi              ...  linux    -
  100.81.189.92   justant-server2      ...  linux    -   (온라인)
  100.109.66.57   laptop-77ohs9p       ...  windows  -   (온라인, 이 노트북)
  ```

  Pi(`haru-pi`, tailnet IP `100.117.239.83`)가 서버(`justant-server2`)·노트북과 **같은 tailnet에서 서로 온라인**으로 확인됨. 이제 물리적으로 노트북이 아닌 **서버(`justant-server2`)에서도 Tailscale을 통해 Pi에 SSH 접속이 가능하다** — 다만 이 저장소 `CLAUDE.md`의 "노트북 세션 → `/pi`, `/docs/pi`" 담당 규칙은 프린터가 물리적으로 노트북에 USB로 붙어 있다는 전제로 정해둔 것이라, Pi의 네트워크 도달성과는 별개다. 담당을 서버 세션으로 옮기려면 `CLAUDE.md`를 사용자가 직접(또는 요청해서) 고쳐야 한다 — 이번 세션에서는 고치지 않았다.

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

`pi/.env.example`의 안내("서버 `server/.env`의 `HARU_DEVICE_TOKEN`과 같은 값")는 **더 이상 맞지 않는다.** M6부터 토큰은 서버 `.env`의 단일 값이 아니라 **기기별로 DB에 해시 저장**된다(`server/.../device/DeviceTokenAuthFilter.java` 주석 확인). 기존에 로컬 개발용으로 쓰던 `pi/.env`의 토큰을 그대로 Pi에 넣었더니 `POST /api/device/poll`이 401을 반환했다(무효화됨, 확인됨).

**발급 절차 [확인됨·실물]**: 웹앱(`https://justant-server2.tail2b65d1.ts.net`) 로그인 → 기기 메뉴 → "토큰 발급받기"(`POST /api/devices/me/token`) → 응답에 평문 토큰이 **이번 한 번만** 표시됨 → `pi/.env`의 `HARU_DEVICE_TOKEN`에 저장. 재발급하면 이전 토큰은 즉시 무효화된다(기기당 1개).

### 5.3 폴링 확인 [확인됨·실물]

```
POST /api/device/poll HTTP/1.1" 200
GET /api/device/snapshot HTTP/1.1" 200
```

재부팅 전·후 모두 위 로그를 확인했다(9절 M5 통과 조건).

**별도로 확인된 문서 불일치(이번 범위 밖, 기록만)**: `docs/architecture.md`는 `GET /api/device`를 "인증 없음(앱용)"으로 규정하지만, 실제로는 인증 없이 호출하면 401("로그인이 필요하다")을 반환한다. 서버 코드 또는 문서 중 하나가 갱신이 필요하다 — 이번 세션은 `/pi`·`/docs/pi` 범위라 손대지 않았다.

이 절의 아래 목록은 위 실행으로 전부 확인된 스펙이다.

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
- [x] 서버 폴링 정상 — **[확인됨·실물, 2026-09-16]** `POST /api/device/poll` 200, `GET /api/device/snapshot` 200 (재부팅 전후 모두). 다만 "서버 앱의 기기 화면에 마지막 폴링 시각 표시"는 앱 화면으로 직접 보지는 않았다(로그로 확인) — `GET /api/device`가 문서(무인증)와 달리 401을 반환하는 문제가 있어(5.3절) 앱 화면 확인은 이 문제 해소 후로 남는다
- [ ] V3·V4 결과로 **연결 방식 결정** ([hardware-verification.md](hardware-verification.md)) — **미착수.** 사용자가 Pi와 M832를 물리적으로 연결하지 않고 BT/Wi-Fi로만 잇겠다고 확정했으므로(2026-09-16) **V4(USB 직결)는 대상에서 제외**되고 V1~V3(BT 경로)만 남는다. V3(Pi 내장 BT 재부팅 20회 생존)는 프린터 페어링 없이도 가능하지만 이번 세션에서는 하지 않았다
- [ ] 결정된 transport로 **실물 인쇄 1회** — 프린터가 Pi에 아직 없어 미착수. 현재 `HARU_PRINTER_DRIVER=fake`로 소프트웨어 경로만 살아있는 상태

**현재 상태 요약**: 4개 중 2개 완료. 남은 2개는 프린터를 Pi 쪽 BT/Wi-Fi로 붙여야 진행 가능하다 — 프린터가 물리적으로 노트북에 있는 동안은 이 저장소·세션 담당 규칙(`CLAUDE.md` "노트북 세션: 프린터가 USB로 붙어 있음")대로 노트북 세션이 이어서 진행한다.

M5 이후 PoC 완료 시험(WAN 차단 상태 07:00 인쇄 + 복구 후 이력, 3일 연속)으로 간다.
