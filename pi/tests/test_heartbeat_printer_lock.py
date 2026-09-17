"""폴링 스레드의 heartbeat(printer.profile()/status())가 인쇄 중인 프린터 소켓을
가로채지 않는지 확인한다(③, 2026-09-17 Opus 검토로 발견).

M832Printer.status()는 `with self.transport:`로 실제 BT 연결을 연다. BtTransport는
소켓 하나를 공유하고 자체 락이 없다 — 스케줄러 스레드가 print_image 중(전체
데드라인 60초)일 때 30초 주기 heartbeat가 겹치면 소켓을 가로채 전송 중 send()가
실패할 수 있다(부분 인쇄 + failed). Executor._print_lock을 heartbeat도 거치되,
인쇄 60초를 기다리며 폴링이 멈추면 안 되므로 논블로킹으로 시도하고 실패하면 직전
값을 재사용한다.

하드웨어는 열지 않는다: FakePrinter만 쓰고, "인쇄 중"은 테스트에서 직접
executor._print_lock을 잡아 흉내낸다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from agent import __main__ as main_module
from agent.config import AgentConfig
from agent.sync import PollResponse
from printer.fake import FakePrinter

KST = timezone(timedelta(hours=9))


@pytest.fixture(autouse=True)
def _isolate_fake_printer_data_dir(tmp_path, monkeypatch):
    """FakePrinter는 생성 시점에 HARU_DATA_DIR을 직접 읽는다(agent.data_dir과
    무관) — 설정하지 않으면 공용 /tmp/haru-paper-fake에 파일을 남긴다."""
    monkeypatch.setenv("HARU_DATA_DIR", str(tmp_path / "fake-printer-data"))


class CountingPrinter(FakePrinter):
    """profile()/status() 호출 횟수를 센다."""

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.profile_calls = 0
        self.status_calls = 0

    def profile(self):
        self.profile_calls += 1
        return super().profile()

    def status(self):
        self.status_calls += 1
        return super().status()


class NoOpSync:
    """poll()만 최소 응답을 돌려준다. snapshot/명령/업로드 경로를 건드리지 않는다."""

    def poll(self, heartbeat: dict) -> PollResponse:
        self.last_heartbeat = heartbeat
        return PollResponse(
            server_time=datetime.now(KST).isoformat(),
            snapshot_hash="",
            snapshot_changed=False,
            commands=[],
            paper_state=None,
            poll_interval_sec=30,
        )


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


def make_agent_with_counting_printer(tmp_path) -> tuple[main_module.Agent, CountingPrinter]:
    config = make_agent_config(tmp_path)
    agent = main_module.Agent(config)
    printer = CountingPrinter(connected=True)
    agent.printer = printer
    agent.executor.printer = printer
    # __init__이 이미 초기 1회씩 불렀으므로(락 없이, 스레드 시작 전) 카운터를
    # 리셋해 테스트가 _do_poll 안에서의 호출만 본다.
    printer.profile_calls = 0
    printer.status_calls = 0
    agent.sync = NoOpSync()
    return agent, printer


class TestHeartbeatPrinterLock:
    def test_lock_free_refreshes_profile_and_status(self, tmp_path):
        """락이 비어 있으면(평소 상태) heartbeat가 실제로 printer.profile()/status()를
        불러 최신 값을 얻는다."""
        agent, printer = make_agent_with_counting_printer(tmp_path)

        agent._do_poll()

        assert printer.profile_calls == 1
        assert printer.status_calls == 1

    def test_lock_held_by_print_does_not_call_printer_and_reuses_last_values(self, tmp_path):
        """③[중요, 2026-09-17 Opus 검토로 발견]: 스케줄러 스레드가 인쇄 중이라
        _print_lock이 잡혀 있으면, heartbeat는 printer.profile()/status()를 다시
        부르지 않고(=BT 소켓을 건드리지 않고) 직전 값을 재사용한다. 폴링이 락을
        기다리며 멈추지도 않는다(이 테스트 자체가 타임아웃 없이 끝나는 것으로
        논블로킹임을 보여 준다)."""
        agent, printer = make_agent_with_counting_printer(tmp_path)

        # "인쇄 중"을 흉내낸다: 스케줄러 스레드가 하는 것과 같은 락을 테스트에서
        # 직접 잡는다.
        agent.executor._print_lock.acquire()
        try:
            agent._do_poll()
        finally:
            agent.executor._print_lock.release()

        # profile()/status()가 다시 호출되지 않았다 — 인쇄 중인 프린터 접근 없음
        assert printer.profile_calls == 0
        assert printer.status_calls == 0

        # 그런데도 poll은 정상적으로 나갔고, heartbeat에는 __init__ 시점에 캐시된
        # 직전 값이 실려 있다(생략이 아니라 재사용을 선택했다는 설계 결정 확인).
        sent = agent.sync.last_heartbeat
        assert sent["printerProfile"] == agent._last_profile.to_dict()
        assert sent["printerStatus"] == agent._last_status.to_dict()

    def test_lock_released_after_do_poll_even_when_acquired(self, tmp_path):
        """try_lock_printer가 락을 잡았으면 _do_poll이 끝난 뒤 반드시 풀어 준다
        (스케줄러 스레드가 다음 인쇄를 위해 잡을 수 있어야 한다)."""
        agent, printer = make_agent_with_counting_printer(tmp_path)

        agent._do_poll()

        # 락이 풀려 있어야 즉시 다시 잡을 수 있다(블록 없이).
        acquired = agent.executor._print_lock.acquire(blocking=False)
        assert acquired is True
        agent.executor._print_lock.release()
