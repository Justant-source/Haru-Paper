# 하루종이(Haru-Paper) — init_plan

> 작성: 2026-09-13, detox-printer 세션(노트북 WSL)에서 grill-me 라운드(Q1~Q34)로 합의한 내용.
> 이 문서가 Haru-Paper의 **최초 결정 원본**이다. 저장소 생성 시 `Haru-Paper/docs/init_plan.md`로 복사한다.
> 이후 변경은 `docs/`의 해당 문서를 고치고, 이 문서는 "처음에 무엇을 왜 정했는가"의 기록으로 둔다.
>
> 표기: **[확인됨]** 실물로 눈으로 확인 / **[미검증]** 확인 전 / **[추정]** 자료·계열 기종 기반 추론 / **[기본값]** 사용자에게 따로 묻지 않고 정한 값(바꿔도 됨)

---

## 1. 목표와 범위

- **하루종이(Haru-Paper)**: 디지털 디톡스를 종이로 해보자는 프로젝트. 폰(웹앱)에서 고른 콘텐츠를 예약한 시각에 감열 프린터가 인쇄한다.
- **범위(PoC)**: 사용자 1명(본인), 프린터 1대(Phomemo M832), 용지 **110mm 연속 롤 고정**.
- **선행 프로젝트**: `~/Data/detox-printer` — M832 프로토콜 리버싱 실험실. CUPS·벤더 드라이버 없이 순수 파이썬(pyusb)으로 110mm 인쇄 [확인됨]. 앞으로도 **하드웨어 실험은 detox-printer에서 먼저** 하고, [확인됨]이 된 것만 Haru-Paper `/pi/printer/m832`로 옮긴다.

### PoC 완료 기준

폰에서 포맷을 만들고 다음 날 07:00으로 예약 → **공유기 WAN을 뽑은 상태에서도** 07:00에 인쇄 → 인터넷 복구 후 앱에 이력 표시. **3일 연속 성공.**

---

## 2. 전체 구조

```
폰 웹앱(PWA, s21) ─HTTPS(tailscale serve)─ 서버(justant-server2, Docker) ─30초 폴링─ Pi(Orange Pi Zero 2W) ─BT 또는 USB─ M832
                              (전 구간 Tailscale 내부망)
```

| 구성 | 디렉터리 | 하는 일 | 모르는 것 |
|---|---|---|---|
| 앱 | `/app` | 포맷 편집·미리보기, 예약, 지금 인쇄, 이력·기기 상태 | 프린터 |
| 서버 | `/server` | 포맷·예약 저장, 프린터 프로필 폭으로 PNG 렌더, 날씨, Pi 동기화 API | m832 프로토콜 |
| Pi | `/pi` | 폴링 동기화, 예약 로컬 계산, PNG 캐시, 디더링·정렬보정·래스터, 결과 대기열 | 레이아웃·콘텐츠 |

**핵심 경계**: m832를 아는 코드는 `/pi/printer/m832`뿐이다. 서버와 앱은 "폭 N px, dpi D의 종이"만 안다.

---

## 3. 인프라 사실 (조사 결과)

### 서버 `justant-server2`
- Tailscale `100.81.189.92`, `justant-server2.tail2b65d1.ts.net`. Ubuntu 24.04.4, 8코어, RAM 31GB, 디스크 1.8TB(12%)
- 이미 Docker 컨테이너 약 45개(Again-Spring, Green-Forest, Family-Brain 등 `~/Data/<프로젝트>`), Cloudflare Tunnel로 공개 도메인 서비스 중. `tailscale serve` 설정은 현재 없음
- OpenJDK 21, Docker 29 설치됨
- 참고: MariaDB/MySQL 포트(3306/3308/3309)가 `0.0.0.0`에 열려 있음 — Haru-Paper는 DB 포트를 호스트에 노출하지 않는다

### 노트북
- Windows 11 + WSL2 Ubuntu. WSL에는 Bluetooth 없음(Windows에 Intel BT 어댑터는 있음)
- Flutter/JDK/Android SDK 없음, node v18, docker 있음, `gh`는 `Justant-source`로 로그인됨
- 프린터는 usbipd로 WSL에 attach해서 사용. `networkingMode=mirrored` 적용됨, 연결 끊김 해소 여부는 [미검증]

