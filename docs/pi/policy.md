# Pi 운영 정책 (현재값)

> Pi 에이전트가 "언제 인쇄하고, 언제 인쇄하지 않고, 무엇을 기록하는가"의 **현재 정책값**이다.
> PoC 단계라 동작하는 것이 우선이며, **나중에 얼마든지 조정할 수 있다. 정책이 바뀌면 이 문서를 먼저 고친다.**
> 최초 결정: [../init_plan.md](../init_plan.md) 8.1, Q14·Q30. 환경변수는 `pi/.env.example`.

## 1. 한눈에 보기

| 항목 | 현재값 | 환경변수 |
|---|---|---|
| 용지 정책 | `unverified` (H4 통과 전) | `HARU_PAPER_POLICY` |
| 늦은 실행 유예 | 30분 | `HARU_GRACE_MINUTES` |
| 재시도 간격 | 60초 | `HARU_RETRY_INTERVAL_SEC` |
| 폴링 주기 | 30초 | `HARU_POLL_INTERVAL_SEC` |
| 시각 미동기 시 | 인쇄 보류 | — |
| RTC | 없음 (DS3231은 선택 부품) | — |
| 보낸 바이트 보관 | 30일 순환 | `HARU_SENT_RETENTION_DAYS` |
| journald | 크기 제한 | ([setup.md](setup.md)) |
| 시간대 | `Asia/Seoul` 고정 | — |

## 2. 용지 정책

용지 없는 상태에서 래스터를 보내면 헤드가 상할 수 있다(detox-printer 금지 5). M832는 아직 용지 유무를 감지할 방법이 확인되지 않았다(H4, [hardware-verification.md](hardware-verification.md)).

**설계 원칙은 fail-closed다**: "용지가 있는지 확실히 모른다"는 항상 인쇄를 막는 쪽으로 떨어져야 한다(CLAUDE.md 절대 금지 1 — 용지를 눈으로 확인하기 전에는 래스터를 보내지 않는다). `pi/agent/executor.py`의 `_check_paper_policy`는 어느 분기에서도 "확인 안 됨"이 기본 허용으로 새지 않도록 짜여 있다(2026-09-17 수정. 이전 코드는 `status_query`에서 프린터 연결만 되면 통과시켰고, `manual_flag`는 `paperState` 키가 아예 없으면 통째로 건너뛰어 둘 다 사실상 허용이 기본값이었다 — 둘 다 CLAUDE.md 절대 금지 1 위반 소지였다).

**"지금 인쇄"의 `paperConfirmed` 경로는 2026-09-17에 실제로 구현됐다** [확인됨·코드: `pi/agent/executor.py`]. 안전장치는 함수 시그니처에 있다 — `_check_paper_policy(self, render: dict, paper_confirmed: bool = False)`의 **기본값이 `False`**이고, 예약 경로(`execute_occurrence`)는 이 인자를 코드에서 **아예 넘기지 않는다**(호출부가 `self._check_paper_policy(render)`뿐, 두 번째 인자 없음). 그래서 "예약(무인) 실행이 `paperConfirmed` 경로를 타는 것"은 우연이 아니라 **구조적으로 불가능**하다 — 인자를 넘기는 코드 자체가 없으므로 누가 실수로 값을 흘려보낼 지점이 없다. `paper_confirmed=True`가 의미를 가지는 유일한 호출부는 `execute_command`("지금 인쇄")다. `unverified`는 `paper_confirmed`가 참이면 통과, `manual_flag`는 `paperState.loaded or paper_confirmed`로 판단한다 — `status_query` 분기는 코드에서 이 인자를 **한 번도 읽지 않는다**(아래 표와 일치, [확인됨·코드]).

