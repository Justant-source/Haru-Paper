"""유예 만료 처리 → 결과 업로드 테스트(.temp/05 설계 6.2, 과제 3).

2026-09-17에 새로 발견한 결함: `missed`는 지금까지 단 한 번도 서버에 올라간 적이
없었다(__main__.py의 유예 만료 처리가 save_executed_occurrence만 부르고
save_result를 부르지 않았다). Agent._do_scheduler_tick() 전체를 통해 확인한다
(Executor.finalize_occurrence 단위 테스트는 tests/test_executor.py의
TestExecutorFinalizesStaleAttempts에 있다).
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from agent import __main__ as main_module
from agent.config import AgentConfig

KST = timezone(timedelta(hours=9))


@pytest.fixture(autouse=True)
def _isolate_fake_printer_data_dir(tmp_path, monkeypatch):
    """FakePrinter는 생성 시점에 HARU_DATA_DIR을 직접 읽는다(agent.data_dir과
    무관) — 설정하지 않으면 공용 /tmp/haru-paper-fake에 파일을 남긴다. 테스트마다
    독립된 tmp_path로 고정한다."""
    monkeypatch.setenv("HARU_DATA_DIR", str(tmp_path / "fake-printer-data"))


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
        retry_interval_sec=60,
        h_offset_mm=2.0,
        data_dir=str(tmp_path),
        sent_retention_days=30,
        command_ttl_sec=600,
    )
    defaults.update(overrides)
    return AgentConfig(**defaults)


def make_snapshot_with_recurring_schedule(schedule_id: str, format_id: str, scheduled: datetime) -> dict:
    weekday_map = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
    return {
        "schedules": [
            {
                "id": schedule_id,
                "formatId": format_id,
                "type": "recurring",
                "daysOfWeek": [weekday_map[scheduled.weekday()]],
                "time": scheduled.strftime("%H:%M"),
                "enabled": True,
            }
        ],
        "renders": [],
    }


class TestGraceExpiryResultUpload:
    def test_t11_missed_result_is_queued_when_never_attempted(self, tmp_path):
        """T11: 유예 만료 + 시도 기록이 아예 없음 → missed 결과가 큐에 들어간다."""
        # 예약 시각을 유예(30분)보다 훨씬 전으로 둔다 — occurrence_key도 그 시각
        # 기준으로 계산돼야 하므로, calculate_occurrences가 "오늘 실행 시각"을
        # 이미 지난 시각으로 계산하도록 스케줄 time을 과거로 준다.
        scheduled = datetime.now(KST) - timedelta(minutes=45)
        config = make_agent_config(tmp_path, grace_minutes=30)
        agent = main_module.Agent(config)
        agent.snapshot = make_snapshot_with_recurring_schedule("s1", "fmt1", scheduled)

        agent._do_scheduler_tick()

        occurrence_key = f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}"
        stored = agent.storage.get_executed_occurrence(occurrence_key)
        assert stored is not None
        assert stored["status"] == "missed"
        assert stored["final"] == 1

        queued = agent.storage.get_queued_results()
        assert len(queued) == 1
        payload = queued[0]["payload_json"]
        assert payload["status"] == "missed"
        assert payload["occurrenceKey"] == occurrence_key
        assert payload["commandId"] is None

    def test_t11b_last_reason_preserved_over_missed(self, tmp_path):
        """T11b: 유예 만료 시 마지막 사유가 skipped_no_paper였으면 최종 status도
        skipped_no_paper(missed로 뭉개지 않는다)."""
        scheduled = datetime.now(KST) - timedelta(minutes=45)
        config = make_agent_config(tmp_path, grace_minutes=30, paper_policy="manual_flag")
        agent = main_module.Agent(config)
        agent.snapshot = make_snapshot_with_recurring_schedule("s1", "fmt1", scheduled)

        occurrence_key = f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}"
        # 실제로 한 번 시도해서 skipped_no_paper로 남긴 상태를 흉내낸다(용지 꺼짐).
        agent.storage.begin_occurrence_attempt(
            occurrence_key, format_id="fmt1", render_id="", scheduled_at=scheduled.isoformat(), result_id="r-1"
        )
        agent.storage.finish_occurrence_attempt(
            occurrence_key, status="skipped_no_paper", detail="", final=False
        )

        agent._do_scheduler_tick()

        stored = agent.storage.get_executed_occurrence(occurrence_key)
        assert stored["status"] == "skipped_no_paper"
        assert stored["final"] == 1

        queued = agent.storage.get_queued_results()
        assert len(queued) == 1
        assert queued[0]["payload_json"]["status"] == "skipped_no_paper"

    def test_t11c_non_terminal_status_mapped_to_failed(self, tmp_path):
        """T11c: 유예 만료 시 status가 attempting/checking처럼 서버 ENUM에 없는
        내부 상태로 남아 있으면 failed로 매핑된다(서버 INSERT 보호)."""
        scheduled = datetime.now(KST) - timedelta(minutes=45)
        config = make_agent_config(tmp_path, grace_minutes=30, paper_policy="manual_flag")
        agent = main_module.Agent(config)
        agent.snapshot = make_snapshot_with_recurring_schedule("s1", "fmt1", scheduled)

        occurrence_key = f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}"
        # attempting으로 남은 상태(예: 이 틱 전에 죽었다고 가정) — 기동 정리 대상이
        # 되기 전에 유예가 먼저 만료된 경우를 흉내낸다.
        agent.storage.begin_occurrence_attempt(
            occurrence_key, format_id="fmt1", render_id="", scheduled_at=scheduled.isoformat(), result_id="r-1"
        )
        agent.storage.mark_occurrence_attempting(occurrence_key)

        agent._do_scheduler_tick()

        stored = agent.storage.get_executed_occurrence(occurrence_key)
        assert stored["status"] == "failed"
        assert stored["final"] == 1

        queued = agent.storage.get_queued_results()
        assert len(queued) == 1
        assert queued[0]["payload_json"]["status"] == "failed"
