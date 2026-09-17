"""BT(SPP/RFCOMM) 전송 계층 테스트. 하드웨어 없이 확인 가능한 것만 (socket 모킹)."""

from __future__ import annotations

import socket
from unittest.mock import MagicMock, patch

import pytest

# 상대 import (pi/tests/test_bt_transport.py → pi/transport/)
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from transport import TransportError
from transport.bt import BtTransport


class TestBtTransportDefaults:
    """생성자 기본값 확인 (docs/pi/transport.md 3절 확정값과 일치해야 함)."""

    def test_default_address(self):
        """기본 MAC 주소가 1호기와 일치."""
        transport = BtTransport()
        assert transport.address == "C5:0D:F7:B7:B2:A1"

    def test_default_channel(self):
        """기본 RFCOMM 채널이 1(SPP)."""
        transport = BtTransport()
        assert transport.channel == 1

    def test_default_chunk_size(self):
        """기본 청크 크기가 문서값과 일치 (4096바이트)."""
        transport = BtTransport()
        assert transport.chunk_size == 4096

    def test_default_connect_timeout(self):
        """기본 connect 타임아웃이 문서값과 일치 (10초)."""
        transport = BtTransport()
        assert transport.connect_timeout_sec == 10.0

    def test_default_write_timeout(self):
        """기본 write(청크당) 타임아웃이 문서값과 일치 (20000ms)."""
        transport = BtTransport()
        assert transport.DEFAULT_WRITE_TIMEOUT_MS == 20000

    def test_default_read_timeout(self):
        """기본 read 타임아웃이 문서값과 일치 (3000ms)."""
        transport = BtTransport()
        assert transport.DEFAULT_READ_TIMEOUT_MS == 3000

    def test_default_total_write_deadline(self):
        """기본 전체 write 데드라인이 USB와 동일 (60초)."""
        transport = BtTransport()
        assert transport.total_write_deadline_sec == 60

    def test_custom_parameters(self):
        """생성자 매개변수로 기본값 재정의 가능(실험기·다른 MAC 대비)."""
        transport = BtTransport(
            address="AA:BB:CC:DD:EE:FF",
            channel=2,
            chunk_size=2048,
            connect_timeout_sec=5.0,
            total_write_deadline_sec=30,
        )
        assert transport.address == "AA:BB:CC:DD:EE:FF"
        assert transport.channel == 2
        assert transport.chunk_size == 2048
        assert transport.connect_timeout_sec == 5.0
        assert transport.total_write_deadline_sec == 30


class TestBtTransportOpen:
    """open() 메서드 테스트."""

    @patch("socket.socket")
    def test_open_success_with_mock(self, mock_socket_ctor):
        """모킹된 소켓으로 open 성공."""
        mock_sock = MagicMock()
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()

        mock_socket_ctor.assert_called_once_with(
            socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM
        )
        mock_sock.connect.assert_called_once_with(("C5:0D:F7:B7:B2:A1", 1))
        assert transport.sock is mock_sock

    @patch("socket.socket")
    def test_open_connect_failure(self, mock_socket_ctor):
        """connect 실패(페어링 안 됨/프린터 꺼짐 등): TransportError, 원인이 메시지에 드러남."""
        mock_sock = MagicMock()
        mock_sock.connect.side_effect = OSError("Connection refused")
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        with pytest.raises(TransportError) as exc_info:
            transport.open()

        error_msg = str(exc_info.value)
        assert "RFCOMM connect failed" in error_msg
        assert "Connection refused" in error_msg
        assert "C5:0D:F7:B7:B2:A1" in error_msg
        # 실패 시 소켓을 닫고 정리한다
        mock_sock.close.assert_called_once()

    @patch("socket.socket")
    def test_open_custom_address_channel(self, mock_socket_ctor):
        """커스텀 주소·채널로 연결 시도."""
        mock_sock = MagicMock()
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport(address="11:22:33:44:55:66", channel=3)
        transport.open()

        mock_sock.connect.assert_called_once_with(("11:22:33:44:55:66", 3))


