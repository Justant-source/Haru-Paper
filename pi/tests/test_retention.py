"""보낸 바이트 30일 순환 삭제(`agent/retention.py`) 테스트.

CLAUDE.md 코드 규칙 "30일 순환 보관", docs/pi/policy.md 7절. 전부 tmp_path
안에서만 동작한다 — 실제 `/var/lib/haru-paper`나 저장소 `pi/data/`는 절대
건드리지 않는다.
"""

from __future__ import annotations

import logging
import os
from datetime import date, timedelta
from pathlib import Path

import pytest

from agent.retention import RUNAWAY_MULTIPLIER, PruneReport, prune_sent_bytes

TODAY = date(2026, 9, 17)  # 오늘 날짜(시스템 리마인더 기준)와 맞춤. 임의 선택 가능.


def _make_dir(sent_dir: Path, name: str, with_file: bool = True) -> Path:
    d = sent_dir / name
    d.mkdir(parents=True)
    if with_file:
        (d / "r-1.bin").write_bytes(b"dummy")
    return d


class TestBoundary:
    """보관 기준 경계(off-by-one)."""

    def test_exactly_retention_days_old_is_kept(self, tmp_path):
        """age == retention_days(30)이면 남는다."""
        sent_dir = tmp_path / "sent"
        old = TODAY - timedelta(days=30)
        _make_dir(sent_dir, old.isoformat())
        _make_dir(sent_dir, TODAY.isoformat())  # 가장 최근(오늘) — 비교 기준용

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert old.isoformat() not in report.deleted
        assert (sent_dir / old.isoformat()).exists()

    def test_retention_days_plus_one_is_deleted(self, tmp_path):
        """age == retention_days+1(31)이면 지워진다."""
        sent_dir = tmp_path / "sent"
        old = TODAY - timedelta(days=31)
        _make_dir(sent_dir, old.isoformat())
        _make_dir(sent_dir, TODAY.isoformat())  # 가장 최근 — old가 최신이 아니게 함

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert old.isoformat() in report.deleted
        assert not (sent_dir / old.isoformat()).exists()


class TestNameFormat:
    """형식이 안 맞는 이름은 절대 건드리지 않는다."""

    @pytest.mark.parametrize("bad_name", ["2026-9-1", "notes", "2026-13-40", "2026-02-30"])
    def test_malformed_or_invalid_name_kept(self, tmp_path, bad_name):
        sent_dir = tmp_path / "sent"
        _make_dir(sent_dir, bad_name)
        _make_dir(sent_dir, TODAY.isoformat())

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert (sent_dir / bad_name).exists()
        assert bad_name not in report.deleted

    def test_dashless_date_format_kept_even_though_parseable(self, tmp_path):
        """`date.fromisoformat`은 대시 없는 "20260101" 같은 basic ISO 형식도
        허용한다(Python 3.11) — 정규식 검사가 없으면 이런 이름도 날짜로
        파싱돼 오래됐으면 지워질 수 있다. `executor.py`가 실제로 만드는
        형식은 항상 대시 있는 `%Y-%m-%d`뿐이므로, 대시 없는 이름은 사람이나
        다른 도구가 만든 것으로 보고 건드리지 않는다."""
        sent_dir = tmp_path / "sent"
        old_dashless = (TODAY - timedelta(days=40)).strftime("%Y%m%d")
        _make_dir(sent_dir, old_dashless)
        _make_dir(sent_dir, TODAY.isoformat())

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert (sent_dir / old_dashless).exists()
        assert old_dashless not in report.deleted

    def test_plain_file_kept(self, tmp_path):
        sent_dir = tmp_path / "sent"
        sent_dir.mkdir(parents=True)
        f = sent_dir / "README.txt"
        f.write_text("hello")

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert f.exists()
        assert "README.txt" not in report.deleted


class TestSymlink:
    """심볼릭 링크는 따라가지 않는다."""

    def test_symlinked_old_dir_not_deleted_and_target_survives(self, tmp_path):
        sent_dir = tmp_path / "sent"
        sent_dir.mkdir(parents=True)
        _make_dir(sent_dir, TODAY.isoformat())  # 최신 — 비교 기준

        outside_target = tmp_path / "outside_target"
        outside_target.mkdir()
        (outside_target / "important.bin").write_bytes(b"do-not-delete")

        old_name = (TODAY - timedelta(days=90)).isoformat()
        link = sent_dir / old_name
        os.symlink(outside_target, link, target_is_directory=True)

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert old_name not in report.deleted
        assert link.is_symlink()
        assert outside_target.exists()
        assert (outside_target / "important.bin").exists()
        # 명시적으로 "symlink"라서 건너뛴 것이어야 한다 — stdlib shutil.rmtree가
        # 최상위 심볼릭 링크를 거부해 우연히 안전한 것과 구별한다(mutation 검증용).
        assert (old_name, "symlink") in report.skipped
        assert old_name not in [n for n, _ in report.failed]


