"""Agent._load_printer / _load_transport 테스트.

2026-09-17에 발견된 버그 재발 방지용: `_load_printer`가 `HARU_TRANSPORT` 문자열을
실제 Transport 객체로 변환하지 않고 그대로 `M832Printer(transport=...)`에 넘기고
있었다 — `HARU_PRINTER_DRIVER=fake`인 동안은 이 경로를 타지 않아 드러나지 않았다.
여기서는 하드웨어를 열지 않는다(생성자만 호출 — `UsbTransport`/`BtTransport` 생성자는
장치를 열지 않고 속성만 설정한다. `.open()`은 호출하지 않는다).
"""

from __future__ import annotations

import sys
import os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import pytest

from agent.__main__ import Agent
from agent.config import AgentConfig
from printer.fake import FakePrinter
from printer.m832 import M832Printer
from transport.usb import UsbTransport
from transport.bt import BtTransport


def make_config(**overrides) -> AgentConfig:
    """테스트용 AgentConfig. 필요한 필드만 오버라이드."""
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
        data_dir="/tmp/haru-paper-test-unused",
        sent_retention_days=30,
        command_ttl_sec=600,
    )
    defaults.update(overrides)
    return AgentConfig(**defaults)


def bare_agent(config: AgentConfig) -> Agent:
    """Agent.__init__을 건너뛰고 config만 세팅한 인스턴스.

    __init__은 Storage(SQLite 파일 생성)·HttpPollSyncChannel·data_dir.mkdir 등
    무거운 초기화를 한다 — `_load_printer`/`_load_transport`만 단위 테스트하려는
    목적에는 불필요하고, 실제로도 하지 않는다.
    """
    agent = Agent.__new__(Agent)
    agent.config = config
    return agent


class TestLoadPrinter:
    def test_fake_driver(self):
        agent = bare_agent(make_config(printer_driver="fake"))
        printer = agent._load_printer("fake")
        assert isinstance(printer, FakePrinter)

    def test_m832_usb(self):
        """m832 + usb: 실제 UsbTransport 인스턴스가 주입돼야 한다(문자열이 아니라)."""
        agent = bare_agent(make_config(printer_driver="m832", transport="usb"))
        printer = agent._load_printer("m832")
        assert isinstance(printer, M832Printer)
        assert isinstance(printer.transport, UsbTransport)
        assert not isinstance(printer.transport, str)  # 회귀 방지: 문자열이 그대로 들어가던 버그

    def test_m832_bt(self):
        """m832 + bt: 실제 BtTransport 인스턴스가 주입되고, 주소가 설정값과 일치해야 한다."""
        agent = bare_agent(
            make_config(printer_driver="m832", transport="bt", bt_address="C5:0D:F7:B7:B2:A1")
        )
        printer = agent._load_printer("m832")
        assert isinstance(printer, M832Printer)
        assert isinstance(printer.transport, BtTransport)
        assert printer.transport.address == "C5:0D:F7:B7:B2:A1"

    def test_m832_h_offset_passed_through(self):
        agent = bare_agent(make_config(printer_driver="m832", transport="usb", h_offset_mm=3.5))
        printer = agent._load_printer("m832")
        assert printer.h_offset_mm == 3.5

    def test_unknown_driver_raises(self):
        agent = bare_agent(make_config())
        with pytest.raises(ValueError, match="Unknown printer driver"):
            agent._load_printer("does-not-exist")

    def test_m832_import_error_propagates_not_fake(self, monkeypatch):
        """회귀 방지(2026-09-17): m832 드라이버 import가 실패해도 FakePrinter로
        조용히 내려가면 안 된다 — 실물은 백지인데 서버엔 printed로 보고되던 버그의
        원인이었다. ImportError는 그대로 올라와야 한다."""
        monkeypatch.setitem(sys.modules, "printer.m832", None)
        agent = bare_agent(make_config(printer_driver="m832", transport="usb"))
        with pytest.raises(ImportError):
            agent._load_printer("m832")


class TestLoadTransport:
    def test_usb(self):
        agent = bare_agent(make_config(transport="usb"))
        transport = agent._load_transport("usb")
        assert isinstance(transport, UsbTransport)

    def test_bt_with_address(self):
        agent = bare_agent(make_config(transport="bt", bt_address="C5:0D:F7:B7:B2:A1"))
        transport = agent._load_transport("bt")
        assert isinstance(transport, BtTransport)
        assert transport.address == "C5:0D:F7:B7:B2:A1"

    def test_bt_without_address_raises(self):
        """HARU_TRANSPORT=bt인데 HARU_BT_ADDRESS가 비어 있으면 fake로 조용히 넘어가지 않고
        바로 에러를 낸다 — 설정 실수를 감추지 않는다."""
        agent = bare_agent(make_config(transport="bt", bt_address=""))
        with pytest.raises(ValueError, match="HARU_BT_ADDRESS"):
            agent._load_transport("bt")

    def test_unknown_transport_raises(self):
        agent = bare_agent(make_config())
        with pytest.raises(ValueError, match="Unknown transport"):
            agent._load_transport("carrier-pigeon")


class TestMainErrorHandling:
    """main()이 설정 로드뿐 아니라 Agent(config) 생성 실패도 sys.exit(1)로 끝내는지.

    2026-09-17 이전에는 `AgentConfig.from_env()`만 try로 감쌌다 — `_load_printer`가
    ImportError를 fake로 삼키지 않게 된 지금은 `Agent(config)` 생성 중에도 예외가
    날 수 있으므로, 그 경로도 사람이 읽을 수 있는 오류 + sys.exit(1)로 끝나야
    systemd 재시작 루프에서 트레이스백만 반복되지 않는다.
    """

    def test_agent_construction_failure_exits_nonzero(self, monkeypatch, tmp_path):
        import agent.__main__ as main_module

        # main()은 Agent(config)를 실제로 생성한다 — Storage가 SQLite 파일을 만드는
        # 경로까지 가므로 data_dir을 tmp_path로 준다. make_config의 기본값
        # (/tmp/haru-paper-test-unused)을 쓰면 테스트 실행마다 실제 DB가 남고
        # 다음 실행의 cleanup_stale_attempts가 그 찌꺼기를 대상으로 돈다.
        broken_config = make_config(
            printer_driver="m832", transport="bt", bt_address="", data_dir=str(tmp_path)
        )
        monkeypatch.setattr(main_module.AgentConfig, "from_env", classmethod(lambda cls, env_path=None: broken_config))

        with pytest.raises(SystemExit) as exc_info:
            main_module.main()
        assert exc_info.value.code == 1

    def test_config_load_failure_still_exits_nonzero(self, monkeypatch):
        """기존 동작(설정 오류) 회귀 방지."""
        import agent.__main__ as main_module

        def raise_value_error(env_path=None):
            raise ValueError("Missing required env vars: HARU_SERVER_URL")

        monkeypatch.setattr(main_module.AgentConfig, "from_env", classmethod(lambda cls, env_path=None: raise_value_error()))

        with pytest.raises(SystemExit) as exc_info:
            main_module.main()
        assert exc_info.value.code == 1