class TestBtTransportWrite:
    """write() 메서드 테스트."""

    def test_write_device_not_open(self):
        """open() 전에 write() 호출 시 예외."""
        transport = BtTransport()
        with pytest.raises(TransportError) as exc_info:
            transport.write(b"test", timeout_ms=1000)
        assert "device not open" in str(exc_info.value)

    @patch("socket.socket")
    def test_write_small_data_single_chunk(self, mock_socket_ctor):
        """작은 데이터는 청크 크기보다 작으므로 한 번에 전송."""
        mock_sock = MagicMock()
        mock_sock.send.return_value = 10
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()
        transport.write(b"0123456789", timeout_ms=5000)

        assert mock_sock.send.call_count == 1
        mock_sock.send.assert_called_once_with(b"0123456789")

    @patch("socket.socket")
    def test_write_large_data_multiple_chunks(self, mock_socket_ctor):
        """큰 데이터는 청크 크기(4096)로 나눠서 전송 — 흐름 제어 없이 연속 send()."""
        mock_sock = MagicMock()
        mock_sock.send.side_effect = [4096, 4096, 100]  # 8292바이트
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()

        data = b"x" * 8292
        transport.write(data, timeout_ms=20000)

        assert mock_sock.send.call_count == 3
        calls = mock_sock.send.call_args_list
        assert len(calls[0][0][0]) == 4096
        assert len(calls[1][0][0]) == 4096
        assert len(calls[2][0][0]) == 100

    @patch("socket.socket")
    def test_write_custom_chunk_size(self, mock_socket_ctor):
        """커스텀 청크 크기로 분할."""
        mock_sock = MagicMock()
        mock_sock.send.side_effect = [2000, 2000, 500]
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport(chunk_size=2000)
        transport.open()

        data = b"y" * 4500
        transport.write(data, timeout_ms=20000)

        assert mock_sock.send.call_count == 3

    @patch("socket.socket")
    def test_write_partial_send_continues(self, mock_socket_ctor):
        """send()가 요청보다 적게 보내면 나머지를 이어서 보낸다(소켓 일반 규약)."""
        mock_sock = MagicMock()
        # 4096바이트 청크를 3000 + 1096으로 나눠 보냄
        mock_sock.send.side_effect = [3000, 1096]
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()
        transport.write(b"x" * 4096, timeout_ms=20000)

        assert mock_sock.send.call_count == 2

    @patch("socket.socket")
    def test_write_send_returns_zero_error(self, mock_socket_ctor):
        """send()가 0을 반환하면(상대가 연결 닫음) TransportError."""
        mock_sock = MagicMock()
        mock_sock.send.return_value = 0
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()

        with pytest.raises(TransportError) as exc_info:
            transport.write(b"test", timeout_ms=20000)
        assert "returned 0" in str(exc_info.value)

    @patch("socket.socket")
    def test_write_socket_error(self, mock_socket_ctor):
        """소켓 오류를 TransportError로 감싸기."""
        mock_sock = MagicMock()
        mock_sock.send.side_effect = OSError("Broken pipe")
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()

        with pytest.raises(TransportError) as exc_info:
            transport.write(b"test", timeout_ms=20000)

        error_msg = str(exc_info.value)
        assert "RFCOMM send failed" in error_msg
        assert "Broken pipe" in error_msg

    @patch("socket.socket")
    def test_write_exceeds_total_deadline(self, mock_socket_ctor):
        """청크별 타임아웃은 통과해도 총 소요 시간이 상한을 넘으면 예외."""
        mock_sock = MagicMock()
        mock_sock.send.side_effect = lambda chunk: len(chunk)
        mock_socket_ctor.return_value = mock_sock

        # 데드라인을 0으로 줘서 첫 청크 전에 바로 초과하게 만든다
        transport = BtTransport(total_write_deadline_sec=0)
        transport.open()

        with pytest.raises(TransportError) as exc_info:
            transport.write(b"x" * 8192, timeout_ms=20000)

        error_msg = str(exc_info.value)
        assert "exceeded total deadline" in error_msg
        assert "0s" in error_msg

    @patch("socket.socket")
    def test_write_within_deadline_succeeds(self, mock_socket_ctor):
        """총 소요 시간이 데드라인 안이면 정상 완료."""
        mock_sock = MagicMock()
        mock_sock.send.side_effect = [4096, 4096, 100]
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport(total_write_deadline_sec=60)
        transport.open()

        data = b"x" * 8292
        transport.write(data, timeout_ms=20000)

        assert mock_sock.send.call_count == 3

    @patch("socket.socket")
    def test_write_default_timeout(self, mock_socket_ctor):
        """timeout_ms 지정 없으면 기본값(20000ms) 사용해 settimeout한다."""
        mock_sock = MagicMock()
        mock_sock.send.return_value = 4
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()
        transport.write(b"test")  # timeout_ms 지정 안 함

        # settimeout이 connect(10.0)과 write(20.0) 두 번 호출됨 — 마지막이 write용
        last_timeout_call = mock_sock.settimeout.call_args_list[-1]
        assert last_timeout_call[0][0] == 20.0  # 20000ms → 20.0s


class TestBtTransportRead:
    """read() 메서드 테스트."""

    def test_read_device_not_open(self):
        """open() 전에 read() 호출 시 예외."""
        transport = BtTransport()
        with pytest.raises(TransportError) as exc_info:
            transport.read(64, timeout_ms=1000)
        assert "device not open" in str(exc_info.value)

    @patch("socket.socket")
    def test_read_success(self, mock_socket_ctor):
        """정상적으로 응답 받기."""
        mock_sock = MagicMock()
        mock_sock.recv.return_value = b"\x01\x02\x03"
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()
        result = transport.read(64, timeout_ms=3000)

        assert result == b"\x01\x02\x03"

    @patch("socket.socket")
    def test_read_timeout_returns_none(self, mock_socket_ctor):
        """socket.timeout 발생 시 None 반환(오류 아님, 무응답)."""
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = socket.timeout("timed out")
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()
        result = transport.read(64, timeout_ms=3000)

        assert result is None

    @patch("socket.socket")
    def test_read_empty_bytes_returns_none(self, mock_socket_ctor):
        """recv()가 빈 바이트(연결 종료)를 반환하면 None(무응답으로 취급)."""
        mock_sock = MagicMock()
        mock_sock.recv.return_value = b""
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()
        result = transport.read(64, timeout_ms=3000)

        assert result is None

    @patch("socket.socket")
    def test_read_non_timeout_error(self, mock_socket_ctor):
        """타임아웃이 아닌 소켓 오류는 예외로 올린다."""
        mock_sock = MagicMock()
        mock_sock.recv.side_effect = OSError("Connection reset")
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()

        with pytest.raises(TransportError) as exc_info:
            transport.read(64, timeout_ms=3000)

        error_msg = str(exc_info.value)
        assert "RFCOMM read failed" in error_msg
        assert "Connection reset" in error_msg


