"""시계 동기화 게이트 테스트(과제 1, docs/pi/policy.md 4절, docs/pi/agent.md:135-137).

세 부분으로 나뉜다:
1. `check_ntp_synchronized`(운영 구현) — 실제 `timedatectl`을 부르지 않고
   `subprocess.run`을 monkeypatch해서 fail-closed·경고 로그를 확인한다.
2. `ClockGate`(sticky 판정, missed/skipped_clock_unsynced 구분 규칙) — 순수
   단위 테스트.
3. `Agent._do_scheduler_tick` 통합 — 미동기 동안 인쇄가 전혀 일어나지 않는지,
   동기화 전환 후 유예 안/밖이 올바르게 갈리는지, 명령도 함께 보류되는지.

CLAUDE.md·과제 지시문: 실제 `timedatectl`을 테스트에서 부르지 않는다(주입 가능하게
설계된 `clock_synced_check`/`check_fn`을 쓴다). 하드웨어·Pi 접속 없음.
"""

from __future__ import annotations

import json
import subprocess
from datetime import datetime, timedelta, timezone

import pytest

from agent import __main__ as main_module
from agent.clock import ClockGate, check_ntp_synchronized, now_iso
from agent.config import AgentConfig
from printer.fake import FakePrinter

KST = timezone(timedelta(hours=9))


# ----------------------------------------------------------------------
# 1. check_ntp_synchronized (운영 구현) — subprocess.run만 monkeypatch한다.
# ----------------------------------------------------------------------


class _FakeCompletedProcess:
    def __init__(self, returncode: int, stdout: str = "", stderr: str = ""):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class TestCheckNtpSynchronized:
    def test_yes_is_synced(self, monkeypatch):
        monkeypatch.setattr(
            subprocess, "run", lambda *a, **k: _FakeCompletedProcess(0, stdout="yes\n")
        )
        assert check_ntp_synchronized() is True

    def test_no_is_unsynced(self, monkeypatch):
        monkeypatch.setattr(
            subprocess, "run", lambda *a, **k: _FakeCompletedProcess(0, stdout="no\n")
        )
        assert check_ntp_synchronized() is False

    def test_missing_binary_is_unsynced_with_warning(self, monkeypatch, caplog):
        """timedatectl 자체가 없는 환경(개발 컨테이너 등) — fail-closed + 경고 로그.
        경고 로그가 없으면 "왜 인쇄가 멈췄는지" 운영자가 journald에서 알아낼 방법이
        없다(과제 1 지시문의 핵심 위험)."""

        def raise_not_found(*a, **k):
            raise FileNotFoundError("timedatectl not found")

        monkeypatch.setattr(subprocess, "run", raise_not_found)
        with caplog.at_level("WARNING"):
            assert check_ntp_synchronized() is False
        assert any("timedatectl" in r.message for r in caplog.records)

    def test_timeout_is_unsynced_with_warning(self, monkeypatch, caplog):
        def raise_timeout(*a, **k):
            raise subprocess.TimeoutExpired(cmd="timedatectl", timeout=5.0)

        monkeypatch.setattr(subprocess, "run", raise_timeout)
        with caplog.at_level("WARNING"):
            assert check_ntp_synchronized() is False
        assert any("timedatectl" in r.message for r in caplog.records)

    def test_nonzero_returncode_is_unsynced_with_warning(self, monkeypatch, caplog):
        monkeypatch.setattr(
            subprocess,
            "run",
            lambda *a, **k: _FakeCompletedProcess(1, stdout="", stderr="no such property"),
        )
        with caplog.at_level("WARNING"):
            assert check_ntp_synchronized() is False
        assert any("timedatectl" in r.message for r in caplog.records)

    def test_oserror_is_unsynced_with_warning(self, monkeypatch, caplog):
        def raise_oserror(*a, **k):
            raise OSError("Permission denied")

        monkeypatch.setattr(subprocess, "run", raise_oserror)
        with caplog.at_level("WARNING"):
            assert check_ntp_synchronized() is False
        assert any("timedatectl" in r.message for r in caplog.records)


# ----------------------------------------------------------------------
# 2. ClockGate — 순수 단위 테스트
# ----------------------------------------------------------------------


class TestClockGateSticky:
    def test_sticky_stops_calling_check_fn_after_first_true(self):
        calls = {"n": 0}

        def check_fn():
            calls["n"] += 1
            return True

        gate = ClockGate(check_fn=check_fn)
        assert gate.is_synced() is True
        assert gate.is_synced() is True
        assert gate.is_synced() is True
        assert calls["n"] == 1  # 두 번째부터는 check_fn을 다시 부르지 않는다(sticky)

    def test_keeps_checking_while_unsynced(self):
        calls = {"n": 0}

        def check_fn():
            calls["n"] += 1
            return False

        gate = ClockGate(check_fn=check_fn)
        assert gate.is_synced() is False
        assert gate.is_synced() is False
        assert calls["n"] == 2  # 동기화되기 전까지는 매번 다시 묻는다

    def test_never_unsynced_when_synced_from_the_start(self):
        gate = ClockGate(check_fn=lambda: True)
        gate.is_synced()
        assert gate.ever_unsynced is False


