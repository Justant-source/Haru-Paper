"""인쇄 경로 격리 확인 — push(SSE) wake 콜백이 절대로 인쇄를 유발하지 않는지.

CLAUDE.md 불변식(용지를 눈으로 확인하기 전에는 래스터를 보내지 않는다)과 직접
관련은 없지만, 같은 급의 안전 불변식이다: `PushListener.on_wake`은
`Agent.run()`에서 `self._poll_wake.set` **한 줄**로만 연결된다(`agent/__main__.py`
`run()`의 `PushListener(...)` 생성부 — `on_wake=self._poll_wake.set`). 인쇄로
이어지는 모든 경로(스케줄러 틱 → `_run_occurrence_tick`/`_run_command_tick` →
`Executor`)는 오직 `_scheduler_loop`(자신의 `_wake` Event)만 돈다. wake 콜백을
아무리 많이 불러도 `_do_scheduler_tick`이 직접 불리지 않는 한 인쇄가 날 수
없다는 것을 여기서 실측으로 고정한다.

과거 함정 재발 방지(작업 지시서 배경 절): "인쇄 0회"만 확인하면 렌더가 아예
없어서 애초에 인쇄가 불가능했던 경우에도 테스트가 통과해 버린다(가드를
지워도 통과) — 그래서 여기서는 `test_lookback.py`의 `CountingPrinter` +
`_seed_render` 패턴을 그대로 가져와 **렌더가 실제로 존재하고 정상 스케줄러
틱이라면 인쇄됐을** 상태를 만든 뒤, (1) 양성 대조군으로 그 상태에서 정상
틱이 실제로 인쇄함을 먼저 확인하고, (2) 같은 상태에서 wake 콜백만 반복
호출해도 인쇄가 전혀 늘지 않음을 확인한다.
"""

from __future__ import annotations

import json
import threading
import time
from datetime import datetime, timedelta, timezone

import pytest

from agent import __main__ as main_module
from agent.config import AgentConfig
from printer.fake import FakePrinter

KST = timezone(timedelta(hours=9))


@pytest.fixture(autouse=True)
def _isolate_fake_printer_data_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("HARU_DATA_DIR", str(tmp_path / "fake-printer-data"))


class CountingPrinter(FakePrinter):
    """print_image 호출을 **세기만** 하고 예외를 던지지 않는다(test_lookback.py와
    동일한 이유 — 예외를 던지면 실행기가 삼켜 failed로 바꿔 버려서 "호출 안 됨"을
    증명하지 못한다. 호출 횟수를 직접 세야 한다)."""

    def __init__(self):
        super().__init__(connected=True)
        self.print_calls = 0

    def print_image(self, png_bytes):
        self.print_calls += 1
        return super().print_image(png_bytes)


def _seed_render(agent, target_date: str, render_id: str, format_id: str) -> dict:
    """인쇄가 **실제로 가능한** 상태를 만든다 — 렌더 파일과 저장소 행을 모두 둔다.
    (test_lookback.py의 동명 헬퍼를 그대로 복제 — 렌더가 없으면 가드를 지워도
    인쇄할 수 없어 "인쇄하지 않았다"는 단언이 무효가 된다.)"""
    png_path = agent.data_dir / "renders" / f"{render_id}.png"
    png_path.parent.mkdir(parents=True, exist_ok=True)
    png_path.write_bytes(b"fake-png-bytes")
    agent.storage.save_render(render_id, format_id, target_date, "deadbeef", str(png_path), verified=True)
    return {
        "renderId": render_id,
        "formatId": format_id,
        "targetDate": target_date,
        "sha256": "deadbeef",
        "renderedAt": f"{target_date}T00:00:00+09:00",
    }


def make_agent_config(tmp_path, **overrides) -> AgentConfig:
    defaults = dict(
        server_url="http://127.0.0.1:18080",
        device_token="test-token",
        poll_interval_sec=300,
        printer_driver="fake",
        transport="usb",
        bt_address="",
        paper_policy="manual_flag",  # loaded=True로 두면 용지 게이트를 통과시킬 수 있다
        grace_minutes=30,
        retry_interval_sec=60,
        h_offset_mm=2.0,
        data_dir=str(tmp_path),
        sent_retention_days=30,
        command_ttl_sec=600,
        events_enabled=False,
    )
    defaults.update(overrides)
    return AgentConfig(**defaults)


