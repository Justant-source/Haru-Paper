"""`Agent._poll_wake`(폴링 전용 Event) 테스트.

`_polling_loop`는 이제 `time.sleep(poll_interval_sec)` 대신
`self._poll_wake.wait(self.poll_interval_sec)`로 대기한다(`pi/agent/__main__.py`
`_polling_loop`). push(SSE) wake가 오면(`agent.py`에서는 `PushListener.on_wake` ==
`self._poll_wake.set`) 이 대기가 즉시 풀린다. 단, 몰아쳐 오는 wake로 폴링이
폭증하지 않도록 `PUSH_MIN_POLL_GAP_SEC`(2.0초, `Agent` 클래스 상수) 최소 간격
디바운스가 있다 — 깨어난 직후 그 간격을 다 못 채웠으면 남은 시간만큼 한 번 더
잔다(`_polling_loop` 마지막 블록).

이 파일은 이 디바운스가 실제로 동작하는지, 그리고 `_poll_wake`(폴링 전용)와
`_wake`(스케줄러 전용, `_scheduler_loop`)가 서로 독립적인 Event인지를 확인한다.
네트워크는 열지 않는다(`NoOpSync`, `printer_driver="fake"` —
`test_heartbeat_printer_lock.py`의 패턴을 그대로 따른다).
"""

from __future__ import annotations

import threading
import time
from datetime import datetime, timedelta, timezone

import pytest

from agent import __main__ as main_module
from agent.config import AgentConfig
from agent.sync import PollResponse
from printer.fake import FakePrinter

KST = timezone(timedelta(hours=9))


@pytest.fixture(autouse=True)
def _isolate_fake_printer_data_dir(tmp_path, monkeypatch):
    """FakePrinter는 생성 시점에 HARU_DATA_DIR을 직접 읽는다 — test_heartbeat_printer_lock.py
    와 같은 이유로 공용 /tmp 경로를 쓰지 않게 격리한다."""
    monkeypatch.setenv("HARU_DATA_DIR", str(tmp_path / "fake-printer-data"))


class NoOpSync:
    """poll()만 최소 응답을 돌려준다. snapshot/명령/업로드 경로를 건드리지 않는다.

    poll_interval_sec은 서버가 지시한 값으로 매 poll마다 agent.poll_interval_sec을
    덮어쓴다(`_do_poll`의 6단계) — 테스트에서 아주 큰 값(예: 300)을 계속 돌려줘야
    "안 깨웠으면 이 시간 안에 두 번째 폴링이 절대 안 난다"는 전제가 유지된다.
    """

    def __init__(self, poll_interval_sec: int = 300):
        self.poll_interval_sec = poll_interval_sec

    def poll(self, heartbeat: dict) -> PollResponse:
        return PollResponse(
            server_time=datetime.now(KST).isoformat(),
            snapshot_hash="",
            snapshot_changed=False,
            commands=[],
            paper_state=None,
            poll_interval_sec=self.poll_interval_sec,
        )


def make_agent_config(tmp_path, **overrides) -> AgentConfig:
    defaults = dict(
        server_url="http://127.0.0.1:18080",
        device_token="test-token",
        poll_interval_sec=300,  # 큰 값 — 자연 폴링 주기로는 테스트 시간 안에 절대 안 돈다
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
        events_enabled=False,  # 이 파일은 PushListener 통합이 아니라 _poll_wake 자체를 본다
    )
    defaults.update(overrides)
    return AgentConfig(**defaults)


def make_agent(tmp_path, **overrides) -> main_module.Agent:
    config = make_agent_config(tmp_path, **overrides)
    agent = main_module.Agent(config)
    agent.sync = NoOpSync(poll_interval_sec=config.poll_interval_sec)
    return agent


