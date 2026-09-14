"""가짜 프린터 (docs/pi/printer.md 4절).

프린터 없이 에이전트(M4)를 개발·테스트하기 위한 구현.
"""

from __future__ import annotations

import logging
import os
from datetime import datetime
from pathlib import Path

from printer import Printer, PrinterProfile, PrinterStatus, PrintOutcome

logger = logging.getLogger(__name__)


class FakePrinter(Printer):
    """가짜 프린터.

    - profile(): M832와 동일한 프로필 (서버 렌더 폭 맞추기용)
    - status(): 설정으로 paper_state와 연결 상태 흉내
    - print_image(): PNG를 HARU_DATA_DIR/fake/에 저장만 함 (transport 미사용)

    M4 통과 조건의 실패 경로 테스트용.
    """

    def __init__(
        self,
        paper_state: str = "unknown",
        connected: bool = True,
    ) -> None:
        """가짜 프린터 초기화.

        Args:
            paper_state: 용지 상태 ("present", "absent", "unknown").
                        [기본값] docs/pi/printer.md 4절
            connected: True면 status()가 "ok" 반환, False면 "offline".
        """
        if paper_state not in ("present", "absent", "unknown"):
            raise ValueError(f"유효하지 않은 paper_state: {paper_state}")
        self.paper_state = paper_state
        self.connected = connected

        # HARU_DATA_DIR 생성
        self.data_dir = Path(os.environ.get("HARU_DATA_DIR", "/tmp/haru-paper-fake"))
        self.fake_dir = self.data_dir / "fake"
        self.fake_dir.mkdir(parents=True, exist_ok=True)

    def profile(self) -> PrinterProfile:
        """M832와 동일한 프로필.

        [기본값] docs/pi/printer.md 4절
        """
        return PrinterProfile(
            model="m832",  # M832와 동일 (서버 렌더 폭 실제와 맞추기)
            dpi=300,
            paper_width_mm=110,
            printable_width_px=1300,
        )

    def status(self) -> PrinterStatus:
        """상태 흉내내기.

        connected=False면 "offline", True면 "ok".
        용지 상태는 지금(H4 전)은 항상 unknown이므로 state에만 영향.
        """
        if not self.connected:
            return PrinterStatus(state="offline", detail="가짜 프린터 미연결")
        return PrinterStatus(state="ok", detail="가짜 프린터 정상")

    def print_image(self, png_bytes: bytes) -> PrintOutcome:
        """PNG를 파일로 저장.

        HARU_DATA_DIR/fake/ 아래 타임스탬프 파일로 저장.
        transport를 열지 않음.

        Args:
            png_bytes: PNG 바이트

        Returns:
            PrintOutcome: 저장된 파일 (실제로는 png_bytes를 그대로 반환)
        """
        timestamp = datetime.now().isoformat()
        filename = f"{timestamp}.png"
        filepath = self.fake_dir / filename

        logger.info(f"가짜 프린터: {filepath}에 PNG 저장 ({len(png_bytes)} 바이트)")
        filepath.write_bytes(png_bytes)

        # 실제 인쇄와 동일한 PrintOutcome 반환
        # (sent_bytes는 png_bytes 자체가 아니라 프린터로 보낸 명령 바이트여야 하지만,
        #  가짜 프린터는 transport를 쓰지 않으므로 PNG 자체를 반환)
        return PrintOutcome(sent_bytes=png_bytes)
