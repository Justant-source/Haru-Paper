# 데이터 모델 (MariaDB)

> M2 `V1__init.sql`(PoC 단일 사용자) + M6 `V2__users_and_ownership.sql`(계정·소유권, [`auth.md`](auth.md)) + `V3__render_pbm.sql`(1-bpp PBM 컬럼, [`../architecture.md`](../architecture.md) 3.4)의 현재 상태다.
> **`V4__backfill_render_owner.sql`(`renders.owner_user_id` 백필)은 파일만 작성됐고 아직 적용되지 않았다** — 아래 `renders` 절 참고.
> 도메인 정의는 [`../init_plan.md`](../init_plan.md) 6절, 포맷 문서 구조는 [`format-schema.md`](format-schema.md)가 원본이다.
> 컬럼 타입·인덱스·제약은 **[기본값]**. 구현하면서 바꾸면 이 문서를 같이 고친다. **이미 적용된 마이그레이션 파일은 수정하지 않는다 — 바꿀 게 있으면 `V3__...`로 추가한다.**

## 1. 공통 규칙

- **DB**: MariaDB, 프로젝트 전용 컨테이너 `haru-db`. 스키마 이름 `haru_paper`, 사용자 `haru`([`deploy.md`](deploy.md)). 문자셋 `utf8mb4`, collation `utf8mb4_unicode_ci`
- **ID** [기본값]: UUID v4 문자열 `CHAR(36)`. API의 id도 같은 문자열. `pairing_codes.code`(8자 코드)만 예외
- **소유권**(M6): `formats`·`schedules`·`assets`·`renders`·`commands`·`results`에 `owner_user_id CHAR(36) NULL`이 있다. NULL은 M2~M5 시절(로그인 없음)에 만들어진 레거시 행이고, 관리자가 `POST /api/admin/claim-legacy`로 자신의 것으로 가져올 수 있다([`auth.md`](auth.md) 6·7절). `formats`·`schedules`·`assets`는 `users` FK(`ON DELETE CASCADE`), `renders`·`commands`·`results`는 이력 보존을 위해 FK 없이 컬럼만 둔다
- **시각 저장** [기본값]: **순간(instant)은 UTC로 `DATETIME(3)`에 저장**한다.
  - JDBC/Hibernate 시간대를 UTC로 고정(`hibernate.jdbc.time_zone=UTC` 등)
  - API 응답은 ISO-8601 **+09:00 오프셋**으로 변환해서 보낸다
- **예약의 벽시계 값은 KST 그대로**: `schedules.time`(`TIME`), `schedules.date`(`DATE`), `days_of_week`는 **Asia/Seoul 기준 값**이다. 시간대 컬럼은 두지 않는다(KST 고정, init_plan Q9)
- **`targetDate`**: KST 날짜 `DATE`
- **JSON**: MariaDB의 `JSON`은 `LONGTEXT` 별칭이다. `CHECK (JSON_VALID(col))`를 붙인다
- **접근 계층** [기본값]: Spring Data JPA, `spring.jpa.hibernate.ddl-auto=validate`(스키마는 Flyway만 바꾼다)

## 2. Flyway

- 위치: `server/src/main/resources/db/migration/`
- 파일명: `V{n}__{snake_case_설명}.sql` — `V1__init.sql`, `V2__add_xxx.sql` …
- **이미 적용된 마이그레이션 파일은 절대 수정하지 않는다.** 바꿀 게 있으면 새 번호로 추가
- M2 시절(`V1__init.sql`)에는 초기 데이터(`device` 1행, `settings` 기본 위치)를 애플리케이션 시작 시 없으면 생성하는 방식이었다 [기본값: 시작 시 생성]. **M6(`V2`)에서 `device`·`settings`는 DROP되고 `devices`(사용자당 1개, 페어링 시 생성)·`user_settings`(사용자별, 조회 시 채움)로 대체됐다** — 아래 `devices`·`user_settings` 절 참고, 전역 1행 초기 데이터는 더 이상 없다

## 3. 테이블

