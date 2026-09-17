"""로컬 SQLite 저장소. docs/pi/agent.md 5절."""

from __future__ import annotations

import json
import logging
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

logger = logging.getLogger(__name__)


class Storage:
    """SQLite 저장소. 테이블 5개: snapshot, renders, executed_occurrences, commands, results_queue, kv."""

    def __init__(self, db_path: Path):
        self.db_path = db_path
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_schema()

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
                SELECT occurrence_key, status, result_id, first_attempt_at, last_attempt_at, attempts, final
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
    ):
        """실행된 occurrence 저장.

        attempts: 새 시도를 시작할 때만 호출부가 명시적으로 전달한다(executor.py가
        인쇄 전 "attempting" 기록에 attempts=1을 넘기는 식). 생략(None)하면 기존
        값을 그대로 유지한다 — 같은 시도를 final로 마무리하는 두 번째 호출이라는
        뜻이다. 2026-09-17 발견: 이전에는 이 인자를 무시하고 호출마다 기존값+1을
        써서, 실행 1회(시작 기록 1번 + 최종 기록 1번)에 attempts가 2씩 늘었다.
        """
        now = datetime.now().isoformat()
        if first_attempt_at is None:
            first_attempt_at = now
        if last_attempt_at is None:
            last_attempt_at = now

        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()

            # 기존 레코드 확인
            cursor.execute("SELECT attempts FROM executed_occurrences WHERE occurrence_key = ?", (occurrence_key,))
            existing = cursor.fetchone()

            if attempts is not None:
                new_attempts = attempts
            elif existing:
                new_attempts = existing[0]
            else:
                new_attempts = 1

            cursor.execute(
                """
                REPLACE INTO executed_occurrences
                (occurrence_key, status, result_id, first_attempt_at, last_attempt_at, attempts, final)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (occurrence_key, status, result_id, first_attempt_at, now, new_attempts, int(final)),
            )
            conn.commit()

    def cleanup_stale_attempts(self) -> list[str]:
        """기동 시 1회: 전송 도중 죽어 'attempting'·final=0으로 남은 레코드를 정리한다.

        docs/pi/agent.md 9절: 전송 도중 프로세스가 죽으면(정전, OOM, `systemctl
        restart` 등) 재시작 후 그 occurrence를 자동으로 다시 인쇄하지 않고
        failed(detail: 전송 중 중단)로 끝낸다 — 같은 내용이 두 번 나오는 것보다
        한 번 빠지는 쪽을 택한다. final=1로 표시해 두면
        scheduler.filter_executable_occurrences가 final만 보고 걸러내므로 재실행되지
        않는다.

        주의(범위): 이 메서드는 executed_occurrences만 갱신하고 results_queue에는
        아무것도 넣지 않는다 — executed_occurrences에는 formatId·renderId·
        scheduledAt이 없어 서버가 기대하는 결과 payload(../architecture.md)를 여기서
        온전히 재구성할 수 없다. 즉 이 정리 결과는 기존 업로드 경로(uploader.py →
        POST /api/device/results)를 타지 않고 로컬 중복 인쇄 방지에만 쓰인다
        (호출부 __main__.py에서 이 사실을 그대로 로그로 남긴다).

        Returns:
            정리한 occurrence_key 목록.
        """
        now = datetime.now().isoformat()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                "SELECT occurrence_key FROM executed_occurrences WHERE status = 'attempting' AND final = 0"
            )
            keys = [row[0] for row in cursor.fetchall()]
            if keys:
                cursor.executemany(
                    """
                    UPDATE executed_occurrences
                    SET status = 'failed', last_attempt_at = ?, final = 1
                    WHERE occurrence_key = ?
                    """,
                    [(now, key) for key in keys],
                )
                conn.commit()
        return keys

    def get_command(self, command_id: str) -> Optional[dict]:
        """명령 조회."""
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                SELECT command_id, format_id, render_id, sha256, paper_confirmed, created_at, received_at, status, result_id
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
        """명령 저장."""
        now = datetime.now().isoformat()
        with sqlite3.connect(self.db_path) as conn:
            cursor = conn.cursor()
            cursor.execute(
                """
                REPLACE INTO commands (command_id, format_id, render_id, sha256, paper_confirmed, created_at, received_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'pending')
                """,
                (command_id, format_id, render_id, sha256, int(paper_confirmed), created_at, now),
            )
            conn.commit()

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