### Orange Pi Zero 2W (2일 내 도착 예정)
- Allwinner H618(Cortex-A53 x4), **RAM 1GB**, Wi-Fi 5 + BT 5.0/BLE(UWE5622, UART 연결), USB-C(USB2.0) x2, Micro SD(**32GB 사용**), 40핀(GPIO/UART/I2C/SPI/PWM)
- 전원 규격 **Type-C 5V 2A**. **RTC 없음**
- 지원 OS: Debian 11/12, Ubuntu 20.04/22.04, Android 12 TV, Orange Pi OS(Arch)
- USB0 = 호스트/디바이스 겸용(공식 이미지 기본은 디바이스), USB1 = 호스트 전용. 매뉴얼은 USB0 전원 + USB1 장치 연결을 권장 [확인됨·매뉴얼]
- 알려진 문제: Armbian에서 부팅 후 BT가 자주 안 뜸(미해결 보고), DietPi 6.18 커널 Wi-Fi 다운로드 중 oops [확인됨·사용자 보고]

### M832 (detox-printer 결과 + 조사)
- USB `0483:5740`, Printer class, BULK OUT 0x02 / IN 0x81. 110mm: WIDTH_BYTES=163(1304dot), 1=검정, 압축 없음, 헤더 `1F 11 0B`+`1F 11 35 00`+`1D 76 30 00 xL xH yL yH`, 꼬리 `1B 64 01`+`1B 64 02`+`1F 11 11` [확인됨·실물]
- `--h-offset-mm 2.0` 기본값으로 좌우 여백 대칭 약 1mm [확인됨·실물]
- `1F 11 11`(findpaper) 후 BULK IN **무응답** → 용지 감지 방법 미확정 [미검증]
- 노트북 USB 연결 상태로 1시간 넘게 꺼지지 않음 (V0) [관찰·미검증]
- BT: 같은 계열(M04S/M834)은 Classic SPP(RFCOMM ch1)와 BLE(서비스 `0000ff00`, write `ff02`, notify `ff01`/`ff03`) 둘 다로 USB와 같은 바이트를 받음. M832 FCC 인증에 Classic·BLE 등급 모두 있음 [추정]
- 배터리 2600mAh, 충전 5V 2A [확인됨·제조사 문서]. 자동 꺼짐 조회 `1F 11 0E`, 설정 `1B 4E 07 n`(n=0 안 꺼짐) [추정·타 기종]
- 계열 기종 사례: BT로 한 번에 많이 보내면 끝부분 유실(256B/20ms 청크로 해결), M835는 USB에서도 빽빽한 텍스트 줄 누락(1KB씩 보내며 상태 조회 `A8/A9`=용지 있음/없음, `98/99`=커버 닫힘/열림) [추정·타 기종]

---

## 4. 결정 기록 (grill-me Q1~Q34 요약)

