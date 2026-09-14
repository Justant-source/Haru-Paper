"""USB 전송 계층 (docs/pi/transport.md 2절). M832 드라이버가 보낸 바이트를 프린터로 보낸다."""

from __future__ import annotations

import errno
import usb.core
import usb.util

from pi.printer.m832.constants import (
    EP_IN as M832_EP_IN,
    EP_OUT as M832_EP_OUT,
    PID as M832_PID,
    USB_CHUNK_SIZE as M832_USB_CHUNK_SIZE,
    USB_INTERFACE as M832_USB_INTERFACE,
    USB_WRITE_TIMEOUT_MS as M832_USB_WRITE_TIMEOUT_MS,
    VID as M832_VID,
)
from . import Transport, TransportError


class UsbTransport(Transport):
    """Phomemo M832와 USB로 통신. pyusb(libusb-1.0) 기반.

    상수 근거: docs/pi/transport.md 2절, docs/pi/printer-m832.md 2.1절 [확인됨]
    """

    # 장치 식별자
    VID = M832_VID

    # 인터페이스·엔드포인트
    PID = M832_PID
    USB_INTERFACE = M832_USB_INTERFACE
    OUT_ENDPOINT = M832_EP_OUT
    IN_ENDPOINT = M832_EP_IN

    # 전송 파라미터
    DEFAULT_CHUNK_SIZE = M832_USB_CHUNK_SIZE
    DEFAULT_WRITE_TIMEOUT_MS = M832_USB_WRITE_TIMEOUT_MS

    def __init__(
        self,
        vendor_id: int = VID,
        product_id: int = PID,
        out_endpoint: int = OUT_ENDPOINT,
        in_endpoint: int = IN_ENDPOINT,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
    ):
        """생성자.

        Args:
            vendor_id: USB VID (기본 0x0483 M832)
            product_id: USB PID (기본 0x5740 M832)
            out_endpoint: BULK OUT 엔드포인트 (기본 0x02)
            in_endpoint: BULK IN 엔드포인트 (기본 0x81)
            chunk_size: write() 청크 크기 바이트 (기본 4096)
        """
        self.vendor_id = vendor_id
        self.product_id = product_id
        self.out_endpoint = out_endpoint
        self.in_endpoint = in_endpoint
        self.chunk_size = chunk_size
        self.dev = None

    def open(self) -> None:
        """장치 탐색·연결.

        Raises:
            TransportError: 장치를 찾을 수 없거나 초기화 실패
        """
        # 장치 탐색
        self.dev = usb.core.find(idVendor=self.vendor_id, idProduct=self.product_id)
        if self.dev is None:
            raise TransportError(
                f"device not found: vid=0x{self.vendor_id:04x} "
                f"pid=0x{self.product_id:04x}"
            )

        try:
            # 커널 드라이버 분리 (활성 시)
            # detox-printer m832/src/07_print_image.py do_send(), findings "E-1", "E-3" [확인됨·실물]
            if self.dev.is_kernel_driver_active(self.USB_INTERFACE):
                self.dev.detach_kernel_driver(self.USB_INTERFACE)

            # 설정 설정
            self.dev.set_configuration()

            # 인터페이스 점유
            usb.util.claim_interface(self.dev, self.USB_INTERFACE)
        except usb.core.USBError as e:
            self.dev = None
            raise TransportError(f"failed to initialize USB device: {e}") from e

    def write(self, data: bytes, timeout_ms: int = None) -> None:
        """바이트 전송. 청크로 나눠 전부 보낼 때까지 반복.

        Args:
            data: 전송할 바이트
            timeout_ms: 타임아웃 밀리초 (기본 5000ms)

        Raises:
            TransportError: 부분 전송, 타임아웃, 또는 기타 USB 오류
        """
        if self.dev is None:
            raise TransportError("device not open")

        if timeout_ms is None:
            timeout_ms = self.DEFAULT_WRITE_TIMEOUT_MS

        # 청크 단위로 전송
        # 전체를 보낼 때까지 반복 (detox-printer findings "E-1" 281,199바이트 성공)
        offset = 0
        while offset < len(data):
            chunk_end = min(offset + self.chunk_size, len(data))
            chunk = data[offset:chunk_end]

            try:
                bytes_written = self.dev.write(self.out_endpoint, chunk, timeout=timeout_ms)
            except usb.core.USBError as e:
                raise TransportError(
                    f"USB write failed at offset {offset}/{len(data)}: {e}"
                ) from e

            # 부분 전송 감지 (의도와 다른 바이트 수)
            # docs/pi/transport.md 1절 "부분 전송·타임아웃은 예외로 올린다(삼키지 않음)"
            if bytes_written != len(chunk):
                raise TransportError(
                    f"partial write: expected {len(chunk)} bytes, "
                    f"but wrote {bytes_written} at offset {offset}/{len(data)}"
                )

            offset = chunk_end

    def read(self, max_bytes: int, timeout_ms: int) -> bytes | None:
        """응답 읽기.

        Args:
            max_bytes: 읽을 최대 바이트
            timeout_ms: 타임아웃 밀리초

        Returns:
            읽은 바이트, 또는 타임아웃이면 None

        Raises:
            TransportError: 권한·해제 등 기타 USB 오류
        """
        if self.dev is None:
            raise TransportError("device not open")

        try:
            data = self.dev.read(self.in_endpoint, max_bytes, timeout=timeout_ms)
            return bytes(data)
        except usb.core.USBError as e:
            # errno 110: ETIMEDOUT (Linux)
            # 다양한 플랫폼의 타임아웃 코드를 모두 처리
            # docs/pi/transport.md 2절 "전송 후 BULK IN: 무응답" [확인됨·무응답]
            # docs/pi/printer-m832.md 2.1절 "무응답(타임아웃)" [확인됨·무응답]
            if e.errno == errno.ETIMEDOUT or "timeout" in str(e).lower():
                return None
            # 타임아웃이 아닌 USB 오류는 올린다
            raise TransportError(f"USB read failed: {e}") from e

    def close(self) -> None:
        """인터페이스 해제·리소스 정리. 이미 닫혀있어도 안전(idempotent).

        상수 근거: detox-printer m832/src/07_print_image.py do_send(),
        findings "E-1", "E-3" [확인됨·실물]
        """
        if self.dev is None:
            return  # 이미 닫혀있음, 안전

        try:
            # 인터페이스 해제
            usb.util.release_interface(self.dev, self.USB_INTERFACE)
            # 리소스 정리
            usb.util.dispose_resources(self.dev)
        except usb.core.USBError:
            # 이미 해제됐거나 기타 오류는 무시(idempotent)
            pass
        finally:
            self.dev = None