| 정책 | 언제 쓰나 | 예약(무인) 인쇄 | "지금 인쇄"(앱 명령) |
|---|---|---|---|
| **`unverified`** (현재) | H4 통과 전 | **래스터를 보내지 않는다.** 렌더 선택·변환까지 하고 `dry_run`으로 기록 | 명령의 `paperConfirmed=true`(앱에서 "프린터 앞에서 용지를 눈으로 확인함" 체크)일 때만 전송. 아니면 `skipped_no_paper` |
| `status_query` | H4 통과 후(**과도기 동작 — 아래 참고**) | 프린터 연결(`status().state == "ok"`)만으로는 인쇄하지 않는다. `PrinterStatus`에 용지 필드가 없어 "용지 있음"을 확인할 방법이 아직 없으므로 **현재는 항상 `skipped_no_paper`**(연결이 살아 있어도) | 같은 이유로 항상 `skipped_no_paper`. `paperConfirmed`는 이 정책에서 아직 참고되지 않는다 |
| `manual_flag` | H4 실패 시 폴백 | 서버의 수동 "용지 장착됨"(폴링 응답 `paperState`, `kv.paperState`)이 켜져 있을 때만 전송. `paperState` 키가 없거나 JSON 파싱이 실패해도 **무조건 `skipped_no_paper`**(fail-closed) — `loaded=true`일 때만 전송 | 같은 조건, 또는 `paperConfirmed=true` |

**"지금 인쇄" 명령의 종결 시점은 정책마다 다르다(2026-09-17 구현, [agent.md](agent.md) 8절 상세)**: `unverified`에서 `paperConfirmed=false`인 명령은 `HARU_COMMAND_TTL_SEC` 만료를 기다리지 않고 **즉시** `skipped_no_paper`로 종결한다 — `paperConfirmed`는 명령 생성 시 고정된 값이라 재시도해도 결과가 절대 바뀌지 않기 때문이다(`Executor.execute_command`). 반대로 `manual_flag`의 용지 미확인(`paperState.loaded=false`)은 서버가 앱의 "용지 확인" 토글로 언제든 값을 바꿀 수 있으므로 이 즉시 종결에 해당하지 않고, TTL까지 재시도 간격(`HARU_RETRY_INTERVAL_SEC`)으로 계속 재확인한다.

### `status_query`가 지금 인쇄를 전혀 하지 않는 이유(과도기)

`printer/m832/driver.py`의 `status()`는 "장치를 열 수 있는가"만 보고하고 `PrinterStatus`(`printer/__init__.py`)에는 용지 유무 필드가 아직 없다(H4 미통과, [printer-m832.md](printer-m832.md) 5절). "용지가 명시적으로 있다"를 확인할 방법이 없는 이상 `_check_paper_policy`는 fail-closed 원칙에 따라 항상 `skipped_no_paper`를 반환한다 — **`HARU_PAPER_POLICY=status_query`로 전환하면 예약·"지금 인쇄" 모두 인쇄가 전혀 되지 않는다는 뜻**이다. H4가 통과해 `PrinterStatus`에 용지 필드가 생기면 그 필드로 판단하도록 이 분기를 바꾼다. 그 전까지 실제로 인쇄가 필요하면 `unverified`(지금 인쇄만, `paperConfirmed` 체크) 또는 `manual_flag`를 쓴다.

**(2026-09-17 구현) `printer.status()`가 예외를 던지는 경우도 fail-closed로 떨어진다.** `_check_paper_policy`의 `status_query` 분기는 이 호출을 `try`로 감싸, 실패하면 `skipped_no_paper`가 아니라 `skipped_printer_offline`로 즉시 반환한다(연결 자체가 안 되면 용지 여부를 따질 수도 없다는 뜻). **이전에는 이 분기만 예외를 감싸지 않아(`_preflight_printer`는 이미 감싸고 있었다) 예외가 그대로 `execute_occurrence` 밖까지 새어 나갔다** — 이미 `begin_occurrence_attempt`가 찍어 둔 `checking` 행이 종결되지 않은 채 남고(스케줄러 루프 바깥의 `try/except`가 로그만 남기고 틱을 통째로 버린다), 유예 만료 전까지는 `scheduler.filter_executable_occurrences`의 재시도 간격 판정으로만 구제됐다 — 즉 회차가 인쇄되지도, 명확히 종결되지도 않은 채 `checking`으로 남을 수 있었다.

