"""Agent 기동 시 'attempting' 정리 통합 테스트. docs/pi/agent.md 9절.

전송 도중(정전, OOM, `systemctl restart` 등) 죽으면 'attempting'·final=0 레코드가
남는다. 재시작 후 자동으로 다시 인쇄하지 않아야 한다 — Agent.__init__이 실제로
Storage.cleanup_stale_attempts()를 부르는지까지 확인한다(storage 단위 테스트는
test_executor.py의 TestCleanupStaleAttempts).

네트워크는 열지 않는다: printer_driver="fake", HttpPollSyncChannel은 생성자에서
소켓을 열지 않는다(requests.Session()만 만듦) — poll()을 호출하지 않으므로 안전.
"""

from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from agent.__main__ import Agent
from agent.config import AgentConfig
from agent.storage import Storage


def make_config(data_dir: str) -> AgentConfig:
    return AgentConfig(
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
        data_dir=data_dir,
        sent_retention_days=30,
    )


def test_agent_init_cleans_up_stale_attempting_record(tmp_path):
    data_dir = tmp_path / "haru-data"
    data_dir.mkdir(parents=True, exist_ok=True)

    # Agent 생성 전에 '전송 도중 죽은' 상태를 미리 심어 둔다.
    pre_storage = Storage(data_dir / "agent.db")
    key = "s1@2026-09-17T07:00"
    pre_storage.save_executed_occurrence(key, status="attempting", result_id="r-1", attempts=1, final=False)

    config = make_config(str(data_dir))
    agent = Agent(config)

    stored = agent.storage.get_executed_occurrence(key)
    assert stored["status"] == "failed"
    assert stored["final"] == 1


def test_agent_init_with_no_stale_records_is_a_noop(tmp_path):
    data_dir = tmp_path / "haru-data"
    data_dir.mkdir(parents=True, exist_ok=True)

    config = make_config(str(data_dir))
    # 예외 없이 생성되면 충분하다 (정리할 게 없어도 안전).
    agent = Agent(config)
    assert agent.storage is not None
