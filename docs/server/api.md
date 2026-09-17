# API 구현 가이드

> **API 규약(경로·요청·응답 필드)의 원본은 [`../architecture.md`](../architecture.md)** 이다(최초 초안: [`../init_plan.md`](../init_plan.md) 7절).
> 이 문서는 그 규약을 Spring Boot로 **어떻게 구현하는지**를 적는다. 규약과 이 문서가 다르면 규약이 맞고, 이 문서를 고친다.
> 규약에 없는 것은 **[기본값]**(구현 선택)으로 표시한다. 규약 자체를 바꾸거나 넓혀야 하면 `architecture.md`를 먼저 고친다.

## 1. 공통

- 모든 경로는 `/api` 아래. 웹앱과 **같은 origin**(`/` = 웹, `/api` = Spring). `haru-web`(nginx)이 `/api/`를 `haru-api`로 프록시한다 → **CORS 설정 불필요**
- JSON: UTF-8, 필드는 camelCase
- 시각: ISO-8601 **+09:00 오프셋**으로 응답(`2026-09-14T07:00:00.000+09:00`). DB 저장은 UTC([`data-model.md`](data-model.md))
- 날짜(`targetDate`, `date`): `YYYY-MM-DD`(KST). 시각(`time`): `HH:mm`(KST)
- ID: UUID 문자열

## 2. 컨트롤러 묶음 (M6, 실제 13개) [확인됨·코드]

| 컨트롤러 | 경로 | 인증 |
|---|---|---|
| `HealthController` | `GET /api/health` | 없음 |
| `AuthController` | `POST /api/auth/signup`(없음), `POST /api/auth/login`(없음), `POST /api/auth/logout`(세션), `GET /api/auth/me`(세션) | 대부분 세션 |
| `AccountController` | `PATCH /api/account` | 세션 |
| `AdminController` | `GET /api/admin/users`, `POST /api/admin/users/{id}/temp-password`, `POST /api/admin/users/{id}/suspend`, `POST /api/admin/claim-legacy` | **ADMIN** |
| `FormatController` | `/api/formats`, `/api/formats/{id}`, `/api/formats/import`, `/api/formats/{id}/export`, `/api/formats/{id}/preview.png`, `POST /api/formats/preview` | 세션 |
| `AssetController` | `POST /api/assets`, `GET /api/assets/{assetId}` | 세션 |
| `ScheduleController` | `/api/schedules`, `/api/schedules/{id}` | 세션 |
| `PrintNowController` | `POST /api/print-now` | 세션 |
| `HistoryController` | `GET /api/history` | 세션 |
| `DeviceController`(단수, 앱+무인증 혼재) | `GET /api/device`(세션), `PUT /api/device/paper-state`(세션), `POST /api/device/pair`(**없음** — 코드가 인증) | 혼재 |
| `DeviceManagementController`(복수 `/api/devices`) | `GET/PATCH /api/devices/me`, `POST /api/devices/me/token`, `POST /api/devices/pairing-codes` | 세션 |
| `SettingsController` | `GET/PUT /api/settings` | 세션 |
| `DeviceSyncController`(Pi용) | `POST /api/device/poll`, `GET /api/device/snapshot`, `GET /api/device/renders/{renderId}.png`, `GET /api/device/renders/{renderId}.pbm`(1-bpp PBM P4, 2단계 기기용, `architecture.md` 3.4, 2026-09-17 승인 — 구현됨, PBM 없는 렌더는 404), `POST /api/device/results` | **Bearer** + 렌더 다운로드 2종은 컨트롤러가 추가로 소유권 검사(`assertOwnership`, 2026-09-17 — Bearer 필터의 경로 기반 보호와 별개. `NULL` 소유자는 `HARU_OWNERSHIP_STRICT` 플래그로 갈림, 기본값 `false`=허용). [`auth.md`](auth.md) 4.1절 |

