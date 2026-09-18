# 환경 — 머신·접속·sudo 함정

지금 이 프로젝트를 다루는 실물 환경이다. **비밀번호·토큰 값은 여기 적지 않는다**(공개 저장소, `CLAUDE.md` 절대금지 4) — 존재와 다루는 방법만 적는다.

## 1. 머신 3대

| 머신 | 역할 | 접근 |
|---|---|---|
| **Orange Pi Zero 2W** (`haru-pi`, tailnet `100.117.239.83`) | **운영 기기.** `haru-paper-agent`가 systemd로 상시 구동, 서버를 30초마다 폴링 | `ssh haru-pi` (아래 2절) |
| **서버** (`justant-server2`, tailnet `100.81.189.92`, `justant-server2.tail2b65d1.ts.net`) | **모든 작업의 기준 세션.** 서버(Spring Boot)·DB·웹앱이 여기서 돌고, 하드웨어 BT 실험도 여기서 한다 | 이 세션 자체 |
| **노트북(Windows + WSL2)** | **더 이상 사용하지 않는다.** 예전에는 usbipd로 M832를 USB로 붙여 썼지만, 운영 경로가 BT로 확정되면서 고유 역할이 없어졌다 | — |

서버에는 Haru-Paper 말고도 다른 운영 중인 프로젝트가 여럿 있다(Again-Spring 등). 무관한 파일·설정·경고에는 손대지 않는다.

## 2. Pi 접속 (`ssh haru-pi`)

- 서버의 `~/.ssh/config`에 `Host haru-pi`가 등록돼 있고, 전용 키(`~/.ssh/haru_pi_key`)로 **비밀번호 없이** 접속된다.
- Pi 쪽 계정은 `justant`이고 `/etc/sudoers.d/`에 NOPASSWD 규칙이 있어 Pi 안에서는 `sudo`에 비밀번호가 필요 없다.
- 저장소는 `/opt/haru-paper`에 있고 **`haru` 서비스 계정 소유**다. `sudo -u haru git -C /opt/haru-paper pull --ff-only`처럼 **`haru`로** 실행해야 한다 — root(`sudo git ...`)로 실행하면 Git 2.35.2+의 "detected dubious ownership" 보호에 걸린다.
- 배포·재시작 절차 전체는 [`pi/setup.md`](pi/setup.md) 5절.

### 2.1 Pi의 DNS 함정 (`.ts.net` 이름이 안 풀릴 수 있다) [확인됨, 2026-09-18]

Pi의 `/etc/resolv.conf`는 `../run/systemd/resolve/stub-resolv.conf`로 가는 심볼릭 링크인데, 이 Pi에는
**systemd-resolved가 실제로 안 돈다**(`resolvectl` 명령 자체가 없음) — 대신 **NetworkManager가 그 경로에
직접 ISP DHCP DNS(예: 통신사 DNS 서버)를 써넣는다.** `tailscaled`는 `dns: using "debian-resolvconf" mode`로
자신의 DNS(100.100.100.100, MagicDNS)를 주입하려 하지만, 이 시스템에는 `/etc/resolvconf/resolv.conf.d/`
디렉터리 자체가 없어서(전통적 Debian `resolvconf` 패키지가 아니라 NetworkManager가 흉내만 내는 껍데기)
**그 주입이 조용히 아무 효과가 없다.** 결과: `tailscale status`로는 서버가 online으로 보이고 IP로 ping도
되는데(`100.81.189.92` 같은 tailnet IP), **`justant-server2.tail2b65d1.ts.net` 같은 호스트명은 해석되지
않는다**(`NameResolutionError`) — Pi 에이전트의 poll·SSE·render 다운로드가 전부 실패한다.

이 상태는 **재부팅해도 저절로 안 고쳐진다**(NetworkManager가 매번 같은 방식으로 resolv.conf를 다시 쓴다).
근본 수정(NetworkManager가 DNS를 아예 안 건드리게 `/etc/NetworkManager/conf.d/`에 `dns=none` 설정 후
재시작)은 SSH로만 접근하는 원격 임베디드 장치의 네트워크 관리자를 재시작하는 일이라 잘못되면 접속이
끊길 위험이 있어 **사용자 승인 없이는 하지 않는다.**

