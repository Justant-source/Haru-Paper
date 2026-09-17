# Pi 에이전트 (`/pi/agent`)

> Orange Pi Zero 2W에서 systemd 서비스 `haru-paper-agent`로 도는 Python 프로세스.
> 서버에서 예약과 렌더된 PNG를 받아 두었다가, **인터넷이 끊겨도** 예약 시각에 인쇄하고 결과를 나중에 올린다.
> 최초 결정: [../init_plan.md](../init_plan.md) 6장·7장·8.1, Q4·Q23·Q31. 코드는 `pi/agent/*`에 있다(config·storage·scheduler·executor·sync·uploader).
> **API 경로·필드의 원본은 [../architecture.md](../architecture.md)다.** 이 문서는 에이전트가 그 API를 어떻게 쓰는지만 적는다. 둘이 어긋나면 architecture.md를 따르고 이 문서를 고친다.

## 1. 책임

| 한다 | 하지 않는다 |
|---|---|
| 30초마다 서버 폴링, 스냅샷(예약·렌더 목록) 동기화 | 레이아웃·렌더링(서버 몫, Pi RAM 1GB) |
| 렌더 PNG 다운로드·sha256 검증·로컬 캐시 | 반복 규칙 편집(앱·서버 몫) |
| 예약 규칙으로 occurrence를 **로컬에서 계산**해 실행 | m832 바이트 조립(드라이버 몫) |
| "지금 인쇄" 명령 실행 | 용지 없는 상태에서 래스터 전송 |
| 용지 정책 적용([policy.md](policy.md)) | |
| 결과를 로컬 대기열에 쌓고 온라인일 때 업로드 | |
| 프린터 프로필·상태를 폴링에 실어 보고 | |

## 2. 모듈 구조

```
pi/
├── agent/        # 이 문서
│   ├── 설정 로드(.env)
│   ├── 동기화 채널 (SyncChannel 인터페이스 + HTTP 폴링 구현)
│   ├── 로컬 저장소 (SQLite)
│   ├── 스케줄러 (occurrence 계산)
│   ├── 실행기 (렌더 선택 → 용지 정책 → Printer.print_image → 결과 기록)
│   └── 결과 업로더
├── printer/      # 공통 인터페이스 — printer.md
│   ├── m832/     # M832 드라이버 — printer-m832.md
│   └── fake/     # 가짜 프린터 — printer.md 4절
├── transport/    # usb, bt — transport.md
├── deploy/       # install.sh, systemd unit — setup.md
└── tests/
```

- 실행 진입점은 `python -m agent` 형태 [기본값].
- 의존성 [기본값]: Pillow(12.3.0 고정, [printer-m832.md](printer-m832.md)), pyusb, HTTP 클라이언트(requests 계열), BLE가 필요해지면 bleak. 테스트는 pytest.
- Python 3.11(Pi Debian 12 기본)에서 돌아야 한다. 노트북(Python 3.12)에서 개발하더라도 3.11 문법 범위를 지킨다.

## 3. 설정

`pi/.env`(gitignore)에서 읽는다. 목록과 설명은 `pi/.env.example`.

| 변수 | 쓰임 |
|---|---|
| `HARU_SERVER_URL` | 서버 주소 (tailnet HTTPS) |
| `HARU_DEVICE_TOKEN` | `Authorization: Bearer` 기기 토큰 |
| `HARU_POLL_INTERVAL_SEC` | 폴링 주기(기본 30). 서버 응답의 `pollIntervalSec`가 오면 그 값을 따르고, 응답이 없거나 오프라인이면 이 값을 쓴다(규약) |
| `HARU_TRANSPORT`, `HARU_BT_ADDRESS` | [transport.md](transport.md) |
| `HARU_PAPER_POLICY`, `HARU_GRACE_MINUTES`, `HARU_RETRY_INTERVAL_SEC` | [policy.md](policy.md) |
| `HARU_H_OFFSET_MM` | M832 정렬 보정 |
| `HARU_DATA_DIR`, `HARU_SENT_RETENTION_DAYS` | 로컬 데이터 위치·보관 |

