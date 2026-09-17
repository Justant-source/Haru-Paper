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

| 정책 | 언제 쓰나 | 예약(무인) 인쇄 | "지금 인쇄"(앱 명령) |
|---|---|---|---|
| **`unverified`** (현재) | H4 통과 전 | **래스터를 보내지 않는다.** 렌더 선택·변환까지 하고 `dry_run`으로 기록 | 명령의 `paperConfirmed=true`(앱에서 "프린터 앞에서 용지를 눈으로 확인함" 체크)일 때만 전송. 아니면 `skipped_no_paper` |
| `status_query` | H4 통과 후(**과도기 동작 — 아래 참고**) | 프린터 연결(`status().state == "ok"`)만으로는 인쇄하지 않는다. `PrinterStatus`에 용지 필드가 없어 "용지 있음"을 확인할 방법이 아직 없으므로 **현재는 항상 `skipped_no_paper`**(연결이 살아 있어도) | 같은 이유로 항상 `skipped_no_paper`. `paperConfirmed`는 이 정책에서 아직 참고되지 않는다 |
| `manual_flag` | H4 실패 시 폴백 | 서버의 수동 "용지 장착됨"(폴링 응답 `paperState`, `kv.paperState`)이 켜져 있을 때만 전송. `paperState` 키가 없거나 JSON 파싱이 실패해도 **무조건 `skipped_no_paper`**(fail-closed) — `loaded=true`일 때만 전송 | 같은 조건, 또는 `paperConfirmed=true` |

### `status_query`가 지금 인쇄를 전혀 하지 않는 이유(과도기)

`printer/m832/driver.py`의 `status()`는 "장치를 열 수 있는가"만 보고하고 `PrinterStatus`(`printer/__init__.py`)에는 용지 유무 필드가 아직 없다(H4 미통과, [printer-m832.md](printer-m832.md) 5절). "용지가 명시적으로 있다"를 확인할 방법이 없는 이상 `_check_paper_policy`는 fail-closed 원칙에 따라 항상 `skipped_no_paper`를 반환한다 — **`HARU_PAPER_POLICY=status_query`로 전환하면 예약·"지금 인쇄" 모두 인쇄가 전혀 되지 않는다는 뜻**이다. H4가 통과해 `PrinterStatus`에 용지 필드가 생기면 그 필드로 판단하도록 이 분기를 바꾼다. 그 전까지 실제로 인쇄가 필요하면 `unverified`(지금 인쇄만, `paperConfirmed` 체크) 또는 `manual_flag`를 쓴다.

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
