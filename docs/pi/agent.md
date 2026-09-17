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
│   ├── 시각 유틸 (`clock.py`, 2026-09-17 신설 — KST 상수·`now_kst`/`now_iso`/`parse_local_iso`를 한 곳에 모은다. scheduler·executor·storage가 각자 갖고 있던 KST 상수 중복을 없앴다. 같은 파일에 NTP 동기화 게이트 `check_ntp_synchronized`/`ClockGate`도 있다 — 4·7절)
│   ├── 로컬 저장소 (SQLite)
│   ├── 스케줄러 (occurrence 계산)
│   ├── 실행기 (렌더 선택 → 용지 정책 → Printer.print_image → 결과 기록, "지금 인쇄" 명령 실행 포함)
│   ├── 결과 업로더
│   ├── 보낸 바이트 순환 삭제 (`retention.py`, 2026-09-17 신설 — `prune_sent_bytes` 순수 함수. 4절)
│   └── SSE 깨우기 채널 수신기 (`events.py`, 2026-09-17 신설 — 서버 하트비트·wake 이벤트를 받아 폴링 루프를 즉시 깨운다. 프린터·Storage를 전혀 모른다. 6절)
├── printer/      # 공통 인터페이스 — printer.md
│   ├── m832/     # M832 드라이버 — printer-m832.md
│   └── fake/     # 가짜 프린터 — printer.md 4절
├── transport/    # usb, bt — transport.md
├── deploy/       # install.sh, systemd unit — setup.md
└── tests/
```

- 실행 진입점은 `python -m agent` 형태 [기본값].
- 의존성 [기본값]: Pillow(12.3.0 고정, [printer-m832.md](printer-m832.md)), pyusb, HTTP 클라이언트(requests 계열), BLE가 필요해지면 bleak. 테스트는 pytest.
- Python 3.11(Pi Debian 12 기본)에서 돌아야 한다. 서버(Python 3.12, [environment.md](../environment.md))에서 개발하더라도 3.11 문법 범위를 지킨다.

## 3. 설정

`pi/.env`(gitignore)에서 읽는다. 목록과 설명은 `pi/.env.example`.

| 변수 | 쓰임 |
|---|---|
| `HARU_SERVER_URL` | 서버 주소 (tailnet HTTPS) |
| `HARU_DEVICE_TOKEN` | `Authorization: Bearer` 기기 토큰 |
| `HARU_POLL_INTERVAL_SEC` | 폴링 주기(기본 30). 서버 응답의 `pollIntervalSec`가 오면 그 값을 따르고, 응답이 없거나 오프라인이면 이 값을 쓴다(규약) |
| `HARU_TRANSPORT`, `HARU_BT_ADDRESS` | [transport.md](transport.md) |
| `HARU_PAPER_POLICY`, `HARU_GRACE_MINUTES`, `HARU_RETRY_INTERVAL_SEC` | [policy.md](policy.md) |
| `HARU_COMMAND_TTL_SEC` | "지금 인쇄" 명령의 Pi측 자체 TTL(초, 기본 600 [기본값]). 서버가 명령을 10분 뒤 `expired` 처리하는 것과 맞춘 값이며, 서버 만료에 기대지 않고 Pi가 독립적으로 판단한다. 필수 키가 아니다(생략하면 600, 기존 Pi의 `.env`에 없어도 기동된다) — 8절 |
| `HARU_H_OFFSET_MM` | M832 정렬 보정 |
| `HARU_DATA_DIR`, `HARU_SENT_RETENTION_DAYS` | 로컬 데이터 위치·보관 |
| `HARU_EVENTS_ENABLED` | SSE 깨우기 채널(`GET /api/device/events`) 사용 여부(기본 `true`). `false`면 순수 폴링으로 동작 — 필수 키 아님 |
| `HARU_EVENT_READ_TIMEOUT_SEC` | 이벤트 스트림 read 타임아웃(초, 기본 45). 이 시간 동안 하트비트조차 안 오면 죽은 연결로 보고 재연결 — 필수 키 아님 |

서버에서 개발할 때 `HARU_DATA_DIR`은 저장소 **밖**의 쓰기 가능한 경로(예: `~/.local/share/haru-paper`)로 둔다 [기본값]. 저장소 안에 두면 gitignore 관리가 필요해진다.

## 4. 데이터 디렉터리 (`/var/lib/haru-paper`)

```
/var/lib/haru-paper/
├── agent.db              # SQLite (5절)
├── renders/<renderId>.png
├── sent/<YYYY-MM-DD>/<resultId>.bin   # 보낸 바이트, 30일 순환
└── fake/                 # 가짜 프린터 출력 (개발용)
```

systemd의 `StateDirectory=haru-paper`로 만들고 서비스 사용자 소유로 둔다 [기본값] ([setup.md](setup.md)).

**`sent/` 30일 순환 삭제, 구현됨(2026-09-17, `pi/agent/retention.py`의 `prune_sent_bytes`)**: `HARU_SENT_RETENTION_DAYS`가 실제로 쓰인다(이전에는 읽히기만 하고 아무도 부르지 않았다). 하루 1회, **시계 동기화 게이트를 통과한 뒤에만**(`Agent._maybe_prune_sent_bytes` → `_do_scheduler_tick` 0단계, 7절) 돈다 — RTC가 없어 부팅 직후 `now`를 못 믿는 동안은 `today` 판정 자체가 틀릴 수 있어서다. 보관 경계는 "오늘부터 거슬러 `retention_days`일치(오늘 포함 `retention_days+1`개 날짜)는 항상 남긴다" — `age = (today − 디렉터리날짜).days`가 `retention_days`를 넘는 디렉터리만 지운다(기본 30이면 31일째부터 삭제). 날짜 판정은 **디렉터리 이름**(`sent/YYYY-MM-DD/`)만 쓰고 mtime은 쓰지 않는다(RTC 없는 Pi는 부팅 직후 mtime이 틀린 시각일 수 있다). 이름이 `^\d{4}-\d{2}-\d{2}$` 정규식에 맞고 `date.fromisoformat()`으로도 파싱되는 디렉터리만 대상으로 한다 — 정규식만으로는 부족한 이유는 Python 3.11의 `date.fromisoformat("20260901")`(대시 없이 8자리)도 파싱에 성공하는 함정이 있어서다. 그 밖의 안전장치: 심볼릭 링크는 추적·삭제하지 않음, 삭제 직전 `resolve()`로 `sent_dir` 바로 아래인지 재확인(밖으로 못 나감), 미래 날짜 디렉터리는 보존, `retention_days <= 0`이면 아무것도 안 지움, **가장 최근 날짜 디렉터리는 나이와 무관하게 항상 보존**, **시계 폭주 방지**(`today`가 가장 최근 디렉터리보다 `retention_days × 3`일[기본값] 넘게 앞서 있으면 시계가 미래로 튄 것으로 보고 전체 삭제를 거부). 개별 삭제 실패는 로그만 남기고 나머지를 계속 처리하며, 순환 삭제 자체의 실패는 인쇄 경로를 막지 않는다([policy.md](policy.md) 7절에 보관 경계·안전장치 표).

## 5. 로컬 SQLite 테이블 (확정, `pi/agent/storage.py`)

| 테이블 | 주요 컬럼 | 용도 |
|---|---|---|
| `snapshot` | `id`(=1 고정), `snapshot_hash`, `fetched_at`, `json` | 마지막으로 받은 스냅샷 원문(예약 목록·렌더 목록). 오프라인 동안 이것으로 스케줄 계산 |
| `renders` | `render_id` PK, `format_id`, `target_date`, `sha256`, `path`, `downloaded_at`, `verified` | PNG 캐시. `verified=1`(sha256 일치)인 것만 인쇄에 쓴다 |
| `executed_occurrences` | `occurrence_key` PK, `status`, `result_id`, `first_attempt_at`, `last_attempt_at`, `attempts`, `final`, `format_id`, `render_id`, `scheduled_at`, `detail`(뒤 4개는 2026-09-17 추가) | 중복 방지와 재시도 상태. `final=1`이면 다시 실행하지 않는다. `status`는 서버 ENUM 7종 외에 로컬 전용 `checking`/`attempting`도 거친다([policy.md](policy.md) 3절). 새 컬럼 4개는 기동 정리·유예 만료 종결이 서버 결과 payload(`formatId`/`renderId`/`scheduledAt`/`detail`)를 온전히 재구성할 수 있게 한다(9절) |
| `commands` | `command_id` PK, `format_id`, `render_id`, `sha256`, `paper_confirmed`, `created_at`, `received_at`, `status`, `result_id`, `attempts`, `last_attempt_at`, `detail`(뒤 3개는 2026-09-17 추가) | "지금 인쇄" 명령 수신·처리 상태. 같은 명령을 두 번 실행하지 않는다. 로컬 `status`는 `pending → checking → attempting → done`을 거친다(8절) |
| `results_queue` | `result_id` PK, `payload_json`, `created_at`, `uploaded_at`(NULL=미업로드), `upload_attempts` | 서버 업로드 대기열 |
| `kv` | `key` PK, `value` | `last_tick_at`(마지막 스케줄러 동작 시각, 재부팅 후 되돌아보기 기준), `last_poll_ok_at` 등 |

**멱등 마이그레이션(2026-09-17 신설)**: `Storage.__init__`은 `_init_schema()`(없으면 `CREATE TABLE`) 다음에 `_migrate()`를 부른다. `_migrate()`는 `PRAGMA table_info(table)`로 테이블의 실제 컬럼을 조회해 위 표의 새 컬럼(4+3개) 중 없는 것만 `ALTER TABLE ... ADD COLUMN`으로 추가하고 `PRAGMA user_version`을 기록한다. 이미 컬럼이 있으면 아무 것도 하지 않으므로 몇 번을 다시 실행해도 안전하다. 상시 구동 중인 Pi의 기존 `agent.db`도 데이터를 잃지 않고(테이블 재작성 없음, 기존 행의 새 컬럼은 NULL) 열린다 — `user_version`은 기록용일 뿐 마이그레이션 여부 판정 근거가 아니다(`table_info`가 근거). 옛 행의 `scheduled_at`이 NULL이면 종결 시 `occurrence_key`(`{scheduleId}@{YYYY-MM-DD}T{HH:mm}`)에서 복원을 시도하고(`Executor._recover_scheduled_at`), 그것도 실패하면 `null`로 올린다(서버 검증이 `scheduledAt` NULL을 허용).

## 6. 동기화 (폴링 + 이벤트 알림)

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

1. `poll` 요청에 `agentVersion`, `printerProfile`(= `Printer.profile()`), `printerStatus`(= `Printer.status()`를 `{state, detail}`로 요약, [printer.md](printer.md) 2절), `paperPolicy`(= `HARU_PAPER_POLICY`, **최상위 필드**), 로컬 `snapshotHash`를 싣는다. **(2026-09-17 구현)** 이 `printer.profile()`/`printer.status()` 호출은 이제 직접 부르지 않고 `Executor.try_lock_printer()`(`_print_lock`을 **논블로킹**으로 시도)를 거친다(`Agent._do_poll`). 스케줄러 스레드가 `print_image` 중이면(전체 데드라인 60초) 이 락을 못 잡는데, 그렇다고 폴링 스레드가 그만큼 멈추면 안 되므로 락을 못 잡으면 **직전 `printer.profile()`/`printer.status()` 값**(`Agent._last_profile`/`_last_status` — 최초 값은 폴링·스케줄러 스레드가 시작되기 전 `Agent.__init__`에서 채워 둔다)을 그대로 재사용하고 다음 폴링으로 넘긴다. 이유: `M832Printer.status()`는 `with self.transport:`로 실제 BT 연결을 열고, `BtTransport`는 소켓 하나를 자체 락 없이 공유한다 — 스케줄러 스레드가 전송 중일 때 30초 주기 heartbeat가 겹치면 소켓을 가로채 전송 중 `send()`가 실패해 부분 인쇄가 날 수 있다. 인쇄가 60초 안에 끝나기를 기다리며 폴링이 막히는 것보다, heartbeat 정확도를 한 틱 양보하는 쪽(직전 값 재사용)을 택했다 — 서버가 `printerProfile`/`printerStatus`를 heartbeat 필수 필드로 기대하므로 생략보다 안전하다.
2. 응답 처리:
   - `serverTime`: 로컬 시계와 비교해 크게 어긋나면 경고 로그만 ([policy.md](policy.md) 4절)
   - `snapshotChanged`가 참이면 → `fetch_snapshot` → `snapshot` 테이블 교체
   - 스냅샷의 렌더 목록 중 로컬에 없거나 `verified=0`인 것 → `download_render` → **sha256 검증** → 일치하면 `renders/<renderId>.png` 저장·`verified=1`, 불일치면 버리고 다음 폴링에서 재시도. **(2026-09-17 수정)** 이 동기화는 `snapshotChanged`와 **무관하게 매 poll마다** 돈다(`Agent._sync_renders`) — 이전에는 `snapshot_changed`일 때만 불려서, 스냅샷이 안 바뀌면 재시도 기회가 없어 "렌더 없음 → 재시도"가 실제로는 구제되지 않는 결함이 있었다. 이미 `verified=1`이면 DB 조회 한 번으로 끝나므로 비용은 낮다
   - `commands[]`: 항목은 `{commandId, type: "print_now", formatId, renderId, sha256, paperConfirmed, createdAt}`. `commands` 테이블에 없는 `commandId`만 저장하고 실행 대기(at-least-once로 반복해서 오므로 중복 제거). 명령 렌더는 스냅샷 `renders`에 없을 수 있으므로 `download_render(renderId)` → 명령의 `sha256`으로 검증해 캐시. **(2026-09-17)** 새로 받은 명령뿐 아니라 아직 검증 안 된 **pending 명령 전부**에 대해 매 poll마다 `Agent._ensure_command_render`가 재시도한다(다운로드가 한 번 실패했을 수 있다). 명령의 `sha256`이 **빈 문자열**(서버가 렌더를 못 찾은 경우)이면 다운로드 자체를 하지 않는다(fail-closed)
     - **생성 후 10분이 지나 만료된 명령은 서버가 더 이상 보내지 않는다.** Pi는 이미 받아 둔 명령만 처리한다
   - `paperState` `{loaded, updatedAt}`: `manual_flag` 정책에서 쓰도록 `kv`에 저장
   - `pollIntervalSec`: 다음 주기에 반영(없으면 `HARU_POLL_INTERVAL_SEC`)
3. `results_queue`에 미업로드 결과가 있으면 `upload_results`. 성공하면 `uploaded_at` 기록.
4. 네트워크 오류는 로그만 남기고 다음 주기에 다시 시도한다. **동기화 실패가 스케줄러를 멈추게 하면 안 된다.**

### 이벤트 리스너(SSE) — `agent/events.py`, 2026-09-17 신설, `[미검증]`

`GET /api/device/events`에 **연결 1개만** 유지하는 `PushListener`. 서버 규약(형식·트리거·타임아웃 불변식)은 [../architecture.md](../architecture.md) 4.3절 "`GET /api/device/events`"가 원본이다.

- 연결은 `requests(stream=True)` + `iter_lines()`로 연다(새 의존성 없음). `event: ready` 1회로 연결 성립·백오프 초기화를 인지하고, 이후 `event: wake` + `data: {"reason":...}`를 받을 때마다 폴링 루프를 즉시 깨운다. **깨우기 신호만 받고 명령 데이터는 없다** — 다음 `poll`이 항상 실제 내용을 가져온다
- **별도 `threading.Event`(`_poll_wake`)를 쓴다.** 기존 `_wake`(8절, "지금 인쇄" 명령 도착 시 스케줄러 틱을 깨우는 용도)는 스케줄러 전용이고 매 대기 후 무조건 `clear()`한다 — 이벤트 리스너가 같은 `_wake`를 같이 쓰면, 리스너가 `set()`한 신호를 스케줄러가 먼저 `clear()`해 버리거나 그 반대로 신호를 잡아먹는 경합이 생긴다. 소비자(폴링 루프 vs 스케줄러 루프)가 다르므로 `Event`도 분리한다
- 404(서버가 옛 버전)나 401/403(토큰 문제)을 받으면 긴 간격(약 600초)으로 물러나 계속 재시도한다. **폴링에는 영향을 주지 않는다** — 이 스레드가 완전히 죽어도 폴링 루프는 `HARU_POLL_INTERVAL_SEC` 주기로 그대로 돈다
- 디바운스(약 2초): 짧은 시간에 여러 wake가 겹쳐 와도 폴링을 그만큼 여러 번 추가로 돌리지 않는다
- **불변식(가장 중요한 안전 규칙): 리스너 스레드는 프린터·Storage·Executor에 절대 접근하지 않는다 — 콜백은 `Event.set()` 한 줄뿐이다.** 이 스레드는 서버가 준 문자열(`reason`)을 신뢰하지 않고 그냥 깨우기 신호로만 쓴다. 실제 명령 조회·소유권 판정·중복 제거·TTL 판단은 전부 기존 폴링 루프(→ `Agent._do_poll`)가 한다
- `HARU_EVENTS_ENABLED=false`면 이 리스너 자체를 기동하지 않는다(폴링만으로 기존과 완전히 동일하게 동작)

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
- 재시도: 마지막 시도 후 `HARU_RETRY_INTERVAL_SEC`(60초)가 지났을 때만. **(2026-09-17 구현)** `scheduler.filter_executable_occurrences(candidates, now, grace_minutes, existing_occurrences, retry_interval_sec)`가 판정한다 — `retry_interval_sec`는 **필수 인자(기본값 없음)**다. 재시도 대상이 되는 것은 `checking` 단계(렌더 선택·용지 판단·프린터 상태 조회, 아직 `print_image`를 부르지 않음)에서 끝난 시도뿐이다. `attempting` 표식(`print_image` 호출 직전) 이후의 실패는 재시도 후보에서 빠지고 무조건 종결된다 — 상세 상태 표는 [policy.md](policy.md) 3절. 이 구분의 근거는 9절의 원칙과 같다 — 프린터로 바이트가 나갔을 수 있는 시도(`attempting`)는 "같은 내용이 두 번 나오는 것보다 한 번 빠지는 쪽"을 택해 **이 프로세스 안에서는 영구히** 재시도 후보에서 제외하고(`filter_executable_occurrences`가 `existing.get("status") == "attempting"`이면 매 틱마다 건너뜀), 프로세스가 `attempting` 상태로 멈춘 채 죽었다면 재시작 시 기동 정리(9절, `cleanup_stale_attempts`)가 `failed`로 종결한다. 반대로 `checking`(바이트 전송 **전** — 렌더 선택·용지 판단·프린터 상태 조회 중)은 바이트가 안 나갔으므로 재시도 간격만 지나면 다시 후보가 된다 — 재시작을 기다리지 않는다
- 유예가 지났는데 `final`이 아니면 마지막 사유로 `final=1` 기록 ([policy.md](policy.md) 3절). **(2026-09-17 구현)** 이 종결은 `_run_occurrence_tick`의 4단계가 하며, 반드시 `Executor.finalize_occurrence(...)`를 거쳐 `results_queue`에도 결과를 쌓는다(9절). 4단계는 3단계가 같은 틱에서 방금 기록한 값을 봐야 하므로 **저장소를 다시 읽는다**(틱 시작 시점의 `existing_occs` 스냅샷을 그대로 쓰지 않는다). 직전 상태가 비종결 값(`checking`/`attempting`)으로 남아 있으면 `failed`로 매핑해 서버 ENUM을 지킨다

### 재부팅·중단 후 되돌아보기 (구현됨, 2026-09-17)

- 시작하면 `kv.last_tick_at`부터 지금까지 지나간 occurrence를 확인한다. 유예 안이면 실행하고, 유예를 넘었으면 `missed`(또는 `skipped_clock_unsynced`, 아래)로 최종 기록한다. 되돌아보는 범위는 최대 24시간 [기본값](`scheduler.LOOKBACK_MAX_HOURS`).
- **시계가 동기화되지 않았으면 이 계산 자체를 보류**한다. 동기화된 뒤 계산하고, 그 사이 유예를 넘긴 것은 `skipped_clock_unsynced`.
- **쓰기 간격 60초 [기본값]**(`Agent.LAST_TICK_WRITE_INTERVAL_SEC`) — 매 틱(10초)마다 쓰면 하루 8,640회 SD 쓰기가 되므로 하루 최대 1,440회로 줄였다. 기록된 `last_tick_at`은 실제 마지막 정상 틱보다 최대 (간격 − 1)초 더 과거일 수 있지만, 재시작 시 `calculate_occurrences`가 그만큼 더 넓은 범위를 다시 훑을 뿐이고 이미 `final=1`인 occurrence는 `filter_executable_occurrences`·`_finalize_expired_occurrences`가 `occurrence_key`로 정확히 재조회해 건너뛰므로 중복 인쇄나 잘못된 재기록은 없다 — 손해는 약간의 재계산 비용뿐이다.

**계산 범위**: `pi/agent/scheduler.py::calculate_occurrences(snapshot, now, grace_minutes, storage_kv_last_tick)`가 `[max(kv.last_tick_at, now − 24시간), now]` 구간의 **모든 날짜**를 훑어 `recurring`/`once` occurrence를 만든다(자정을 넘겨 꺼져 있었어도 그 전날 회차가 잡힌다). `kv.last_tick_at`이 없거나 파싱 실패면(최초 기동, 옛 값 형식 오류) 기존 동작(오늘 00:00부터만)을 그대로 유지한다. 범위 밖(24시간 상한을 넘긴 부분)의 occurrence는 애초에 **만들지 않는다** — "생성되지 않음"(앱 이력에 흔적 없음)과 "생성됐지만 유예를 넘겨 `missed`"(이력에 남음)는 다르다. `grace_minutes` 인자는 이 함수가 참고하지 않는다(유예 판정은 `filter_executable_occurrences` 몫) — 호출부 호환을 위해 시그니처만 남아 있다.

**유예 만료 종결 대상 확장**: `Agent._finalize_expired_occurrences`는 두 집합의 합을 본다 — (a) 방금 계산한 `candidates`(현재 스냅샷 + 되돌아보기 범위), (b) `storage.get_unfinished_occurrences()`(`final=0`으로 남은 행 전부). 스냅샷에서 예약이 **삭제되거나 꺼지면** (a)에 다시 나타나지 않으므로, 이미 한 번이라도 시도된(= 행이 존재하는) occurrence는 (b)가 없으면 `final=0`인 채 영원히 남는 고아 행이 된다. **판단**: 스냅샷에서 사라진/비활성화된 예약의 **한 번도 시도 안 된** 과거 회차는 만들지 않는다(사용자가 예약을 지우거나 끈 것을 "이 예약에 대한 새 이력을 만들지 말라"는 의사로 해석 — 모르는 예약을 `missed`로 만들면 앱 이력이 오염될 수 있다). 반대로 이미 시도 이력이 있는 행은 (b) 경로로 **반드시** 종결한다. `attempting`으로 멈춘 채 유예를 넘긴 행도 이 경로를 거쳐 `_coerce_result_status`가 `failed`로 매핑해 종결한다([policy.md](policy.md) 3절 "서버 업로드 매핑").

**`missed` vs `skipped_clock_unsynced` 구분 규칙**(`ClockGate.expired_during_unsynced_window(expiry_at)`): 이 프로세스가 **동기화를 확인하기 전에** 실제로 미동기 상태를 관측한 적이 있고(`ever_unsynced`), 그 occurrence의 유예 만료 시각(`scheduled_at + grace`)이 **이 프로세스가 처음 동기화를 확인한 시각**(`first_synced_at`) **이전**일 때만 `skipped_clock_unsynced`다. 그 밖의 모든 "시도 기록 없이 유예를 넘긴" 회차는 `missed`. 시각 경계로 좁힌 이유: 그렇지 않으면 부팅 직후 잠깐 미동기였던 프로세스가 그 뒤 수십 일(30일 연속 운영 목표) 동안 겪는 모든 **진짜** `missed`까지 `skipped_clock_unsynced`로 영구히 오분류하게 된다 — `ever_unsynced` 플래그 하나만으로는 "그 프로세스가 살아 있는 동안"이라는 조건만 남고 "언제"가 빠지기 때문이다.

**시계 동기화 게이트(`pi/agent/clock.py`의 `check_ntp_synchronized`·`ClockGate`)**:
- 판정은 `timedatectl show -p NTPSynchronized --value`(타임아웃 5초 [기본값], `TIMEDATECTL_TIMEOUT_SEC`). 명령 실패·타임아웃·`timedatectl` 자체가 없는 환경은 전부 **미동기로 취급**한다(fail-closed) — 반대로 취급하면 `timedatectl`이 고장 난 Pi에서 게이트가 아예 없는 것과 같아진다. 실패·미동기 판정마다 경고 로그를 남긴다.
- **sticky 판정**: 한 번 `True`를 관측하면 이 프로세스가 사는 동안 `check_fn`(`timedatectl` 호출)을 다시 부르지 않는다 — 동기화 이후 시스템 시계는 RTC 없이도 커널 타이머로 정상 진행하고, "동기화가 풀리는" 시나리오는 정책 대상이 아니다. 매 스케줄러 틱(10초)마다 서브프로세스를 fork하는 비용을 동기화 전 짧은 구간으로만 제한하려는 설계다.
- **미동기 동안은 예약·"지금 인쇄" 명령 실행·`kv.last_tick_at` 갱신·`sent/` 순환 삭제를 전부 보류**한다(`_do_scheduler_tick`의 0단계, [policy.md](policy.md) 4절). "지금 인쇄" 명령까지 보류하는 것은 이번에 새로 정한 규칙이다 — TTL 계산(`Executor._command_ttl_expired`)과 결과 payload의 `executedAt`이 전부 `now_kst()`에 의존해, 시계를 못 믿는 동안은 명령 실행 판단도 신뢰할 수 없기 때문이다.
- **`pi/deploy/haru-paper-agent.service`의 `After=network-online.target time-sync.target` + `install.sh`의 `systemd-time-wait-sync.service` 활성화**: 인터넷이 있는 정상 부팅에서는 `systemd-timesyncd`가 곧바로 NTP 동기화를 마치므로 `time-sync.target` 도달과 `ClockGate`의 첫 확인이 거의 동시에 통과해 체감상 게이트가 없는 것처럼 보인다. 이 관계가 실제로 드러나는 것은 **인터넷 없는 재부팅**뿐이다 — 그때는 `time-sync.target`(과 `systemd-time-wait-sync`)이 동기화를 기다리며 도달하지 않고, 에이전트 프로세스는 이미 떠서 스케줄러 틱을 돌지만 `ClockGate.is_synced()`가 계속 `False`를 돌려줘 예약·명령·`last_tick_at`·순환 삭제가 전부 보류된 채 매 틱 경고 로그만 남긴다.

## 8. 실행 흐름

예약 occurrence와 "지금 인쇄" 명령 모두 같은 실행기를 탄다. **한 번에 하나씩** 순서대로 처리한다(같은 시각이면 예약 시각 → scheduleId 순, 명령은 받은 순).

1. **렌더 선택**
   - 예약: `renders`에서 `(formatId, 오늘 KST 날짜)`이고 `verified=1`인 것 → 없으면 그 `formatId`의 가장 최근 `target_date`(같으면 가장 최근 다운로드) 렌더 → 그것도 없으면 `failed`(detail: 렌더 없음)
   - 명령: 명령의 `renderId` 렌더(폴링 때 명령의 `sha256`으로 검증해 받아 둔 것)를 쓴다. 아직 다운로드·검증 전이면 다음 틱에 다시 시도한다
2. **용지 정책 판단** ([policy.md](policy.md) 2절): `unverified`면 예약은 `dry_run`으로 끝(변환은 해 볼 수 있음), 명령은 `paperConfirmed` 확인 / `status_query`면 `Printer.status()` / `manual_flag`면 `kv`의 `paperState.loaded`
3. **인쇄**: `Printer.print_image(png)` — 드라이버가 변환·전송. 연결 실패는 `skipped_printer_offline`, 그 밖의 예외는 `failed`(예외 메시지를 `detail`에)
4. **보낸 바이트 보관**: `sent/<날짜>/<resultId>.bin`
5. **기록**: `executed_occurrences` 또는 `commands` 갱신 → 최종 결과면 `results_queue`에 추가
6. 결과 payload 필드(`resultId`, `occurrenceKey`, `commandId`, `formatId`, `renderId`, `status`, `detail`, `scheduledAt`, `executedAt`)는 [../architecture.md](../architecture.md)가 원본. 명령의 `scheduledAt`은 명령 생성 시각(`created_at`)이다([../architecture.md](../architecture.md) 3.6)

### 실행 스레드와 직렬화 (2026-09-17 구현)

폴링 스레드와 스케줄러 스레드 2개가 돈다(`Agent.run`). **프린터로 나가는 모든 경로(예약 occurrence + "지금 인쇄" 명령)는 스케줄러 스레드 하나로 모인다** — 폴링 스레드는 명령을 받아 **저장만** 하고(`_handle_command`) 실행하지 않는다. 이유: BT 전송 데드라인이 60초라, 폴링 스레드에서 직접 실행하면 그동안 결과 업로드·`paperState` 갱신·다음 poll이 밀린다.

**(2026-09-17 신설) SSE 이벤트 리스너가 추가돼 이제 스레드가 3개(폴링·스케줄러·SSE 리스너)다.** 리스너 스레드는 `_poll_wake`를 `set()`하는 것 말고는 아무 것도 하지 않고, 명령 도착 시 스케줄러 틱을 깨우는 `_wake`와는 소비자(폴링 루프 vs 스케줄러 루프)가 달라 별도 `Event`로 분리돼 있다(6절 "이벤트 리스너(SSE)").

- 새 명령이 도착하면 `_handle_command`가 `self._wake.set()`(`threading.Event`)으로 스케줄러 틱을 **즉시** 깨운다. 스케줄러 루프는 `time.sleep(tick_interval)` 대신 `self._wake.wait(tick_interval)`로 대기하므로, 명령은 poll 직후에 실행되고 평상시 틱 주기(10초)는 그대로 유지된다 — poll(최대 30초) + 다음 틱(최대 10초) = 최대 40초를 기다리지 않는다.
- **(2026-09-17 구현) 종료 처리도 같은 메커니즘으로 모인다.** `Agent.run()`의 `KeyboardInterrupt` 처리와 `main()`의 `SIGINT`/`SIGTERM` 핸들러 모두 `agent.running = False`를 직접 건드리지 않고 `Agent.stop()`을 부른다 — `stop()`이 `self.running = False`에 이어 `self._wake.set()`도 호출해 `_scheduler_loop`를 즉시 깨운다. `self.running = False`만으로는 `_scheduler_loop`가 `self._wake.wait(tick_interval)`(최대 10초)에서 잠들어 있을 수 있는데, `Agent.run()`의 스레드 종료 대기(`join(timeout=5)`)가 그보다 먼저 끝나면 데몬 스레드가 (운이 나쁘면) 인쇄 도중에 프로세스 종료로 강제 중단될 수 있었다.
- `Executor._print_lock`(`threading.Lock`)이 `printer.print_image(...)` 호출 구간을 감싼다. 지금은 스케줄러 스레드 하나만 이 메서드를 부르므로 실질적 경합은 없지만, "프린터로 나가는 구간은 한 번에 하나"라는 불변식을 코드로 남긴다.
- `_do_scheduler_tick`의 순서: 스냅샷이 있으면 occurrence 처리(계산 → 재시도 필터 → 실행 → **저장소 재조회** → 유예 만료 종결)를 먼저 하고, 스냅샷이 없어도 명령 처리(`_run_command_tick`)는 계속한다 — 명령은 스냅샷과 무관하기 때문이다.

### 명령("지금 인쇄") 실행 (2026-09-17 구현)

- Pi 자체 TTL: `HARU_COMMAND_TTL_SEC`(기본 600초 [기본값], 서버 만료 10분과 맞춘 값)을 `min(createdAt, received_at)`에 더해 데드라인을 계산한다(`Executor._command_ttl_expired`). **서버가 명령을 `expired`로 바꾸는 것과 별개로 Pi가 독립적으로 판단한다** — "서버가 안 보냈을 것"이라는 가정에 기대지 않는다. 두 시각 중 하나도 파싱할 수 없으면 즉시 만료로 취급한다(안전한 쪽). TTL을 넘기기 전까지는 렌더 미수신·프린터 오프라인, 그리고 `manual_flag` 계열의 용지 미확인은 종결하지 않고 다음 스케줄러 틱에 재시도한다(`_run_command_tick`이 `HARU_RETRY_INTERVAL_SEC` 간격으로 `execute_command`를 다시 부른다). TTL을 넘기면 그 시점 사유로 종결한다.
  - **예외 — `unverified` 정책에서 `paperConfirmed=false`인 용지 미확인은 TTL을 기다리지 않고 즉시 종결한다.** `paper_confirmed`는 명령 생성 시 고정된 값이라(명령 생명주기 동안 바뀌지 않는다) 재시도해도 결과가 절대 바뀌지 않으므로, `Executor.execute_command`가 이 조합을 감지하면 첫 시도에서 바로 `skipped_no_paper`로 끝내 앱 이력에 즉시 반영한다. **반대로 `manual_flag`의 용지 미확인(`paperState.loaded=false`)은 서버가 앱의 "용지 확인" 토글로 언제든 값을 바꿀 수 있으므로 이 즉시 종결에 해당하지 않고, TTL까지 재시도 간격으로 계속 재확인한다** — [policy.md](policy.md) 2절·3절
  - **(2026-09-17 구현) TTL은 `print_image` 호출 직전에도 다시 확인한다**(`Executor.execute_command`, 함수 상단의 확인과 별도 두 번째 확인). 그 사이(용지 정책 확인·`_preflight_printer`의 `printer.status()` 호출 — BT는 콜드 ACL 워크어라운드로 최대 15초 걸릴 수 있다)에도 시간은 계속 흐른다. 두 번째 확인에서 만료로 판정되면 렌더·용지 정책·프린터 상태가 전부 통과했어도 인쇄하지 않고 `missed`로 종결한다 — 진입 시점 값만 썼다면, 프린터가 TTL 만료 이후에 다시 온라인이 된 경우 TTL을 넘긴 채로 그냥 인쇄해 버리는 결함이 있었다(예: 프린터가 11분간 꺼져 있다가 켜지면, 사용자는 10분짜리 "용지 확인"을 보고 이미 자리를 떴을 수 있는데도 인쇄됨)
- 용지 정책에 `paper_confirmed`를 넘기는 경로는 `execute_command`뿐이다. `_handle_command`가 `cmd.get("paperConfirmed") is True`로 **엄격 비교**해 저장한다 — `bool()`/`int()` 변환은 `null`이나 `"false"` 같은 값을 참으로 왜곡할 수 있어서다(2026-09-17 발견: 이전 코드는 `cmd.get("paperConfirmed", False)`로 읽었는데, 키가 있고 값이 `null`이면 `None`이 그대로 `storage.save_command`에 넘어가 `int(None)`에서 `TypeError`가 났다 — 지금은 고쳐졌다)
- 로컬 `commands.status`는 `pending → checking → attempting → done`을 거친다. `done`은 **"로컬에서 다시 시도하지 않는다"**는 뜻일 뿐이고, 인쇄 성공 여부는 업로드한 결과 payload의 `status`에 있다([policy.md](policy.md) 3절 표와 동일한 구분)
- **(2026-09-17 구현) 명령 경로에도 `attempting` 재실행 방지 가드가 있다.** `Agent._run_command_tick`은 매 틱마다 `status == "attempting"`인 명령을 건너뛴다 — occurrence의 `scheduler.filter_executable_occurrences` `attempting` 가드(7절)와 같은 원칙이다. 이 가드가 없으면, `print_image` 성공 후 `finalize_command`의 DB 쓰기가 예외(디스크 꽉 참, `database is locked` 등)로 실패해 명령이 `attempting`인 채로 남았을 때 재시도 간격만 보고 같은 명령을 다시 `execute_command`에 넘겨 **두 번째 장이 나갈 수 있었다**(전에는 이 가드가 없어 중복 인쇄가 가능했다). `attempting`으로 멈춘 명령은 이 프로세스 안에서는 절대 다시 실행되지 않고, 재시작 시 기동 정리(`get_stale_attempting_commands`, 9절)만이 종결시킨다
- 렌더 로딩은 예약(`_load_render_bytes`)과 다른 헬퍼(`_read_stored_render_bytes`)를 쓴다 — `execute_command`가 sha256 검증을 위해 이미 `storage.get_render()`로 얻어 둔 행(`path` 키 포함)을 그대로 읽는다(2026-09-17 발견·수정: 이전에는 이 값을 `_load_render_bytes`에 그대로 넘겨 `"renderId"` 키를 못 찾고 매번 `FileNotFoundError` → 재시도로만 빠지는 버그가 있었다)

## 9. 중복 방지와 멱등

- **인쇄 중복 방지**: `occurrence_key`(예약)와 `command_id`(명령)를 PK로 기록한다. 재시작·재동기화·스냅샷 교체가 일어나도 `final=1`인 것은 다시 인쇄하지 않는다.
- **인쇄 직전 기록**: 전송 시작 전에 "시도 중"(`attempting`) 상태를 먼저 기록한다(`storage.mark_occurrence_attempting(...)` / `storage.mark_command_attempting(...)`, `executor.py`). 전송 도중 프로세스가 죽으면(정전, OOM, `systemctl restart` 등) 재시작 후 그 occurrence를 자동으로 다시 인쇄하지 않고 `failed`로 끝낸다 — 같은 내용이 두 번 나오는 것보다 한 번 빠지는 쪽을 택한다 [기본값]. 이 표식에는 **두 겹의 방어**가 있다: ① 프로세스가 살아 있는 동안은 `scheduler.filter_executable_occurrences`(occurrence, 7절)와 `Agent._run_command_tick`(명령, 8절)이 매 틱마다 `attempting` 상태를 재시도 후보에서 제외하고, ② 프로세스가 `attempting` 상태로 멈춘 채 죽었으면(정전·OOM 등) 재시작 시 기동 정리가 종결한다. 둘 다 같은 원칙(같은 내용이 두 번 나오는 것보다 한 번 빠지는 쪽)을 서로 다른 시점에 구현한 것이다. **구현됨(2026-09-17)**: `Agent.__init__`(`__main__.py`)이 기동 시 1회 `storage.cleanup_stale_attempts()`(occurrence)와 `storage.get_stale_attempting_commands()`(명령)를 호출한다. 둘 다 `status='attempting' AND final=0`(또는 명령은 `status='attempting'`)인 레코드(재시작 전에 전송이 끝나지 못한 것)를 찾아서 돌려준다. `final=1`이면 `scheduler.filter_executable_occurrences`가 걸러내므로 재실행되지 않는다.
  - **해결됨(2026-09-17)**: 예전에는 `cleanup_stale_attempts()`가 직접 `UPDATE`까지 해서 종결시켰지만, 그 자리에서 `results_queue`에 아무것도 넣지 않아 정리 결과가 서버에 영영 올라가지 않는 한계가 있었다. **이제는 동작이 바뀌었다** — `Storage.cleanup_stale_attempts()`/`get_stale_attempting_commands()`는 **읽어서 돌려주기만** 하고, 종결(`final=1`로 바꾸기)과 결과 큐잉(`results_queue`에 넣기)은 호출부인 `Agent.__init__`이 행마다 `Executor.finalize_occurrence(key, status="failed", detail="전송 중 중단(재시작)")` / `Executor.finalize_command(command_id, status="failed", detail="전송 중 중단(재시작)")`를 불러 **한 곳에서** 처리한다 — "결과는 `final=0 → final=1` 전이 때 정확히 한 번만 큐에 넣는다"는 불변식을 지키기 위해 쓰기 주체를 하나로 모은 것이다. 그 결과 기동 정리로 찾은 잔여 시도는 기존 업로드 경로(`uploader.py` → `POST /api/device/results`)를 타서 **앱 이력 화면(`GET /api/history`)에도 `failed`로 표시된다.**
  - **같은 근본 원인으로 발견된 별도 결함도 함께 고쳐졌다**: 유예 만료 처리(7절, `_run_occurrence_tick` 4단계)도 예전에는 `executed_occurrences`만 갱신하고 `save_result`(결과 큐잉)를 부르지 않았다 — 즉 **`missed` 결과는 그때까지 단 한 번도 서버에 올라간 적이 없었다**(Pi가 꺼져 있던 회차는 앱 이력에 아예 나타나지 않았다). 이제는 유예 만료 시에도 반드시 `Executor.finalize_occurrence(...)`를 거치므로 `missed`(및 유예 만료 시점의 마지막 사유)도 `results_queue`에 쌓이고 업로드된다.
- **업로드 멱등**: `resultId`(UUID, Pi가 생성)로 업로드한다. 서버는 같은 `resultId`를 한 번만 저장한다. 업로드 응답을 못 받으면 같은 `resultId`로 다시 보낸다.
- 명령 완료도 결과 업로드로 서버에 알린다(명령에 대응하는 결과에 `commandId`를 담음).

## 10. 로그

- stdout/stderr → journald (`journalctl -u haru-paper-agent`). 크기 제한은 [setup.md](setup.md).
- 폴링 성공/실패, 스냅샷 변경, 렌더 다운로드·검증 실패, 실행 결과, 시계 동기화 상태를 남긴다.
- 기기 토큰은 로그에 찍지 않는다.

## 11. M4 통과 조건 (init_plan 10절)

코드(`pi/agent/*`, `pi/printer/fake`)는 있고 단위 테스트(`pi/tests/test_scheduler.py` 등)로 occurrence 계산 로직은 확인됐다. **(2026-09-17 갱신)** 같은 날 신설된 `test_command_execution.py`(16개)·`test_retry.py`(14개)·`test_grace_expiry.py`(3개)·`test_lookback.py`(18개)·`test_clock_gate.py`(21개)·`test_heartbeat_printer_lock.py`(3개)·`test_storage_migration.py`(4개)는 `scheduler.py`의 순수 함수 단위를 넘어 `Executor`·`Agent`(`agent/__main__.py`)를 `FakePrinter` + 실제 SQLite(`tmp_path`)로 직접 구성해 재시도·용지 게이트 우회 금지·시계 게이트·기동 정리를 검증한다 — 이전보다 훨씬 깊다. 전체 스위트는 266개 통과(`cd pi && .venv/bin/python -m pytest tests/ -q`, 2026-09-17). **그러나 이 테스트들도 `Agent.run()`이 실제로 띄우는 폴링 스레드 + 스케줄러 스레드 두 개를 동시에 돌리며 모의(mock) 서버를 상대로 여러 틱에 걸쳐 처음부터 끝까지 실행한 것은 아니다** — 개별 메서드(`_do_scheduler_tick`, `execute_occurrence`, `execute_command` 등)를 직접 호출해 검증한다. 그래서 아래 4개는 여전히 **[미검증]**(성공한 것처럼 쓰지 않는다):

- (a) 예약 시각에 `dry_run` 기록이 남는다
- (b) 서버 연결을 끊어도 캐시된 PNG로 예약이 실행된다
- (c) 연결을 복구하면 결과가 서버에 업로드된다
- (d) 에이전트를 재시작해도 같은 occurrence가 중복 실행되지 않는다

Pi 실물에서 확인된 것은 이것과 다른 사실이다: `haru-paper-agent`가 실제로 폴링에 성공하고(`POST /api/device/poll` 200) 재부팅 후에도 자동 복구된다([setup.md](setup.md) 9절) — 이는 M5 조건이지 위 a~d를 대신하지 않는다.

**배포 상태(2026-09-17)**: 이 절이 설명하는 용지 게이트 fail-closed·재시도·명령 실행기·결과 업로드·시계 게이트·되돌아보기·순환 삭제 구현(`623a8dc`·`8b7194b`·`51a6a8d`)은 **아직 Pi에 배포되지 않았다** — Pi는 이 커밋들 이전 코드로 구동 중일 수 있다([확인 불가, 미검증], [setup.md](setup.md) "배포" 참고). 배포 전까지 위 a~d의 실물 검증도 당연히 시작할 수 없다.

이어서 **실제 프린터**(`printer/m832` + `transport/bt`, M5에서 작성 완료)로 앱의 "지금 인쇄"(용지 확인 체크) **실물 1회** — 이 부분은 아직 검증되지 않았다. M5에서 실제로 이뤄진 실물 인쇄 1회([setup.md](setup.md) 9절)는 `M832Printer`+`BtTransport`를 직접 호출한 것이라, 여기서 말하는 **에이전트 실행기 → 앱 명령 → 서버 결과 업로드까지 이어지는 경로는 여전히 (a)~(d)와 마찬가지로 [미검증]**이다.

M4 전제: M1 통과(드라이버), M2의 device API 동작(서버, 완료). 서버가 준비되기 전에는 동기화 채널을 목(mock) 구현으로 대신해 스케줄러·실행기를 먼저 만들 수 있다.