인증·계정·기기 관리(`AuthController`~`DeviceManagementController`)의 세션·CSRF 메커니즘, 필드 상세는 [`auth.md`](auth.md)가 원본이다. `DeviceController`(단수)와 `DeviceManagementController`(복수, `/api/devices`)는 이름이 비슷하지만 다른 클래스다 — 헷갈리지 않도록 주의.

## 3. 인증

### 앱용: 세션 로그인 (M6)

M6부터 이메일/비밀번호 로그인 + HttpOnly 세션 쿠키(Spring Session JDBC) + CSRF 더블서밋 쿠키다. "Tailscale이 인증"이던 PoC 시절 모델은 대체됐다 — Tailscale은 여전히 네트워크 경계로 유지하되(외부 노출 없음), 그 안에서도 계정별 로그인이 필요하다. 무인증 경로는 `/api/health`, `/api/auth/signup`, `/api/auth/login`, `POST /api/device/pair`뿐이다. `haru-web`은 기본적으로 `127.0.0.1`에만 바인딩되고, HTTPS는 `tailscale serve`로 연다. 사용자 승인 하에 Tailscale IP:포트 HTTP를 추가로 열 수 있다([`deploy.md`](deploy.md) 3.1절). `0.0.0.0`은 금지. 세션·CSRF 메커니즘 상세는 [`auth.md`](auth.md) 1~3절.

### Pi용: `Authorization: Bearer <기기별 토큰>`

- `OncePerRequestFilter`(`DeviceTokenAuthFilter`) 하나로 처리 [확인됨·코드]
- **대상 경로를 정확히 나열한다. `/api/device/**` 접두사로 걸면 안 된다.** `GET /api/device`, `PUT /api/device/paper-state`는 **세션 인증(앱용)**, `POST /api/device/pair`는 **무인증**(코드 자체가 1회용 비밀)이다.
  - 인증 대상: `POST /api/device/poll`, `GET /api/device/snapshot`, `GET /api/device/renders/*`, `POST /api/device/results`
- **M6부터 서버 `.env`의 단일 `HARU_DEVICE_TOKEN`은 없다.** 토큰은 기기별로 DB에 SHA-256 해시로 저장(`devices.token_hash`)하고, 요청 헤더의 토큰을 해시해 조회한다([`auth.md`](auth.md) 4절)
- 헤더 없음·불일치 → 401(`application/problem+json`)
- 토큰은 로그에 찍지 않는다

## 4. 에러 응답 형식 [기본값]

규약([`../architecture.md`](../architecture.md) 4.1): Spring 6의 `ProblemDetail`(RFC 9457/7807, `application/problem+json`) — `{type, title, status, detail, instance}` + 검증 오류일 때 확장 필드 `errors: [{path, message}]`.

```json
{
  "type": "about:blank",
  "title": "Validation failed",
  "status": 422,
  "detail": "format document is invalid",
  "instance": "/api/formats",
  "errors": [
    { "path": "rows[0].slots[0].block.type", "message": "unknown block type: html" },
    { "path": "style.fontFamily", "message": "must be one of [Pretendard, Noto Sans KR]" }
  ]
}
```

| 상태 | 언제 |
|---|---|
| 400 | JSON 파싱 실패, 잘못된 쿼리 파라미터 |
| 401 | 세션 로그인 필요(앱), 또는 Pi 토큰 없음·불일치 |
| 403 | 로그인은 됐으나 권한 없음(예: `/api/admin/**`에 비관리자) |
| 404 | 없는 id |
| 409 | 예약이 참조 중인 포맷 삭제(`errors`에 예약 id 목록). 프린터 프로필이 아직 없어도 409를 쓰지 않는다(4.1절) |
| 413 | 업로드·가져오기 크기 초과 |
| 415 | 허용하지 않는 MIME |
| 422 | 스키마 검증 실패([`format-schema.md`](format-schema.md)), 예약 검증 실패, 지원하지 않는 `schemaVersion` |
| 500 | 그 외(렌더 실패 포함, `detail`에 원인 요약) |

### 4.1 프린터 프로필이 아직 없을 때

