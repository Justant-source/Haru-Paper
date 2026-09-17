# 하루종이(Haru-Paper)

> 이 저장소는 **서버 `justant-server2` 세션 하나**가 담당한다. Pi는 `ssh haru-pi`로 다루고, 하드웨어 실험은
> 이 서버의 `~/Data/detox-printer`에서 한다. **노트북 WSL은 쓰지 않는다.** 머신·접속·sudo 함정은 `docs/environment.md`.
> 결정의 원본 기록은 `docs/init_plan.md`, 현재 설계는 `docs/architecture.md`와 `docs/{pi,server,app}/`,
> 문서 색인과 현재 상태는 `docs/README.md`, 진행 중인 계획은 `.temp/`(완료되면 삭제)에 있다.

## 이 프로젝트의 목표

디지털 디톡스를 종이로 해보는 프로젝트다. 폰 웹앱에서 고른 콘텐츠(포맷)를 예약한 시각에
감열 프린터(Phomemo M832)가 인쇄한다.

- **하드웨어 PoC 범위**: 프린터 1대(1호기, 추가 구매 없음), 용지 110mm 연속 롤 고정
- **플랫폼 범위**: 멀티유저(계정·작가 구독·레이아웃/위젯 공유·마켓), Tailscale 내부망, 사용자 1인당 기기 1대
- **구조**: 폰 웹앱(PWA) ─ 서버(Spring Boot, Docker) ─ 30초 폴링 ─ Pi(Orange Pi Zero 2W) ─ **BT(SPP/RFCOMM)** ─ M832. 전 구간 Tailscale 내부망
- **PoC 완료 기준**: 폰에서 포맷을 만들고 다음 날 07:00으로 예약 → **공유기 WAN을 뽑은 상태에서도** 07:00에 인쇄 → 인터넷 복구 후 앱에 이력 표시. **3일 연속 성공**. 그 뒤 30일 연속 운영(`.temp/01`)
- 선행 프로젝트: `~/Data/detox-printer` — M832 프로토콜 리버싱 실험실(이 서버에 clone됨). 여기서 [확인됨]이 된 것만 이 저장소로 옮긴다
- 플랫폼(M6~M10) 사양의 원본은 `.temp/03-플랫폼-작업지시서-v1.0.md`. 완료된 마일스톤은 `docs/`에 반영하고, 전부 끝나면 `.temp/`를 지운다
- 2단계 기기(ESP32 인라인 어댑터)는 `.temp/02-esp32-디바이스-계획서-v1.4.md`. Pi PoC가 끝나기 전에는 부품을 사지 않는다

## 현재 상태 (2026-09-17 — 마일스톤 통과 시 이 절과 `docs/README.md`를 같이 고친다)

| 항목 | 상태 |
|---|---|
| M0 뼈대 · M2 서버 · M3 웹앱 | 완료 |
| M1 드라이버 · M4 에이전트 | 코드 있음, Pi에서 상시 구동 중이나 **배포본이 뒤처져 있다** — Pi는 `581899e`까지만 반영(12커밋 뒤), Pi 코드 커밋 3개(용지 게이트 fail-closed `623a8dc`, 재시도·명령 실행기·결과 업로드 `8b7194b`, 시계 게이트·되돌아보기·순환 삭제 `51a6a8d`) 및 이번에 추가된 SSE 깨우기 채널 관련 커밋도 전부 미배포라 [미검증]. **Pi는 옛 fail-open 용지 정책 코드로 구동 중 — 배포 전에 `HARU_PAPER_POLICY`를 `unverified`에서 바꾸지 않는다**(절대금지 1). M1의 "실물 1회"는 M5로 이월 |
| M5 Pi 실물 | **완료(4/4).** Pi ↔ M832 페어링, `pi/transport/bt.py`(콜드 ACL 워크어라운드 포함), 실물 인쇄 1회 육안 확인(드라이버·전송 계층 직접 호출 — 앱 "지금 인쇄"를 통한 에이전트 실행기→서버 업로드 체인은 [미검증]). `HARU_PRINTER_DRIVER=m832` 상시 |
| M6 계정·기기 | 구현 완료(세션 로그인, 기기별 토큰, 페어링 코드, 관리자). **통과 조건 미충족** — V4 백필·레거시 claim 미실행(`.temp/03` 4.5절), 통과 전에는 M7 이후로 넘어가지 않는다 |
| M7 레이아웃·위젯 | 부분 — 포맷 스키마 v2(행/슬롯) + 드래그 편집기 구현. `haru-widget-runner`는 **미구현** |
| M8~M10 | 미착수 |
| 하드웨어 | V1·V2·V3 통과 → `bt` 확정, V4 제외. H4(용지 감지) 부분 진행(findpaper 응답 재현, 용지 반영 미확정)·H5(줄 누락) 대기. 서버 1-bpp(PBM) 출력 구현됨[확인됨·코드] — 배포·기기 연동은 [미검증] |

