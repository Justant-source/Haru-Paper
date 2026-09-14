"""프린터 공통 인터페이스 (docs/pi/printer.md).

서버·앱은 PrinterProfile만 안다. 이 모듈은 프로토콜을 모른다 —
m832 프로토콜을 아는 코드는 printer/m832/ 뿐이다.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from dataclasses import dataclass, field


@dataclass(frozen=True)
class PrinterProfile:
    """docs/architecture.md 3.5절. poll 요청의 printerProfile로 그대로 나간다."""

    model: str
    dpi: int
    paper_width_mm: int
    printable_width_px: int

    def to_dict(self) -> dict:
        return {
            "model": self.model,
            "dpi": self.dpi,
            "paperWidthMm": self.paper_width_mm,
            "printableWidthPx": self.printable_width_px,
        }


@dataclass(frozen=True)
class PrinterStatus:
    """printer.md 2절. state: ok | offline | error | unknown."""

    state: str
    detail: str = ""

    def to_dict(self) -> dict:
        return {"state": self.state, "detail": self.detail}


@dataclass(frozen=True)
class PrintOutcome:
    """print_image()가 돌려준다. agent가 sent/에 보관할 수 있게 보낸 바이트를 담는다."""

    sent_bytes: bytes
    byte_count: int = field(init=False)

    def __post_init__(self) -> None:
        object.__setattr__(self, "byte_count", len(self.sent_bytes))


class Printer(ABC):
    """printer.md 2절 인터페이스. m832/, fake/가 구현한다."""

    @abstractmethod
    def profile(self) -> PrinterProfile:
        """서버에 보고할 프로필."""

    @abstractmethod
    def status(self) -> PrinterStatus:
        """연결 가능 여부·오류. 용지 상태는 agent가 별도 정책으로 판단한다(policy.md)."""

    @abstractmethod
    def print_image(self, png_bytes: bytes) -> PrintOutcome:
        """PNG를 변환해 전송한다. 용지 정책은 판단하지 않는다 — agent가 호출 여부를 정한다.
        전송 오류는 삼키지 않고 예외로 올린다."""