Pi가 한 번도 poll하지 않았으면 `devices.printer_profile`이 없다. 이때는 **기본 프로필** `{model:"m832", dpi:300, paperWidthMm:110, printableWidthPx:1300}`로 렌더한다 [기본값]. 실제 프로필이 들어와 `profile_key`가 달라지면 렌더 스케줄러가 다시 렌더한다([`rendering.md`](rendering.md)). 그래서 미리보기·지금 인쇄는 409 없이 동작한다.

## 5. 앱용 엔드포인트 구현 메모

### 포맷

| 요청 | 구현 |
|---|---|
| `GET /api/formats` | 요약 목록: `[{id, name, author, forkedFrom, hasDynamicBlocks, updatedAt}]`(규약), `updatedAt` 내림차순 [기본값] |
| `POST /api/formats` | 본문 = 포맷 문서. 엄격 검증(`assets` 있으면 422, 없는 `assetId` 422) → 201 `{id, document, hasDynamicBlocks, createdAt, updatedAt}` |
| `GET /api/formats/{id}` | `{id, document, hasDynamicBlocks, createdAt, updatedAt}` |
| `PUT /api/formats/{id}` | 전체 교체. 나중에 쓴 쪽이 이김 [기본값]. `meta.forkedFrom`은 클라이언트 값 무시하고 기존 값 유지 |
| `DELETE /api/formats/{id}` | 참조하는 `schedules`가 있으면 409. 없으면 삭제 + 렌더 행·파일 정리 → 204 |
| `POST /api/formats/import` | [`format-schema.md`](format-schema.md) 5절 순서 그대로. 요청 본문 최대 30MB [기본값](에셋 base64 포함) → 201 |
| `GET /api/formats/{id}/export` | `assets` 내장 JSON, `Content-Disposition: attachment; filename="<name>.haru-format.json"` |
| `GET /api/formats/{id}/preview.png` | 저장된 포맷(소유자만, 아니면 404). 쿼리 `date=YYYY-MM-DD`(선택, 기본 KST 오늘). **그 포맷 소유자의 기기 프로필(기기 없으면 기본 프로필)**로 즉시 렌더(`kind=preview`) → `image/png`. 같은 (포맷 `updated_at`, date, profile_key) 렌더가 10분 안에 있으면 재사용 [기본값]. **(2026-09-17)** 이전에는 "아무 기기나 하나"인 전역 폴백 프로필로 렌더했다 — 멀티유저에서 다른 사용자 기기의 폭으로 잘못 렌더될 수 있던 것을 소유자 기준으로 고쳤다(`PrinterProfileProvider.getCurrentProfile(ownerUserId)`) |
| `POST /api/formats/preview` | **저장하지 않은 편집본** 미리보기. 본문 = 포맷 문서(`assets` 없음), 쿼리 `date` 선택. `POST /api/formats`와 같은 엄격 검증(실패 422) → **요청한 로그인 사용자 소유 기기의 프로필**(기기 없으면 기본 프로필)로 렌더 → `200 image/png`. **`formats`·`renders` 행과 렌더 파일을 남기지 않는다**(PNG 바이트를 바로 응답) [기본값]. 앱 편집기가 입력이 멈춘 뒤 1초 디바운스로 부른다. **(2026-09-17)** `renderEphemeral`이 `ownerUserId` 인자를 받도록 바뀌어, 이전의 "아무 기기나 하나" 전역 폴백 프로필 대신 요청자 자신의 기기 프로필을 쓴다(세션 인증은 이 절 헤더대로 기존과 동일하게 필수 — 미로그인 401) |

### 에셋

