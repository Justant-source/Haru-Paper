"""Bluetooth(SPP/RFCOMM) 전송 계층 (docs/pi/transport.md 3절). M832 드라이버가 보낸 바이트를 프린터로 보낸다.

V2 실물 검증으로 확정된 값만 쓴다: SPP("JL_SPP", 0x1101), RFCOMM 채널 1, 흐름 제어 없이
4096바이트 연속 send(). USB와 완전히 같은 바이트 스트림을 받는다(같은 파일 → 같은 출력물,
detox-printer findings "V2" 5건, m832/src/10_bt_rfcomm_replay.py) [확인됨·실물].
HCRP(L2CAP PSM 4107)는 페어링 후에도 EPERM으로 거부돼(원인 미해결) 쓰지 않는다.
"""

from __future__ import annotations

import socket
import time

from pi.printer.m832.constants import (
    BT_ADDRESS_DEFAULT as M832_BT_ADDRESS_DEFAULT,
    BT_CHUNK_SIZE as M832_BT_CHUNK_SIZE,
    BT_CONNECT_TIMEOUT_SEC as M832_BT_CONNECT_TIMEOUT_SEC,
    BT_READ_TIMEOUT_MS as M832_BT_READ_TIMEOUT_MS,
    BT_RFCOMM_CHANNEL as M832_BT_RFCOMM_CHANNEL,
    BT_TOTAL_WRITE_DEADLINE_SEC as M832_BT_TOTAL_WRITE_DEADLINE_SEC,
    BT_WRITE_TIMEOUT_MS as M832_BT_WRITE_TIMEOUT_MS,
)
from . import Transport, TransportError