- 알 수 없는(오타 등) `HARU_PAPER_POLICY` 값도 로그에 원인을 남기고 `skipped_no_paper`로 fail-closed 처리한다. `unknown_policy`라는 status 값은 쓰지 않는다(5절 목록에 없다).
- `manual_flag`에서 인쇄가 프린터 오류로 실패하면 결과(`failed` 또는 `skipped_printer_offline`)를 보통처럼 업로드한다. **서버가 그 결과를 받으면 수동 상태를 끈다**(`paperState.loaded=false`, `updatedBy: "server"`) [기본값]. Pi가 따로 해제 API를 부르지 않는다. 규약 원본은 [../architecture.md](../architecture.md) 3.7.
- 사용자는 롤을 갈 때 앱에서 수동 상태를 켠다.
- 정책 전환은 `HARU_PAPER_POLICY`를 바꾸고 서비스를 재시작한다.

## 3. 실행 시각 규칙

- **예약 시각이 되면** 실행한다. 판단은 Pi의 로컬 시계(KST)로 한다.
- **유예 30분**: 예약 시각에 인쇄하지 못한 경우(Pi가 꺼져 있었음, 프린터 무응답, 용지 없음 판정 등) 예약 시각 + 30분까지 **60초 간격으로 재시도**한다.
- 유예가 지나도 성공하지 못하면 최종 결과를 남긴다: Pi가 그 시간에 꺼져 있었거나 실행 기회가 없었으면 `missed`, 재시도했지만 조건 미충족이면 마지막 사유(`skipped_no_paper`, `skipped_printer_offline`, `failed`).
- **같은 occurrence는 두 번 인쇄하지 않는다**(재부팅·재동기화 중복 방지, [agent.md](agent.md)).
- 같은 시각에 예약이 여러 개면 **예약 시각 → scheduleId 순서로 하나씩** 인쇄한다.
- 예약을 끄면(`enabled=false`) 그 이후 occurrence는 만들지 않는다.

### 재시도·종결 상태 표 (2026-09-17 구현 — `HARU_RETRY_INTERVAL_SEC`가 이제 실제로 쓰인다)

핵심 원칙([agent.md](agent.md) 9절): **프린터로 바이트가 나갔을 가능성이 있는 시도는 재시도하지 않는다.** 실행기(`pi/agent/executor.py`)는 시도 단계를 내부 status 2개로 나눈다 — 이 둘은 서버 `results.status` ENUM에는 없는 **로컬 전용 값**이고, 절대 그대로 업로드되지 않는다(아래 "서버 업로드 매핑" 참고).

| 내부 status | 뜻 | `print_image()` 호출 여부 |
|---|---|---|
| `checking` | 렌더 선택 · 용지 정책 판단 · 프린터 상태 조회(`_preflight_printer`) 중 | 아니오 |
| `attempting` | `print_image()` 호출 직전 ~ 반환 전 | 예 |

`attempting`은 `print_image` 호출 **직전 한 줄**(`storage.mark_occurrence_attempting` / `mark_command_attempting`)에서만 찍는다. 이 표식 이후의 실패는 부분 인쇄 여부를 알 수 없으므로 예외 없이 종결한다. 이 구분이 재시도 판정 전체의 근거다.

| 결과 status | 발생 단계 | 재시도(`final=0`) / 종결(`final=1`) | 비고 |
|---|---|---|---|
| `printed` | `attempting` 이후 전송 성공 | 종결 | 다시 보내면 두 장 |
| `dry_run` | `unverified` 예약, `checking` 단계 | 종결 | 정책값은 서비스 재시작 전에는 안 바뀐다 — 재시도해도 같은 결과이고, 이력이 같은 회차로 계속 늘어나는 것도 막는다 |
| `skipped_no_paper` | `manual_flag` 꺼짐 · `status_query` · 명령 `paperConfirmed=false`, `checking` 단계 | **재시도** | 사용자가 롤을 갈고 앱에서 켜면 다음 poll에 `paperState`가 바뀐다 — 이 설계의 주 목적 |
| `skipped_printer_offline` | 전송 **전** `_preflight_printer` 실패, `checking` 단계 | **재시도** | 07:00에 프린터가 꺼져 있고 07:05에 켜는 경우. 바이트는 안 나갔다 |
| `failed` (렌더 없음 · 로컬 파일 없음) | `_select_render` / `_load_render_bytes`, `checking` 단계 | **재시도** | 다음 poll(최대 30초)에 렌더가 도착할 수 있다 |
| `failed` (전송 중 예외) | `print_image()` 예외, `attempting` 단계 | 종결 | 부분 인쇄 여부를 알 수 없다 |
| `failed` (재시작 후 `attempting` 잔여 정리) | 기동 정리 | 종결 | 기존 규칙 유지 |
| `missed` | 유예 만료 + 시도 기록이 아예 없는 회차 | 종결 | — |
| (유예 만료, 시도 기록은 있음) | 마지막 사유를 그대로 최종 status로 | 종결 | `skipped_no_paper`가 마지막 사유였으면 최종 status도 `skipped_no_paper`(`missed`가 아니다) |