- `POST /api/assets`: `multipart/form-data`, 필드 이름 `file` [기본값]
- MIME `image/png`, `image/jpeg`만(파일 매직 바이트로 확인) → 아니면 415
- 크기 10MB(`HARU_UPLOAD_MAX_MB`) → 초과 413. Spring `spring.servlet.multipart.max-file-size`와 nginx `client_max_body_size`를 **둘 다** 맞춘다
- 응답 201 `{assetId, contentType, widthPx, heightPx, sizeBytes}` (DB 컬럼 `assets.content_type`)
- 원본을 그대로 저장한다. 크기 조정은 렌더할 때 한다
- `GET /api/assets/{assetId}`: 업로드 원본을 `Content-Type: <content_type>`으로 반환(편집기 썸네일용). 없으면 404. `Cache-Control: private, max-age=86400` [기본값](에셋은 id별로 불변)

### 예약

| 요청 | 구현 |
|---|---|
| `GET /api/schedules` | **내 것만**(켜짐/꺼짐 모두 포함, `architecture.md` 4.2 규약) — M2 시절 "전체 목록"이던 서술을 M6 소유권 스코핑에 맞춰 정정 |
| `POST /api/schedules` | 본문: init_plan 6.2 형태에서 `id` 제외. 검증: `formatId`가 존재하고 소유자여야 함(아니면 404 — `formatId`의 `owner_user_id`가 NULL인 경우는 `HARU_OWNERSHIP_STRICT`를 따름: 기본 `false`면 허용, `true`면 404, [`auth.md`](auth.md) 7.1절), `time` `HH:mm`, `recurring`이면 `daysOfWeek` 1개 이상, `once`면 `date` 필수이고 **과거 시각이면 422** [기본값] → 201 |
| `PUT /api/schedules/{id}` | 전체 교체(`formatId` 소유권 검사는 `POST`와 동일 — strict일 때 소유자가 NULL인 포맷은 404). 켜기/끄기도 `enabled`를 바꾼 PUT |
| `DELETE /api/schedules/{id}` | 204 |

예약·포맷이 바뀌면 렌더 스케줄러를 즉시 한 번 깨운다([`rendering.md`](rendering.md)).

### 지금 인쇄

- `POST /api/print-now` 본문 `{formatId, paperConfirmed}`
- 처리: 포맷 존재 확인 → **즉시 렌더**(`targetDate` = KST 오늘, `kind=command`) → `commands` 행 생성(`pending`) → **202** `{commandId, formatId, renderId, paperConfirmed, status: "pending", createdAt}`(규약)
- `paperConfirmed=false`여도 서버는 거절하지 않고 그대로 전달한다 [기본값]. 용지 정책 판단은 Pi 몫이다(`unverified` 정책이면 Pi가 `skipped_no_paper`로 기록). 앱 UI는 체크를 요구한다.
- 상태 `pending → delivered → done`, 생성 후 10분 안에 `done`이 안 되면 `expired` [기본값] ([`data-model.md`](data-model.md) `commands`). 만료 처리는 poll 처리 시(또는 주기 작업) `created_at + 10분 < 지금`인 `pending`·`delivered`를 `expired`로 바꾼다

### 이력

- `GET /api/history?limit=50&before=<executedAt ISO>` [기본값]
- `executedAt` 내림차순. 포맷이 아직 있으면 `formatName`을 붙여 준다
- 각 항목에 `source`: `occurrenceKey`가 있으면 `"schedule"`, `commandId`가 있으면 `"command"`(규약)

### 기기

- `GET /api/device` 응답 [기본값]:

```json
{
  "deviceId": "3f0c1a2b-...(uuid)",
  "online": true,
  "lastPollAt": "2026-09-14T06:59:40.000+09:00",
  "agentVersion": "0.1.0",
  "printerProfile": { "model": "m832", "dpi": 300, "paperWidthMm": 110, "printableWidthPx": 1300 },
  "printerStatus": { "state": "ok", "detail": "" },
  "paperPolicy": "unverified",
  "paperState": { "loaded": false, "updatedAt": null, "updatedBy": null }
}
```