| # | 주제 | 결정 |
|---|---|---|
| Q1 | 사용자 범위 | 본인 1명, 프린터 1대. 데이터 모델에 `device_id`는 둔다 |
| Q2 | 콘텐츠·용지 | 앱에서 사람이 직접 고른다. 웹앱이면 충분. **110mm 고정**. 이름 **Haru-Paper(하루종이)** |
| Q3 | 예약 조작 | 앱에서 사용자가 제어 |
| Q4 | 스케줄 원본·실행 | **서버가 원본, Pi가 실행**. 예약 시각에 인터넷이 끊겨도 Pi는 인쇄. Pi도 Tailscale |
| Q5 | 연결 방식 | USB·BT 무관. 단 USB 직결 시 전원 문제 우려 |
| Q6 | 자원 | Android 사용자. Java/Python/React/TS/JS. PoC는 웹앱, 네이티브는 나중 |
| Q7 | 저장소 | 새 프로젝트, 서버에서 개발. `/pi` `/server`(Java) `/app` `/docs`(pi/app/server). m832는 `/pi/printer/m832`만 |
| Q8/Q20 | 포맷 모델 | 블록 쌓기, **포맷=카드로 통일**, 저장·재사용, 가져오기는 fork(출처 기록). **JSON 블록 + 허용된 스타일 속성만**(HTML/CSS/JS 금지). 나중에 자유 커스터마이징·남의 포맷 가져와 수정 가능해야 함 |
| Q8/Q25 | 첫 블록 | 텍스트, 이미지, 날짜 헤더, 날씨(Open-Meteo, 기본 위치 서울시청 좌표) |
| Q9 | 예약 기능 | 반복(요일+시각), 일회성, 지금 인쇄, 켜기/끄기. KST 고정. 예약 1개 = 포맷 1개 |
| Q10/Q21 | 렌더링 | **서버가 용지 PNG 렌더**. Pi가 프린터 프로필 보고 → 서버는 그 폭으로 렌더. 나중에 다른 프린터 지원 가능 구조 |
| Q11 | 접속 경로 | **Tailscale 내부망 전용**, `tailscale serve` HTTPS. 웹앱 로그인 없음 |
| Q12/Q22 | 프린터 연결·전원 | **허브 없이**. 목표 BT(프린터는 전용 충전기), 코드는 USB transport부터. V1~V4로 결정, 전원 문제 시 허브 구매 |
| Q13 | 진행 | 하드웨어 트랙(detox-printer PLAN-02)과 소프트웨어 트랙 병렬. Pi용 코드 먼저 작성 가능 |
| Q14/Q30 | Pi 정책 | 유예 30분, 시각 미동기 시 보류, 이력 업로드. H4 전 예약=dry-run, 지금 인쇄=용지 확인 체크. H4 실패 시 수동 "용지 장착됨" 상태 |
| Q15 | 서버 스택 | Spring Boot 3 + Java 21 + **Gradle** + **MariaDB** + Flyway + **Docker**, **prod만** |
| Q16/Q26/Q33 | 앱 | `/app/web` React+TS+Vite PWA. `/app/android`, `/app/ios` 빈 폴더(.gitkeep), 네이티브 기술 미정 |
| Q17/Q19 | 저장소 | **공개** `Justant-source/Haru-Paper`. `.claude/skills` 커밋, `settings.local.json`은 gitignore 유지·파일 복사(죽은 IP 규칙 2줄 삭제). 비밀값은 `.env`만 |
| Q18/Q28 | 작업 분담·인증 | 노트북=`/pi`, 서버=`/server`·`/app`. 저장소 생성은 노트북 `gh`, 서버는 Again-Spring의 GitHub 인증(같은 계정)을 가져와 사용 |
| Q23/Q31 | 동기화 | **단순 폴링 30초**. Pi가 규칙 로컬 계산, 포맷별 PNG 캐시, 결과 대기열, 기기 토큰. 상용화 시 WebSocket 검토 |
| Q24 | Pi OS | 공식 Debian 12 먼저(V3 실패 시 Armbian), `install.sh` + `docs/pi/setup.md` |
| Q27 | 마일스톤 | M0~M5 + 위 완료 기준 |
| Q29 | Git 규칙 | `main` 직접 커밋, 작업 전후 `git pull --ff-only`, 디렉터리 담당, ff 실패 시 멈추고 보고 |
| Q34 | Pi 사양 | RAM 1GB, 5V 2A → Pi에서 렌더 안 함, V4에는 5V 3A 어댑터 필요 |

---

## 5. 저장소 구조 (M0에서 생성)

```
Haru-Paper/
├── CLAUDE.md                  # 규칙·디렉터리 담당·Git 규칙·금지사항·docs 안내
├── README.md                  # 짧은 소개(하루종이), 구조, docs 링크
├── .gitignore
├── .claude/
│   ├── skills/                # detox-printer에서 복사(커밋): investigate-first, git-guardrails-claude-code, caveman-explore, verify-and-stop
│   └── settings.local.json    # 복사하되 gitignore(커밋 안 함)
├── docs/
│   ├── init_plan.md           # 이 문서 복사본
│   ├── architecture.md        # 전체 구조, 구성요소 경계, API 규약(원본), 프린터 프로필, 용어
│   ├── pi/                    # 노트북 담당
│   ├── server/                # 서버 담당
│   └── app/                   # 서버 담당
├── pi/                        # Python 에이전트 (M1·M4에서 코드)
│   ├── README.md
│   ├── .env.example
│   ├── printer/               # 공통 인터페이스
│   │   └── m832/              # M832 드라이버(detox-printer 이식)
│   ├── transport/             # usb, bt
│   ├── agent/                 # 폴링·스케줄러·캐시·결과 대기열
│   ├── deploy/                # install.sh, systemd unit
│   └── tests/
├── server/                    # Spring Boot (M2에서 코드)
│   ├── README.md
│   └── .env.example
└── app/
    ├── README.md
    ├── web/                   # React+TS+Vite PWA (M3에서 코드)
    ├── android/.gitkeep       # 네이티브 예약(기술 미정)
    └── ios/.gitkeep
```

- M0에서는 **코드 없이** 디렉터리(.gitkeep), README, docs, 설정 파일만 만든다.
- `.env.example`은 구성요소마다 둔다(`server/`는 docker compose가, `pi/`는 systemd가 각자 읽기 때문).
- **디렉터리 담당(Q29)**: 노트북 세션 = `/pi`, `/docs/pi` / 서버 세션 = `/server`, `/app`, `/docs/server`, `/docs/app` / 공통(수정 직전 pull) = `CLAUDE.md`, `README.md`, `docs/architecture.md`, `.gitignore`