def _make_agent_with_ready_render(tmp_path):
    """렌더가 준비되고, 오늘 07:05(유예 30분 안)에 예약이 있고, 용지 게이트도
    통과하는 상태의 Agent를 만든다 — 정상 스케줄러 틱이라면 인쇄됐을 상황."""
    config = make_agent_config(tmp_path)
    agent = main_module.Agent(config, clock_synced_check=lambda: True)
    agent.storage.set_kv("paperState", json.dumps({"loaded": True}))

    printer = CountingPrinter()
    agent.printer = printer
    agent.executor.printer = printer

    now = datetime.now(KST)
    scheduled = now - timedelta(minutes=5)  # 유예(30분) 안
    today = now.strftime("%Y-%m-%d")
    render = _seed_render(agent, today, "r1", "fmt1")

    weekday_map = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
    agent.snapshot = {
        "schedules": [{
            "id": "s1", "formatId": "fmt1", "type": "recurring",
            "daysOfWeek": [weekday_map[scheduled.weekday()]],
            "time": scheduled.strftime("%H:%M"), "enabled": True,
        }],
        "renders": [render],
    }
    return agent, printer


class TestPositiveControlSchedulerTickPrints:
    """양성 대조군: 이 상태 세팅 자체가 유효함을 먼저 증명한다. 이게 실패하면
    아래 음성 테스트의 "0회"는 아무 의미가 없다(가드 때문인지 셋업 실수인지
    구분이 안 된다)."""

    def test_normal_scheduler_tick_actually_prints(self, tmp_path):
        agent, printer = _make_agent_with_ready_render(tmp_path)

        agent._do_scheduler_tick()

        assert printer.print_calls == 1, (
            "양성 대조군 실패 — 정상 스케줄러 틱에서도 인쇄가 안 됨. 이 상태로는 "
            "아래 '리스너가 인쇄를 유발하지 않는다' 테스트가 무의미하다."
        )


class TestPushWakeNeverPrints:
    """음성 테스트: 같은(인쇄 가능한) 상태에서 wake 콜백만 반복 호출해도 인쇄가
    전혀 늘지 않는다.

    `on_wake` 콜백 자체가 `agent._poll_wake.set` 한 줄뿐임을 코드로도 확인한다
    (`agent/__main__.py` `run()`의 `PushListener(..., on_wake=self._poll_wake.set, ...)`
    — 다른 인자를 계산하거나 executor/printer를 참조하는 코드가 섞여 있지 않다.
    그래서 이 콜백이 `_do_scheduler_tick`이나 `Executor`를 직접 부를 방법이
    구조적으로 없다 — 아래 두 테스트는 이를 실측으로 고정한다)."""

    def test_on_wake_callback_called_many_times_never_prints(self, tmp_path):
        """`on_wake` 콜백(=`agent._poll_wake.set`)을 100번 연달아 직접 호출해도
        `print_image` 호출 횟수가 전혀 늘지 않는다. 폴링/스케줄러 스레드는 아예
        띄우지 않는다 — 콜백 자체가 인쇄와 무관함을 스레드 타이밍에 기대지 않고
        확인한다."""
        agent, printer = _make_agent_with_ready_render(tmp_path)

        on_wake = agent._poll_wake.set  # run()이 PushListener에 넘기는 것과 동일한 콜백
        for _ in range(100):
            on_wake()

        assert printer.print_calls == 0
        # occurrence도 전혀 시도되지 않았어야 한다(스케줄러 틱을 아예 안 돌렸으므로).
        today = datetime.now(KST).strftime("%Y-%m-%d")
        assert agent.storage.get_queued_results() == []

    def test_poll_wake_set_via_running_loops_never_triggers_scheduler_or_print(self, tmp_path):
        """실제 폴링 루프를 띄운 채 `agent._poll_wake.set()`을 100번 연달아
        호출해도(디바운스 때문에 실제 폴링 자체는 몇 번 안 일어나겠지만, 그것과
        무관하게) `print_image` 호출 횟수는 늘지 않는다. 스케줄러 루프는 이
        테스트에서 의도적으로 띄우지 않는다 — 스케줄러가 돌지 않는 한 폴링이
        아무리 반복돼도 인쇄로 이어질 수 없다는 것 자체가 이 불변식의 핵심이다."""
        agent, printer = _make_agent_with_ready_render(tmp_path)

        class NoOpSyncForPoll:
            def poll(self, heartbeat):
                from agent.sync import PollResponse
                return PollResponse(
                    server_time=datetime.now(KST).isoformat(),
                    snapshot_hash="",
                    snapshot_changed=False,
                    commands=[],
                    paper_state=None,
                    poll_interval_sec=300,
                )

        agent.sync = NoOpSyncForPoll()

        agent.running = True
        thread = threading.Thread(target=agent._polling_loop, daemon=True)
        thread.start()
        try:
            time.sleep(0.1)  # 초기 폴링 1회가 자리잡을 시간
            for _ in range(100):
                agent._poll_wake.set()
                time.sleep(0.005)
            time.sleep(0.5)

            assert printer.print_calls == 0
        finally:
            agent.stop()
            thread.join(timeout=3)
            assert not thread.is_alive()