- `online` = `lastPollAt`이 90초(폴링 30초 × 3) 이내 [기본값]
- `deviceId` = `devices.id`(UUID, M6부터 사용자당 1개 — 페어링 전이면 `null`) [확인됨·코드: `DeviceController.getDevice()`]
- `printerStatus` = Pi가 poll로 보고한 `{state, detail}` 그대로(`devices.printer_status`). `state`: `ok | offline | error | unknown`
- `paperPolicy` = Pi가 poll 요청 **최상위 필드**로 보고한 값(`devices.paper_policy`). 아직 보고 전이면 `null`
- `paperState` = `{loaded, updatedAt, updatedBy}` — `loaded`는 DB 컬럼 `devices.paper_state_manual`, `updatedAt`은 `devices.paper_state_updated_at`, `updatedBy`는 `devices.paper_state_updated_by`
- `PUT /api/device/paper-state` 본문 `{loaded}` → `paper_state_manual` 갱신 → 200 `{loaded, updatedAt, updatedBy: "app"}`. `manual_flag` 정책에서 프린터 오류 결과(`failed`, `skipped_printer_offline`)를 받아 서버가 끌 때는 `updatedBy: "server"`로 기록 [기본값]

### 설정

- `GET /api/settings` → `{weather: {lat, lon, label}}`
- `PUT /api/settings` → 같은 형태. `lat` −90~90, `lon` −180~180, `label` 1~50자. 바뀌면 날씨 캐시 무효화 + 동적 포맷 재렌더 트리거

## 6. Pi용 엔드포인트 구현 메모

### `POST /api/device/poll` (30초마다)

요청 `{agentVersion, printerProfile, printerStatus, paperPolicy, snapshotHash}` → 처리 순서:

1. `device` 행 갱신(`last_poll_at`, `agent_version`, `printer_profile`, `printer_status`, `paper_policy`)
2. 프로필이 이전과 다르면(`profile_key` 변경) 렌더 스케줄러 트리거
3. 현재 스냅샷의 `snapshotHash` 계산(6.1절) → `snapshotChanged = (요청 hash != 현재 hash)`
4. 만료 처리(`created_at + 10분 < 지금`인 `pending`·`delivered` → `expired`) 후, 남은 `pending`·`delivered`를 `commands[]`로 싣고 `pending`은 `delivered`로 표시
5. 응답:

```json
{
  "serverTime": "2026-09-14T06:59:40.123+09:00",
  "snapshotHash": "9f2c...",
  "snapshotChanged": false,
  "commands": [ { "commandId": "…", "type": "print_now", "formatId": "…", "renderId": "…", "sha256": "…",
                  "paperConfirmed": true, "createdAt": "…" } ],
  "paperState": { "loaded": false, "updatedAt": null, "updatedBy": null },
  "pollIntervalSec": 30
}
```

- `commands[].sha256`: 명령 렌더 파일 해시. 명령 렌더는 스냅샷 `renders`에 없을 수 있어 Pi가 이 값으로 직접 검증한다(규약)
- `pollIntervalSec`: 서버 `.env`의 `HARU_POLL_INTERVAL_SEC`(기본 30) 값을 반환 [기본값]. Pi는 응답 값이 있으면 따르고, 응답이 없거나 오프라인이면 자기 `pi/.env` 값을 쓴다(규약)
- `serverTime`: Pi 시계 이상 감지용(시각 동기화는 NTP).

### `GET /api/device/snapshot`

`snapshotChanged`일 때만 Pi가 부른다. 응답:

```json
{
  "snapshotHash": "9f2c...",
  "generatedAt": "2026-09-14T06:59:41.000+09:00",
  "schedules": [ { "id": "…", "formatId": "…", "type": "recurring", "daysOfWeek": ["MON"], "time": "07:00", "date": null, "enabled": true } ],
  "renders": [ { "renderId": "…", "formatId": "…", "targetDate": "2026-09-14", "sha256": "…", "widthPx": 1300, "renderedAt": "…",
                 "url": "/api/device/renders/….png",
                 "urlPbm": "/api/device/renders/….pbm", "sha256Pbm": "…" } ]
}
```