## 구성요소 경계 (어기지 말 것)

- **m832를 아는 코드는 `/pi/printer/m832`뿐이다.** 서버와 앱은 Pi가 보고한 **프린터 프로필**
  (`model`, `dpi`, `paperWidthMm`, `printableWidthPx`)만 안다. 서버·앱에 M832 명령 바이트, WIDTH_BYTES, h-offset 같은 프로토콜 상수를 넣지 않는다
- **서버는 그레이스케일 PNG를 렌더**하고, 좌우 정렬 보정·헤드 폭 패딩·비트 패킹·헤더/꼬리 조립·전송은 Pi 드라이버 몫이다.
  디더링(흑백 변환)은 원칙적으로 기기 몫이지만, **2단계 MCU 기기를 위해 서버가 프로필 폭 기준 1-bpp(PBM P4)도 낸다**(2026-09-17 승인, 구현됨[확인됨·코드] — `docs/architecture.md` 3.4). 디더링은 프린터 무관한 범용 처리이고, 프린터 상수는 여전히 서버에 없다
- **스케줄의 원본은 서버, 실행은 Pi**다. Pi는 예약 규칙과 렌더를 캐시해 두고 인터넷이 끊겨도 스스로 인쇄한다
- API 규약의 원본은 `docs/architecture.md`다. 서버·Pi·앱 문서는 이를 링크하고, 다르게 정의하지 않는다
- **(M7 계획, 미구현) 사용자 스크립트(위젯)를 아는 코드는 `/server/runner`(Node, `haru-widget-runner` 컨테이너)뿐이다.** JVM은 사용자 번들을 직접 실행하지 않고 러너의 내부 HTTP만 호출한다. 러너는 호스트 포트를 열지 않는다

## 세션·담당

- **서버 세션이 모든 경로를 담당한다**(`/pi`, `/server`, `/app`, `/docs`, 공통 파일). 노트북 WSL 항목은 없다
- Pi: `ssh haru-pi`(무비밀번호, Pi 쪽 `justant`는 NOPASSWD sudo). 배포·재시작 절차는 `docs/pi/setup.md` 5절. 저장소 `/opt/haru-paper`는 `haru` 소유라 `git`은 `sudo -u haru`로
- 하드웨어 실험: 프린터가 서버 옆에 있을 때 `~/Data/detox-printer`에서(서버 BT와 M832는 페어링돼 있음). 새 BT 실험 스크립트는 `m832/src/run_v2*.sh`로 감싸면 sudoers 규칙에 걸려 바로 sudo 실행된다(`docs/environment.md`)
- **이 서버의 `!` 로컬 명령은 TTY가 없다**: 비밀번호가 필요한 sudo는 실패한다 — 사용자가 진짜 터미널에서 실행하게 요청한다. sudoers.d 파일은 긴 한 줄 `echo|sudo tee`로 쓰지 말고 파일을 만든 뒤 `sudo install`, 이어서 `sudo visudo -c`
- 병렬 에이전트(최대 6개)는 **파일이 겹치지 않는 단위**로만. 공통 파일(`CLAUDE.md`, `AGENTS.md`, `README.md`, `docs/architecture.md`, `docs/README.md`, `.gitignore`, `.cursor/`)은 수정 직전 반드시 `git pull --ff-only`
- `docs/init_plan.md`는 최초 결정 기록이다. 내용이 바뀌어도 이 파일은 고치지 않고 해당 docs를 고친다

## Git 규칙

- 기본 브랜치 `main`에 직접 커밋한다
- **작업 시작 전과 push 전에 `git pull --ff-only`**. 공통 파일은 수정 직전에 한 번 더
- **fast-forward가 실패하면 멈추고 사용자에게 보고한다.** 스스로 합치지 않는다
  (`.claude/settings.local.json` / `.cursor/cli.json`·`.cursor/hooks.json`이 `git merge*`, `git rebase*`, `git reset*`을 거부한다)