노트북 개발 시 `HARU_DATA_DIR`은 저장소 **밖**의 쓰기 가능한 경로(예: `~/.local/share/haru-paper`)로 둔다 [기본값]. 저장소 안에 두면 gitignore 관리가 필요해진다.

## 4. 데이터 디렉터리 (`/var/lib/haru-paper`)

```
/var/lib/haru-paper/
├── agent.db              # SQLite (5절)
├── renders/<renderId>.png
├── sent/<YYYY-MM-DD>/<resultId>.bin   # 보낸 바이트, 30일 순환
└── fake/                 # 가짜 프린터 출력 (개발용)
```

systemd의 `StateDirectory=haru-paper`로 만들고 서비스 사용자 소유로 둔다 [기본값] ([setup.md](setup.md)).

## 5. 로컬 SQLite 테이블 (확정, `pi/agent/storage.py`)

| 테이블 | 주요 컬럼 | 용도 |
|---|---|---|
| `snapshot` | `id`(=1 고정), `snapshot_hash`, `fetched_at`, `json` | 마지막으로 받은 스냅샷 원문(예약 목록·렌더 목록). 오프라인 동안 이것으로 스케줄 계산 |
| `renders` | `render_id` PK, `format_id`, `target_date`, `sha256`, `path`, `downloaded_at`, `verified` | PNG 캐시. `verified=1`(sha256 일치)인 것만 인쇄에 쓴다 |
| `executed_occurrences` | `occurrence_key` PK, `status`, `result_id`, `first_attempt_at`, `last_attempt_at`, `attempts`, `final` | 중복 방지와 재시도 상태. `final=1`이면 다시 실행하지 않는다 |
| `commands` | `command_id` PK, `format_id`, `render_id`, `sha256`, `paper_confirmed`, `created_at`, `received_at`, `status`, `result_id` | "지금 인쇄" 명령 수신·처리 상태. 같은 명령을 두 번 실행하지 않는다 |
| `results_queue` | `result_id` PK, `payload_json`, `created_at`, `uploaded_at`(NULL=미업로드), `upload_attempts` | 서버 업로드 대기열 |
| `kv` | `key` PK, `value` | `last_tick_at`(마지막 스케줄러 동작 시각, 재부팅 후 되돌아보기 기준), `last_poll_ok_at` 등 |

## 6. 동기화 (폴링)

### 동기화 채널 인터페이스

나중에 상용화 단계에서 WebSocket으로 바꿀 수 있게, 에이전트 본체는 구체 구현이 아니라 인터페이스에 의존한다.

| 메서드 | PoC 구현(HTTP 폴링)이 부르는 API |
|---|---|
| `poll(heartbeat)` → 폴링 응답 | `POST /api/device/poll` |
| `fetch_snapshot()` → 스냅샷 | `GET /api/device/snapshot` |
| `download_render(render_id)` → PNG 바이트 | `GET /api/device/renders/{renderId}.png` |
| `upload_results(results)` | `POST /api/device/results` |

모든 요청에 `Authorization: Bearer <HARU_DEVICE_TOKEN>`. 요청·응답 필드는 [../architecture.md](../architecture.md).

### 폴링 루프 (30초마다)