포함 규칙 [기본값]:
- `schedules`: **전체**(꺼진 것 포함, `enabled`로 Pi가 거름)
- `renders`: 켜진 예약이 참조하는 포맷마다, 현재 `profile_key`로
  - 지금부터 36시간 안 occurrence의 `(formatId, targetDate)`별 최신 렌더(보통 KST 오늘·내일)
  - 날짜와 무관한 그 포맷의 최신 렌더 1개(Pi 오프라인 폴백용)
- 중복 `renderId`는 한 번만

### 6.1 `snapshotHash` 계산 [기본값]

결정적이어야 한다(같은 내용이면 항상 같은 값).

1. `schedules`를 `id` 오름차순 정렬. 각 항목은 `{id, formatId, type, daysOfWeek(MON→SUN 순서), time, date, enabled}`만
2. `renders`를 `(formatId, targetDate, renderId)` 오름차순 정렬. 각 항목은 `{renderId, formatId, targetDate, sha256}`만(`url`, `widthPx`, `renderedAt`, `generatedAt`은 해시에서 제외)
3. `{"schedules": [...], "renders": [...]}`를 **키 정렬 + 공백 없는** JSON으로 직렬화(UTF-8)
4. SHA-256 hex 소문자

사용자 1명 규모라 매 poll마다 계산해도 된다. 필요해지면 캐시한다.

### `GET /api/device/renders/{renderId}.png`

- `image/png`, `ETag: "<sha256>"`, `Cache-Control: private, max-age=31536000`(2026-09-17까지는 `public` — 개인 인쇄물이라 공유 캐시에 두지 않도록 바꿨다) [확인됨·코드]
- 정리돼서 없으면 404(Pi는 다음 스냅샷을 기다린다)
- **소유권 검사(2026-09-17)**: `Authorization: Bearer` 필터를 통과했더라도, 요청한 `renderId`의 `owner_user_id`가 이 기기의 소유자와 다르면 404(`assertOwnership`, [`auth.md`](auth.md) 4.1절). `.pbm`도 동일하다. `owner_user_id`가 NULL(레거시 렌더, V4 백필 이전 또는 포맷 삭제된 고아 렌더)이면 `HARU_OWNERSHIP_STRICT`(기본 `false`)를 따른다: `false`면 허용(경고 로그), `true`면 404(`log.error`에 `RENDER_OWNER_NULL`) — 켜는 시점·절차는 [`auth.md`](auth.md) 4.1절·[`deploy.md`](deploy.md) 7.2절.

### `POST /api/device/results`

요청 [기본값]: `{"results": [ {resultId, occurrenceKey, commandId, formatId, renderId, status, detail, scheduledAt, executedAt}, ... ]}`

**멱등 처리**:
- 각 `resultId`에 대해 `INSERT ... ON DUPLICATE KEY` 무시(첫 수신 값 유지)
- 같은 `resultId`인데 내용이 다르면 경고 로그만 남기고 첫 값 유지
- `commandId`가 있으면 해당 명령을 `done`으로
- `devices.paper_policy = manual_flag`이고 새로 받은 결과의 `status`가 `failed` 또는 `skipped_printer_offline`이면 수동 용지 상태를 끈다(`paper_state_manual=false`, `paper_state_updated_by='server'`) — 규약 [`../architecture.md`](../architecture.md) 3.7 [기본값]
- 응답 200 `{"accepted": [...resultId], "duplicates": [...resultId]}`

Pi는 응답을 못 받았으면 같은 묶음을 그대로 다시 보내면 된다.

## 7. curl 테스트 시나리오 (M2·M6 통과 조건용)

