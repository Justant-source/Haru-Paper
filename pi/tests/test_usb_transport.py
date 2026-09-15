"""USB 전송 계층 테스트. 하드웨어 없이 확인 가능한 것만."""

from __future__ import annotations

import errno
from unittest.mock import MagicMock, Mock, patch

import pytest
import usb.core
import usb.util

# 상대 import (pi/tests/test_usb_transport.py → pi/transport/)
import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from transport import TransportError
from transport.usb import UsbTransport


class TestUsbTransportDefaults:
    """생성자 기본값 확인."""

    def test_default_vid_pid(self):
        """기본 VID/PID가 M832 스펙과 일치."""
        transport = UsbTransport()
        assert transport.vendor_id == 0x0483
        assert transport.product_id == 0x5740

    def test_default_endpoints(self):
        """기본 엔드포인트가 M832 스펙과 일치."""
        transport = UsbTransport()
        assert transport.out_endpoint == 0x02
        assert transport.in_endpoint == 0x81

    def test_default_chunk_size(self):
        """기본 청크 크기가 문서값과 일치 (4096 바이트)."""
        transport = UsbTransport()
        assert transport.chunk_size == 4096

    def test_default_write_timeout(self):
        """기본 write 타임아웃이 문서값과 일치 (5000ms)."""
        transport = UsbTransport()
        assert transport.DEFAULT_WRITE_TIMEOUT_MS == 5000

    def test_custom_parameters(self):
        """생성자 매개변수로 기본값 재정의 가능."""
        transport = UsbTransport(
            vendor_id=0x1234,
            product_id=0x5678,
            out_endpoint=0x03,
            in_endpoint=0x82,
            chunk_size=2048,
        )
        assert transport.vendor_id == 0x1234
        assert transport.product_id == 0x5678
        assert transport.out_endpoint == 0x03
        assert transport.in_endpoint == 0x82
        assert transport.chunk_size == 2048