### `formats`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | |
| `owner_user_id` | CHAR(36) NULL | M6. FK → `users.id` ON DELETE CASCADE. NULL = 레거시(claim-legacy 대상) |
| `name` | VARCHAR(100) NOT NULL | `body.meta.name` 복사본(목록 조회용) |
| `schema_version` | INT NOT NULL | `body.schemaVersion` |
| `body` | JSON NOT NULL | 포맷 문서 전체(`assets` 제외, [`format-schema.md`](format-schema.md)). API 응답 필드 `document` |
| `has_dynamic_blocks` | BOOLEAN NOT NULL | `weather` 블록 존재 여부(렌더 스케줄러용) |
| `created_at` | DATETIME(3) NOT NULL | UTC |
| `updated_at` | DATETIME(3) NOT NULL | UTC. 렌더 최신 여부 판단 기준 |

- 인덱스: `idx_formats_updated_at (updated_at)`
- 동시 수정은 **나중에 쓴 쪽이 이김**(사용자 1명) [기본값]
- 삭제: `schedules`가 참조 중이면 거부(409, [`api.md`](api.md))

### `assets`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | `assetId` |
| `owner_user_id` | CHAR(36) NULL | M6. FK → `users.id` ON DELETE CASCADE |
| `content_type` | VARCHAR(50) NOT NULL | `image/png` \| `image/jpeg` (API 필드 `contentType`) |
| `size_bytes` | INT NOT NULL | ≤ 10MB |
| `width_px` | INT NOT NULL | 원본 크기 |
| `height_px` | INT NOT NULL | |
| `sha256` | CHAR(64) NOT NULL | |
| `path` | VARCHAR(255) NOT NULL | `haru-files` 볼륨 기준 상대 경로 `uploads/{id}.{png|jpg}` |
| `created_at` | DATETIME(3) NOT NULL | |

- 포맷 `body`(JSON) 안에서 `assetId`로 참조하므로 FK를 걸 수 없다.
- 정리 [기본값]: 하루 한 번 모든 `formats.body`를 훑어 **참조되지 않고 생성 24시간이 지난 에셋**을 행·파일 모두 삭제(편집 중 업로드한 이미지를 바로 지우지 않기 위해 24시간 유예).

### `schedules`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | |
| `owner_user_id` | CHAR(36) NULL | M6. FK → `users.id` ON DELETE CASCADE |
| `device_id` | CHAR(36) NULL | M6. FK → `devices.id` ON DELETE SET NULL |
| `format_id` | CHAR(36) NOT NULL | FK → `formats.id` **ON DELETE RESTRICT** |
| `type` | ENUM('recurring','once') NOT NULL | |
| `days_of_week` | VARCHAR(50) NULL | `recurring`만. 비어 있으면 안 됨. 콤마 구분 값 (예: `"MON,TUE,WED"`) - MariaDB SET 타입 대신 JPA 호환성 위해 VARCHAR 사용 |
| `time` | TIME NOT NULL | KST 벽시계 `HH:mm:00` |
| `date` | DATE NULL | `once`만, KST |
| `enabled` | BOOLEAN NOT NULL DEFAULT TRUE | 켜기/끄기 |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | UTC |

- 검증(애플리케이션): `recurring` ⇒ `days_of_week` 1개 이상 & `date` NULL / `once` ⇒ `date` NOT NULL & `days_of_week` NULL
- 인덱스: `idx_schedules_format (format_id)`, `idx_schedules_enabled (enabled)`
- occurrence key(`{scheduleId}@{YYYY-MM-DD}T{HH:mm}`)는 **Pi가 계산**한다. 서버는 저장하지 않고 결과(`results.occurrence_key`)로만 받는다.