---

## 6. 도메인 모델

### 6.1 포맷(Format) — 카드와 같은 것

- 재사용·공유 단위. 예약은 포맷을 가리킨다.
- 가져오기(import)하면 내 라이브러리에 새 포맷이 생기고 `meta.forkedFrom`에 출처를 남긴다. 이후 자유롭게 수정.
- **허용되는 것은 JSON 블록 + 화이트리스트 스타일 속성뿐**. 임의 HTML/CSS/JS는 받지 않는다.
  - 이유: 서버의 헤드리스 Chromium이 남의 HTML을 열면 서버 LAN·tailnet 내부 주소 접근(SSRF)이나 JS 실행이 가능해진다. HTML 템플릿은 네트워크·JS 차단 샌드박스가 생긴 뒤에만 재검토.
- 단위는 **mm/pt** — 프린터 dpi와 무관하게 정의하고, 렌더 시 프로필 dpi로 환산.
- `style`(전체)과 `blocks[].style`(개별)을 분리 — 나중에 "내 스타일 입히기"를 스타일 덮어쓰기로 구현.

스키마 v1 초안 (확정은 M2, `docs/server/format-schema.md`가 원본이 된다):

```json
{
  "schemaVersion": 1,
  "meta": { "name": "아침 브리핑", "author": "justant", "description": "", "forkedFrom": null },
  "style": {
    "fontFamily": "Pretendard", "baseFontSizePt": 11, "lineHeight": 1.4,
    "marginMm": { "top": 3, "right": 3, "bottom": 8, "left": 3 },
    "blockGapMm": 3, "divider": "none"
  },
  "blocks": [
    { "type": "dateHeader", "props": { "pattern": "YYYY년 M월 D일 dddd" }, "style": { "align": "center", "fontSizePt": 16, "bold": true } },
    { "type": "text", "props": { "text": "{{date}} {{weekday}}\n오늘의 할 일" }, "style": { "align": "left" } },
    { "type": "image", "props": { "assetId": "a1b2", "widthPercent": 100 } },
    { "type": "weather", "props": { "location": "default", "fields": ["tempMin", "tempMax", "precipProb", "sky"] } }
  ],
  "assets": { "a1b2": "data:image/png;base64,..." }
}
```

- `assets`는 **내보내기 파일에만** 포함(이미지를 파일 안에 내장). 서버 내부에서는 업로드 파일로 저장하고 `assetId`로 참조.
- 텍스트 변수: `{{date}}`, `{{weekday}}` — 값은 **렌더 대상 날짜(targetDate)** 기준.
- 블록 스타일 화이트리스트 초안: `align`(left/center/right), `fontSizePt`, `bold`, `marginTopMm`, `marginBottomMm`.

### 6.2 예약(Schedule)

```json
{ "id": "s1", "formatId": "f1", "type": "recurring", "daysOfWeek": ["MON","TUE","WED","THU","FRI"], "time": "07:00", "enabled": true }
{ "id": "s2", "formatId": "f2", "type": "once", "date": "2026-09-15", "time": "08:30", "enabled": true }
```

- 시간대는 `Asia/Seoul` 고정(필드로 두지 않음).
- **occurrence key** = `{scheduleId}@{YYYY-MM-DD}T{HH:mm}` — Pi가 계산. 같은 key는 두 번 인쇄하지 않는다(재시작·재동기화 중복 방지).
- "지금 인쇄"는 예약이 아니라 **명령(command)**: `{commandId, formatId, paperConfirmed}`.

### 6.3 렌더(Render)

- `{renderId, formatId, targetDate, profileKey, widthPx, sha256, renderedAt}` — PNG 파일 1개.
- 서버는 앞으로 36시간 안의 occurrence마다 `(formatId, targetDate)` 렌더를 준비한다.
- 날씨처럼 바뀌는 블록이 있는 포맷은 occurrence **약 60분 전에 다시 렌더**한다.
- Pi는 실행 시 `(formatId, 오늘 날짜)` 렌더를 쓰고, 없으면 그 포맷의 가장 최근 렌더를 쓴다.
- **알려진 한계(PoC 수용)**: 오프라인이 길어지면 날짜·날씨가 마지막으로 받은 렌더 값으로 인쇄된다.

### 6.4 프린터 프로필(Printer Profile)

Pi가 폴링할 때 보고하고, 서버는 이 값으로만 렌더 폭을 정한다.