재시도 간격 판정은 `pi/agent/scheduler.py`의 `filter_executable_occurrences(candidates, now, grace_minutes, existing_occurrences, retry_interval_sec)`가 한다(`retry_interval_sec`는 **기본값이 없다** — 기본값을 두면 "읽히기만 하고 안 쓰이는" 상황이 조용히 재현될 수 있어서다). `last_attempt_at`이 없거나 파싱 실패면 "시도한 적 없음"으로 보고 즉시 허용하고, 있으면 `now - last_attempt_at >= HARU_RETRY_INTERVAL_SEC`일 때만 후보에 넣는다. `existing['status'] in ('checking', 'attempting')`으로 아직 `final=0`인 행(같은 틱 재진입 방어용 — 지금은 스케줄러 스레드 하나만 실행기를 부르므로 실제로는 발생하지 않지만, 상태 모델의 불변식을 코드로도 남긴다)도 건너뛴다.

**"지금 인쇄" 명령은 같은 원칙이되 종결 시점이 다르다**(8절 상세): 렌더 미수신 · 프린터 오프라인, 그리고 용지 미확인 중 **`manual_flag`** 계열은 **TTL(`HARU_COMMAND_TTL_SEC`, 기본 600초) 안에서는 종결하지 않는다**. 로컬 `commands.status`는 최초 수신 시 `pending`이고, 첫 시도부터는 매 시도(`begin_command_attempt`)마다 `checking`으로 다시 세팅된 채 다음 스케줄러 틱에 재시도한다(`done`이 아니므로 `get_pending_commands()`가 계속 집어 온다). TTL을 넘기면 그 시점의 사유로 종결한다(`missed` 또는 `failed`/사유별 `skipped_*`). **예외**: `unverified` 정책에서 `paperConfirmed=false`인 용지 미확인은 TTL을 기다리지 않고 첫 시도에서 즉시 `skipped_no_paper`로 종결한다(2절 — `paperConfirmed`는 명령 생성 시 고정값이라 재시도해도 결과가 바뀌지 않기 때문). 전송(`attempting`) 이후 실패는 명령도 TTL과 무관하게 즉시 종결한다.

**(2026-09-17 구현) 명령 경로에도 `attempting` 재실행 방지 가드가 있다.** `attempting`으로 멈춘 명령은 occurrence와 같은 원칙으로 이 프로세스 안에서 다시 실행되지 않는다 — `Agent._run_command_tick`이 매 틱마다 `status == "attempting"`인 명령을 건너뛰고, 재시작 시에만 `get_stale_attempting_commands()`(기동 정리)가 종결시킨다. 이 가드가 없으면 `print_image` 성공 후 `finalize_command`의 DB 쓰기가 예외로 실패해 `attempting`인 채로 남았을 때, 재시도 간격만 보고 같은 명령을 다시 실행해 중복 인쇄가 날 수 있었다.

**서버 업로드 매핑**: 서버 `results.status`는 ENUM 7종(`printed`/`dry_run`/`missed`/`failed`/`skipped_no_paper`/`skipped_clock_unsynced`/`skipped_printer_offline`)뿐이다(`server/src/main/resources/db/migration/V1__init.sql:77` [확인됨·코드]). 위 표의 내부 status(`checking`/`attempting`)가 실수로 최종 status 자리에 남아 있으면(예: 유예 만료 시점에 직전 상태가 `checking`이었던 경우) `Executor._coerce_result_status`가 로그를 남기고 `failed`로 강제 매핑해서 올린다 — 내부 상태 문자열이 그대로 서버로 올라가는 경로는 없다.

## 4. 시계 (RTC 없음)