class BtTransport(Transport):
    """Phomemo M832와 Bluetooth Classic SPP(RFCOMM)로 통신. 표준 라이브러리 socket 기반.

    상수 근거: docs/pi/transport.md 3절, docs/pi/printer-m832.md 6절 [확인됨·실물]
    """

    # 대상 장치
    ADDRESS = M832_BT_ADDRESS_DEFAULT
    RFCOMM_CHANNEL = M832_BT_RFCOMM_CHANNEL

    # 전송 파라미터
    DEFAULT_CHUNK_SIZE = M832_BT_CHUNK_SIZE
    DEFAULT_CONNECT_TIMEOUT_SEC = M832_BT_CONNECT_TIMEOUT_SEC
    DEFAULT_WRITE_TIMEOUT_MS = M832_BT_WRITE_TIMEOUT_MS
    DEFAULT_READ_TIMEOUT_MS = M832_BT_READ_TIMEOUT_MS
    DEFAULT_TOTAL_WRITE_DEADLINE_SEC = M832_BT_TOTAL_WRITE_DEADLINE_SEC

    def __init__(
        self,
        address: str = ADDRESS,
        channel: int = RFCOMM_CHANNEL,
        chunk_size: int = DEFAULT_CHUNK_SIZE,
        connect_timeout_sec: float = DEFAULT_CONNECT_TIMEOUT_SEC,
        total_write_deadline_sec: float = DEFAULT_TOTAL_WRITE_DEADLINE_SEC,
    ):
        """생성자.

        Args:
            address: M832 Bluetooth MAC 주소 (기본 1호기, `HARU_BT_ADDRESS`로 오버라이드)
            channel: RFCOMM 채널 (기본 1, SPP "JL_SPP" 고정값)
            chunk_size: write() 청크 크기 바이트 (기본 4096)
            connect_timeout_sec: connect() 타임아웃 초 (기본 10)
            total_write_deadline_sec: write() 전체 청크 합산 상한 초 (기본 60) — USB transport와
                같은 이유(프린터가 중간에 소비를 멈추는 경우 폴링 스레드를 오래 막지 않기 위함)
        """
        self.address = address
        self.channel = channel
        self.chunk_size = chunk_size
        self.connect_timeout_sec = connect_timeout_sec
        self.total_write_deadline_sec = total_write_deadline_sec
        self.sock: socket.socket | None = None

    def open(self) -> None:
        """RFCOMM 소켓 연결.

        Raises:
            TransportError: 이 파이썬 빌드가 AF_BLUETOOTH를 지원하지 않거나,
                연결 실패(페어링 안 됨, 프린터 꺼짐, 범위 밖 등 원인이 드러나는 메시지)
        """
        if not hasattr(socket, "AF_BLUETOOTH") or not hasattr(socket, "BTPROTO_RFCOMM"):
            raise TransportError(
                "this Python build does not support AF_BLUETOOTH/BTPROTO_RFCOMM "
                "(BlueZ 필요, Linux 전용)"
            )

        sock = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        sock.settimeout(self.connect_timeout_sec)
        try:
            sock.connect((self.address, self.channel))
        except OSError as e:
            sock.close()
            raise TransportError(
                f"RFCOMM connect failed: addr={self.address} channel={self.channel}: {e} "
                "(페어링 안 됨, 프린터 꺼짐/범위 밖, 또는 다른 기기가 이미 연결 중일 수 있음)"
            ) from e

        self.sock = sock

    def write(self, data: bytes, timeout_ms: int = None) -> None:
        """바이트 전송. 청크로 나눠 전부 보낼 때까지 반복.

        Args:
            data: 전송할 바이트
            timeout_ms: 청크당 타임아웃 밀리초 (기본 20000ms)

        Raises:
            TransportError: 부분 전송, 타임아웃, 전체 데드라인 초과, 또는 기타 소켓 오류
        """
        if self.sock is None:
            raise TransportError("device not open")

        if timeout_ms is None:
            timeout_ms = self.DEFAULT_WRITE_TIMEOUT_MS

        # 청크 단위로 전송. USB transport(transport/usb.py)와 동일한 구조.
        # 흐름 제어·간격 없이 연속 send() — V2 실측(106,300byte, MIN_CHUNK 폴백 미발동)
        start_time = time.monotonic()
        offset = 0
        self.sock.settimeout(timeout_ms / 1000.0)
        while offset < len(data):
            elapsed = time.monotonic() - start_time
            if elapsed > self.total_write_deadline_sec:
                raise TransportError(
                    f"write exceeded total deadline of {self.total_write_deadline_sec}s "
                    f"at offset {offset}/{len(data)} (elapsed {elapsed:.1f}s)"
                )

            chunk_end = min(offset + self.chunk_size, len(data))
            chunk = data[offset:chunk_end]

            try:
                bytes_written = self.sock.send(chunk)
            except OSError as e:
                raise TransportError(
                    f"RFCOMM send failed at offset {offset}/{len(data)}: {e}"
                ) from e

            if bytes_written == 0:
                # send()가 0을 반환하는 것은 상대가 연결을 닫았다는 신호(소켓 일반 규약).
                raise TransportError(
                    f"RFCOMM send returned 0 at offset {offset}/{len(data)} "
                    "(상대가 연결을 닫았을 수 있음)"
                )

            offset += bytes_written

    def read(self, max_bytes: int, timeout_ms: int) -> bytes | None:
        """응답 읽기.

        Args:
            max_bytes: 읽을 최대 바이트
            timeout_ms: 타임아웃 밀리초

        Returns:
            읽은 바이트, 또는 타임아웃/무응답이면 None

        Raises:
            TransportError: 타임아웃이 아닌 소켓 오류
        """
        if self.sock is None:
            raise TransportError("device not open")

        self.sock.settimeout(timeout_ms / 1000.0)
        try:
            data = self.sock.recv(max_bytes)
        except socket.timeout:
            return None
        except OSError as e:
            raise TransportError(f"RFCOMM read failed: {e}") from e

        if not data:
            # recv()가 빈 바이트를 반환하면 상대가 연결을 닫은 것(오류 아님, 무응답으로 취급)
            return None
        return data

    def close(self) -> None:
        """소켓 해제. 이미 닫혀있어도 안전(idempotent)."""
        if self.sock is None:
            return
        try:
            self.sock.close()
        except OSError:
            pass
        finally:
            self.sock = None