### `renders`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | `renderId` |
| `owner_user_id` | CHAR(36) NULL | M6. FK 없음(이력 보존 관례). **2026-09-17까지는 항상 NULL이었다** — `RenderServiceImpl`이 렌더 생성 시 이 컬럼을 채우지 않는 버그였다(다른 소유권 컬럼(`formats`·`schedules`·`assets` 등)은 각 서비스가 채웠는데 렌더만 빠져 있었다). 지금은 렌더 생성 시 포맷에서 `owner_user_id`를 복사한다(`format.getOwnerUserId()`, 렌더는 포맷에서 파생되므로) [확인됨·코드: `RenderServiceImpl.saveRender()` — 이후 생성되는 모든 렌더에 적용]. 기존 NULL 행은 `V4__backfill_render_owner.sql`이 `formats` 조인으로 백필하도록 작성돼 있으나 **아직 적용되지 않았다**(파일만 있는 상태). 포맷이 나중에 삭제된 고아 렌더는 이 백필의 JOIN에 걸리지 않아 계속 NULL로 남는다 — `DeviceSyncController.assertOwnership()`([`auth.md`](auth.md) 4.1절)은 NULL을 레거시로 보고 다운로드를 허용하도록 설계돼 이 경우도 그대로 동작한다 |
| `format_id` | CHAR(36) NOT NULL | FK → `formats.id` ON DELETE CASCADE(파일도 함께 정리) |
| `target_date` | DATE NOT NULL | KST |
| `profile_key` | VARCHAR(100) NOT NULL | 프린터 프로필 식별자. 규약([`../architecture.md`](../architecture.md) 3.4): `{model}-{dpi}-{paperWidthMm}-{printableWidthPx}` (예: `m832-300-110-1300`) |
| `width_px` | INT NOT NULL | = 프로필 `printableWidthPx` |
| `height_px` | INT NOT NULL | 내용 길이 |
| `sha256` | CHAR(64) NOT NULL | PNG 파일 해시 |
| `pbm_sha256` | CHAR(64) NULL | 1-bpp PBM(P4) 파일 해시. `V3` 마이그레이션 이전 렌더거나 PBM 생성이 실패했으면 `NULL`(architecture.md 3.4, 2026-09-17 승인) |
| `path` | VARCHAR(255) NOT NULL | `renders/{id}.png` |
| `pbm_path` | VARCHAR(255) NULL | `renders/{id}.pbm`. `NULL`이면 PBM 없음 |
| `kind` | ENUM('scheduled','preview','command') NOT NULL | [기본값]. `preview`는 저장된 포맷의 `GET …/preview.png`만. 편집본 `POST /api/formats/preview`는 행을 만들지 않는다 |
| `format_updated_at` | DATETIME(3) NOT NULL | 렌더에 쓴 포맷의 `updated_at`(포맷이 바뀌면 stale) |
| `weather_fetched_at` | DATETIME(3) NULL | 동적 포맷일 때 날씨 데이터 시각 |
| `rendered_at` | DATETIME(3) NOT NULL | UTC |

- 인덱스: `idx_renders_lookup (format_id, target_date, profile_key, rendered_at)`
- 같은 `(format_id, target_date, profile_key)`에 여러 행이 있을 수 있다. **가장 최근 `rendered_at`이 유효**
- 정리 규칙은 [`rendering.md`](rendering.md) 5절

### `commands`

"지금 인쇄" 명령. 예약이 아니다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | `commandId` |
| `owner_user_id` | CHAR(36) NULL | M6. FK 없음(이력 보존 관례) |
| `device_id` | CHAR(36) NULL | M6. FK 없음. 명령 생성 시점의 사용자 기기(페어링 전이면 NULL) |
| `type` | ENUM('print_now') NOT NULL | |
| `format_id` | CHAR(36) NOT NULL | (FK 없음 — 포맷 삭제 후에도 이력 보존) |
| `render_id` | CHAR(36) NULL | 명령 생성 시 즉시 렌더한 결과 |
| `paper_confirmed` | BOOLEAN NOT NULL | 앱의 "용지를 눈으로 확인함" 체크 |
| `status` | ENUM('pending','delivered','done','expired') NOT NULL | [기본값] |
| `created_at` | DATETIME(3) NOT NULL | |
| `delivered_at` | DATETIME(3) NULL | 처음 poll 응답에 실린 시각 |
| `completed_at` | DATETIME(3) NULL | 해당 `commandId`의 결과 수신 시각 |