class TestClockGateExpiredDuringUnsyncedWindow:
    def test_never_unsynced_process_always_false(self):
        """이 프로세스가 한 번도 미동기를 겪지 않았으면(대부분의 정상 기동)
        어떤 만료 시각을 넣어도 항상 False — missed로만 분류돼야 한다."""
        gate = ClockGate(check_fn=lambda: True)
        gate.is_synced()
        very_old = datetime(2000, 1, 1, tzinfo=KST)
        assert gate.expired_during_unsynced_window(very_old) is False

    def test_expiry_before_first_sync_is_clock_unsynced(self):
        sequence = iter([False, False, True])
        gate = ClockGate(check_fn=lambda: next(sequence))
        gate.is_synced()  # False
        gate.is_synced()  # False
        gate.is_synced()  # True -> first_synced_at 기록
        expiry_before_sync = gate._first_synced_at - timedelta(minutes=1)
        assert gate.expired_during_unsynced_window(expiry_before_sync) is True

    def test_expiry_after_first_sync_is_not_clock_unsynced(self):
        """이 프로세스가 미동기를 겪었더라도, 동기화가 확인된 *이후*에 만료된
        회차까지 시계 탓으로 돌리면 안 된다(30일 연속 운영 중 나중에 생기는 진짜
        missed까지 영구히 오분류하는 것을 막는다)."""
        sequence = iter([False, True])
        gate = ClockGate(check_fn=lambda: next(sequence))
        gate.is_synced()  # False
        gate.is_synced()  # True
        expiry_after_sync = gate._first_synced_at + timedelta(hours=5)
        assert gate.expired_during_unsynced_window(expiry_after_sync) is False

    def test_before_any_sync_confirmed_is_false(self):
        """아직 한 번도 동기화를 확인하지 못했으면(first_synced_at이 아직 None)
        판정 자체를 할 수 없다 — False(= missed 쪽)로 떨어진다. 실제로는
        _do_scheduler_tick이 이 상태에서는 애초에 finalize를 부르지 않는다."""
        gate = ClockGate(check_fn=lambda: False)
        gate.is_synced()
        assert gate.expired_during_unsynced_window(datetime(2026, 1, 1, tzinfo=KST)) is False


# ----------------------------------------------------------------------
# 3. Agent 통합 — 미동기 동안 인쇄 보류, 동기화 전환 후 올바른 라벨링
# ----------------------------------------------------------------------


