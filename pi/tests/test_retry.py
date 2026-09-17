"""유예 내 재시도 테스트(.temp/05 설계 6.1, 과제 1).

HARU_RETRY_INTERVAL_SEC가 실제로 쓰이는지, 그리고 재시도 도입이 중복 인쇄를
만들지 않는지(전송 시작 후 실패는 절대 재시도하지 않는다)를 확인한다.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

from agent import __main__ as main_module
from agent.config import AgentConfig
from agent.executor import Executor
from agent.scheduler import Occurrence, filter_executable_occurrences
from agent.storage import Storage
from printer.fake import FakePrinter

KST = timezone(timedelta(hours=9))


@pytest.fixture(autouse=True)
def _isolate_fake_printer_data_dir(tmp_path, monkeypatch):
    """FakePrinter는 생성 시점에 HARU_DATA_DIR을 직접 읽는다(agent.data_dir과
    무관) — 설정하지 않으면 공용 /tmp/haru-paper-fake에 파일을 남긴다. 테스트마다
    독립된 tmp_path로 고정한다."""
    monkeypatch.setenv("HARU_DATA_DIR", str(tmp_path / "fake-printer-data"))


class ExplodingPrinter(FakePrinter):
    """print_image가 호출되면 즉시 테스트를 실패시킨다(호출되면 안 되는 경로용)."""

    def print_image(self, png_bytes):
        raise AssertionError("print_image가 호출되면 안 되는 경로에서 호출됨")


def make_executor(tmp_path, paper_policy="manual_flag", printer=None, command_ttl_sec=600) -> Executor:
    storage = Storage(tmp_path / "agent.db")
    if printer is None:
        printer = FakePrinter(connected=True)
    return Executor(
        storage=storage,
        sync=None,
        printer=printer,
        data_dir=tmp_path,
        paper_policy=paper_policy,
        command_ttl_sec=command_ttl_sec,
    )


def make_occurrence(tmp_path_or_ex, today: str) -> Occurrence:
    """예약 시각을 실제 현재 시각으로 둔다(초 단위를 0으로 맞춰 occurrence_key와
    일치시킨다) — 하드코딩한 07:00을 쓰면 테스트를 실제로 07:00~07:30 사이에
    돌릴 때만 통과하는 시간대 의존 버그가 생긴다."""
    now = datetime.now(KST).replace(second=0, microsecond=0)
    hh_mm = now.strftime("%H:%M")
    return Occurrence(
        occurrence_key=f"s1@{today}T{hh_mm}",
        schedule_id="s1",
        format_id="fmt1",
        scheduled_at=now,
    )


def save_ready_render(ex: Executor, today: str, render_id="r1", format_id="fmt1") -> dict:
    png_path = ex.data_dir / "renders" / f"{render_id}.png"
    png_path.parent.mkdir(parents=True, exist_ok=True)
    png_path.write_bytes(b"fake-png-bytes")
    ex.storage.save_render(render_id, format_id, today, "deadbeef", str(png_path), verified=True)
    return {
        "renderId": render_id,
        "formatId": format_id,
        "targetDate": today,
        "sha256": "deadbeef",
        "renderedAt": f"{today}T00:00:00+09:00",
    }


class TestFilterExecutableRetryInterval:
    """T2: 재시도 간격 경계값. 실행기 없이 scheduler만 단위 테스트한다."""

    def test_just_before_interval_not_candidate(self):
        now = datetime(2026, 9, 17, 7, 1, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        last_attempt = now - timedelta(seconds=59)
        occ = Occurrence("s1@2026-09-17T07:00", "s1", "fmt1", scheduled)
        existing = {
            occ.occurrence_key: {"final": False, "status": "skipped_no_paper", "last_attempt_at": last_attempt.isoformat()}
        }
        executable = filter_executable_occurrences([occ], now, 30, existing, 60)
        assert executable == []

    def test_just_after_interval_is_candidate(self):
        now = datetime(2026, 9, 17, 7, 1, 2, tzinfo=KST)
        scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        last_attempt = now - timedelta(seconds=61)
        occ = Occurrence("s1@2026-09-17T07:00", "s1", "fmt1", scheduled)
        existing = {
            occ.occurrence_key: {"final": False, "status": "skipped_no_paper", "last_attempt_at": last_attempt.isoformat()}
        }
        executable = filter_executable_occurrences([occ], now, 30, existing, 60)
        assert executable == [occ]

    def test_no_last_attempt_at_is_immediately_candidate(self):
        """시도 기록이 아예 없으면(첫 실행) 재시도 간격을 기다리지 않는다."""
        now = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        occ = Occurrence("s1@2026-09-17T07:00", "s1", "fmt1", scheduled)
        executable = filter_executable_occurrences([occ], now, 30, {}, 60)
        assert executable == [occ]


class TestFilterExecutableCheckingVsAttempting:
    """④[중요, 2026-09-17 Opus 검토로 발견]: 'checking'(바이트가 나가기 전에 멈춘
    시도 — begin_occurrence_attempt 직후 프로세스가 죽거나 _check_paper_policy가
    예외를 낸 경우)은 'attempting'과 달리 재시도 간격만 지나면 다시 후보가 된다.
    예전에는 둘 다 무조건 건너뛰어, 'checking'에서 멈춘 회차가 유예 만료까지
    영원히 재시도되지 않았다."""

    def test_checking_past_retry_interval_becomes_candidate(self):
        now = datetime(2026, 9, 17, 7, 2, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        last_attempt = now - timedelta(seconds=61)
        occ = Occurrence("s1@2026-09-17T07:00", "s1", "fmt1", scheduled)
        existing = {
            occ.occurrence_key: {"final": False, "status": "checking", "last_attempt_at": last_attempt.isoformat()}
        }
        executable = filter_executable_occurrences([occ], now, 30, existing, 60)
        assert executable == [occ]

    def test_checking_within_retry_interval_not_yet_candidate(self):
        """checking도 다른 재시도 대상과 동일하게 재시도 간격은 지켜야 한다
        (즉시 무조건 허용은 아니다 — 60초 간격 규약, policy.md 3절)."""
        now = datetime(2026, 9, 17, 7, 0, 30, tzinfo=KST)
        scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        last_attempt = now - timedelta(seconds=10)
        occ = Occurrence("s1@2026-09-17T07:00", "s1", "fmt1", scheduled)
        existing = {
            occ.occurrence_key: {"final": False, "status": "checking", "last_attempt_at": last_attempt.isoformat()}
        }
        executable = filter_executable_occurrences([occ], now, 30, existing, 60)
        assert executable == []

    def test_attempting_never_becomes_candidate_even_after_long_interval(self):
        """attempting은 바이트가 나갔을 수 있으므로 재시도 간격이 아무리 지나도
        절대 후보가 아니다(중복 인쇄 방지의 핵심 불변식, R1) — checking과의 대비."""
        now = datetime(2026, 9, 17, 8, 0, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        last_attempt = now - timedelta(hours=1)
        occ = Occurrence("s1@2026-09-17T07:00", "s1", "fmt1", scheduled)
        existing = {
            occ.occurrence_key: {"final": False, "status": "attempting", "last_attempt_at": last_attempt.isoformat()}
        }
        executable = filter_executable_occurrences([occ], now, 30, existing, 60)
        assert executable == []


class TestRetryEndToEnd:
    def test_t1_skipped_no_paper_leaves_final_zero(self, tmp_path):
        """T1: skipped_no_paper 후 행이 final=0으로 남는다(재시도 대상)."""
        ex = make_executor(tmp_path, paper_policy="manual_flag")
        today = datetime.now(KST).strftime("%Y-%m-%d")
        render = save_ready_render(ex, today)
        occ = make_occurrence(tmp_path, today)
        snapshot = {"renders": [render]}

        result = ex.execute_occurrence(occ, snapshot)

        assert result is None  # 아직 종결되지 않음(재시도 대상)
        stored = ex.storage.get_executed_occurrence(occ.occurrence_key)
        assert stored["status"] == "skipped_no_paper"
        assert stored["final"] == 0

    def test_t3_retry_after_paper_loaded_succeeds(self, tmp_path):
        """T3: manual_flag에서 1차 시도는 용지 꺼짐 → 스킵, paperState.loaded=true로
        바꾸고 재시도 간격이 지난 뒤 재시도 → printed."""
        printer = FakePrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="manual_flag", printer=printer)
        today = datetime.now(KST).strftime("%Y-%m-%d")
        render = save_ready_render(ex, today)
        occ = make_occurrence(tmp_path, today)
        snapshot = {"renders": [render]}

        # 1차: 용지 꺼짐 → skipped_no_paper, final=0
        result1 = ex.execute_occurrence(occ, snapshot)
        assert result1 is None
        stored1 = ex.storage.get_executed_occurrence(occ.occurrence_key)
        assert stored1["status"] == "skipped_no_paper"
        assert stored1["attempts"] == 1

        # 재시도 간격이 지난 것으로 backdate(테스트에서 직접 시간을 흘려보내지 않는다)
        backdated = (datetime.now(KST) - timedelta(seconds=61)).isoformat()
        ex.storage.save_executed_occurrence(
            occ.occurrence_key, status="skipped_no_paper", last_attempt_at=backdated, final=False
        )

        # 재시도 후보인지 scheduler로 확인
        existing = {occ.occurrence_key: ex.storage.get_executed_occurrence(occ.occurrence_key)}
        executable = filter_executable_occurrences([occ], datetime.now(KST), 30, existing, 60)
        assert executable == [occ]

        # 용지를 넣는다
        ex.storage.set_kv("paperState", json.dumps({"loaded": True}))

        # 2차 시도: printed
        result2 = ex.execute_occurrence(occ, snapshot)
        assert result2 is not None
        assert result2["status"] == "printed"

        stored2 = ex.storage.get_executed_occurrence(occ.occurrence_key)
        assert stored2["status"] == "printed"
        assert stored2["final"] == 1
        assert stored2["attempts"] == 2

        # T7 일부: 재시도를 거쳐도 결과는 정확히 1개만 큐에 있다(dry_run/스킵은 결과를
        # 만들지 않고, printed 종결 때만 만든다)
        queued = ex.storage.get_queued_results()
        assert len(queued) == 1
        assert queued[0]["payload_json"]["status"] == "printed"

    def test_t4_printed_is_never_retried(self):
        """T4: printed는 절대 재시도되지 않는다(간격이 아무리 지나도 후보 아님)."""
        now = datetime(2026, 9, 17, 10, 0, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        occ = Occurrence("s1@2026-09-17T07:00", "s1", "fmt1", scheduled)
        existing = {
            occ.occurrence_key: {
                "final": True,
                "status": "printed",
                "last_attempt_at": (now - timedelta(hours=1)).isoformat(),
            }
        }
        executable = filter_executable_occurrences([occ], now, 30, existing, 60)
        assert executable == []

    def test_t5_print_exception_finalizes_and_is_never_retried(self, tmp_path):
        """T5: print_image가 예외를 던지면 final=1이고, 재시도 간격이 지나도 후보가
        아니다 — 중복 인쇄 방지의 핵심."""

        class DyingPrinter(FakePrinter):
            def print_image(self, png_bytes):
                raise RuntimeError("전송 중 오류 (예: BT 타임아웃)")

        printer = DyingPrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="manual_flag", printer=printer)
        ex.storage.set_kv("paperState", json.dumps({"loaded": True}))
        today = datetime.now(KST).strftime("%Y-%m-%d")
        render = save_ready_render(ex, today)
        occ = make_occurrence(tmp_path, today)
        snapshot = {"renders": [render]}

        result = ex.execute_occurrence(occ, snapshot)
        assert result is not None
        assert result["status"] == "failed"

        stored = ex.storage.get_executed_occurrence(occ.occurrence_key)
        assert stored["status"] == "failed"
        assert stored["final"] == 1

        # 61초가 지났다고 해도 후보가 아니다(final=1이 우선)
        existing = {occ.occurrence_key: stored}
        executable = filter_executable_occurrences(
            [occ], datetime.now(KST) + timedelta(seconds=61), 30, existing, 60
        )
        assert executable == []

        # 결과는 정확히 1개
        queued = ex.storage.get_queued_results()
        assert len(queued) == 1
        assert queued[0]["payload_json"]["status"] == "failed"

    def test_t6_printer_offline_never_calls_print_image(self, tmp_path):
        """T6: 프린터 오프라인이면 print_image를 부르지 않고
        skipped_printer_offline·final=0으로 남긴다(재시도 대상)."""
        printer = ExplodingPrinter(connected=False)
        ex = make_executor(tmp_path, paper_policy="manual_flag", printer=printer)
        ex.storage.set_kv("paperState", json.dumps({"loaded": True}))
        today = datetime.now(KST).strftime("%Y-%m-%d")
        render = save_ready_render(ex, today)
        occ = make_occurrence(tmp_path, today)
        snapshot = {"renders": [render]}

        result = ex.execute_occurrence(occ, snapshot)

        assert result is None
        stored = ex.storage.get_executed_occurrence(occ.occurrence_key)
        assert stored["status"] == "skipped_printer_offline"
        assert stored["final"] == 0

    def test_t7_five_retries_then_success_yields_one_result_row(self, tmp_path):
        """T7: 재시도 5회(4번 스킵 + 1번 성공) 후에도 results_queue 행은 정확히 1개."""
        printer = FakePrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="manual_flag", printer=printer)
        today = datetime.now(KST).strftime("%Y-%m-%d")
        render = save_ready_render(ex, today)
        occ = make_occurrence(tmp_path, today)
        snapshot = {"renders": [render]}

        for _ in range(4):
            result = ex.execute_occurrence(occ, snapshot)
            assert result is None  # 용지 꺼짐 상태로 매번 재시도 대상

        ex.storage.set_kv("paperState", json.dumps({"loaded": True}))
        result = ex.execute_occurrence(occ, snapshot)
        assert result is not None
        assert result["status"] == "printed"

        stored = ex.storage.get_executed_occurrence(occ.occurrence_key)
        assert stored["attempts"] == 5
        assert stored["final"] == 1

        queued = ex.storage.get_queued_results()
        assert len(queued) == 1

    def test_t9_attempts_increment_first_attempt_at_stable(self, tmp_path):
        """T9: attempts가 재시도 횟수만큼 늘고 first_attempt_at은 첫 시도 값 그대로."""
        ex = make_executor(tmp_path, paper_policy="manual_flag")
        today = datetime.now(KST).strftime("%Y-%m-%d")
        render = save_ready_render(ex, today)
        occ = make_occurrence(tmp_path, today)
        snapshot = {"renders": [render]}

        ex.execute_occurrence(occ, snapshot)
        first = ex.storage.get_executed_occurrence(occ.occurrence_key)
        first_attempt_at = first["first_attempt_at"]
        assert first["attempts"] == 1

        ex.execute_occurrence(occ, snapshot)
        second = ex.storage.get_executed_occurrence(occ.occurrence_key)
        assert second["attempts"] == 2
        assert second["first_attempt_at"] == first_attempt_at  # 절대 안 바뀐다(1.1 실측 버그 수정)

        ex.execute_occurrence(occ, snapshot)
        third = ex.storage.get_executed_occurrence(occ.occurrence_key)
        assert third["attempts"] == 3
        assert third["first_attempt_at"] == first_attempt_at


def make_agent_config(tmp_path, **overrides) -> AgentConfig:
    defaults = dict(
        server_url="http://127.0.0.1:18080",
        device_token="test-token",
        poll_interval_sec=30,
        printer_driver="fake",
        transport="usb",
        bt_address="",
        paper_policy="unverified",
        grace_minutes=30,
        retry_interval_sec=77,  # 기본값(60)과 다른 값을 써서 실제로 전달되는지 확인
        h_offset_mm=2.0,
        data_dir=str(tmp_path),
        sent_retention_days=30,
        command_ttl_sec=600,
    )
    defaults.update(overrides)
    return AgentConfig(**defaults)


class TestConfigRetryIntervalWiring:
    """T8: HARU_RETRY_INTERVAL_SEC(config.retry_interval_sec)가 실제로
    filter_executable_occurrences까지 전달되는지 — 2026-09-17 이전에는 사용처가
    0건이었다."""

    def test_scheduler_tick_passes_configured_retry_interval(self, tmp_path, monkeypatch):
        config = make_agent_config(tmp_path)
        agent = main_module.Agent(config)
        agent.snapshot = {"schedules": [], "renders": []}  # occurrence 없음 → 명령 처리로 빠르게 빠짐

        captured = {}
        original = main_module.filter_executable_occurrences

        def spy(candidates, now, grace_minutes, existing_occurrences, retry_interval_sec):
            captured["retry_interval_sec"] = retry_interval_sec
            return original(candidates, now, grace_minutes, existing_occurrences, retry_interval_sec)

        monkeypatch.setattr(main_module, "filter_executable_occurrences", spy)

        agent._do_scheduler_tick()

        assert captured["retry_interval_sec"] == 77