- 상태 전이 [기본값]: `pending` →(poll 응답에 실림) `delivered` →(results 수신) `done`
- `done`/`expired`가 아닌 명령은 **매 poll마다 다시 실어 보낸다**. Pi는 `commandId`로 중복 실행을 막는다.
- **만료** [기본값]: 생성 후 10분 안에 `done`이 안 되면 `expired`. Pi가 오프라인일 때 몇 시간 뒤 뜬금없이 인쇄되지 않게 하기 위해서다. 만료된 명령은 poll 응답에 더 이상 싣지 않는다(규약: [`../architecture.md`](../architecture.md) 3.3, Pi 쪽: [`../pi/agent.md`](../pi/agent.md))
- 인덱스: `idx_commands_status (status, created_at)`

### `results`

Pi가 올리는 실행 결과. **`id` = Pi가 만든 `resultId`** 로 멱등 처리한다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | `resultId` |
| `owner_user_id` | CHAR(36) NULL | M6. FK 없음(이력 보존 관례) |
| `device_id` | CHAR(36) NULL | M6. FK 없음 |
| `occurrence_key` | VARCHAR(80) NULL | 예약 실행일 때 |
| `command_id` | CHAR(36) NULL | 지금 인쇄일 때 |
| `format_id` | CHAR(36) NULL | FK 없음(이력 보존) |
| `render_id` | CHAR(36) NULL | |
| `status` | ENUM('printed','dry_run','missed','failed','skipped_no_paper','skipped_clock_unsynced','skipped_printer_offline') NOT NULL | |
| `detail` | TEXT NULL | |
| `scheduled_at` | DATETIME(3) NULL | UTC |
| `executed_at` | DATETIME(3) NOT NULL | UTC |
| `received_at` | DATETIME(3) NOT NULL | 서버 수신 시각 |

- 인덱스: `idx_results_executed (executed_at)`, `idx_results_occurrence (occurrence_key)`, `idx_results_command (command_id)`

### `users` (M6, `V2__users_and_ownership.sql`)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | |
| `email` | VARCHAR(255) NOT NULL UNIQUE | 로그인 식별자 |
| `password_hash` | VARCHAR(100) NOT NULL | `PasswordEncoderFactories.createDelegatingPasswordEncoder()` |
| `handle` | VARCHAR(20) NOT NULL UNIQUE | 3~20자, `[a-z0-9-]`, 변경 불가([`auth.md`](auth.md) 5절) |
| `display_name` | VARCHAR(50) NOT NULL | |
| `bio` | VARCHAR(300) NULL | |
| `role` | ENUM('user','admin') NOT NULL DEFAULT 'user' | 가입 시 이메일이 `HARU_ADMIN_EMAIL`과 같으면 `admin` |
| `status` | ENUM('active','suspended') NOT NULL DEFAULT 'active' | 정지되면 로그인 불가(`UserPrincipal.isAccountNonLocked()`) |
| `must_change_password` | BOOLEAN NOT NULL DEFAULT FALSE | 관리자가 임시 비밀번호 발급 시 true |
| `created_at` / `updated_at` | DATETIME(3) NOT NULL | UTC |

### `devices` (M6 — 옛 `device` 테이블 대체)