- 원격: `https://github.com/Justant-source/Haru-Paper` — **공개 저장소**다. detox-printer도 공개다

## 절대 금지

1. **용지를 눈으로 확인하기 전에 래스터(인쇄) 명령을 보내지 않는다.** 헤드가 상한다.
   용지 정책 3종(`unverified` / `status_query` / `manual_flag`)은 `docs/pi/policy.md`. 용지가 없는 상태에서 래스터를 보내 반응을 보는 시험도 금지다. 상태 조회 명령만 허용
2. **실제로 프린터에 보내서 눈으로 확인하지 않은 하드웨어 사실을 [확인됨]으로 쓰지 않는다.**
   전송이 성공해도 출력물을 못 봤으면 [미검증]이다. 다른 기종(M02, M04, M834, M835 등) 자료는 [추정]이다
3. **새 하드웨어 실험을 이 저장소에서 먼저 하지 않는다.** 실험은 `~/Data/detox-printer`에서
   그 저장소 규칙(보낸 바이트 `captures/sent/` 저장, `findings.md` 3줄 기록, 새 번호 스크립트)대로 하고, [확인됨]이 된 것만 `/pi`로 옮긴다
4. **비밀값을 커밋하지도, 대화에 붙여넣지도 않는다.** `.env`, 기기 토큰, DB 비밀번호, API 키는 `.env`에만 두고
   `.env.example`만 커밋한다. 커밋 전 `git diff --cached`. 토큰을 Pi에 넣을 때는 SSH stdin으로 보내고 argv·로그에 남기지 않는다. Tailscale 주소는 문서에 적어도 되지만 코드에는 환경변수로 받는다
5. **`/pi` 밖에서 m832 프로토콜 코드나 상수를 쓰지 않는다** (위 "구성요소 경계")
6. **사용자 레이아웃·위젯에 임의 HTML/CSS/JS를 허용하지 않는다.** 레이아웃은 JSON 블록 + 화이트리스트 스타일 속성뿐이고,
   위젯 스크립트는 `haru-widget-runner` 샌드박스(네트워크·파일·`import` 없음)에서만 실행되며 **블록 JSON만 반환**한다.
   서버의 헤드리스 Chromium이 사용자 HTML·스크립트를 직접 여는 경로가 있으면 안 된다 — `docs/server/rendering.md`
7. **토큰 최적화 / 컨텍스트 압축 / 리팩터링 계열 스킬을 자동으로 호출하지 않는다.** `CLAUDE.md`와 `docs/`는 축약 대상이 아니다
8. **30일 연속 운영 중에는 1호기 프린터로 실험하지 않는다.** 프린터는 1대다. 실험(H4·H5, ESP32 P1)은 운영 시작 전이나 종료 후에 한다

## 기록 규칙

- 사실의 확실성을 표기한다: **[확인됨]** 실물로 눈으로 확인 / **[미검증]** 확인 전 / **[추정]** 자료·계열 기종 기반 / **[기본값]** 따로 묻지 않고 정한 값(바꿔도 됨)
- 결정이 바뀌면 해당 `docs/` 문서를 고친다. `docs/init_plan.md`는 원본 기록으로 둔다
- 하드웨어 실험 기록은 `~/Data/detox-printer/m832/docs/findings.md`에 남기고, 이 저장소의 `docs/pi/hardware-verification.md`에는 결과 요약과 링크만 적는다. `PLAN-02.md`라는 파일은 존재하지 않는다 — 참조하지 않는다
- 실패했는데 성공한 것처럼 요약하지 않는다. 무반응이면 무반응이라고 쓴다

## 코드 규칙 (detox-printer 계승)

- 하드코딩한 상수 옆에는 반드시 근거 주석을 단다
  (`WIDTH_BYTES = 163  # 110mm, detox-printer findings "D단계" 실물 확인 163byte*8`)
- 모든 USB/BT write는 타임아웃을 명시하고, 실패 시 예외를 삼키지 않는다(로그에 그대로 남긴다). 전체 write 데드라인 60초
- 프린터로 보낸 바이트는 Pi 데이터 디렉터리(`HARU_DATA_DIR`, 기본 `/var/lib/haru-paper`)에 저장하고 30일 순환 보관한다
- 시간대는 `Asia/Seoul` 고정. Pi는 Python 3.11 문법 범위를 지킨다

