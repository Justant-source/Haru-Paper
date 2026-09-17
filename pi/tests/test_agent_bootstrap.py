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