옛 `device`(항상 `id=1`인 단일 행)는 **DROP**됐다. PoC 데이터는 재사용 가치가 없다고 판단했다([`auth.md`](auth.md) 8절). 사용자당 최대 1대(`owner_user_id UNIQUE`).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | |
| `owner_user_id` | CHAR(36) NOT NULL UNIQUE | FK → `users.id` ON DELETE CASCADE |
| `name` | VARCHAR(50) NOT NULL DEFAULT '내 프린터' | 사용자가 `PATCH /api/devices/me`로 변경 |
| `token_hash` | CHAR(64) NOT NULL UNIQUE | Bearer 토큰의 SHA-256(`TokenHasher`). 평문은 저장하지 않는다 |
| `token_issued_at` | DATETIME(3) NOT NULL | |
| `last_poll_at` | DATETIME(3) NULL | |
| `last_seen_ip` | VARCHAR(45) NULL | |
| `agent_version` | VARCHAR(50) NULL | |
| `printer_profile` | JSON NULL | `{model, dpi, paperWidthMm, printableWidthPx}` |
| `printer_status` | JSON NULL | Pi가 보고한 `printerStatus` `{state, detail}` 그대로 |
| `paper_policy` | VARCHAR(20) NULL | Pi가 poll 요청 최상위 `paperPolicy`로 보고한 값(`unverified` \| `status_query` \| `manual_flag`) |
| `paper_state_manual` | BOOLEAN NOT NULL DEFAULT FALSE | 앱의 수동 "용지 장착됨"(H4 실패 시 폴백). API `paperState.loaded` |
| `paper_state_updated_at` | DATETIME(3) NULL | API `paperState.updatedAt` |
| `paper_state_updated_by` | ENUM('app','server') NULL | `PUT /api/device/paper-state` 응답 `updatedBy` [기본값] |
| `created_at` | DATETIME(3) NOT NULL | |

### `pairing_codes` (M6)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `code` | CHAR(8) PK | 대문자+숫자, `I/O/0/1` 제외 |
| `user_id` | CHAR(36) NOT NULL | FK → `users.id` ON DELETE CASCADE |
| `expires_at` | DATETIME(3) NOT NULL | 발급 후 10분 |
| `used_at` | DATETIME(3) NULL | 1회 사용되면 채워짐(재사용 방지) |
| `created_at` | DATETIME(3) NOT NULL | |

### `SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` (M6, Spring Session JDBC 표준 스키마)

세션 저장소. `spring-session-jdbc`의 `schema-mysql.sql`과 동일한 구조를 Flyway로 직접 만든다(`spring.session.jdbc.initialize-schema=never`로 두어 Flyway가 유일한 스키마 원본이 되게 한다). 컬럼은 라이브러리 표준이라 이 문서에서 따로 설명하지 않는다 — [`auth.md`](auth.md) 1절.

### `user_settings` (M6 — 옛 `settings` 대체)

옛 `settings`(전역 키-값 1벌)는 DROP했다. 설정이 사용자별로 바뀌므로 복합키로 새로 만들었다. 기존 값(날씨 위치 등)은 보존하지 않는다 — `claim-legacy` 이후 설정 화면에서 다시 저장한다.

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `user_id` | CHAR(36) | PK(복합), FK → `users.id` ON DELETE CASCADE |
| `setting_key` | VARCHAR(50) | PK(복합) |
| `value` | JSON NOT NULL | |
| `updated_at` | DATETIME(3) NOT NULL | |

키 목록 [기본값]:

| 키 | 값 | 기본값 |
|---|---|---|
| `weather.location` | `{"lat": 37.5663, "lon": 126.9779, "label": "서울시청"}` | `.env`의 `HARU_WEATHER_LAT/LON`(사용자가 처음 설정을 조회할 때 채움) |

날씨 응답 캐시는 DB에 두지 않고 메모리에 둔다([`weather.md`](weather.md)).

## 4. 파일 저장 (볼륨 `haru-files`)

컨테이너 경로 [기본값]: `/data/haru-files`

```
/data/haru-files/
├── uploads/     # 에셋 원본: {assetId}.png | {assetId}.jpg
└── renders/     # 렌더 PNG: {renderId}.png (그레이스케일) + {renderId}.pbm (1-bpp PBM P4, V3~. 없을 수 있음 — pbm_path NULL)
```

- DB 행과 파일은 **같이 만들고 같이 지운다.** 파일 쓰기가 끝난 뒤에 행을 커밋한다.
- `uploads/`는 다시 만들 수 없으므로 백업 대상이다. `renders/`는 다시 렌더할 수 있다([`deploy.md`](deploy.md) 백업 절).