1. `poll` 요청에 `agentVersion`, `printerProfile`(= `Printer.profile()`), `printerStatus`(= `Printer.status()`를 `{state, detail}`로 요약, [printer.md](printer.md) 2절), `paperPolicy`(= `HARU_PAPER_POLICY`, **최상위 필드**), 로컬 `snapshotHash`를 싣는다.
2. 응답 처리:
   - `serverTime`: 로컬 시계와 비교해 크게 어긋나면 경고 로그만 ([policy.md](policy.md) 4절)
   - `snapshotChanged`가 참이면 → `fetch_snapshot` → `snapshot` 테이블 교체
   - 스냅샷의 렌더 목록 중 로컬에 없거나 `verified=0`인 것 → `download_render` → **sha256 검증** → 일치하면 `renders/<renderId>.png` 저장·`verified=1`, 불일치면 버리고 다음 폴링에서 재시도
   - `commands[]`: 항목은 `{commandId, type: "print_now", formatId, renderId, sha256, paperConfirmed, createdAt}`. `commands` 테이블에 없는 `commandId`만 저장하고 실행 대기(at-least-once로 반복해서 오므로 중복 제거). 명령 렌더는 스냅샷 `renders`에 없을 수 있으므로 `download_render(renderId)` → 명령의 `sha256`으로 검증해 캐시
     - **생성 후 10분이 지나 만료된 명령은 서버가 더 이상 보내지 않는다.** Pi는 이미 받아 둔 명령만 처리한다
   - `paperState` `{loaded, updatedAt}`: `manual_flag` 정책에서 쓰도록 `kv`에 저장
   - `pollIntervalSec`: 다음 주기에 반영(없으면 `HARU_POLL_INTERVAL_SEC`)
3. `results_queue`에 미업로드 결과가 있으면 `upload_results`. 성공하면 `uploaded_at` 기록.
4. 네트워크 오류는 로그만 남기고 다음 주기에 다시 시도한다. **동기화 실패가 스케줄러를 멈추게 하면 안 된다.**

## 7. 스케줄러 (occurrence 계산)

동기화 루프와 별도로 짧은 주기(예: 10초 [기본값])로 돈다. 판단은 **Pi 로컬 시계, `Asia/Seoul`**.

### occurrence 만들기

스냅샷의 예약마다(`enabled=true`인 것만):

| type | occurrence |
|---|---|
| `recurring` | KST 날짜 D의 요일이 `daysOfWeek`에 포함되면 `D + time` |
| `once` | `date + time` 한 번 |

- **occurrence key** = `{scheduleId}@{YYYY-MM-DD}T{HH:mm}` (예: `s1@2026-09-14T07:00`)
- 실행 대상: 예약 시각 `t`가 `t ≤ 지금 ≤ t + 유예(30분)`이고 `executed_occurrences`에 `final=1`로 없는 것
- 재시도: 마지막 시도 후 `HARU_RETRY_INTERVAL_SEC`(60초)가 지났을 때만
- 유예가 지났는데 `final`이 아니면 마지막 사유로 `final=1` 기록 ([policy.md](policy.md) 3절)

### 재부팅·중단 후 되돌아보기

- 시작하면 `kv.last_tick_at`부터 지금까지 지나간 occurrence를 확인한다. 유예 안이면 실행하고, 유예를 넘었으면 `missed`로 최종 기록한다. 되돌아보는 범위는 최대 24시간 [기본값].
- **시계가 동기화되지 않았으면 이 계산 자체를 보류**한다. 동기화된 뒤 계산하고, 그 사이 유예를 넘긴 것은 `skipped_clock_unsynced`.
- 매 틱마다 `kv.last_tick_at`을 갱신한다(SD 쓰기를 줄이려면 갱신 간격을 늘린다 — M4에서 정함).

## 8. 실행 흐름

예약 occurrence와 "지금 인쇄" 명령 모두 같은 실행기를 탄다. **한 번에 하나씩** 순서대로 처리한다(같은 시각이면 예약 시각 → scheduleId 순, 명령은 받은 순).

1. **렌더 선택**
   - 예약: `renders`에서 `(formatId, 오늘 KST 날짜)`이고 `verified=1`인 것 → 없으면 그 `formatId`의 가장 최근 `target_date`(같으면 가장 최근 다운로드) 렌더 → 그것도 없으면 `failed`(detail: 렌더 없음)
   - 명령: 명령의 `renderId` 렌더(폴링 때 명령의 `sha256`으로 검증해 받아 둔 것)를 쓴다. 아직 다운로드·검증 전이면 다음 틱에 다시 시도한다