def _wait_until(predicate, timeout=3.5, interval=0.02) -> bool:
    """predicate()가 True가 될 때까지 최대 timeout초 짧은 간격으로 폴링한다.
    실시간 스레드 타이밍을 보는 테스트라 정확한 시각을 고정할 수 없어, 조건이
    성립하는 순간 바로 반환해 테스트를 불필요하게 느리게 만들지 않는다."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return True
        time.sleep(interval)
    return predicate()


def _stop_and_join(agent: main_module.Agent, *threads: threading.Thread) -> None:
    """모든 테스트가 끝에 반드시 거치는 정리 — 백그라운드 스레드가 다음 테스트로
    새지 않게 한다."""
    agent.stop()
    for t in threads:
        t.join(timeout=3)
        assert not t.is_alive(), "폴링/스케줄러 스레드가 정리되지 않고 남음"


class TestPollWakeTriggersImmediatePoll:
    def test_set_causes_extra_poll_well_before_interval(self, tmp_path):
        """`_poll_wake.set()`을 부르면, `poll_interval_sec`(300초)을 다 기다리지
        않고 훨씬 이른 시점(디바운스 상한 근처, 수 초 안)에 `_do_poll`이 한 번 더
        불린다 — 안 깨웠으면 이 테스트 시간 안에는 절대 두 번째 폴링이 나지
        않는다(300초 vs 테스트 타임아웃 수 초)."""
        agent = make_agent(tmp_path)
        poll_calls = []
        orig_do_poll = agent._do_poll

        def counting_do_poll():
            poll_calls.append(time.monotonic())
            return orig_do_poll()

        agent._do_poll = counting_do_poll

        agent.running = True
        thread = threading.Thread(target=agent._polling_loop, daemon=True)
        thread.start()
        try:
            assert _wait_until(lambda: len(poll_calls) >= 1, timeout=2.0), "초기 폴링이 나지 않음"

            agent._poll_wake.set()

            got_second_poll = _wait_until(lambda: len(poll_calls) >= 2, timeout=3.5)
            assert got_second_poll, (
                f"poll_wake.set() 후 3.5초 안에 두 번째 _do_poll이 나지 않음 "
                f"(poll_interval_sec=300이므로 안 깨웠으면 이 시간 안에 절대 안 남) — {poll_calls}"
            )
        finally:
            _stop_and_join(agent, thread)


class TestPollWakeDebounce:
    def test_rapid_sets_do_not_flood_polling(self, tmp_path):
        """`_poll_wake.set()`을 아주 짧은 간격으로 5번 연달아 호출해도, 그 직후
        관찰 구간(3초) 동안 늘어난 `_do_poll` 호출 수는 5보다 훨씬 적다
        (`PUSH_MIN_POLL_GAP_SEC` 최소 간격 디바운스, `_polling_loop` 참고)."""
        agent = make_agent(tmp_path)
        poll_calls = []
        orig_do_poll = agent._do_poll

        def counting_do_poll():
            poll_calls.append(time.monotonic())
            return orig_do_poll()

        agent._do_poll = counting_do_poll

        agent.running = True
        thread = threading.Thread(target=agent._polling_loop, daemon=True)
        thread.start()
        try:
            assert _wait_until(lambda: len(poll_calls) >= 1, timeout=2.0), "초기 폴링이 나지 않음"
            count_before = len(poll_calls)

            for _ in range(5):
                agent._poll_wake.set()
                time.sleep(0.01)

            time.sleep(3.0)
            extra_polls = len(poll_calls) - count_before
            assert extra_polls < 5, f"디바운스 없이 5번 다 반영된 것처럼 보임: extra_polls={extra_polls}"
            assert extra_polls <= 2, f"디바운스 상한(약 2초 간격)을 크게 벗어남: extra_polls={extra_polls}"
        finally:
            _stop_and_join(agent, thread)


class TestPollWakeAndSchedulerWakeAreIndependent:
    def test_poll_wake_does_not_trigger_scheduler_tick(self, tmp_path):
        """`agent._poll_wake`(폴링 전용)를 깨워도 `agent._wake`(스케줄러 전용)를
        소비하는 `_do_scheduler_tick`은 그 즉시 추가로 불리지 않는다 — 두 Event가
        서로 다른 객체이기 때문이다(`Agent.__init__` 주석: "두 소비자가 섞이면
        신호를 서로 잡아먹는다")."""
        agent = make_agent(tmp_path)
        tick_calls = []
        orig_tick = agent._do_scheduler_tick

        def counting_tick():
            tick_calls.append(time.monotonic())
            return orig_tick()

        agent._do_scheduler_tick = counting_tick
        agent.snapshot = {"schedules": [], "renders": []}

        agent.running = True
        poll_thread = threading.Thread(target=agent._polling_loop, daemon=True)
        sched_thread = threading.Thread(target=agent._scheduler_loop, daemon=True)
        poll_thread.start()
        sched_thread.start()
        try:
            assert _wait_until(lambda: len(tick_calls) >= 1, timeout=2.0), "초기 스케줄러 틱이 나지 않음"
            tick_count_before = len(tick_calls)

            agent._poll_wake.set()
            time.sleep(0.5)

            assert len(tick_calls) == tick_count_before, (
                "poll_wake.set()이 스케줄러 틱을 건드렸다 — _wake와 _poll_wake가 섞였을 가능성"
            )
        finally:
            _stop_and_join(agent, poll_thread, sched_thread)

    def test_scheduler_wake_does_not_trigger_extra_poll(self, tmp_path):
        """반대 방향: `agent._wake`(스케줄러 전용)를 깨워도 `_do_poll`은 그 즉시
        추가로 불리지 않는다. `_wake.set()`이 실제로 스케줄러를 깨우는지도 함께
        확인해(양성 대조) 이 테스트 자체가 죽은 채로 통과하는 것을 막는다."""
        agent = make_agent(tmp_path)
        poll_calls = []
        tick_calls = []
        orig_do_poll = agent._do_poll
        orig_tick = agent._do_scheduler_tick

        def counting_do_poll():
            poll_calls.append(time.monotonic())
            return orig_do_poll()

        def counting_tick():
            tick_calls.append(time.monotonic())
            return orig_tick()

        agent._do_poll = counting_do_poll
        agent._do_scheduler_tick = counting_tick
        agent.snapshot = {"schedules": [], "renders": []}

        agent.running = True
        poll_thread = threading.Thread(target=agent._polling_loop, daemon=True)
        sched_thread = threading.Thread(target=agent._scheduler_loop, daemon=True)
        poll_thread.start()
        sched_thread.start()
        try:
            assert _wait_until(lambda: len(poll_calls) >= 1 and len(tick_calls) >= 1, timeout=2.0), (
                "초기 폴링/스케줄러 틱이 나지 않음"
            )
            poll_count_before = len(poll_calls)
            tick_count_before = len(tick_calls)

            agent._wake.set()
            time.sleep(0.5)

            # 양성 대조: _wake는 실제로 스케줄러를 즉시 다시 돌게 한다(살아있는 채널임을 증명).
            assert len(tick_calls) > tick_count_before, "_wake.set()이 스케줄러 틱을 깨우지 못함(테스트 셋업 문제 의심)"
            # 본 확인: 같은 순간 폴링 카운터는 늘지 않는다.
            assert len(poll_calls) == poll_count_before, (
                "_wake.set()이 폴링을 건드렸다 — _wake와 _poll_wake가 섞였을 가능성"
            )
        finally:
            _stop_and_join(agent, poll_thread, sched_thread)


class TestStopUnblocksPollingLoop:
    def test_stop_releases_polling_thread(self, tmp_path):
        """`agent.stop()`은 `_poll_wake`를 즉시 set()해 폴링 루프의 대기
        (`poll_interval_sec`=300초)를 끊는다. 디바운스(`PUSH_MIN_POLL_GAP_SEC`,
        최대 2초)가 겹칠 수 있어 stop()을 초기 폴링으로부터 충분히(2초 이상)
        지난 뒤에 불러, 디바운스 sleep과 겹치지 않는 상태에서 "대기에서 풀려
        루프를 즉시 빠져나옴"만 순수하게 확인한다."""
        agent = make_agent(tmp_path)
        agent.running = True
        thread = threading.Thread(target=agent._polling_loop, daemon=True)
        thread.start()
        try:
            # 초기 _do_poll + 디바운스 여유(PUSH_MIN_POLL_GAP_SEC=2.0초)가 확실히
            # 지나 폴링 루프가 poll_wake.wait(300)에 순수하게 들어가 있을 때까지 기다린다.
            time.sleep(main_module.Agent.PUSH_MIN_POLL_GAP_SEC + 0.3)

            agent.stop()
            thread.join(timeout=2.0)
            assert not thread.is_alive(), "stop() 후 폴링 스레드가 2초 안에 종료되지 않음"
        finally:
            if thread.is_alive():
                agent.stop()
                thread.join(timeout=3)