class TestFutureDate:
    def test_future_dated_dir_kept(self, tmp_path):
        sent_dir = tmp_path / "sent"
        future = (TODAY + timedelta(days=5)).isoformat()
        _make_dir(sent_dir, future)

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert future not in report.deleted
        assert (sent_dir / future).exists()


class TestRetentionDaysNonPositive:
    @pytest.mark.parametrize("retention_days", [0, -1, -30])
    def test_nothing_deleted_and_warns(self, tmp_path, retention_days, caplog):
        sent_dir = tmp_path / "sent"
        very_old = (TODAY - timedelta(days=400)).isoformat()
        _make_dir(sent_dir, very_old)

        with caplog.at_level(logging.WARNING):
            report = prune_sent_bytes(sent_dir, retention_days=retention_days, today=TODAY)

        assert report.deleted == []
        assert (sent_dir / very_old).exists()
        assert report.aborted is True
        assert any("retention_days" in rec.message for rec in caplog.records)


class TestRunawayClockAbort:
    """비정상적으로 먼 today(시계 폭주)에서 전체 삭제 거부."""

    def test_far_future_today_aborts_everything(self, tmp_path, caplog):
        sent_dir = tmp_path / "sent"
        recent = TODAY - timedelta(days=1)
        older = TODAY - timedelta(days=40)
        _make_dir(sent_dir, recent.isoformat())
        _make_dir(sent_dir, older.isoformat())

        runaway_today = date(2038, 1, 1)
        assert (runaway_today - recent).days > 30 * RUNAWAY_MULTIPLIER  # 전제 확인

        with caplog.at_level(logging.WARNING):
            report = prune_sent_bytes(sent_dir, retention_days=30, today=runaway_today)

        assert report.deleted == []
        assert report.aborted is True
        assert (sent_dir / recent.isoformat()).exists()
        assert (sent_dir / older.isoformat()).exists()
        assert any("폭주" in rec.message or "runaway" in rec.message.lower() for rec in caplog.records)

    def test_within_runaway_threshold_still_prunes_normally(self, tmp_path):
        """today가 가장 최근 디렉터리보다 앞서 있어도 임계값 안이면 정상 동작."""
        sent_dir = tmp_path / "sent"
        recent = TODAY - timedelta(days=1)
        old = TODAY - timedelta(days=50)  # retention=30, threshold=90 → 폭주 아님
        _make_dir(sent_dir, recent.isoformat())
        _make_dir(sent_dir, old.isoformat())

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert report.aborted is False
        assert old.isoformat() in report.deleted


class TestMostRecentKept:
    def test_only_dir_is_old_but_kept_as_most_recent(self, tmp_path):
        """가장 최근 디렉터리는 보관 기간을 넘겨도(폭주 임계값 안이면) 남는다."""
        sent_dir = tmp_path / "sent"
        only = TODAY - timedelta(days=40)  # age 40 > retention 30, but < 90(threshold)
        _make_dir(sent_dir, only.isoformat())

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert report.deleted == []
        assert (sent_dir / only.isoformat()).exists()
        assert any(name == only.isoformat() and reason == "most_recent_kept" for name, reason in report.skipped)


class TestMissingSentDir:
    def test_missing_dir_returns_empty_report_without_error(self, tmp_path):
        sent_dir = tmp_path / "sent"  # 만들지 않음

        report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert report == PruneReport()
        assert not sent_dir.exists()  # 부수효과로 만들어지지 않아야 함


class TestDeletionFailureContinues:
    def test_individual_failure_logs_and_others_continue(self, tmp_path, monkeypatch, caplog):
        import agent.retention as retention_mod

        sent_dir = tmp_path / "sent"
        recent = TODAY.isoformat()
        deletable_ok = (TODAY - timedelta(days=40)).isoformat()
        deletable_fail = (TODAY - timedelta(days=50)).isoformat()
        _make_dir(sent_dir, recent)
        _make_dir(sent_dir, deletable_ok)
        _make_dir(sent_dir, deletable_fail)

        real_rmtree = retention_mod.shutil.rmtree

        def fake_rmtree(path, *args, **kwargs):
            if Path(path).name == deletable_fail:
                raise OSError("simulated failure")
            return real_rmtree(path, *args, **kwargs)

        monkeypatch.setattr(retention_mod.shutil, "rmtree", fake_rmtree)

        with caplog.at_level(logging.ERROR):
            report = prune_sent_bytes(sent_dir, retention_days=30, today=TODAY)

        assert deletable_ok in report.deleted
        assert not (sent_dir / deletable_ok).exists()

        assert (sent_dir / deletable_fail).exists()
        assert any(name == deletable_fail for name, _ in report.failed)
        assert any(deletable_fail in rec.message for rec in caplog.records)