2. **용지 정책 판단** ([policy.md](policy.md) 2절): `unverified`면 예약은 `dry_run`으로 끝(변환은 해 볼 수 있음), 명령은 `paperConfirmed` 확인 / `status_query`면 `Printer.status()` / `manual_flag`면 `kv`의 `paperState.loaded`
3. **인쇄**: `Printer.print_image(png)` — 드라이버가 변환·전송. 연결 실패는 `skipped_printer_offline`, 그 밖의 예외는 `failed`(예외 메시지를 `detail`에)
4. **보낸 바이트 보관**: `sent/<날짜>/<resultId>.bin`
5. **기록**: `executed_occurrences` 또는 `commands` 갱신 → 최종 결과면 `results_queue`에 추가
6. 결과 payload 필드(`resultId`, `occurrenceKey`, `commandId`, `formatId`, `renderId`, `status`, `detail`, `scheduledAt`, `executedAt`)는 [../architecture.md](../architecture.md)가 원본

## 9. 중복 방지와 멱등

- **인쇄 중복 방지**: `occurrence_key`(예약)와 `command_id`(명령)를 PK로 기록한다. 재시작·재동기화·스냅샷 교체가 일어나도 `final=1`인 것은 다시 인쇄하지 않는다.
- **인쇄 직전 기록**: 전송 시작 전에 "시도 중" 상태를 먼저 기록한다. 전송 도중 프로세스가 죽으면 재시작 후 그 occurrence를 자동으로 다시 인쇄하지 않고 `failed`(detail: 전송 중 중단)로 끝낸다 [기본값] — 같은 내용이 두 번 나오는 것보다 한 번 빠지는 쪽을 택한다.
- **업로드 멱등**: `resultId`(UUID, Pi가 생성)로 업로드한다. 서버는 같은 `resultId`를 한 번만 저장한다. 업로드 응답을 못 받으면 같은 `resultId`로 다시 보낸다.
- 명령 완료도 결과 업로드로 서버에 알린다(명령에 대응하는 결과에 `commandId`를 담음).

## 10. 로그

- stdout/stderr → journald (`journalctl -u haru-paper-agent`). 크기 제한은 [setup.md](setup.md).
- 폴링 성공/실패, 스냅샷 변경, 렌더 다운로드·검증 실패, 실행 결과, 시계 동기화 상태를 남긴다.
- 기기 토큰은 로그에 찍지 않는다.

## 11. M4 통과 조건 (init_plan 10절)

코드(`pi/agent/*`, `pi/printer/fake`)는 있고 단위 테스트(`pi/tests/test_scheduler.py` 등)로 occurrence 계산 로직은 확인됐다. 그러나 아래 4개는 **가짜 프린터로 처음부터 끝까지 실행해 결과를 본 적이 없어 [미검증]** — 성공한 것처럼 쓰지 않는다:

- (a) 예약 시각에 `dry_run` 기록이 남는다
- (b) 서버 연결을 끊어도 캐시된 PNG로 예약이 실행된다
- (c) 연결을 복구하면 결과가 서버에 업로드된다
- (d) 에이전트를 재시작해도 같은 occurrence가 중복 실행되지 않는다

Pi 실물에서 확인된 것은 이것과 다른 사실이다: `haru-paper-agent`가 실제로 폴링에 성공하고(`POST /api/device/poll` 200) 재부팅 후에도 자동 복구된다([setup.md](setup.md) 9절) — 이는 M5 조건이지 위 a~d를 대신하지 않는다.

이어서 **실제 프린터**(`printer/m832` + `transport/bt`, M5에서 작성)로 앱의 "지금 인쇄"(용지 확인 체크) **실물 1회**.

M4 전제: M1 통과(드라이버), M2의 device API 동작(서버, 완료). 서버가 준비되기 전에는 동기화 채널을 목(mock) 구현으로 대신해 스케줄러·실행기를 먼저 만들 수 있다.
