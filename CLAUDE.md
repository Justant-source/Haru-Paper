# 하루종이(Haru-Paper)

> 이 저장소는 **노트북(WSL)** 과 **서버(`justant-server2`)** 두 곳에서 동시에 작업한다.
> 세션을 시작하면 먼저 `hostname`으로 지금 어느 머신인지 확인하고, 아래 "디렉터리 담당"을 따른다.
> 결정의 원본 기록은 `docs/init_plan.md`, 현재 설계는 `docs/architecture.md`와 `docs/{pi,server,app}/`에 있다.

## 이 프로젝트의 목표

디지털 디톡스를 종이로 해보는 프로젝트다. 폰 웹앱에서 고른 콘텐츠(포맷)를 예약한 시각에
감열 프린터(Phomemo M832)가 인쇄한다.

- **범위(PoC)**: 사용자 1명(본인), 프린터 1대, 용지 110mm 연속 롤 고정
- **구조**: 폰 웹앱(PWA) ─ 서버(Spring Boot, Docker) ─ 30초 폴링 ─ Pi(Orange Pi Zero 2W) ─ BT 또는 USB ─ M832. 전 구간 Tailscale 내부망
- **PoC 완료 기준**: 폰에서 포맷을 만들고 다음 날 07:00으로 예약 → **공유기 WAN을 뽑은 상태에서도** 07:00에 인쇄 → 인터넷 복구 후 앱에 이력 표시. **3일 연속 성공**
- 선행 프로젝트: `~/Data/detox-printer` — M832 프로토콜 리버싱 실험실(노트북). 여기서 [확인됨]이 된 것만 이 저장소로 옮긴다

## 구성요소 경계 (어기지 말 것)

- **m832를 아는 코드는 `/pi/printer/m832`뿐이다.** 서버와 앱은 Pi가 보고한 **프린터 프로필**
  (`model`, `dpi`, `paperWidthMm`, `printableWidthPx`)만 안다. 서버·앱에 M832 명령 바이트, WIDTH_BYTES,
  h-offset 같은 프로토콜 상수를 넣지 않는다
- **서버가 그레이스케일 PNG를 렌더**하고, 디더링·정렬 보정·패딩·비트 패킹·전송은 Pi 드라이버 몫이다
- **스케줄의 원본은 서버, 실행은 Pi**다. Pi는 예약 규칙과 렌더 PNG를 캐시해 두고 인터넷이 끊겨도 스스로 인쇄한다
- API 규약의 원본은 `docs/architecture.md`다. 서버·Pi·앱 문서는 이를 링크하고, 다르게 정의하지 않는다

## 디렉터리 담당

| 세션 | 수정하는 곳 |
|---|---|
| 노트북 세션 (프린터가 USB로 붙어 있음) | `/pi`, `/docs/pi` |
| 서버 세션 (`justant-server2`) | `/server`, `/app`, `/docs/server`, `/docs/app` |
| 공통 (수정 직전 반드시 `git pull --ff-only`) | `CLAUDE.md`, `AGENTS.md`, `README.md`, `docs/architecture.md`, `.gitignore`, `.cursor/` |

- 담당 밖 경로는 **사용자가 요청하지 않으면 수정하지 않는다.** 필요하면 멈추고 사용자에게 말한다
- `docs/init_plan.md`는 최초 결정 기록이다. 내용이 바뀌어도 이 파일은 고치지 않고, 해당 docs를 고친다

## Git 규칙

- 기본 브랜치 `main`에 직접 커밋한다
- **작업 시작 전과 push 전에 `git pull --ff-only`**. 공통 파일은 수정 직전에 한 번 더 pull
- **fast-forward가 실패하면 멈추고 사용자에게 보고한다.** 스스로 합치지 않는다
  (Claude Code: `.claude/settings.local.json` / Cursor: `.cursor/cli.json`과 `.cursor/hooks.json`이
  `git merge*`, `git rebase*`, `git reset*` 등을 거부한다)
- 원격: `https://github.com/Justant-source/Haru-Paper` — **공개 저장소**다

## 절대 금지

1. **용지를 확인하기 전에 래스터(인쇄) 명령을 보내지 않는다.** 헤드가 상한다.
   Pi의 용지 정책 3종(`unverified` / `status_query` / `manual_flag`)을 따른다 — `docs/pi/policy.md` 참고.
   용지가 없는 상태에서 래스터를 보내 프린터 반응을 보는 시험도 금지다
2. **실제로 프린터에 보내서 눈으로 확인하지 않은 하드웨어 사실을 [확인됨]으로 쓰지 않는다.**
   다른 기종(M02, M04, M834, M835 등)에서 됐다는 자료는 [추정]이다
3. **새 하드웨어 실험을 이 저장소에서 먼저 하지 않는다.** 실험은 `~/Data/detox-printer`에서
   그 저장소 규칙(보낸 바이트 저장, findings.md 기록)대로 하고, [확인됨]이 된 것만 `/pi/printer/m832`로 옮긴다
4. **비밀값을 커밋하지 않는다.** `.env`, 기기 토큰, DB 비밀번호, API 키는 `.env`에만 두고
   `.env.example`만 커밋한다. 커밋 전에 `git diff --cached`로 확인한다. Tailscale 주소는 문서에 적어도 되지만 코드에는 환경변수로 받는다