```bash
BASE=https://justant-server2.tail2b65d1.ts.net   # tailscale serve 적용 전에는 http://127.0.0.1:<HARU_WEB_BIND 포트>
JAR=cookies.txt

# 0. 가입 → 로그인(세션 쿠키 저장) → CSRF 토큰 쿠키 읽기 → 이후 쓰기 요청에 헤더로 되돌려 보낸다
curl -sS -c "$JAR" -X POST "$BASE/api/auth/signup" -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"1234567890","handle":"tester","displayName":"테스터"}'
curl -sS -c "$JAR" -b "$JAR" -X POST "$BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d '{"email":"test@example.com","password":"1234567890"}'
CSRF=$(grep XSRF-TOKEN "$JAR" | awk '{print $NF}')
# 이후 POST/PUT/PATCH/DELETE에는 -b "$JAR" -H "X-XSRF-TOKEN: $CSRF" 를 붙인다(GET은 필요 없음)

# 1. 기기 토큰 발급(Pi TOKEN — 응답의 token 필드는 이번 한 번만 표시된다)
TOKEN=$(curl -sS -b "$JAR" -H "X-XSRF-TOKEN: $CSRF" -X POST "$BASE/api/devices/me/token" | jq -r .token)
```

| # | 시나리오 | 기대 |
|---|---|---|
| 1 | `GET $BASE/api/health` | 200(무인증) |
| 1-1 | 로그인 없이 `GET /api/formats` | 401 |
| 2 | `POST /api/assets`(`-F file=@test.png`, 세션+CSRF) | 201, `assetId` |
| 3 | 11MB 파일 업로드 / `.gif` 업로드 | 413 / 415 |
| 4 | 행/슬롯 구조(v2)에 블록 4종(text·image·dateHeader·weather)으로 `POST /api/formats` | 201, `id` |
| 5 | `rows[0].slots[0].block.type="html"` 또는 스타일 키 `color` | 422, `errors[].path` 포함 |
| 6 | `GET /api/formats/{id}`, `PUT`로 이름 변경 | 200, 변경 반영 |
| 7 | `GET /api/formats/{id}/preview.png -o p.png` | PNG, **폭 = `printableWidthPx`(기본 1300)**, 한글·날짜 변수 정상(육안) |
| 8 | export → 파일 그대로 import | 201, **새 id**, `meta.forkedFrom` 채워짐, `assetId` 새 값 |
| 9 | `schemaVersion: 99` import | 422 |
| 10 | 반복 예약·일회성 예약 생성 / `daysOfWeek` 빈 반복 예약 / 과거 일회성 | 201 / 422 / 422 |
| 11 | 예약이 참조 중인 포맷 `DELETE` | 409 |
| 12 | 토큰 없이 `POST /api/device/poll` | 401 |
| 13 | 세션 없이 `GET /api/device` | 401(M6부터 세션 필요, PoC 시절의 "200 앱용 무인증"에서 바뀜) |
| 13-1 | 편집본으로 `POST /api/formats/preview` / 잘못된 블록 타입 | `200 image/png`(DB 행·파일 증가 없음) / 422 |
| 13-2 | `GET /api/assets/{assetId}` (2번에서 받은 id) | 200, 원본 `Content-Type` |
| 14 | 토큰으로 poll(`snapshotHash: null`) | 200, `snapshotChanged: true` |
| 15 | `GET /api/device/snapshot` → `renders[0].url` 다운로드 | PNG `sha256` = 스냅샷 값 |
| 16 | 받은 hash로 다시 poll / 예약 하나 수정 후 poll | `false` / `true` |
| 17 | `POST /api/print-now` → 다음 poll | 202 `{commandId, …, status: "pending"}` / `commands[]`에 `type: "print_now"`, `sha256` 포함 |
| 18 | `POST /api/device/results`(그 `commandId` 결과) 두 번 | 1회차 `accepted`, 2회차 `duplicates`, `/api/history`에 1건만, 명령 `done` |
| 19 | 명령 생성 후 10분 동안 결과 없음 | 이후 poll에 실리지 않음(`expired`) |
| 20 | `PUT /api/device/paper-state {loaded:true}` → poll | `paperState.loaded: true` |
| 21 | `PUT /api/settings`로 위치 변경 → 날씨 포맷 미리보기 | 새 위치 기준 값(또는 [`weather.md`](weather.md) 실패 표시) |
| 22 | 백업 컨테이너 1회 실행 | 백업 파일 생성([`deploy.md`](deploy.md)) |