**지금 쓰는 안전한 임시 수정**: `/etc/hosts`에 서버 tailnet IP를 그 호스트명으로 고정한다(재부팅해도
유지되고, 한 줄 지우면 즉시 원상복구된다). SNI는 URL의 호스트명 기준으로 보내지므로 TLS·인증서 검증에
영향 없다(`curl --resolve`로 사전 확인함):

```bash
echo '100.81.189.92 justant-server2.tail2b65d1.ts.net' | sudo tee -a /etc/hosts
```

서버의 tailnet IP가 바뀌면(거의 없음) 이 줄도 같이 고쳐야 한다. 이 방법은 **이 Pi가 이야기하는 유일한
tailnet 호스트명**(서버 하나)에만 유효하다 — 다른 tailnet 호스트명을 새로 쓰게 되면 그때도 같은 증상이
날 수 있다.

## 3. 이 서버(justant-server2)의 sudo 함정

- **이 세션(harness)의 `!` 프리픽스 로컬 명령 실행은 실제 TTY를 주지 않는다.** 비밀번호가 필요한 일반 `sudo <cmd>`는 `!`로 보내면 `sudo: a terminal is required to read the password`로 항상 실패한다. 이미 설치된 NOPASSWD 규칙에 정확히 매칭하는 명령만 성공한다.
- 비밀번호가 필요한 일회성 sudo 작업(예: `apt install`, 새 sudoers 파일 설치)은 **사용자가 이 harness가 아닌 진짜 터미널(SSH 세션 등)에서 직접 실행**하게 요청한다.
- **sudoers.d 파일을 긴 한 줄 명령(`echo '...' | sudo tee ...`)으로 직접 쓰지 않는다.** 사용자 터미널 클라이언트가 긴 줄을 표시하다가 실제 개행문자로 바꿔버려 문법 오류가 난 적이 있다. 대신:
  1. 일반 파일로 먼저 내용을 쓴다(`printf '...\n' > ~/tmpfile`, sudo 불필요).
  2. 짧은 명령으로 설치한다: `sudo install -m 440 ~/tmpfile /etc/sudoers.d/NNN`.
  3. `sudo visudo -c`로 전체 sudoers.d 문법을 확인한다 — 다른 프로젝트 파일도 같이 검사되므로 무관한 권한 경고가 섞여 나올 수 있다(내가 만든 게 아니면 손대지 않는다).

### 이미 설치된 규칙

| 파일 | 범위 |
|---|---|
| `/etc/sudoers.d/99-v2` | `m832/src/run_v2_bt.sh` |
| `/etc/sudoers.d/99-v2c` | `m832/src/run_v2*.sh` 와일드카드 — 새 BT 실험 스크립트는 이름을 `run_v2*.sh`로 지으면 추가 sudoers 작업 없이 바로 sudo 실행된다 |

## 4. 하드웨어 실험 환경 (`~/Data/detox-printer`)

- 이 서버에도 `~/Data/detox-printer`가 clone돼 있다(`https://github.com/Justant-source/detox-printer`, 공개 저장소). 새 하드웨어 실험은 전부 여기서, 그 저장소 규칙(보낸 바이트 `captures/sent/` 저장, `findings.md` 3줄 기록)대로 한다 — `CLAUDE.md` 절대금지 3.
- 서버에는 실제 Bluetooth 하드웨어(Realtek 동글)가 있고, M832와 **이미 페어링돼 있다**(`bluetoothctl info <MAC>` → `Bonded: yes`).
- Pi(`haru-pi`)에도 M832가 페어링돼 있다(`Paired: yes`/`Bonded: yes`, 2026-09-17 완료) — [`pi/transport.md`](pi/transport.md) 3절.

## 5. 비밀값 다루는 법

- `.env`, 기기 토큰, DB 비밀번호는 절대 커밋하지 않는다. `.env.example`만 커밋한다.
- 기기 토큰을 Pi에 넣을 때는 SSH stdin으로 보내고 명령줄 인자(argv)나 로그에 남기지 않는다.
- Tailscale 주소(위 표의 IP·호스트명)는 문서에 적어도 되지만, 코드에는 환경변수로만 받는다.