Orange Pi Zero 2W에는 RTC(시계 배터리)가 없다.

- **인터넷이 끊겼지만 재부팅은 없었다** → 시스템 시계가 계속 흐르므로 정상적으로 예약 인쇄한다. (합의 요구사항: 예약 시각에 인터넷이 끊겨도 Pi는 인쇄)
- **정전 등으로 재부팅했는데 인터넷도 없다** → 부팅 후 현재 시각을 믿을 수 없다. **NTP 동기화가 확인되기 전에는 인쇄를 보류한다.** 동기화된 뒤 그 사이 지나간 occurrence는 유예 안이면 실행, 넘었으면 `skipped_clock_unsynced`로 기록한다.
- 동기화 판정은 `timedatectl`의 `NTPSynchronized` [기본값]. 서버 응답의 `serverTime`과 크게 어긋나면 로그에 경고만 남긴다(시각을 서버 시간으로 바꾸지는 않음) [기본값].
- 완전한 오프라인 보장이 필요해지면 **RTC 모듈 DS3231**(40핀 I2C)을 단다 — 선택 부품, PoC에서는 달지 않는다.

## 5. 결과 status

| status | 의미 |
|---|---|
| `printed` | 프린터로 전송 완료 |
| `dry_run` | 용지 정책 `unverified`라 래스터를 보내지 않고 기록만 함 |
| `missed` | 유예 시간 안에 실행 기회가 없었음(Pi 꺼짐, 서비스 중단 등) |
| `failed` | 실행했지만 오류(렌더 없음, 변환 오류, 전송 중 예외 등). `detail`에 원인 |
| `skipped_no_paper` | 용지 정책상 용지 확인이 안 돼 전송하지 않음 |
| `skipped_clock_unsynced` | 시각 미동기 상태로 유예가 지나 실행하지 않음 |
| `skipped_printer_offline` | 프린터에 연결할 수 없었음(USB 장치 없음, BT 연결 실패, 프린터 꺼짐) |

위 7개는 `pi/agent/executor.py`의 `VALID_RESULT_STATUSES`(서버 ENUM 7종을 그대로 복사한 화이트리스트)와 **정확히 일치한다** [확인됨·코드, 2026-09-17]. Pi 내부에서만 도는 `checking`/`attempting`은 이 목록에 없고, 3절 "서버 업로드 매핑"에 적은 대로 종결 시점에 `failed`로 강제 변환된다 — 이 표의 값 밖으로 나가는 업로드는 없다.

결과는 Pi 로컬 SQLite 대기열에 먼저 쌓고, 온라인일 때 서버로 업로드한다. 앱의 이력 화면에 표시된다. **푸시 알림은 PoC 이후.**

## 6. 알려진 한계 (PoC에서 수용)

- **오프라인이 길어지면 날짜·날씨가 오래된 값으로 인쇄된다.** 서버가 PNG를 렌더하고 Pi는 받은 PNG만 인쇄하므로, 인터넷이 끊긴 동안에는 마지막으로 받은 렌더(날짜 변수·날씨 포함)가 그대로 나간다. Pi는 `(formatId, 오늘)` 렌더가 없으면 그 포맷의 최신 렌더를 쓴다.
- 인터넷이 끊긴 동안 앱에서 바꾼 예약·포맷은 반영되지 않는다.
- "지금 인쇄"는 폴링 주기(최대 30초)만큼 늦게 시작된다. 인터넷이 끊겨 있으면 명령 자체가 도착하지 않는다.
- 재부팅 + 오프라인이 겹치면 시각 동기화 전까지 인쇄하지 않는다(4절).

## 7. 저장소(SD카드) 보호

- 보낸 바이트(`.bin`)는 `HARU_DATA_DIR/sent/`에 저장하고 **30일이 지난 것은 지운다**(detox-printer의 "보낸 바이트 전부 저장" 규칙을 SD카드 용량·수명에 맞게 순환 보관으로 바꾼 것).
- PNG 캐시는 예약에서 더 이상 참조하지 않는 오래된 렌더를 정리한다(보관 기준은 M4에서 정함).
- journald 크기 제한, 불필요한 디스크 쓰기 줄이기는 [setup.md](setup.md).