```json
{ "model": "m832", "dpi": 300, "paperWidthMm": 110, "printableWidthPx": 1300 }
```

- `printableWidthPx`는 잠정 1300. **M1에서 detox-printer `07_print_image.py`(WIDTH_DOTS=1304, h-offset 2mm) 기준으로 확정**하고 `docs/pi/printer-m832.md`에 근거를 적는다.
- 서버 PNG는 **그레이스케일**. 흑백 변환(디더링), 좌우 정렬 보정, 1304dot 패딩, 비트 패킹은 Pi 드라이버 몫.

### 6.5 실행 결과(Result)

```json
{ "resultId": "uuid", "occurrenceKey": "s1@2026-09-14T07:00", "commandId": null, "formatId": "f1", "renderId": "r9",
  "status": "printed", "detail": "", "scheduledAt": "...", "executedAt": "..." }
```

`status`: `printed` | `dry_run` | `missed` | `failed` | `skipped_no_paper` | `skipped_clock_unsynced` | `skipped_printer_offline`

---

## 7. API 규약 v0 (원본은 `docs/architecture.md`로 옮겨 관리)

모든 경로는 `/api` 아래. 웹앱과 같은 origin(`/`=웹, `/api`=Spring).

### 앱용 (인증 없음 — tailnet이 인증)

| 메서드 | 경로 | 용도 |
|---|---|---|
| GET/POST | `/api/formats` | 목록 / 생성 |
| GET/PUT/DELETE | `/api/formats/{id}` | 조회 / 수정 / 삭제 |
| POST | `/api/formats/import` | 내보낸 JSON 가져오기(fork) |
| GET | `/api/formats/{id}/export` | `assets` 내장 JSON 내보내기 |
| GET | `/api/formats/{id}/preview.png` | 현재 프린터 프로필로 미리보기 렌더 |
| POST | `/api/assets` | 이미지 업로드(최대 10MB) → `assetId` |
| GET/POST | `/api/schedules` | 목록 / 생성 |
| PUT/DELETE | `/api/schedules/{id}` | 수정(켜기/끄기 포함) / 삭제 |
| POST | `/api/print-now` | `{formatId, paperConfirmed}` → 명령 생성 |
| GET | `/api/history` | 실행 결과 목록 |
| GET | `/api/device` | Pi 마지막 폴링 시각, 프린터 프로필·상태, 용지 정책, 수동 용지 상태 |
| PUT | `/api/device/paper-state` | `{loaded}` — H4 실패 시 폴백용 수동 상태 |
| GET/PUT | `/api/settings` | 날씨 기본 위치 등 |
| GET | `/api/health` | 헬스체크 |