class TestUsbTransportOpen:
    """open() 메서드 테스트."""

    def test_open_device_not_found(self):
        """장치 없음: TransportError 발생, 메시지에 vid/pid 포함.

        이 서버에는 실제 프린터가 없으므로 항상 실패한다(정상).
        """
        transport = UsbTransport()
        with pytest.raises(TransportError) as exc_info:
            transport.open()
        error_msg = str(exc_info.value)
        assert "device not found" in error_msg
        assert "0x0483" in error_msg
        assert "0x5740" in error_msg

    def test_open_custom_vid_pid_not_found(self):
        """커스텀 VID/PID로도 장치 없음 예외."""
        transport = UsbTransport(vendor_id=0x9999, product_id=0x9999)
        with pytest.raises(TransportError) as exc_info:
            transport.open()
        error_msg = str(exc_info.value)
        assert "device not found" in error_msg
        assert "0x9999" in error_msg

    @patch("usb.core.find")
    def test_open_success_with_mock(self, mock_find):
        """모킹된 장치로 open 성공."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()

        # 올바른 VID/PID로 탐색했는지 확인
        mock_find.assert_called_once_with(
            idVendor=0x0483, idProduct=0x5740
        )
        # 커널 드라이버 활성화 확인
        mock_device.is_kernel_driver_active.assert_called_once_with(0)
        # set_configuration 호출 확인
        mock_device.set_configuration.assert_called_once()
        # 인터페이스 점유 확인
        assert transport.dev is mock_device

    @patch("usb.core.find")
    def test_open_detach_kernel_driver(self, mock_find):
        """커널 드라이버가 활성이면 분리."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = True
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()

        # 커널 드라이버 분리 호출 확인
        mock_device.detach_kernel_driver.assert_called_once_with(0)

    @patch("usb.core.find")
    def test_open_usb_error(self, mock_find):
        """USB 오류 시 TransportError로 감싸기."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.side_effect = usb.core.USBError("Permission denied")
        mock_find.return_value = mock_device

        transport = UsbTransport()
        with pytest.raises(TransportError) as exc_info:
            transport.open()
        assert "failed to initialize USB device" in str(exc_info.value)


class TestUsbTransportWrite:
    """write() 메서드 테스트."""

    def test_write_device_not_open(self):
        """open() 전에 write() 호출 시 예외."""
        transport = UsbTransport()
        with pytest.raises(TransportError) as exc_info:
            transport.write(b"test", timeout_ms=1000)
        assert "device not open" in str(exc_info.value)

    @patch("usb.core.find")
    def test_write_small_data_single_chunk(self, mock_find):
        """작은 데이터는 청크 크기보다 작으므로 한 번에 전송."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_device.write.return_value = 10  # 10바이트 전송
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()
        transport.write(b"0123456789", timeout_ms=5000)

        # write()가 한 번 호출되었는지 확인
        assert mock_device.write.call_count == 1
        # 올바른 엔드포인트와 타임아웃으로 호출
        mock_device.write.assert_called_once()
        call_args = mock_device.write.call_args
        assert call_args[0][0] == 0x02  # out_endpoint
        assert call_args[0][1] == b"0123456789"
        assert call_args[1]["timeout"] == 5000

    @patch("usb.core.find")
    def test_write_large_data_multiple_chunks(self, mock_find):
        """큰 데이터는 청크 크기(4096)로 나눠서 전송."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        # 각 청크가 정확히 쓰여졌다고 반환
        mock_device.write.side_effect = [4096, 4096, 100]  # 8192 + 100 바이트 = 8292
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()

        # 8292바이트 = 첫 4096 + 둘째 4096 + 나머지 100
        data = b"x" * 8292
        transport.write(data, timeout_ms=5000)

        # write()가 3번 호출
        assert mock_device.write.call_count == 3

        # 각 청크 크기 확인
        calls = mock_device.write.call_args_list
        assert len(calls[0][0][1]) == 4096  # 첫 청크
        assert len(calls[1][0][1]) == 4096  # 둘째 청크
        assert len(calls[2][0][1]) == 100   # 나머지

    @patch("usb.core.find")
    def test_write_custom_chunk_size(self, mock_find):
        """커스텀 청크 크기로 분할."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_device.write.side_effect = [2000, 2000, 500]  # 4500바이트
        mock_find.return_value = mock_device

        transport = UsbTransport(chunk_size=2000)
        transport.open()

        data = b"y" * 4500
        transport.write(data, timeout_ms=1000)

        assert mock_device.write.call_count == 3
        calls = mock_device.write.call_args_list
        assert len(calls[0][0][1]) == 2000
        assert len(calls[1][0][1]) == 2000
        assert len(calls[2][0][1]) == 500

    @patch("usb.core.find")
    def test_write_partial_transfer_error(self, mock_find):
        """부분 전송 시 예외 발생."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        # 4096바이트를 보내려 했으나 3000만 성공
        mock_device.write.return_value = 3000
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()

        with pytest.raises(TransportError) as exc_info:
            transport.write(b"x" * 4096, timeout_ms=5000)

        error_msg = str(exc_info.value)
        assert "partial write" in error_msg
        assert "3000" in error_msg
        assert "4096" in error_msg

    @patch("usb.core.find")
    def test_write_usb_error(self, mock_find):
        """USB 오류를 TransportError로 감싸기."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_device.write.side_effect = usb.core.USBError("Device disconnected")
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()

        with pytest.raises(TransportError) as exc_info:
            transport.write(b"test", timeout_ms=5000)

        error_msg = str(exc_info.value)
        assert "USB write failed" in error_msg
        assert "Device disconnected" in error_msg

    @patch("usb.core.find")
    def test_write_exceeds_total_deadline(self, mock_find):
        """청크별 타임아웃은 통과해도 총 소요 시간이 상한을 넘으면 예외.

        .temp/01-orangepi-poc-작업지시서-v1.2.md 4.3절: 프린터가 데이터 소비를
        멈추면 청크 타임아웃만으로는 전체 write가 무한정 쌓일 수 있다.
        """
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False

        call_count = 0

        def slow_write(endpoint, chunk, timeout):
            nonlocal call_count
            call_count += 1
            return len(chunk)

        mock_device.write.side_effect = slow_write
        mock_find.return_value = mock_device

        # 데드라인을 0으로 줘서 첫 청크 전에 바로 초과하게 만든다
        transport = UsbTransport(total_write_deadline_sec=0)
        transport.open()

        with pytest.raises(TransportError) as exc_info:
            transport.write(b"x" * 8192, timeout_ms=5000)

        error_msg = str(exc_info.value)
        assert "exceeded total deadline" in error_msg
        assert "0s" in error_msg

    @patch("usb.core.find")
    def test_write_within_deadline_succeeds(self, mock_find):
        """총 소요 시간이 데드라인 안이면 정상 완료."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_device.write.side_effect = [4096, 4096, 100]
        mock_find.return_value = mock_device

        transport = UsbTransport(total_write_deadline_sec=60)
        transport.open()

        data = b"x" * 8292
        transport.write(data, timeout_ms=5000)

        assert mock_device.write.call_count == 3

    def test_default_total_write_deadline(self):
        """기본 전체 write 데드라인이 문서값과 일치 (60초)."""
        transport = UsbTransport()
        assert transport.total_write_deadline_sec == 60

    @patch("usb.core.find")
    def test_write_default_timeout(self, mock_find):
        """timeout_ms 지정 없으면 기본값(5000ms) 사용."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_device.write.return_value = 4
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()
        transport.write(b"test")  # timeout_ms 지정 안 함

        # 기본값 5000ms로 호출됐는지 확인
        call_args = mock_device.write.call_args
        assert call_args[1]["timeout"] == 5000