class TestBtTransportClose:
    """close() 메서드 테스트."""

    def test_close_not_open(self):
        """open() 전에 close() 호출해도 안전 (idempotent)."""
        transport = BtTransport()
        transport.close()

    @patch("socket.socket")
    def test_close_after_failed_open(self, mock_socket_ctor):
        """open() 실패 후 close() 호출해도 안전."""
        mock_sock = MagicMock()
        mock_sock.connect.side_effect = OSError("refused")
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        with pytest.raises(TransportError):
            transport.open()

        # open() 실패 경로 자체가 이미 소켓을 닫으므로 sock은 None
        assert transport.sock is None
        transport.close()  # 예외 없이 넘어감

    def test_close_twice(self):
        """close()를 두 번 호출해도 안전."""
        transport = BtTransport()
        transport.close()
        transport.close()

    @patch("socket.socket")
    def test_close_success(self, mock_socket_ctor):
        """정상 open 후 close()."""
        mock_sock = MagicMock()
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()
        transport.close()

        mock_sock.close.assert_called_once()
        assert transport.sock is None

    @patch("socket.socket")
    def test_close_suppresses_socket_error(self, mock_socket_ctor):
        """close() 중 소켓 오류는 무시 (idempotent)."""
        mock_sock = MagicMock()
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()

        mock_sock.close.side_effect = OSError("already closed")
        transport.close()  # 예외 없이 넘어감
        assert transport.sock is None


class TestBtTransportContextManager:
    """Context manager (with) 테스트."""

    @patch("socket.socket")
    def test_context_manager_success(self, mock_socket_ctor):
        """with 문으로 자동 open/close."""
        mock_sock = MagicMock()
        mock_socket_ctor.return_value = mock_sock

        with BtTransport() as transport:
            assert transport.sock is mock_sock

        mock_sock.close.assert_called_once()

    @patch("socket.socket")
    def test_context_manager_with_exception(self, mock_socket_ctor):
        """with 블록 내 예외 발생해도 close() 호출."""
        mock_sock = MagicMock()
        mock_socket_ctor.return_value = mock_sock

        try:
            with BtTransport() as transport:
                assert transport.sock is mock_sock
                raise ValueError("Test exception")
        except ValueError:
            pass

        mock_sock.close.assert_called_once()


class TestBtTransportIntegration:
    """통합 테스트 (모킹 조합). 실제 페이로드는 절대 실물 프린터로 보내지 않는다(테스트는 소켓 모킹뿐)."""

    @patch("socket.socket")
    def test_full_write_sequence(self, mock_socket_ctor):
        """전체 쓰기 시퀀스: open → write(큰 데이터) → close."""
        mock_sock = MagicMock()
        mock_sock.send.side_effect = [4096, 4096, 100]  # 8292바이트
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()

        data = b"z" * 8292
        transport.write(data, timeout_ms=20000)
        assert mock_sock.send.call_count == 3

        transport.close()
        mock_sock.close.assert_called_once()
        assert transport.sock is None

    @patch("socket.socket")
    def test_write_read_sequence(self, mock_socket_ctor):
        """쓰기 후 읽기 시퀀스 — V2 실측(전송 후 11바이트 응답)과 같은 형태."""
        mock_sock = MagicMock()
        mock_sock.send.return_value = 5
        mock_sock.recv.return_value = bytes.fromhex("1a3e00001a3b0419000500")
        mock_socket_ctor.return_value = mock_sock

        transport = BtTransport()
        transport.open()

        transport.write(b"hello", timeout_ms=20000)
        response = transport.read(64, timeout_ms=3000)
        assert response == bytes.fromhex("1a3e00001a3b0419000500")

        transport.close()

    def test_open_and_close_only_no_write(self):
        """연결 확인용: open() 직후 write() 없이 바로 close()해도 정상.

        실물 프린터 연결 테스트는 이 시퀀스만 쓴다 — write()를 호출하지 않으므로
        어떤 상황에서도 프린터에 바이트가 전송되지 않는다(CLAUDE.md 절대금지 1).
        """
        with patch("socket.socket") as mock_socket_ctor:
            mock_sock = MagicMock()
            mock_socket_ctor.return_value = mock_sock

            transport = BtTransport()
            transport.open()
            transport.close()

            mock_sock.send.assert_not_called()
            mock_sock.close.assert_called_once()
