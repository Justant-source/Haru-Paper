"""Executor 테스트: 용지 정책 fail-closed, 렌더 경로 해석, attempts 카운트,
'attempting' 정리. docs/pi/policy.md 2절, docs/pi/agent.md 8·9절.

2026-09-17에 세 가지 회귀가 고쳐졌다 — 이 파일은 그 회귀를 다시 잡기 위한 것이다:
1) 용지 정책이 "확인 안 됨"인데도 인쇄를 허용하던 것(fail-open)
2) render dict에 "path" 키가 없어 KeyError가 나던 것(서버 원본 RenderDto엔 path가 없음)
3) 인쇄 1회 실행에 attempts가 2씩 늘던 것
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

from agent.executor import Executor
from agent.scheduler import Occurrence
from agent.storage import Storage
from printer.fake import FakePrinter

KST = timezone(timedelta(hours=9))


def make_executor(tmp_path, paper_policy="unverified", printer=None) -> Executor:
    storage = Storage(tmp_path / "agent.db")
    if printer is None:
        printer = FakePrinter(connected=True)
    return Executor(storage=storage, sync=None, printer=printer, data_dir=tmp_path, paper_policy=paper_policy)


class TestCheckPaperPolicyFailClosed:
    """docs/pi/policy.md 2절: 용지 상태를 확인할 수 없으면 인쇄하지 않는다."""

    def test_unverified_is_dry_run(self, tmp_path):
        ex = make_executor(tmp_path, paper_policy="unverified")
        should_print, reason = ex._check_paper_policy({})
        assert should_print is False
        assert reason == "dry_run"

    def test_status_query_printer_offline(self, tmp_path):
        ex = make_executor(tmp_path, paper_policy="status_query", printer=FakePrinter(connected=False))
        should_print, reason = ex._check_paper_policy({})
        assert should_print is False
        assert reason == "skipped_printer_offline"

    def test_status_query_printer_ok_but_no_paper_field_fails_closed(self, tmp_path):
        """회귀 방지: PrinterStatus(printer/__init__.py)에는 용지 필드가 없다(H4 미통과).
        이전 코드는 연결만 되면(state == "ok") 그대로 인쇄를 허용했다 — 반드시
        skipped_no_paper여야 한다."""
        ex = make_executor(tmp_path, paper_policy="status_query", printer=FakePrinter(connected=True))
        should_print, reason = ex._check_paper_policy({})
        assert should_print is False
        assert reason == "skipped_no_paper"

    def test_manual_flag_no_paper_state_key_fails_closed(self, tmp_path):
        """회귀 방지: kv에 paperState 키가 아예 없으면 이전 코드는 검사를 통째로
        건너뛰고 True를 반환했다."""
        ex = make_executor(tmp_path, paper_policy="manual_flag")
        should_print, reason = ex._check_paper_policy({})
        assert should_print is False
        assert reason == "skipped_no_paper"

    def test_manual_flag_invalid_json_fails_closed(self, tmp_path):
        ex = make_executor(tmp_path, paper_policy="manual_flag")
        ex.storage.set_kv("paperState", "{ this is not valid json")
        should_print, reason = ex._check_paper_policy({})
        assert should_print is False
        assert reason == "skipped_no_paper"

    def test_manual_flag_loaded_false_fails_closed(self, tmp_path):
        ex = make_executor(tmp_path, paper_policy="manual_flag")
        ex.storage.set_kv("paperState", json.dumps({"loaded": False}))
        should_print, reason = ex._check_paper_policy({})
        assert should_print is False
        assert reason == "skipped_no_paper"

    def test_manual_flag_loaded_true_allows_print(self, tmp_path):
        ex = make_executor(tmp_path, paper_policy="manual_flag")
        ex.storage.set_kv("paperState", json.dumps({"loaded": True}))
        should_print, reason = ex._check_paper_policy({})
        assert should_print is True
        assert reason == ""

    def test_unknown_policy_fails_closed_with_documented_status(self, tmp_path):
        """docs/pi/policy.md 5절 결과 status 목록에 'unknown_policy'는 없다.
        목록에 있는 값(skipped_no_paper)으로 fail-closed 처리해야 한다."""
        ex = make_executor(tmp_path, paper_policy="totally-not-a-real-policy")
        should_print, reason = ex._check_paper_policy({})
        assert should_print is False
        assert reason == "skipped_no_paper"


class TestLoadRenderBytes:
    """render dict는 스냅샷의 서버 원본 JSON(RenderDto)이라 'path' 키가 없다."""

    def test_resolves_path_via_storage_get_render(self, tmp_path):
        ex = make_executor(tmp_path)
        png_path = tmp_path / "renders" / "r1.png"
        png_path.parent.mkdir(parents=True, exist_ok=True)
        png_path.write_bytes(b"fake-png-bytes")
        ex.storage.save_render("r1", "fmt1", "2026-09-17", "deadbeef", str(png_path), verified=True)

        server_render = {
            "renderId": "r1",
            "formatId": "fmt1",
            "targetDate": "2026-09-17",
            "sha256": "deadbeef",
            "widthPx": 1300,
            "renderedAt": "2026-09-17T00:00:00+09:00",
            "url": "/api/device/renders/r1.png",
            "urlPbm": None,
            "sha256Pbm": None,
        }
        assert "path" not in server_render  # 전제 확인: 서버 원본에는 path가 없다

        data = ex._load_render_bytes(server_render)
        assert data == b"fake-png-bytes"

    def test_render_not_in_local_cache_raises(self, tmp_path):
        ex = make_executor(tmp_path)
        with pytest.raises(FileNotFoundError):
            ex._load_render_bytes({"renderId": "never-downloaded"})

    def test_render_file_missing_on_disk_raises(self, tmp_path):
        ex = make_executor(tmp_path)
        ex.storage.save_render("r2", "fmt1", "2026-09-17", "sha", str(tmp_path / "gone.png"), verified=True)
        with pytest.raises(FileNotFoundError):
            ex._load_render_bytes({"renderId": "r2"})


class TestExecuteOccurrenceEndToEnd:
    """render['path'] KeyError 버그는 unverified(dry_run) 뒤에 가려 있었다 — 3일 연속
    인쇄 시험을 통째로 막을 수 있었던 버그라 manual_flag로 끝까지 돌려 확인한다."""

    def test_printed_with_server_style_render_dict(self, tmp_path):
        printer = FakePrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="manual_flag", printer=printer)
        ex.storage.set_kv("paperState", json.dumps({"loaded": True}))

        today = datetime.now(KST).strftime("%Y-%m-%d")

        png_path = tmp_path / "renders" / "r1.png"
        png_path.parent.mkdir(parents=True, exist_ok=True)
        png_path.write_bytes(b"fake-png-bytes")
        ex.storage.save_render("r1", "fmt1", today, "deadbeef", str(png_path), verified=True)

        occurrence = Occurrence(
            occurrence_key=f"s1@{today}T07:00",
            schedule_id="s1",
            format_id="fmt1",
            scheduled_at=datetime.now(KST).replace(hour=7, minute=0, second=0, microsecond=0),
        )
        snapshot = {
            "renders": [
                {
                    "renderId": "r1",
                    "formatId": "fmt1",
                    "targetDate": today,
                    "sha256": "deadbeef",
                    "renderedAt": "2026-09-17T00:00:00+09:00",
                }
            ]
        }

        result = ex.execute_occurrence(occurrence, snapshot)
        assert result["status"] == "printed"
        assert result["detail"] == ""

        stored = ex.storage.get_executed_occurrence(occurrence.occurrence_key)
        assert stored["status"] == "printed"
        assert stored["final"] == 1

    def test_unverified_is_dry_run_and_never_calls_printer(self, tmp_path):
        """unverified 정책에서는 렌더가 있어도 인쇄기를 건드리지 않는다."""

        class ExplodingPrinter(FakePrinter):
            def print_image(self, png_bytes):
                raise AssertionError("unverified 정책에서 print_image가 호출되면 안 된다")

        printer = ExplodingPrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="unverified", printer=printer)

        today = datetime.now(KST).strftime("%Y-%m-%d")
        png_path = tmp_path / "renders" / "r1.png"
        png_path.parent.mkdir(parents=True, exist_ok=True)
        png_path.write_bytes(b"fake-png-bytes")
        ex.storage.save_render("r1", "fmt1", today, "deadbeef", str(png_path), verified=True)

        occurrence = Occurrence(
            occurrence_key=f"s1@{today}T07:00",
            schedule_id="s1",
            format_id="fmt1",
            scheduled_at=datetime.now(KST).replace(hour=7, minute=0, second=0, microsecond=0),
        )
        snapshot = {"renders": [{"renderId": "r1", "formatId": "fmt1", "targetDate": today, "sha256": "deadbeef"}]}

        result = ex.execute_occurrence(occurrence, snapshot)
        assert result["status"] == "dry_run"


class TestSaveExecutedOccurrenceAttempts:
    """attempts 이중 증가 회귀(2026-09-17): 시도 시작 1번 + 최종 기록 1번 = 실제로는
    1회 실행인데 attempts가 2로 저장되던 버그."""

    def test_one_print_attempt_counts_as_one(self, tmp_path):
        storage = Storage(tmp_path / "agent.db")
        key = "s1@2026-09-17T07:00"

        # executor.py가 인쇄 전에 하는 것과 동일: 새 시도 시작(attempts=1 명시)
        storage.save_executed_occurrence(key, status="attempting", result_id="r-1", attempts=1)
        # executor.py가 인쇄 후에 하는 것과 동일: 같은 시도를 final로 마무리(attempts 생략)
        storage.save_executed_occurrence(key, status="printed", result_id="r-1", final=True)

        stored = storage.get_executed_occurrence(key)
        assert stored["attempts"] == 1
        assert stored["status"] == "printed"
        assert stored["final"] == 1

    def test_explicit_attempts_overrides(self, tmp_path):
        storage = Storage(tmp_path / "agent.db")
        key = "s1@2026-09-17T07:00"
        storage.save_executed_occurrence(key, status="attempting", attempts=1)
        storage.save_executed_occurrence(key, status="attempting", attempts=2)  # 재시도
        stored = storage.get_executed_occurrence(key)
        assert stored["attempts"] == 2

    def test_omitted_attempts_keeps_existing_value(self, tmp_path):
        storage = Storage(tmp_path / "agent.db")
        key = "s1@2026-09-17T07:00"
        storage.save_executed_occurrence(key, status="attempting", attempts=3)
        storage.save_executed_occurrence(key, status="missed", final=True)  # attempts 생략
        stored = storage.get_executed_occurrence(key)
        assert stored["attempts"] == 3

    def test_first_write_without_attempts_defaults_to_one(self, tmp_path):
        storage = Storage(tmp_path / "agent.db")
        key = "s1@2026-09-17T07:00"
        storage.save_executed_occurrence(key, status="dry_run", final=True)  # attempts 생략, 최초 기록
        stored = storage.get_executed_occurrence(key)
        assert stored["attempts"] == 1


class TestCleanupStaleAttempts:
    """docs/pi/agent.md 9절: 전송 도중 죽은 채로 남은 'attempting' 레코드는 기동 시
    failed·final=1로 정리해 재실행(중복 인쇄)을 막는다."""

    def test_cleans_attempting_final_zero_records(self, tmp_path):
        storage = Storage(tmp_path / "agent.db")
        key = "s1@2026-09-17T07:00"
        storage.save_executed_occurrence(key, status="attempting", result_id="r-1", attempts=1, final=False)

        cleaned = storage.cleanup_stale_attempts()

        assert cleaned == [key]
        stored = storage.get_executed_occurrence(key)
        assert stored["status"] == "failed"
        assert stored["final"] == 1
        # 재시도 카운트는 건드리지 않는다(그대로 1)
        assert stored["attempts"] == 1

    def test_prevents_re_execution_via_scheduler_filter(self, tmp_path):
        """정리 후에는 scheduler.filter_executable_occurrences가 이 occurrence를
        다시 실행 대상으로 잡지 않아야 한다(final=1만 보므로)."""
        from agent.scheduler import filter_executable_occurrences

        storage = Storage(tmp_path / "agent.db")
        key = "s1@2026-09-17T07:00"
        storage.save_executed_occurrence(key, status="attempting", result_id="r-1", attempts=1, final=False)
        storage.cleanup_stale_attempts()

        occ = Occurrence(
            occurrence_key=key,
            schedule_id="s1",
            format_id="fmt1",
            scheduled_at=datetime.now(KST).replace(hour=7, minute=0, second=0, microsecond=0),
        )
        existing = {key: storage.get_executed_occurrence(key)}
        executable = filter_executable_occurrences([occ], datetime.now(KST), 30, existing)
        assert executable == []

    def test_does_not_touch_final_records(self, tmp_path):
        storage = Storage(tmp_path / "agent.db")
        key = "s1@2026-09-17T07:00"
        storage.save_executed_occurrence(key, status="printed", result_id="r-1", final=True)

        cleaned = storage.cleanup_stale_attempts()

        assert cleaned == []
        stored = storage.get_executed_occurrence(key)
        assert stored["status"] == "printed"

    def test_does_not_touch_non_attempting_unfinished_records(self, tmp_path):
        """final=0이라도 status가 'attempting'이 아니면(예: 아직 존재하지 않는 다른
        중간 상태) 건드리지 않는다 — 지금 코드에서 final=0으로 남는 상태는
        'attempting'뿐이지만, 메서드 자체는 status 조건도 함께 건다."""
        storage = Storage(tmp_path / "agent.db")
        key = "s1@2026-09-17T07:00"
        with __import__("sqlite3").connect(storage.db_path) as conn:
            conn.execute(
                "INSERT INTO executed_occurrences (occurrence_key, status, result_id, first_attempt_at, last_attempt_at, attempts, final)"
                " VALUES (?, 'missed', NULL, '2026-09-17T00:00:00', '2026-09-17T00:00:00', 1, 0)",
                (key,),
            )
            conn.commit()

        cleaned = storage.cleanup_stale_attempts()

        assert cleaned == []

    def test_no_stale_records_returns_empty(self, tmp_path):
        storage = Storage(tmp_path / "agent.db")
        assert storage.cleanup_stale_attempts() == []
