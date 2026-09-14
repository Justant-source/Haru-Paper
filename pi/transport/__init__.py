"""전송 계층 공통 인터페이스 (docs/pi/transport.md). 바이트의 의미는 모른다."""

from __future__ import annotations

from abc import ABC, abstractmethod


class TransportError(Exception):
    """open/write 실패, 타임아웃 등. 삼키지 않고 위로 올린다."""


class Transport(ABC):
    @abstractmethod
    def open(self) -> None:
        """장치 연결. 실패 시 원인이 드러나는 예외(장치 없음, 권한, 페어링 안 됨 등)."""

    @abstractmethod
    def write(self, data: bytes, timeout_ms: int) -> None:
        """전부 보낼 때까지 청크로 나눠 전송. 부분 전송·타임아웃은 예외로 올린다."""

    @abstractmethod
    def read(self, max_bytes: int, timeout_ms: int) -> bytes | None:
        """응답 읽기. 타임아웃이면 None(오류와 구분)."""

    @abstractmethod
    def close(self) -> None:
        """해제."""

    def __enter__(self) -> "Transport":
        self.open()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()