class TestUsbTransportRead:
    """read() 메서드 테스트."""

    def test_read_device_not_open(self):
        """open() 전에 read() 호출 시 예외."""
        transport = UsbTransport()
        with pytest.raises(TransportError) as exc_info:
            transport.read(64, timeout_ms=1000)
        assert "device not open" in str(exc_info.value)

    @patch("usb.core.find")
    def test_read_success(self, mock_find):
        """정상적으로 응답 받기."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_device.read.return_value = [0x01, 0x02, 0x03]  # 3바이트 응답
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()
        result = transport.read(64, timeout_ms=1000)

        assert result == b"\x01\x02\x03"

    @patch("usb.core.find")
    def test_read_timeout_returns_none(self, mock_find):
        """타임아웃 시 None 반환 (오류 아님, 무응답).

        docs/pi/transport.md 2절, printer-m832.md 2.1절
        "전송 후 BULK IN: 무응답" [확인됨·무응답]
        """
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False

        # errno 110 ETIMEDOUT 발생
        error = usb.core.USBError("Operation timed out")
        error.errno = errno.ETIMEDOUT
        mock_device.read.side_effect = error
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()
        result = transport.read(64, timeout_ms=100)

        # 타임아웃은 None으로 반환
        assert result is None

    @patch("usb.core.find")
    def test_read_timeout_string_detection(self, mock_find):
        """타임아웃 문자열 감지로도 None 반환."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False

        # 타임아웃 문자열을 포함한 오류
        error = usb.core.USBError("timeout during read")
        mock_device.read.side_effect = error
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()
        result = transport.read(64, timeout_ms=100)

        assert result is None

    @patch("usb.core.find")
    def test_read_non_timeout_error(self, mock_find):
        """타임아웃이 아닌 USB 오류는 예외로 올린다."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_device.read.side_effect = usb.core.USBError("Device disconnected")
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()

        with pytest.raises(TransportError) as exc_info:
            transport.read(64, timeout_ms=1000)

        error_msg = str(exc_info.value)
        assert "USB read failed" in error_msg
        assert "Device disconnected" in error_msg


class TestUsbTransportClose:
    """close() 메서드 테스트."""

    def test_close_not_open(self):
        """open() 전에 close() 호출해도 안전 (idempotent)."""
        transport = UsbTransport()
        # 예외 없이 넘어감
        transport.close()

    def test_close_after_failed_open(self):
        """open() 실패 후 close() 호출해도 안전."""
        transport = UsbTransport()

        # open()은 실패하지만
        with pytest.raises(TransportError):
            transport.open()

        # close()는 안전하게 넘어감
        transport.close()

    def test_close_twice(self):
        """close()를 두 번 호출해도 안전."""
        transport = UsbTransport()
        transport.close()
        transport.close()  # 예외 없음

    @patch("usb.util.dispose_resources")
    @patch("usb.util.release_interface")
    @patch("usb.core.find")
    def test_close_success(self, mock_find, mock_release, mock_dispose):
        """정상 open 후 close()."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()
        transport.close()

        # release_interface와 dispose_resources 호출 확인
        mock_release.assert_called_once_with(mock_device, 0)
        mock_dispose.assert_called_once_with(mock_device)
        # dev를 None으로 설정
        assert transport.dev is None

    @patch("usb.util.dispose_resources")
    @patch("usb.util.release_interface")
    @patch("usb.core.find")
    def test_close_suppresses_usb_error(self, mock_find, mock_release, mock_dispose):
        """close() 중 USB 오류는 무시 (idempotent)."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()

        # close() 중 USB 오류 발생
        mock_release.side_effect = usb.core.USBError("Already closed")
        # 예외 없이 넘어감
        transport.close()
        # dev는 None으로 설정됨
        assert transport.dev is None


class TestUsbTransportContextManager:
    """Context manager (with) 테스트."""

    @patch("usb.util.dispose_resources")
    @patch("usb.util.release_interface")
    @patch("usb.core.find")
    def test_context_manager_success(self, mock_find, mock_release, mock_dispose):
        """with 문으로 자동 open/close."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_find.return_value = mock_device

        with UsbTransport() as transport:
            assert transport.dev is mock_device

        # with 블록 종료 후 close() 호출되었는지 확인
        mock_release.assert_called_once_with(mock_device, 0)

    @patch("usb.util.dispose_resources")
    @patch("usb.util.release_interface")
    @patch("usb.core.find")
    def test_context_manager_with_exception(self, mock_find, mock_release, mock_dispose):
        """with 블록 내 예외 발생해도 close() 호출."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_find.return_value = mock_device

        try:
            with UsbTransport() as transport:
                assert transport.dev is mock_device
                raise ValueError("Test exception")
        except ValueError:
            pass

        # close() 호출 확인
        mock_release.assert_called_once()


class TestUsbTransportIntegration:
    """통합 테스트 (모킹 조합)."""

    @patch("usb.util.dispose_resources")
    @patch("usb.util.release_interface")
    @patch("usb.core.find")
    def test_full_write_sequence(self, mock_find, mock_release, mock_dispose):
        """전체 쓰기 시퀀스: open → write(큰 데이터) → close."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = True  # 커널 드라이버 활성
        mock_device.write.side_effect = [4096, 4096, 100]  # 8292바이트
        mock_find.return_value = mock_device

        transport = UsbTransport()

        # open
        transport.open()
        mock_device.detach_kernel_driver.assert_called_once()

        # write
        data = b"z" * 8292
        transport.write(data, timeout_ms=5000)
        assert mock_device.write.call_count == 3

        # close
        transport.close()
        mock_release.assert_called_once()
        assert transport.dev is None

    @patch("usb.core.find")
    def test_write_read_sequence(self, mock_find):
        """쓰기 후 읽기 시퀀스."""
        mock_device = MagicMock()
        mock_device.is_kernel_driver_active.return_value = False
        mock_device.write.return_value = 5
        mock_device.read.return_value = [0xFF, 0xFF]  # 2바이트 응답
        mock_find.return_value = mock_device

        transport = UsbTransport()
        transport.open()

        # write
        transport.write(b"hello", timeout_ms=5000)

        # read
        response = transport.read(64, timeout_ms=1000)
        assert response == b"\xff\xff"

        transport.close()
