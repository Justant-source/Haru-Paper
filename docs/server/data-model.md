# 데이터 모델 (MariaDB)

> M2에서 `V1__init.sql`로 만들 테이블 초안이다. 도메인 정의는 [`../init_plan.md`](../init_plan.md) 6절, 포맷 문서 구조는 [`format-schema.md`](format-schema.md)가 원본이다.
> 컬럼 타입·인덱스·제약은 **[기본값]**. 구현하면서 바꾸면 이 문서를 같이 고친다.

## 1. 공통 규칙

- **DB**: MariaDB, 프로젝트 전용 컨테이너 `haru-db`. 스키마 이름 `haru_paper`, 사용자 `haru`([`deploy.md`](deploy.md)). 문자셋 `utf8mb4`, collation `utf8mb4_unicode_ci`
- **ID** [기본값]: UUID v4 문자열 `CHAR(36)`. API의 id도 같은 문자열. `device`, `settings`만 예외
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
- 초기 데이터(`device` 1행, `settings` 기본 위치)는 `V1__init.sql` 또는 애플리케이션 시작 시 없으면 생성 [기본값: 시작 시 생성 — 기본 위치를 `.env`(`HARU_WEATHER_LAT/LON`)에서 읽기 위해]

## 3. 테이블

### `formats`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | CHAR(36) PK | |
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
| `format_id` | CHAR(36) NOT NULL | FK → `formats.id` **ON DELETE RESTRICT** |
| `type` | ENUM('recurring','once') NOT NULL | |
| `days_of_week` | SET('MON','TUE','WED','THU','FRI','SAT','SUN') NULL | `recurring`만. 비어 있으면 안 됨 |
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
| `format_id` | CHAR(36) NOT NULL | FK → `formats.id` ON DELETE CASCADE(파일도 함께 정리) |
| `target_date` | DATE NOT NULL | KST |
| `profile_key` | VARCHAR(100) NOT NULL | 프린터 프로필 식별자. 규약([`../architecture.md`](../architecture.md) 3.4): `{model}-{dpi}-{paperWidthMm}-{printableWidthPx}` (예: `m832-300-110-1300`) |
| `width_px` | INT NOT NULL | = 프로필 `printableWidthPx` |
| `height_px` | INT NOT NULL | 내용 길이 |
| `sha256` | CHAR(64) NOT NULL | PNG 파일 해시 |
| `path` | VARCHAR(255) NOT NULL | `renders/{id}.png` |
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

### `device`

Pi 1대(PoC). **항상 1행**(`id = 1`). 데이터 모델에 기기 개념을 남겨 두는 이유는 init_plan Q1(나중에 여러 대로 확장 여지).

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | TINYINT PK | 항상 1 |
| `last_poll_at` | DATETIME(3) NULL | |
| `agent_version` | VARCHAR(50) NULL | |
| `printer_profile` | JSON NULL | `{model, dpi, paperWidthMm, printableWidthPx}` |
| `printer_status` | JSON NULL | Pi가 보고한 `printerStatus` `{state, detail}` 그대로 |
| `paper_policy` | VARCHAR(20) NULL | Pi가 poll 요청 최상위 `paperPolicy`로 보고한 값(`unverified` \| `status_query` \| `manual_flag`) |
| `paper_state_manual` | BOOLEAN NOT NULL DEFAULT FALSE | 앱의 수동 "용지 장착됨"(H4 실패 시 폴백). API `paperState.loaded` |
| `paper_state_updated_at` | DATETIME(3) NULL | API `paperState.updatedAt` |
| `paper_state_updated_by` | ENUM('app','server') NULL | `PUT /api/device/paper-state` 응답 `updatedBy` [기본값] |

### `settings`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `setting_key` | VARCHAR(50) PK | |
| `value` | JSON NOT NULL | |
| `updated_at` | DATETIME(3) NOT NULL | |

키 목록 [기본값]:

| 키 | 값 | 기본값 |
|---|---|---|
| `weather.location` | `{"lat": 37.5663, "lon": 126.9779, "label": "서울시청"}` | `.env`의 `HARU_WEATHER_LAT/LON` |

날씨 응답 캐시는 DB에 두지 않고 메모리에 둔다([`weather.md`](weather.md)).

## 4. 파일 저장 (볼륨 `haru-files`)

컨테이너 경로 [기본값]: `/data/haru-files`

```
/data/haru-files/
├── uploads/     # 에셋 원본: {assetId}.png | {assetId}.jpg
└── renders/     # 렌더 PNG: {renderId}.png (그레이스케일)
```

- DB 행과 파일은 **같이 만들고 같이 지운다.** 파일 쓰기가 끝난 뒤에 행을 커밋한다.
- `uploads/`는 다시 만들 수 없으므로 백업 대상이다. `renders/`는 다시 렌더할 수 있다([`deploy.md`](deploy.md) 백업 절).
