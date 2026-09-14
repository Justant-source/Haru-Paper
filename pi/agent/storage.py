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
        attempts: int = 0,
        final: bool = False,
    ):
        """실행된 occurrence 저장."""
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

            if existing:
                new_attempts = existing[0] + 1
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