5. **`/pi` 밖에서 m832 프로토콜 코드나 상수를 쓰지 않는다** (위 "구성요소 경계")
6. **사용자 포맷에 임의 HTML/CSS/JS를 허용하지 않는다.** 포맷은 JSON 블록 + 화이트리스트 스타일 속성뿐이다.
   서버의 헤드리스 Chromium이 남의 HTML을 열면 내부망 접근(SSRF)·JS 실행이 가능해진다 — `docs/server/rendering.md` 참고
7. **토큰 최적화 / 컨텍스트 압축 / 리팩터링 계열 스킬을 자동으로 호출하지 않는다.**
   `CLAUDE.md`와 `docs/`는 축약 대상이 아니다

## 기록 규칙

- 사실의 확실성을 표기한다
  - **[확인됨]** 실물로 눈으로 확인 / **[미검증]** 확인 전 / **[추정]** 자료·계열 기종 기반 추론 / **[기본값]** 사용자에게 따로 묻지 않고 정한 값(바꿔도 됨)
- 결정이 바뀌면 해당 `docs/` 문서를 고친다. `docs/init_plan.md`는 원본 기록으로 둔다
- 하드웨어 실험 기록은 이 저장소가 아니라 `~/Data/detox-printer/m832/docs/findings.md`에 남긴다.
  이 저장소의 `docs/pi/hardware-verification.md`에는 결과 요약과 링크만 적는다
- 실패했는데 성공한 것처럼 요약하지 않는다. 무반응이면 무반응이라고 쓴다

## 코드 규칙 (detox-printer 계승)

- 하드코딩한 상수 옆에는 반드시 근거 주석을 단다
  (`WIDTH_DOTS = 1304  # 110mm, detox-printer findings "D단계" 실물 확인 163byte*8`)
- 모든 USB/BT write는 타임아웃을 명시하고, 실패 시 예외를 삼키지 않는다(로그에 그대로 남긴다)
- 프린터로 보낸 바이트는 Pi 데이터 디렉터리(`HARU_DATA_DIR`, 기본 `/var/lib/haru-paper`)에 저장하고 30일 순환 보관한다
- 시간대는 `Asia/Seoul` 고정

## 진행 방식

- `docs/init_plan.md` 10절의 마일스톤(M0~M5) 순서를 따른다. **통과 조건을 만족하기 전에 다음 마일스톤으로 넘어가지 않는다**
- 하드웨어 검증 트랙(V1~V4, H4, H5)은 detox-printer `m832/.temp/PLAN-02.md`에서 병렬로 진행된다. 결과에 따라 transport·용지 정책이 정해진다
- 막히면 추측으로 진행하지 말고 멈추고 물어본다
- 서버 설정 변경(`tailscale serve`, 포트 바인딩 등)은 적용 직전에 사용자 승인을 받는다. 서버에는 다른 운영 중인 프로젝트가 많다

## docs 안내

| 문서 | 내용 |
|---|---|
| `docs/init_plan.md` | 최초 합의(grill-me Q1~Q34), 인프라 사실, 마일스톤·통과 조건. 원본 기록 |
| `docs/architecture.md` | 전체 구조, 구성요소 경계, 도메인 모델, **API 규약 원본**, 동기화 흐름, 용어집 |
| `docs/pi/README.md` | Pi 문서 목차와 에이전트 한눈에 보기 |
| `docs/pi/agent.md` | 폴링 루프, 스냅샷·PNG 캐시, occurrence 스케줄러, 실행기, 결과 대기열 |
| `docs/pi/printer.md` | 프린터 공통 인터페이스, 프린터 프로필, 가짜 프린터 |
| `docs/pi/printer-m832.md` | M832 드라이버: detox-printer에서 옮긴 상수·명령과 그 근거([확인됨]/[미검증]) |
| `docs/pi/transport.md` | USB/BT transport, 청크·타임아웃, 선택 기준 |
| `docs/pi/policy.md` | 운영 정책 현재값: 용지 정책 3종, 유예·재시도, 시계 미동기, 보관 기간 |
| `docs/pi/setup.md` | Orange Pi 설치 절차(Debian 12, Tailscale, install.sh, systemd), 도착 시 확인 목록 |
| `docs/pi/hardware-verification.md` | V0~V4, H4, H5 상태표와 결정 규칙, detox-printer 기록 링크 |
| `docs/server/README.md` | 서버 문서 목차와 스택 한눈에 보기 |
| `docs/server/api.md` | architecture.md API 규약의 서버 구현 관점(검증, 오류 코드, 인증) |
| `docs/server/data-model.md` | DB 테이블, Flyway 마이그레이션, 파일 저장 |
| `docs/server/format-schema.md` | **포맷 JSON 스키마 원본**(블록 타입, 스타일 화이트리스트, 변수, 가져오기/내보내기) |
| `docs/server/rendering.md` | 포맷 → HTML → PNG 렌더러, 샌드박스(JS·네트워크 차단), 폰트, 렌더 스케줄러 |
| `docs/server/weather.md` | 날씨 블록: Open-Meteo, 기본 위치, 출처 교체 인터페이스 |
| `docs/server/deploy.md` | Docker compose(prod), 포트·`tailscale serve`, 백업, `.env` |
| `docs/app/README.md` | 앱 문서 목차 |
| `docs/app/web.md` | 웹앱 스택(React+TS+Vite PWA), 구조, API 호출 |
| `docs/app/screens.md` | MVP 화면 7개와 흐름 |
| `docs/app/native.md` | `/app/android`, `/app/ios` 예약 폴더, 네이티브 기술 후보(미정) |