## 진행 방식

- 마일스톤 M0~M5는 `docs/init_plan.md` 10절, M6~M10은 `.temp/03`. **통과 조건을 만족하기 전에 다음 마일스톤으로 넘어가지 않는다**
- 하드웨어 검증(V·H)의 상태표와 결정 규칙은 `docs/pi/hardware-verification.md`, 실험 절차는 `.temp/01`. 결과가 나오면 findings → 상태표 → 영향받는 docs 순으로 같은 날 고친다
- 막히면 추측으로 진행하지 말고 멈추고 물어본다
- **서버 시스템 변경은 적용 직전 사용자 승인**: `tailscale serve`, 포트 바인딩, compose 서비스 추가, sudoers, 공개 도메인. 서버에는 다른 운영 중인 프로젝트가 많다 — 남의 파일·경고에 손대지 않는다
- ESP32 데이터 경로는 USB 호스트로 확정됐다(2026-09-17, 안정성 분석). 부품 구매는 `.temp/02` 게이트(G1)를 통과한 뒤에만

## docs 안내

| 문서 | 내용 |
|---|---|
| `docs/README.md` | 문서 색인, **현재 상태**(마일스톤·하드웨어), 작업별 읽는 순서 |
| `docs/environment.md` | 머신 3대(Pi·서버·배제된 노트북), SSH·tailnet 주소, sudo·TTY 함정, sudoers 규칙, detox-printer 위치 |
| `docs/init_plan.md` | 최초 합의(Q1~Q34), 마일스톤 M0~M5. 원본 기록 — 고치지 않음 |
| `docs/architecture.md` | 전체 구조, 경계, 도메인 모델(포맷 v2), **API 규약 원본**(앱·Pi·인증), 동기화 흐름 |
| `docs/pi/README.md` | Pi 문서 목차·현재 상태 |
| `docs/pi/hardware-verification.md` | V0~V4·H4·H5·R5·CG1 상태표, 결정 규칙, findings 링크 |
| `docs/pi/transport.md` | `usb`(M1)·`bt`(V2 확정값) 전송 계층 |
| `docs/pi/printer-m832.md` | M832 상수·명령과 근거([확인됨]/[미검증]) |
| `docs/pi/printer.md` / `agent.md` / `policy.md` | 프린터 인터페이스·프로필 / 폴링·스케줄러·실행기·대기열 / 용지 정책·유예·시계·보관 |
| `docs/pi/setup.md` | Orange Pi 설치·`install.sh`·systemd·overlayfs, M5 체크리스트 |
| `docs/server/README.md` | 서버 목차·현재 상태 |
| `docs/server/auth.md` | M6: 세션 로그인·가입, 역할, 기기별 토큰·페어링 코드, 소유권 스코핑, 관리자 API |
| `docs/server/api.md` | 컨트롤러별 경로·인증, 오류 형식, 멱등, curl 시나리오 |
| `docs/server/data-model.md` | 테이블(Flyway V1·V2), 파일 저장 |
| `docs/server/format-schema.md` | **포맷 JSON 스키마 v2(행/슬롯) 원본** |
| `docs/server/rendering.md` / `weather.md` / `deploy.md` | 렌더러·샌드박스(+PBM 구현됨) / 날씨 / compose·`tailscale serve`·백업·일회성 운영 작업(V4 백필·`claim-legacy`) |
| `docs/app/README.md` | 앱 목차·현재 상태 |
| `docs/app/web.md` / `screens.md` / `editor.md` / `native.md` | 스택·구조·인증 / 화면 11개 / 드래그 편집기(v2) / 네이티브 예약 |
| `.temp/01`·`02`·`03`·`04`·`05`·`06` | 진행 중 계획: Orange Pi PoC v1.4 / ESP32 v1.4(USB 호스트 확정) / 플랫폼 M6~M10 / 드래그 편집기 / Pi 잔여 과제 설계(구현 완료, 코드 주석이 인용 중) / 서버·앱 잔여 과제 설계(A-2~A-4 소유권 엄격 모드 전환 남음). 완료된 항목은 docs로, 끝나면 삭제 |
