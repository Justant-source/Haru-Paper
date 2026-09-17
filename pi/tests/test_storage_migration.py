"""SQLite 스키마 마이그레이션 테스트(.temp/05 설계 5절, 6.2 T12).

운영 Pi의 agent.db는 이미 데이터가 있고 서비스가 상시 구동 중이다 — 새 컬럼을
추가할 때 기존 행을 잃거나 깨뜨리면 안 된다. 여기서는 옛 스키마(컬럼 추가 전)를
수동으로 만든 뒤 Storage()로 열어 마이그레이션이 안전한지 확인한다.
"""

from __future__ import annotations

import sqlite3

from agent.storage import SCHEMA_VERSION, Storage


def create_legacy_db(db_path) -> None:
    """.temp/05 설계 이전(2026-09-17 이전) 스키마를 그대로 재현한다."""
    db_path.parent.mkdir(parents=True, exist_ok=True)
    with sqlite3.connect(db_path) as conn:
        conn.execute(
            """
            CREATE TABLE executed_occurrences (
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
        conn.execute(
            """
            CREATE TABLE commands (
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
        conn.execute(
            "INSERT INTO executed_occurrences "
            "(occurrence_key, status, result_id, first_attempt_at, last_attempt_at, attempts, final) "
            "VALUES ('s1@2026-09-16T07:00', 'printed', 'r-old', '2026-09-16T07:00:00', "
            "'2026-09-16T07:00:03', 1, 1)"
        )
        conn.execute(
            "INSERT INTO commands "
            "(command_id, format_id, render_id, sha256, paper_confirmed, created_at, received_at, status, result_id) "
            "VALUES ('c-old', 'fmt1', 'r-old', 'deadbeef', 1, '2026-09-16T07:00:00', "
            "'2026-09-16T07:00:01', 'done', 'r-old')"
        )
        conn.commit()


class TestMigration:
    def test_adds_missing_columns_without_touching_other_tables(self, tmp_path):
        db_path = tmp_path / "agent.db"
        create_legacy_db(db_path)

        storage = Storage(db_path)

        with sqlite3.connect(db_path) as conn:
            occ_cols = {row[1] for row in conn.execute("PRAGMA table_info(executed_occurrences)")}
            cmd_cols = {row[1] for row in conn.execute("PRAGMA table_info(commands)")}
            user_version = conn.execute("PRAGMA user_version").fetchone()[0]

        assert {"format_id", "render_id", "scheduled_at", "detail"} <= occ_cols
        assert {"attempts", "last_attempt_at", "detail"} <= cmd_cols
        assert user_version == SCHEMA_VERSION

    def test_preserves_existing_rows(self, tmp_path):
        db_path = tmp_path / "agent.db"
        create_legacy_db(db_path)

        storage = Storage(db_path)

        occ = storage.get_executed_occurrence("s1@2026-09-16T07:00")
        assert occ is not None
        assert occ["status"] == "printed"
        assert occ["result_id"] == "r-old"
        assert occ["final"] == 1
        # 새 컬럼은 옛 행에서 NULL
        assert occ["format_id"] is None
        assert occ["scheduled_at"] is None

        cmd = storage.get_command("c-old")
        assert cmd is not None
        assert cmd["status"] == "done"
        assert cmd["sha256"] == "deadbeef"
        assert cmd["attempts"] is None  # 마이그레이션이 기존 행에 값을 채워 넣지 않는다(NULL)

    def test_running_migration_twice_is_a_noop(self, tmp_path):
        """user_version이 이미 최신이어도(재배포 등) 다시 열면 안전해야 한다."""
        db_path = tmp_path / "agent.db"
        create_legacy_db(db_path)

        Storage(db_path)
        # 두 번째로 다시 연다 — ALTER TABLE을 또 시도해도 예외가 나면 안 된다
        # (PRAGMA table_info로 실제 컬럼을 보고 없는 것만 추가하므로 안전).
        storage2 = Storage(db_path)

        occ = storage2.get_executed_occurrence("s1@2026-09-16T07:00")
        assert occ["status"] == "printed"

    def test_fresh_db_gets_new_columns_too(self, tmp_path):
        """새 DB(마이그레이션 대상 없음)도 _migrate()가 no-op으로 지나가며 최종
        스키마는 동일해야 한다."""
        storage = Storage(tmp_path / "fresh.db")
        with sqlite3.connect(tmp_path / "fresh.db") as conn:
            occ_cols = {row[1] for row in conn.execute("PRAGMA table_info(executed_occurrences)")}
        assert {"format_id", "render_id", "scheduled_at", "detail"} <= occ_cols
