"""M832Printer 드라이버 테스트 (docs/pi/printer-m832.md, transport.md 2·3절).

전송(write) 경로가 transport 종류에 맞는 청크당 타임아웃을 쓰는지 확인한다.
2026-09-17 발견 버그: BT transport로 인쇄해도 항상 USB_WRITE_TIMEOUT_MS(5000ms)가
쓰이고 있었다(docs/pi/transport.md 3절 확정값은 청크당 20000ms). 여기서는 실제
하드웨어를 전혀 열지 않는다 — Transport를 완전히 mock으로 대체한다.
"""

from __future__ import annotations

import os
import sys
from io import BytesIO
from unittest.mock import MagicMock

import PIL.Image
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from printer.m832.constants import BT_WRITE_TIMEOUT_MS, USB_WRITE_TIMEOUT_MS
from printer.m832.driver import M832Printer
from transport import Transport, TransportError
from transport.bt import BtTransport
from transport.usb import UsbTransport


def make_png_bytes() -> bytes:
    """가장 작은 유효한 그레이스케일 PNG (M832Printer.print_image 입력용)."""
    img = PIL.Image.new("L", (16, 8), color=128)
    buf = BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


class RecordingTransport(Transport):
    """write() 호출 인자를 기록하는 mock transport. open/close는 아무것도 안 한다.

    실제 하드웨어를 절대 건드리지 않기 위해 소켓·USB 대신 순수 파이썬으로만 동작.
    """

    def __init__(self, default_write_timeout_ms: int | None = None):
        if default_write_timeout_ms is not None:
            # 인스턴스 속성으로도 노출 — driver.py는 getattr(transport, ...)로 조회하므로
            # 클래스 속성이든 인스턴스 속성이든 상관없이 동작해야 한다.
            self.DEFAULT_WRITE_TIMEOUT_MS = default_write_timeout_ms
        self.write_calls: list[tuple[bytes, int | None]] = []

    def open(self) -> None:
        pass

    def write(self, data: bytes, timeout_ms: int = None) -> None:
        self.write_calls.append((data, timeout_ms))

    def read(self, max_bytes: int, timeout_ms: int) -> bytes | None:
        return None

    def close(self) -> None:
        pass


class TestPrintImageUsesTransportTimeout:
    """print_image()가 transport 종류에 맞는 청크당 write 타임아웃을 쓰는지."""

    def test_bt_transport_class_attribute_used(self):
        """BtTransport(실제 클래스, open/write만 mock)로 인쇄하면 BT 기본 타임아웃(20000ms)이 쓰인다."""
        transport = BtTransport()
        transport.open = MagicMock()
        transport.close = MagicMock()
        transport.write = MagicMock()

        printer = M832Printer(transport=transport)
        printer.print_image(make_png_bytes())

        assert transport.write.call_count == 1
        _, kwargs = transport.write.call_args
        assert kwargs["timeout_ms"] == BT_WRITE_TIMEOUT_MS == 20000

    def test_usb_transport_class_attribute_used(self):
        """UsbTransport(실제 클래스, open/write만 mock)로 인쇄하면 USB 기본 타임아웃(5000ms)이 쓰인다."""
        transport = UsbTransport()
        transport.open = MagicMock()
        transport.close = MagicMock()
        transport.write = MagicMock()

        printer = M832Printer(transport=transport)
        printer.print_image(make_png_bytes())

        assert transport.write.call_count == 1
        _, kwargs = transport.write.call_args
        assert kwargs["timeout_ms"] == USB_WRITE_TIMEOUT_MS == 5000

    def test_generic_transport_with_default_write_timeout_ms_honored(self):
        """isinstance 분기가 아니라 duck typing임을 확인: 임의 transport도
        DEFAULT_WRITE_TIMEOUT_MS만 있으면 그 값을 쓴다(BT/USB 클래스가 아니어도)."""
        transport = RecordingTransport(default_write_timeout_ms=12345)

        printer = M832Printer(transport=transport)
        printer.print_image(make_png_bytes())

        assert len(transport.write_calls) == 1
        _, timeout_ms = transport.write_calls[0]
        assert timeout_ms == 12345

    def test_transport_without_default_falls_back_to_usb_value(self):
        """DEFAULT_WRITE_TIMEOUT_MS가 없는 transport는 USB 값으로 안전하게 폴백한다."""
        transport = RecordingTransport(default_write_timeout_ms=None)
        assert not hasattr(transport, "DEFAULT_WRITE_TIMEOUT_MS")

        printer = M832Printer(transport=transport)
        printer.print_image(make_png_bytes())

        assert len(transport.write_calls) == 1
        _, timeout_ms = transport.write_calls[0]
        assert timeout_ms == USB_WRITE_TIMEOUT_MS

    def test_bt_and_usb_defaults_differ(self):
        """회귀 방지: 두 상수가 실수로 같아지면(리팩터링 사고 등) 이 테스트의 의미가
        없어지므로, 애초에 다르다는 것 자체를 고정해 둔다."""
        assert BT_WRITE_TIMEOUT_MS != USB_WRITE_TIMEOUT_MS


class TestPrintImagePropagatesTransportError:
    """전송 실패는 삼키지 않고 그대로 올라와야 한다(CLAUDE.md 코드 규칙)."""

    def test_write_failure_raises_transport_error(self):
        transport = BtTransport()
        transport.open = MagicMock()
        transport.close = MagicMock()
        transport.write = MagicMock(side_effect=TransportError("RFCOMM send failed"))

        printer = M832Printer(transport=transport)

        with pytest.raises(TransportError):
            printer.print_image(make_png_bytes())