### Pi용 (`Authorization: Bearer <HARU_DEVICE_TOKEN>`)

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/api/device/poll` | 30초마다. 요청: `{agentVersion, printerProfile, printerStatus, snapshotHash}` / 응답: `{serverTime, snapshotHash, snapshotChanged, commands[], paperState, pollIntervalSec}` |
| GET | `/api/device/snapshot` | `snapshotChanged`일 때만. 응답: `{snapshotHash, schedules[], renders[{renderId, formatId, targetDate, sha256, url}]}` |
| GET | `/api/device/renders/{renderId}.png` | PNG 다운로드(sha256 검증) |
| POST | `/api/device/results` | 결과 묶음 업로드. `resultId`로 멱등. 명령 처리 완료도 여기서 보고 |

- `serverTime`은 Pi 시계 이상 감지용(시각 동기화 자체는 NTP).
- 상용화 단계에서 poll을 WebSocket으로 바꿀 수 있도록, Pi 에이전트는 "동기화 채널"을 인터페이스로 둔다.

---

## 8. 구성요소별 설계

### 8.1 `/pi` — Pi 에이전트 (노트북 세션 담당)

- **언어·실행**: Python 3.11(Debian 12 기본) + venv, systemd 서비스 `haru-paper-agent`, 데이터 `/var/lib/haru-paper`(SQLite, PNG 캐시, 보낸 바이트)
- **구조**
  - `printer/`: 공통 인터페이스 `Printer.profile()`, `Printer.status()`, `Printer.print_image(png)`
  - `printer/m832/`: detox-printer 검증 코드 복사·정리(의존성·서브모듈 아님). 상수마다 detox-printer findings 근거 주석 유지. 디더링(Floyd–Steinberg), h-offset 2mm, 1304dot 패딩, 헤더/꼬리 조립
  - `transport/`: `usb`(pyusb, 4096B 청크, 명시 타임아웃) / `bt`(V2 결과에 따라 SPP 소켓 또는 BLE)
  - `agent/`: 폴링 루프, 스냅샷·PNG 캐시, occurrence 계산 스케줄러, 실행기, 결과 대기열(SQLite)
  - `deploy/`: `install.sh`(여러 번 실행해도 안전), systemd unit
  - 가짜 프린터(`printer/fake`): PNG·bin을 파일로만 저장 — 프린터 없이 M4 개발
- **용지 정책 (`HARU_PAPER_POLICY`)**
  - `unverified`(기본, H4 통과 전): 예약 → `dry_run` 기록만. 지금 인쇄 → `paperConfirmed=true`일 때만 전송
  - `status_query`(H4 통과 후): 인쇄 직전 상태 조회로 용지 확인
  - `manual_flag`(H4 실패 시): 서버의 수동 "용지 장착됨"이 켜져 있을 때만. 프린터 오류 시 서버에 해제 보고
- **운영 정책** (`docs/pi/policy.md`에 현재값으로 기록)
  - 늦은 실행: 유예 30분, 60초 간격 재시도, 넘기면 `missed`
  - NTP 미동기(오프라인 재부팅)면 인쇄 보류(`skipped_clock_unsynced`). RTC(DS3231, I2C)는 선택 부품
  - 이력: 로컬 SQLite → 온라인 시 업로드
  - 보낸 바이트 30일 순환 보관(SD 보호), journald 크기 제한
- **OS·설치**: Orange Pi 공식 Debian 12 서버 이미지(헤드리스: Wi-Fi·SSH 키 사전 설정), Tailscale, `Asia/Seoul`, NTP. V3 실패 시 Armbian 시험

`pi/.env.example` 초안:

```
HARU_SERVER_URL=https://justant-server2.tail2b65d1.ts.net
HARU_DEVICE_TOKEN=
HARU_POLL_INTERVAL_SEC=30
HARU_TRANSPORT=usb            # usb | bt
HARU_BT_ADDRESS=
HARU_PAPER_POLICY=unverified  # unverified | status_query | manual_flag
HARU_GRACE_MINUTES=30
HARU_RETRY_INTERVAL_SEC=60
HARU_H_OFFSET_MM=2.0
HARU_DATA_DIR=/var/lib/haru-paper
HARU_SENT_RETENTION_DAYS=30
```

### 8.2 `/server` — 백엔드 (서버 세션 담당)

- **스택**: Spring Boot 3 + Java 21 + Gradle(Groovy DSL), MariaDB(프로젝트 전용 컨테이너) + Flyway, Docker compose **prod만**
- **렌더러** [기본값]: Spring 컨테이너 안 Playwright for Java + Chromium. 포맷 JSON → 내부 HTML 생성 → 스크린샷 PNG(그레이스케일, `printableWidthPx` 폭). **JS 비활성, 외부 네트워크 요청 전부 차단**(내장 폰트·업로드 에셋만)
- **폰트** [기본값]: Pretendard / Noto Sans KR(OFL)을 이미지에 포함
- **날씨**: Open-Meteo(키 없음), 기본 위치 서울시청(37.5663, 126.9779), 앱 설정에서 변경. 날씨 출처는 인터페이스로 분리(나중에 기상청으로 교체 가능)
- **렌더 스케줄러**: 주기적으로 앞으로 36시간 occurrence를 보고 렌더 준비, 날씨 포함 포맷은 약 60분 전 재렌더
- **compose 초안** [기본값]: `haru-api`(Spring+Chromium), `haru-web`(nginx: `app/web` 빌드 결과 서빙 + `/api` 프록시), `haru-db`(MariaDB), `haru-db-backup`(매일 mysqldump, 7일 보관). 볼륨 `haru-db-data`, `haru-files`(업로드·렌더), `haru-backups`
- **노출**: `haru-web`만 `127.0.0.1:<빈 포트>`에 바인딩 → `tailscale serve`로 HTTPS. DB 포트는 호스트에 노출 안 함. **포트는 서버 세션이 빈 포트 확인 후 결정, `tailscale serve` 적용은 그 시점에 사용자 승인**
- **서버 관례**: 기존 프로젝트(`~/Data/Again-Spring` 등)의 compose 관례를 서버 세션이 읽고 맞춘다

`server/.env.example` 초안:

```
HARU_DB_NAME=haru_paper
HARU_DB_USER=haru
HARU_DB_PASSWORD=
HARU_DB_ROOT_PASSWORD=
HARU_DEVICE_TOKEN=
HARU_WEB_BIND=127.0.0.1:18080   # 서버 세션이 빈 포트 확인 후 확정
HARU_WEATHER_LAT=37.5663
HARU_WEATHER_LON=126.9779
HARU_UPLOAD_MAX_MB=10
HARU_BACKUP_RETENTION_DAYS=7
TZ=Asia/Seoul
```

### 8.3 `/app` — 웹앱 (서버 세션 담당)

- **스택**: React + TypeScript + Vite + PWA(홈 화면 설치, HTTPS는 `tailscale serve`)
- **화면(MVP)**
  1. 포맷 목록 — 새로 만들기, 가져오기(JSON), 내보내기
  2. 포맷 편집 — 블록 추가·삭제·순서, 블록/전체 스타일, 서버 미리보기 PNG
  3. 예약 목록·편집 — 반복/일회성, 켜기/끄기
  4. 지금 인쇄 — 포맷 선택 + "프린터 앞에서 용지를 눈으로 확인함" 체크
  5. 이력 — 결과 상태별 표시
  6. 기기 — Pi 마지막 폴링, 프린터 프로필, 용지 정책, 수동 "용지 장착됨" 토글
  7. 설정 — 날씨 기본 위치
- **네이티브**: `/app/android`, `/app/ios` 빈 폴더만. 기술 미정(후보 React Native/Expo, Kotlin+Swift, Flutter) — PoC 후 결정. 상용화 시 실시간 WebSocket 함께 검토

---

## 9. 하드웨어 검증 트랙 (detox-printer `m832/.temp/PLAN-02.md`)

| ID | 내용 | 장소·시점 | 상태 |
|---|---|---|---|
| V0 | 노트북 USB 연결 상태 1시간+ 안 꺼짐 | 사용자 관찰 | [관찰·미검증] findings 기록 |
| V1 | 폰 충전기만(데이터 없음) 연결 2시간 후 켜짐 여부 | 사용자, 즉시 | 대기 |
| V2 | M832 BT(SPP/BLE) 확인 + 검증된 체커보드 BT 전송 | 노트북 Windows 파이썬(분석 도구 예외) | 대기 |
| H4 | 상태 조회로 용지 있음/없음 구분 | 노트북 USB | 대기 |
| H5 | 빽빽한 텍스트 줄 누락 여부 | 노트북 USB | 대기 |
| V3 | Pi 내장 BT 재부팅 20회 생존 | Pi (M5) | Pi 도착 후 |
| V4 | USB 직결 시 Pi 전압강하·재부팅 (5V 3A 어댑터 필요) | Pi (M5) | Pi 도착 후 |

**결정 규칙**: V1·V2·V3 모두 통과 → **BT**. 하나라도 실패 → **USB 직결(V4)** 시험. 전원 문제 → 셀프전원 허브 구매 재논의.
**H4 결과**: 통과 → `status_query` 정책 / 실패 → `manual_flag` 정책.
**금지**: 용지 없는 상태에서 래스터를 보내 프린터 반응을 보는 시험(detox-printer 금지 5).

---

## 10. 마일스톤과 통과 조건

통과 조건을 만족하기 전에 다음으로 넘어가지 않는다. 하드웨어 트랙(9절)은 M1~M3와 병렬.

### M0 — 저장소 뼈대 (노트북 세션, 2026-09-13)
- 5절 구조 생성, CLAUDE.md, README, docs(architecture, pi/server/app 초안, init_plan 복사), `.claude` 복사, `.gitignore`, `.env.example`
- `gh repo create Justant-source/Haru-Paper --public` 후 `main` push
- **통과 조건**: GitHub에 push됨. `git ls-files`에 `.env`, `settings.local.json`, 토큰·비밀번호 없음. 서버에서 `git clone` 가능

### M1 — M832 드라이버 이식 + USB transport (노트북, Pi 도착 전)
- `/pi/printer/m832`, `/pi/transport/usb`, `/pi/printer/fake`, 단위 테스트
- **통과 조건**: (1) 같은 입력(체커보드 테스트 패턴 + 임의 PNG 1장)에 대해 detox-printer `07_print_image.py` dry-run 출력과 `/pi` 출력이 **바이트 단위 동일** (2) usbipd attach + 용지 확인 후 실물 1회 인쇄 육안 확인, 보낸 bin 보관 (3) `printableWidthPx` 확정·문서화

### M2 — 서버 (서버 세션)
- 포맷 CRUD·가져오기/내보내기·미리보기, 에셋 업로드, 예약 CRUD, 렌더러·렌더 스케줄러, 날씨, device API, Flyway, compose prod, 백업
- **통과 조건**: `docker compose up`으로 기동, `tailscale serve` HTTPS에서 `/api/health` 200, 7절 API가 curl 시나리오로 동작, 미리보기 PNG 폭 = 프로필 폭, 한글 렌더 정상, 백업 파일 1회 생성

### M3 — 웹앱 (서버 세션)
- 8.3절 화면 7개, PWA
- **통과 조건**: s21에서 PWA 설치 → 포맷 만들기 → 미리보기 → 예약 → 지금 인쇄 명령 생성 → (가짜 Pi 결과로) 이력 표시

### M4 — Pi 에이전트 (노트북에서 개발)
- 폴링·스냅샷·PNG 캐시·스케줄러·결과 대기열, 용지 정책 3종
- **통과 조건**(노트북 + 가짜 프린터): (a) 예약 시각에 `dry_run` 기록 (b) 서버 연결을 끊어도 캐시 PNG로 예약 실행 (c) 복구 후 결과 업로드 (d) 재시작해도 같은 occurrence 중복 없음. 이어서 실제 프린터로 "지금 인쇄"(용지 확인 체크) 실물 1회

### M5 — Pi 실물 설치 (Pi 도착 후)
- 도착 시 확인: 전원 어댑터 포함·사양(5V 3A 여부), USB-C 케이블·OTG 젠더, 방열판·케이스
- 공식 Debian 12 설치, Tailscale, `install.sh`, V3·V4 → 연결 방식 결정
- **통과 조건**: 재부팅 후 서비스 자동 시작, 서버 폴링 정상, 결정된 transport로 실물 인쇄 1회

### PoC 완료
- 1절 완료 기준(WAN 차단 상태 07:00 인쇄 + 복구 후 이력, 3일 연속). H4 통과(`status_query`) 또는 폴백(`manual_flag`) 상태에서 수행

---

## 11. 협업·Git·보안 규칙

- **작업 위치**: 노트북 `~/Data/Haru-Paper`(`/pi` 담당, 프린터 USB 테스트) / 서버 `~/Data/Haru-Paper`(`/server`·`/app` 담당)
- **Git**: 기본 브랜치 `main`, 직접 커밋. 작업 전후 `git pull --ff-only`. 공통 파일은 수정 직전 pull. fast-forward 실패 시 **멈추고 사용자에게 보고**(`settings.local.json`이 `git merge*`/`git rebase*`를 거부함)
- **GitHub 인증**: 노트북은 기존 `gh`(Justant-source). 서버는 `~/Data/Again-Spring`에 설정된 같은 계정 인증을 가져와 사용
- **비밀값**: DB 비밀번호·기기 토큰·API 키는 `.env`에만(gitignore). `.env.example`만 커밋. Tailscale 주소는 문서에 적어도 되지만 코드에는 환경변수로
- **`.claude`**: skills 커밋, `settings.local.json`은 각 머신에 파일로 복사(gitignore)
- **하드웨어 규칙(detox-printer에서 계승)**: 용지 확인 전 래스터 전송 금지 / 실물로 본 것만 [확인됨] / 새 하드웨어 사실은 detox-printer에서 먼저 확인

---

## 12. 서버 세션 시작 절차

1. 서버에서 `cd ~/Data && git clone https://github.com/Justant-source/Haru-Paper.git`
2. Again-Spring의 GitHub 인증 방식을 확인해 Haru-Paper에서 push 가능하게 설정
3. 노트북의 `.claude/settings.local.json`을 서버 `~/Data/Haru-Paper/.claude/`로 복사
4. Claude Code를 `~/Data/Haru-Paper`에서 실행 → `CLAUDE.md`, `docs/init_plan.md`, `docs/architecture.md`, `docs/server/`, `docs/app/` 읽고 M2 시작

---

## 13. 열린 항목

| 항목 | 언제 정하나 |
|---|---|
| V1 결과(충전기만 연결 시 자동 꺼짐) | 사용자 확인 즉시 |
| BT 방식(SPP/BLE)과 전송 파라미터 | V2 |
| 용지 정책 `status_query` vs `manual_flag` | H4 |
| 흐름 제어 필요 여부 | H5 |
| 최종 transport(BT/USB) | V1~V4 |
| `printableWidthPx` 확정값 | M1 |
| 서버 호스트 포트, `tailscale serve` 적용 | M2 (사용자 승인) |
| Pi 전원 어댑터·케이블 추가 구매 | M5 도착 확인 |
| 네이티브 앱 기술 | PoC 이후 |
| 실시간 동기화(WebSocket) | 상용화 검토 시 |
