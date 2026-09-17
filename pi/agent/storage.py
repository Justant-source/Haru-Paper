"""로컬 SQLite 저장소. docs/pi/agent.md 5절."""

from __future__ import annotations

import json
import logging
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

from .clock import now_iso

logger = logging.getLogger(__name__)

# 1 = 최초(2026-09-17 이전), 2 = 재시도·결과 재구성 컬럼 추가(.temp/05 설계).
# 기록용일 뿐 마이그레이션 판정 근거는 아니다(_migrate 참고).
SCHEMA_VERSION = 2


class Storage:
    """SQLite 저장소. 테이블 5개: snapshot, renders, executed_occurrences, commands, results_queue, kv."""

    def __init__(self, db_path: Path):
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()
        self._migrate()

    def _init_schema(self):
        """스키마 생성 (없으면)."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()

            # snapshot 테이블
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS snapshot (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    snapshot_hash TEXT,
                    fetched_at TEXT,
                    json TEXT
                )
            """
            )

            # renders 테이블
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS renders (
                    render_id TEXT PRIMARY KEY,
                    format_id TEXT,
                    target_date TEXT,
                    sha256 TEXT,
                    path TEXT,
                    downloaded_at TEXT,
                    verified INTEGER
                )
            """
            )

            # executed_occurrences 테이블
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS executed_occurrences (
                    occurrence_key TEXT PRIMARY KEY,
                    status TEXT,
                    result_id TEXT,
                    first_attempt_at TEXT,
                    last_attempt_at TEXT,
                    attempts INTEGER,
                    final INTEGER
                )
            """
            )

            # commands 테이블
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS commands (
                    command_id TEXT PRIMARY KEY,
                    format_id TEXT,
                    render_id TEXT,
                    sha256 TEXT,
                    paper_confirmed INTEGER,
                    created_at TEXT,
                    received_at TEXT,
                    status TEXT,
                    result_id TEXT
                )
            """
            )

            # results_queue 테이블
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS results_queue (
                    result_id TEXT PRIMARY KEY,
                    payload_json TEXT,
                    created_at TEXT,
                    uploaded_at TEXT,
                    upload_attempts INTEGER
                )
            """
            )

            # kv 테이블 (key-value)
            cursor.execute(
                """
                CREATE TABLE IF NOT EXISTS kv (
                    key TEXT PRIMARY KEY,
                    value TEXT
                )
            """
            )

            conn.commit()

    def _migrate(self) -> None:
        """기존 DB(상시 구동 중인 Pi)를 파괴하지 않고 컬럼만 더한다(.temp/05 설계 5절).

        SQLite의 ADD COLUMN은 기본값(DEFAULT) 없이 붙이면 기존 행에 NULL이 들어가고
        테이블 재작성이 없다(즉시 완료, 데이터 이동 없음). `PRAGMA table_info`로 실제
        컬럼을 보고 없는 것만 붙이므로, user_version이 어긋나 있어도(수동 조작·롤백
        후 재배포) 안전하게 여러 번 돌 수 있다 — user_version은 기록용이고 판정
        근거가 아니다. 새 컬럼은 옛 코드가 읽지 않으므로 구현을 되돌려도 DB는 그대로
        돈다(DROP 불필요).
        """
        migrations: dict[str, list[tuple[str, str]]] = {
            "executed_occurrences": [
                ("format_id", "TEXT"),  # 결과 payload 재구성(과제 3)
                ("render_id", "TEXT"),  # 〃
                ("scheduled_at", "TEXT"),  # 〃 (ISO-8601 +09:00)
                ("detail", "TEXT"),  # 〃 (실패 사유)
            ],
            "commands": [
                ("attempts", "INTEGER"),  # 재시도 횟수
                ("last_attempt_at", "TEXT"),  # 재시도 간격 판정
                ("detail", "TEXT"),  # 결과 payload
            ],
        }
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            for table, columns in migrations.items():
                cursor.execute(f"PRAGMA table_info({table})")
                existing_cols = {row[1] for row in cursor.fetchall()}
                for col_name, col_type in columns:
                    if col_name not in existing_cols:
                        cursor.execute(f"ALTER TABLE {table} ADD COLUMN {col_name} {col_type}")
                        logger.info(f"Migrated: added column {table}.{col_name}")
            cursor.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
            conn.commit()

    def get_snapshot(self) -> Optional[dict]:
        """마지막 스냅샷 조회."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT snapshot_hash, fetched_at, json FROM snapshot WHERE id = 1")
            row = cursor.fetchone()
            if row:
                return {
                    "snapshot_hash": row[0],
                    "fetched_at": row[1],
                    "json": json.loads(row[2]),
                }
            return None

    def save_snapshot(self, snapshot_hash: str, snapshot_json: dict):
        """스냅샷 저장."""
        now = datetime.now().isoformat()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            # id=1 고정이므로 replace
            cursor.execute(
                """
                REPLACE INTO snapshot (id, snapshot_hash, fetched_at, json)
                VALUES (1, ?, ?, ?)
                """,
                (snapshot_hash, now, json.dumps(snapshot_json)),
            )
            conn.commit()

    def get_render(self, render_id: str) -> Optional[dict]:
        """렌더 조회."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT render_id, format_id, target_date, sha256, path, downloaded_at, verified
                FROM renders WHERE render_id = ?
                """,
                (render_id,),
            )
            row = cursor.fetchone()
            if row:
                return {
                    "render_id": row[0],
                    "format_id": row[1],
                    "target_date": row[2],
                    "sha256": row[3],
                    "path": row[4],
                    "downloaded_at": row[5],
                    "verified": row[6],
                }
            return None

    def save_render(
        self,
        render_id: str,
        format_id: str,
        target_date: str,
        sha256: str,
        path: str,
        verified: bool,
    ):
        """렌더 저장."""
        now = datetime.now().isoformat()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                REPLACE INTO renders (render_id, format_id, target_date, sha256, path, downloaded_at, verified)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (render_id, format_id, target_date, sha256, path, now, int(verified)),
            )
            conn.commit()

    def get_executed_occurrence(self, occurrence_key: str) -> Optional[dict]:
        """실행된 occurrence 조회."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT occurrence_key, status, result_id, first_attempt_at, last_attempt_at, attempts, final,
                       format_id, render_id, scheduled_at, detail
                FROM executed_occurrences WHERE occurrence_key = ?
                """,
                (occurrence_key,),
            )
            row = cursor.fetchone()
            if row:
                return {
                    "occurrence_key": row[0],
                    "status": row[1],
                    "result_id": row[2],
                    "first_attempt_at": row[3],
                    "last_attempt_at": row[4],
                    "attempts": row[5],
                    "final": row[6],
                    "format_id": row[7],
                    "render_id": row[8],
                    "scheduled_at": row[9],
                    "detail": row[10],
                }
            return None

    def save_executed_occurrence(
        self,
        occurrence_key: str,
        status: str,
        result_id: Optional[str] = None,
        first_attempt_at: Optional[str] = None,
        last_attempt_at: Optional[str] = None,
        attempts: Optional[int] = None,
        final: bool = False,
        format_id: Optional[str] = None,
        render_id: Optional[str] = None,
        scheduled_at: Optional[str] = None,
        detail: Optional[str] = None,
    ):
        """실행된 occurrence 저장(UPSERT).

        attempts: 새 시도를 시작할 때만 호출부가 명시적으로 전달한다. 생략(None)하면
        기존 값을 그대로 유지한다.

        2026-09-17 실측으로 발견한 버그 2건을 여기서 고친다(.temp/05 설계 1.1):
        1) first_attempt_at이 갱신 때마다 now로 덮어써지던 것 — 이제 기존 행에 값이
           있으면 인자를 생략해도(None) 그 값을 보존한다. REPLACE INTO(행 전체 교체)
           대신 UPSERT(INSERT ... ON CONFLICT DO UPDATE)로 바꿔, "조회 → 계산 → 쓰기"
           사이에 기존 값을 잃지 않게 한다.
        2) last_attempt_at 인자가 무시되고 항상 now가 들어가던 것 — 이제 인자를
           그대로 쓰고, 생략時만 now를 쓴다.
        format_id/render_id/scheduled_at/detail도 같은 보존 규칙(인자 None이면 기존
        값 유지, 최초 삽입이면 인자 그대로)을 따른다.
        """
        now = now_iso()

        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()

            cursor.execute(
                """
                SELECT attempts, first_attempt_at, format_id, render_id, scheduled_at, detail
                FROM executed_occurrences WHERE occurrence_key = ?
                """,
                (occurrence_key,),
            )
            existing = cursor.fetchone()

            if attempts is not None:
                new_attempts = attempts
            elif existing:
                new_attempts = existing[0]
            else:
                new_attempts = 1

            if first_attempt_at is not None:
                new_first_attempt_at = first_attempt_at
            elif existing and existing[1]:
                new_first_attempt_at = existing[1]
            else:
                new_first_attempt_at = now

            new_last_attempt_at = last_attempt_at if last_attempt_at is not None else now

            new_format_id = format_id if format_id is not None else (existing[2] if existing else None)
            new_render_id = render_id if render_id is not None else (existing[3] if existing else None)
            new_scheduled_at = scheduled_at if scheduled_at is not None else (existing[4] if existing else None)
            new_detail = detail if detail is not None else (existing[5] if existing else None)

            cursor.execute(
                """
                INSERT INTO executed_occurrences
                    (occurrence_key, status, result_id, first_attempt_at, last_attempt_at, attempts, final,
                     format_id, render_id, scheduled_at, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(occurrence_key) DO UPDATE SET
                    status = excluded.status,
                    result_id = excluded.result_id,
                    first_attempt_at = excluded.first_attempt_at,
                    last_attempt_at = excluded.last_attempt_at,
                    attempts = excluded.attempts,
                    final = excluded.final,
                    format_id = excluded.format_id,
                    render_id = excluded.render_id,
                    scheduled_at = excluded.scheduled_at,
                    detail = excluded.detail
                """,
                (
                    occurrence_key,
                    status,
                    result_id,
                    new_first_attempt_at,
                    new_last_attempt_at,
                    new_attempts,
                    int(final),
                    new_format_id,
                    new_render_id,
                    new_scheduled_at,
                    new_detail,
                ),
            )
            conn.commit()

    def begin_occurrence_attempt(
        self,
        occurrence_key: str,
        *,
        format_id: str,
        render_id: str,
        scheduled_at: str,
        result_id: str,
    ) -> int:
        """새 시도 시작. status='checking', final=0, attempts += 1, last_attempt_at=now.

        최초 호출이면 first_attempt_at=now, 이후 호출은 save_executed_occurrence의
        보존 규칙에 따라 first_attempt_at을 건드리지 않는다.

        Returns:
            새 attempts 값.
        """
        existing = self.get_executed_occurrence(occurrence_key)
        new_attempts = (existing["attempts"] if existing else 0) + 1
        self.save_executed_occurrence(
            occurrence_key,
            status="checking",
            result_id=result_id,
            attempts=new_attempts,
            final=False,
            format_id=format_id,
            render_id=render_id,
            scheduled_at=scheduled_at,
        )
        return new_attempts

    def mark_occurrence_attempting(self, occurrence_key: str) -> None:
        """print_image 직전에 status='attempting'만 바꾼다.

        이 한 줄이 "바이트가 나갔을 수 있다"의 표식이고 기동 정리
        (cleanup_stale_attempts)의 유일한 판정 근거다. attempts·first_attempt_at은
        건드리지 않는다(save_executed_occurrence가 None 인자를 기존값 유지로
        처리하므로 그대로 넘긴다).
        """
        existing = self.get_executed_occurrence(occurrence_key)
        if existing is None:
            raise ValueError(f"occurrence {occurrence_key!r}에 진행 중인 시도가 없음")
        self.save_executed_occurrence(
            occurrence_key,
            status="attempting",
            result_id=existing["result_id"],
            final=False,
            format_id=existing["format_id"],
            render_id=existing["render_id"],
            scheduled_at=existing["scheduled_at"],
        )

    def finish_occurrence_attempt(self, occurrence_key: str, *, status: str, detail: str, final: bool) -> None:
        """시도 종료. status/detail/last_attempt_at/final만 갱신한다.

        attempts·first_attempt_at은 건드리지 않는다.
        """
        existing = self.get_executed_occurrence(occurrence_key)
        if existing is None:
            raise ValueError(f"occurrence {occurrence_key!r}를 찾을 수 없음")
        self.save_executed_occurrence(
            occurrence_key,
            status=status,
            result_id=existing["result_id"],
            final=final,
            format_id=existing["format_id"],
            render_id=existing["render_id"],
            scheduled_at=existing["scheduled_at"],
            detail=detail,
        )

    def get_unfinished_occurrences(self) -> list[dict]:
        """final=0인 행 전부. 기동 시 되돌아보기와 유예 만료 처리에 쓴다."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT occurrence_key FROM executed_occurrences WHERE final = 0")
            keys = [row[0] for row in cursor.fetchall()]
        return [self.get_executed_occurrence(key) for key in keys]

    def cleanup_stale_attempts(self) -> list[dict]:
        """기동 시 1회: 전송 도중 죽어 'attempting'·final=0으로 남은 레코드를 읽는다.

        ★동작 변경(.temp/05 설계 4.2, 2026-09-17)★: 예전에는 이 메서드가 직접
        UPDATE까지 해서 종결시켰지만, 그 자리에서는 results_queue에 아무것도 넣지
        않아 서버에 결과가 영영 올라가지 않는 문제가 있었다(과제 3). 이제는 **읽어서
        돌려주기만** 한다 — 종결과 결과 큐잉은 호출부(Executor.finalize_occurrence)가
        한 곳에서 하게 해서 "결과는 final 전이 때 정확히 한 번"이라는 불변식을
        지킨다(쓰기 주체를 하나로 모음).

        중간에 죽으면(정리 도중 프로세스가 또 죽는 등) 행은 attempting으로 남고
        다음 기동에 다시 정리된다 — 안전한 방향(중복 인쇄보다 재정리가 낫다).

        Returns:
            정리 대상 occurrence 행(dict) 목록.
        """
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT occurrence_key FROM executed_occurrences WHERE status = 'attempting' AND final = 0"
            )
            keys = [row[0] for row in cursor.fetchall()]
        return [self.get_executed_occurrence(key) for key in keys]

    def get_command(self, command_id: str) -> Optional[dict]:
        """명령 조회."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT command_id, format_id, render_id, sha256, paper_confirmed, created_at, received_at,
                       status, result_id, attempts, last_attempt_at, detail
                FROM commands WHERE command_id = ?
                """,
                (command_id,),
            )
            row = cursor.fetchone()
            if row:
                return {
                    "command_id": row[0],
                    "format_id": row[1],
                    "render_id": row[2],
                    "sha256": row[3],
                    "paper_confirmed": row[4],
                    "created_at": row[5],
                    "received_at": row[6],
                    "status": row[7],
                    "result_id": row[8],
                    "attempts": row[9],
                    "last_attempt_at": row[10],
                    "detail": row[11],
                }
            return None

    def save_command(
        self,
        command_id: str,
        format_id: str,
        render_id: str,
        sha256: str,
        paper_confirmed: bool,
        created_at: str,
    ):
        """명령 저장(최초 수신시 1회). paper_confirmed는 호출부가 bool로 보장해야
        한다 — int(None) 같은 변환은 TypeError를 낸다(2026-09-17 발견,
        __main__._handle_command가 `cmd.get("paperConfirmed") is True`로 엄격
        비교해 항상 bool을 넘기도록 고쳤다)."""
        now = now_iso()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                REPLACE INTO commands
                    (command_id, format_id, render_id, sha256, paper_confirmed, created_at, received_at,
                     status, result_id, attempts, last_attempt_at, detail)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending', NULL, 0, NULL, NULL)
                """,
                (command_id, format_id, render_id, sha256, int(paper_confirmed), created_at, now),
            )
            conn.commit()

    def get_pending_commands(self) -> list[dict]:
        """status != 'done' 전부, received_at 오름차순."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT command_id FROM commands WHERE status != 'done' ORDER BY received_at ASC")
            ids = [row[0] for row in cursor.fetchall()]
        return [self.get_command(command_id) for command_id in ids]

    def begin_command_attempt(self, command_id: str, *, result_id: str) -> int:
        """새 시도 시작. status='checking', attempts += 1, last_attempt_at=now.

        Returns:
            새 attempts 값.
        """
        existing = self.get_command(command_id)
        if existing is None:
            raise ValueError(f"command {command_id!r}를 찾을 수 없음")
        new_attempts = (existing["attempts"] or 0) + 1
        now = now_iso()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                UPDATE commands SET status = 'checking', attempts = ?, last_attempt_at = ?, result_id = ?
                WHERE command_id = ?
                """,
                (new_attempts, now, result_id, command_id),
            )
            conn.commit()
        return new_attempts

    def mark_command_attempting(self, command_id: str) -> None:
        """print_image 직전에 status='attempting'만 바꾼다(occurrence와 같은 표식)."""
        now = now_iso()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                "UPDATE commands SET status = 'attempting', last_attempt_at = ? WHERE command_id = ?",
                (now, command_id),
            )
            conn.commit()

    def finish_command(self, command_id: str, *, detail: str) -> None:
        """명령을 로컬에서 종결한다(status='done'). 인쇄 성공 여부는 업로드하는 결과
        payload의 status에 있다 — 로컬 commands.status는 "이제 다시 시도 안 함"만
        뜻한다(.temp/05 설계 3.3)."""
        now = now_iso()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                "UPDATE commands SET status = 'done', detail = ?, last_attempt_at = ? WHERE command_id = ?",
                (detail, now, command_id),
            )
            conn.commit()

    def get_stale_attempting_commands(self) -> list[dict]:
        """기동 정리용: status='attempting'으로 남은 명령 전부."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT command_id FROM commands WHERE status = 'attempting'")
            ids = [row[0] for row in cursor.fetchall()]
        return [self.get_command(command_id) for command_id in ids]

    def get_queued_results(self) -> list[dict]:
        """미업로드 결과 전부 조회."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT result_id, payload_json, created_at, uploaded_at, upload_attempts
                FROM results_queue WHERE uploaded_at IS NULL
                ORDER BY created_at
                """
            )
            results = []
            for row in cursor.fetchall():
                results.append(
                    {
                        "result_id": row[0],
                        "payload_json": json.loads(row[1]),
                        "created_at": row[2],
                        "uploaded_at": row[3],
                        "upload_attempts": row[4],
                    }
                )
            return results

    def save_result(self, result_id: str, payload: dict):
        """결과 저장 (미업로드)."""
        now = datetime.now().isoformat()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                REPLACE INTO results_queue (result_id, payload_json, created_at, upload_attempts)
                VALUES (?, ?, ?, 0)
                """,
                (result_id, json.dumps(payload), now),
            )
            conn.commit()

    def mark_result_uploaded(self, result_id: str):
        """결과를 업로드됨으로 표기."""
        now = datetime.now().isoformat()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                UPDATE results_queue SET uploaded_at = ? WHERE result_id = ?
                """,
                (now, result_id),
            )
            conn.commit()

    def increment_result_upload_attempts(self, result_id: str):
        """결과 업로드 시도 횟수 증가."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                UPDATE results_queue SET upload_attempts = upload_attempts + 1 WHERE result_id = ?
                """,
                (result_id,),
            )
            conn.commit()

    def get_kv(self, key: str) -> Optional[str]:
        """KV 저장소 조회."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("SELECT value FROM kv WHERE key = ?", (key,))
            row = cursor.fetchone()
            return row[0] if row else None

    def set_kv(self, key: str, value: str):
        """KV 저장소 저장."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute("REPLACE INTO kv (key, value) VALUES (?, ?)", (key, value))
            conn.commit()