class CountingPrinter(FakePrinter):
    """print_image 호출 횟수를 센다 — "미동기 동안 호출 0회"를 직접 증명하는 용도."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.print_calls = 0

    def print_image(self, png_bytes):
        self.print_calls += 1
        return super().print_image(png_bytes)


@pytest.fixture(autouse=True)
def _isolate_fake_printer_data_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("HARU_DATA_DIR", str(tmp_path / "fake-printer-data"))


def make_agent_config(tmp_path, **overrides) -> AgentConfig:
    defaults = dict(
        server_url="http://127.0.0.1:18080",
        device_token="test-token",
        poll_interval_sec=30,
        printer_driver="fake",
        transport="usb",
        bt_address="",
        paper_policy="manual_flag",
        grace_minutes=30,
        retry_interval_sec=60,
        h_offset_mm=2.0,
        data_dir=str(tmp_path),
        sent_retention_days=30,
        command_ttl_sec=600,
    )
    defaults.update(overrides)
    return AgentConfig(**defaults)


def make_recurring_snapshot(entries: list[tuple[str, str, datetime]]) -> dict:
    """entries: [(schedule_id, format_id, scheduled_at), ...] 전부 오늘 요일에 맞춘다."""
    weekday_map = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
    schedules = [
        {
            "id": sid,
            "formatId": fid,
            "type": "recurring",
            "daysOfWeek": [weekday_map[sched.weekday()]],
            "time": sched.strftime("%H:%M"),
            "enabled": True,
        }
        for sid, fid, sched in entries
    ]
    return {"schedules": schedules, "renders": []}


def save_ready_render(agent, today: str, render_id: str, format_id: str) -> dict:
    png_path = agent.data_dir / "renders" / f"{render_id}.png"
    png_path.parent.mkdir(parents=True, exist_ok=True)
    png_path.write_bytes(b"fake-png-bytes")
    agent.storage.save_render(render_id, format_id, today, "deadbeef", str(png_path), verified=True)
    return {
        "renderId": render_id,
        "formatId": format_id,
        "targetDate": today,
        "sha256": "deadbeef",
        "renderedAt": f"{today}T00:00:00+09:00",
    }


class TestUnsyncedBlocksExecution:
    def test_1_unsynced_never_calls_print_image_even_within_grace(self, tmp_path):
        """미동기면 유예 안 회차도 인쇄하지 않는다(print_image 호출 0회)."""
        config = make_agent_config(tmp_path, grace_minutes=30)
        agent = main_module.Agent(config, clock_synced_check=lambda: False)

        counting_printer = CountingPrinter(connected=True)
        agent.printer = counting_printer
        agent.executor.printer = counting_printer
        agent.storage.set_kv("paperState", json.dumps({"loaded": True, "updatedAt": now_iso()}))

        now = datetime.now(KST)
        scheduled = now - timedelta(minutes=1)  # 유예(30분) 훨씬 안
        today = now.strftime("%Y-%m-%d")
        render = save_ready_render(agent, today, "r1", "fmt1")
        agent.snapshot = make_recurring_snapshot([("s1", "fmt1", scheduled)])
        agent.snapshot["renders"] = [render]

        agent._do_scheduler_tick()

        assert counting_printer.print_calls == 0
        assert agent.storage.get_executed_occurrence("s1@" + today + "T" + scheduled.strftime("%H:%M")) is None

    def test_2_transition_within_grace_executes_past_grace_skipped_clock_unsynced(self, tmp_path):
        """미동기 → 동기화 전환 시: 유예 안 회차는 실행, 유예 넘긴 회차는
        skipped_clock_unsynced로 정확히 한 번 업로드."""
        sync_state = {"value": False}
        config = make_agent_config(tmp_path, grace_minutes=30)
        agent = main_module.Agent(config, clock_synced_check=lambda: sync_state["value"])

        counting_printer = CountingPrinter(connected=True)
        agent.printer = counting_printer
        agent.executor.printer = counting_printer
        agent.storage.set_kv("paperState", json.dumps({"loaded": True, "updatedAt": now_iso()}))

        now = datetime.now(KST)
        recent = now - timedelta(minutes=5)  # 유예(30분) 안
        stale = now - timedelta(minutes=40)  # 유예 지남
        today = now.strftime("%Y-%m-%d")
        agent.snapshot = make_recurring_snapshot([("s1", "fmt1", recent), ("s2", "fmt2", stale)])
        agent.snapshot["renders"] = [
            save_ready_render(agent, today, "r1", "fmt1"),
            save_ready_render(agent, today, "r2", "fmt2"),
        ]

        # 1틱: 미동기 — 아무 것도 하지 않는다.
        agent._do_scheduler_tick()
        assert counting_printer.print_calls == 0
        recent_key = f"s1@{today}T{recent.strftime('%H:%M')}"
        stale_key = f"s2@{today}T{stale.strftime('%H:%M')}"
        assert agent.storage.get_executed_occurrence(recent_key) is None
        assert agent.storage.get_executed_occurrence(stale_key) is None

        # 동기화됨
        sync_state["value"] = True
        agent._do_scheduler_tick()

        recent_row = agent.storage.get_executed_occurrence(recent_key)
        assert recent_row["status"] == "printed"
        assert recent_row["final"] == 1
        assert counting_printer.print_calls == 1

        stale_row = agent.storage.get_executed_occurrence(stale_key)
        assert stale_row["status"] == "skipped_clock_unsynced"
        assert stale_row["final"] == 1

        # 결과 큐: 각각 정확히 한 번만 쌓였는지(중복 없음)
        queued = agent.storage.get_queued_results()
        statuses = [q["payload_json"]["status"] for q in queued]
        assert statuses.count("printed") == 1
        assert statuses.count("skipped_clock_unsynced") == 1

        # 한 번 더 틱을 돌려도 결과가 늘어나지 않는다(정확히 한 번 불변식).
        agent._do_scheduler_tick()
        queued2 = agent.storage.get_queued_results()
        assert len(queued2) == len(queued)
        assert counting_printer.print_calls == 1

    def test_3_repeated_ticks_while_unsynced_keep_warning(self, tmp_path, caplog):
        """timedatectl 실패/부재가 지속되는 동안 매번 경고 로그를 남긴다(운영 중
        조용히 인쇄가 멈추는 것을 막는 유일한 단서)."""
        config = make_agent_config(tmp_path, grace_minutes=30)
        agent = main_module.Agent(config, clock_synced_check=lambda: False)
        agent.snapshot = {"schedules": [], "renders": []}

        with caplog.at_level("WARNING"):
            agent._do_scheduler_tick()
            agent._do_scheduler_tick()

        warnings = [r for r in caplog.records if "동기화" in r.message or "sync" in r.message.lower()]
        assert len(warnings) >= 2

    def test_command_execution_also_paused_while_unsynced(self, tmp_path):
        """설계 결정(과제 1 "판단하세요"): "지금 인쇄" 명령도 미동기 동안은 보류한다
        — TTL 계산과 결과 payload의 executedAt이 전부 now_kst()에 의존해 시계를
        못 믿는 동안은 신뢰할 수 없기 때문이다(보고서 참고)."""
        config = make_agent_config(tmp_path, grace_minutes=30, paper_policy="unverified")
        agent = main_module.Agent(config, clock_synced_check=lambda: False)

        counting_printer = CountingPrinter(connected=True)
        agent.printer = counting_printer
        agent.executor.printer = counting_printer

        today = datetime.now(KST).strftime("%Y-%m-%d")
        render = save_ready_render(agent, today, "r1", "fmt1")
        agent.snapshot = {"schedules": [], "renders": [render]}
        agent.storage.save_command(
            "cmd-1", "fmt1", "r1", "deadbeef", paper_confirmed=True, created_at=now_iso()
        )

        agent._do_scheduler_tick()

        assert counting_printer.print_calls == 0
        cmd = agent.storage.get_command("cmd-1")
        assert cmd["status"] == "pending"  # checking으로도 넘어가지 않았다(아예 보류)


class TestSentBytesPruneWiring:
    """보낸 바이트 순환 삭제가 시계 게이트 **뒤에서만**, 하루 1회 도는지.

    RTC가 없는 Pi에서 동기화 전 `now`로 순환 삭제를 돌리면 멀쩡한 기록을
    지울 수 있다(docs/pi/policy.md 4·7절).
    """

    def _agent(self, tmp_path, synced_flag):
        config = make_agent_config(tmp_path, sent_retention_days=30)
        agent = main_module.Agent(config, clock_synced_check=lambda: synced_flag["v"])
        agent.snapshot = {"schedules": [], "renders": []}
        sent = agent.data_dir / "sent"
        for d in ("2026-07-01", "2026-09-18"):  # 79일 전(삭제 대상), 오늘
            (sent / d).mkdir(parents=True, exist_ok=True)
            (sent / d / "r.bin").write_bytes(b"x")
        return agent, sent

    def test_unsynced_clock_never_prunes(self, tmp_path, monkeypatch):
        synced = {"v": False}
        agent, sent = self._agent(tmp_path, synced)
        monkeypatch.setattr(main_module, "now_kst", lambda: datetime(2026, 9, 18, 8, 0, tzinfo=KST))

        agent._do_scheduler_tick()

        assert (sent / "2026-07-01").exists(), "미동기 시계로 순환 삭제가 돌면 안 된다"

    def test_synced_prunes_old_and_keeps_recent(self, tmp_path, monkeypatch):
        synced = {"v": True}
        agent, sent = self._agent(tmp_path, synced)
        monkeypatch.setattr(main_module, "now_kst", lambda: datetime(2026, 9, 18, 8, 0, tzinfo=KST))

        agent._do_scheduler_tick()

        assert not (sent / "2026-07-01").exists()
        assert (sent / "2026-09-18").exists()

    def test_runs_at_most_once_per_day(self, tmp_path, monkeypatch):
        synced = {"v": True}
        agent, sent = self._agent(tmp_path, synced)
        calls = []
        real = main_module.prune_sent_bytes
        monkeypatch.setattr(main_module, "prune_sent_bytes", lambda *a, **k: calls.append(a) or real(*a, **k))

        monkeypatch.setattr(main_module, "now_kst", lambda: datetime(2026, 9, 18, 8, 0, tzinfo=KST))
        agent._do_scheduler_tick()
        agent._do_scheduler_tick()
        assert len(calls) == 1

        monkeypatch.setattr(main_module, "now_kst", lambda: datetime(2026, 9, 19, 8, 0, tzinfo=KST))
        agent._do_scheduler_tick()
        assert len(calls) == 2

    def test_prune_failure_does_not_break_tick(self, tmp_path, monkeypatch, caplog):
        synced = {"v": True}
        agent, sent = self._agent(tmp_path, synced)

        def boom(*a, **k):
            raise OSError("disk gone")

        monkeypatch.setattr(main_module, "prune_sent_bytes", boom)
        monkeypatch.setattr(main_module, "now_kst", lambda: datetime(2026, 9, 18, 8, 0, tzinfo=KST))

        agent._do_scheduler_tick()  # 예외가 새어 나오면 여기서 실패

        assert "순환 삭제 실패" in caplog.text
