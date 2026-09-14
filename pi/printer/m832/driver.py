"""M832 프린터 드라이버 (docs/pi/printer-m832.md).

Phomemo M832 300dpi 110mm 연속 롤 프린터 구현.
"""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING

from printer import Printer, PrinterProfile, PrinterStatus, PrintOutcome
from transport import Transport, TransportError

from . import image as m832_image
from .constants import DPI, H_OFFSET_DEFAULT_DOT, H_OFFSET_MM_DEFAULT, WIDTH_BYTES, WIDTH_DOTS

if TYPE_CHECKING:
    pass

logger = logging.getLogger(__name__)


class M832Printer(Printer):
    """Phomemo M832 드라이버.

    docs/pi/printer-m832.md 전체, printer.md 2절 인터페이스 구현.
    """

    def __init__(self, transport: Transport, h_offset_mm: float = H_OFFSET_MM_DEFAULT) -> None:
        """M832 프린터 초기화.

        Args:
            transport: Transport 구현 (USB 등). 드라이버는 이 인터페이스만 알면 됨.
            h_offset_mm: 좌우 정렬 보정값(mm). 기본값 2.0.
                        [확인됨·실물] docs/pi/printer-m832.md 2.3절
        """
        self.transport = transport
        self.h_offset_mm = h_offset_mm

    def profile(self) -> PrinterProfile:
        """프린터 프로필 보고.

        printableWidthPx는 잠정값 1300 (M1 확정 전).
        docs/pi/printer-m832.md 4절, printer.md 3절
        """
        return PrinterProfile(
            model="m832",
            dpi=DPI,
            paper_width_mm=110,
            printable_width_px=1300,  # [기본값] M1에서 확정. docs/pi/printer-m832.md 4절
        )

    def status(self) -> PrinterStatus:
        """프린터 상태 조회.

        현재 M832는 용지 감지 방법이 없으므로 용지 상태는 항상 unknown.
        [미검증·H4] docs/pi/printer-m832.md 5절

        연결 가능 여부만 보고: 장치를 열 수 있으면 "ok", 실패하면 "offline".
        이 서버에는 프린터가 없으므로 정상적으로 offline이 나옴 (버그 아님).
        """
        try:
            with self.transport:
                # 장치 열기만 시도 (읽기/쓰기 안 함)
                pass
            return PrinterStatus(state="ok", detail="")
        except TransportError as e:
            logger.debug(f"프린터 연결 실패: {e}")
            return PrinterStatus(state="offline", detail=str(e))
        except Exception as e:
            logger.warning(f"예상치 못한 프린터 상태 조회 오류: {e}")
            return PrinterStatus(state="offline", detail=str(e))

    def print_image(self, png_bytes: bytes) -> PrintOutcome:
        """PNG를 변환해 프린터로 전송한다.

        처리 순서 (docs/pi/printer-m832.md 2.3절):
        1. load_and_prepare_image: PNG → 그레이스케일, WIDTH_DOTS로 리사이즈
        2. apply_h_offset: h_offset_mm를 적용해 좌측 안전 여백 확보
        3. dither_to_1bit: Floyd–Steinberg 디더링으로 흑백 1비트 변환
        4. pack_bitmap: 비트 극성 반전해 패킹 (1=검정)
        5. build_command: 헤더·비트맵·꼬리 조립
        6. Transport.write: 생성된 명령을 USB/BT로 전송

        Args:
            png_bytes: PNG 바이트

        Returns:
            PrintOutcome: 보낸 바이트와 크기

        Raises:
            ValueError: 이미지 변환 실패
            TransportError: 전송 실패

        [미검증·실물] 3종 테스트 패턴만 실물 인쇄. 텍스트·사진은 M1에서.
        """
        # 1. 이미지 로드 및 준비
        logger.debug("PNG 로드 및 그레이스케일 변환")
        img_grayscale = m832_image.load_and_prepare_image(png_bytes, width_dots=WIDTH_DOTS)

        # 2. 좌우 정렬 보정
        logger.debug(f"좌우 정렬 보정 적용: {self.h_offset_mm}mm ({H_OFFSET_DEFAULT_DOT}dot)")
        img_offset = m832_image.apply_h_offset(img_grayscale, self.h_offset_mm, dpi=DPI)

        # 3. 흑백 변환 (Floyd–Steinberg 디더링)
        logger.debug("흑백 변환 (Floyd–Steinberg)")
        img_1bit = m832_image.dither_to_1bit(img_offset)

        # 4. 비트맵 패킹
        logger.debug("비트맵 패킹")
        packed_bitmap = m832_image.pack_bitmap(img_1bit, width_bytes=WIDTH_BYTES)

        # 5. 명령 조립
        height = img_1bit.size[1]
        logger.debug(f"명령 조립: {len(packed_bitmap)} 바이트 비트맵, 높이 {height}")
        command = m832_image.build_command(packed_bitmap, width_bytes=WIDTH_BYTES, height=height)

        # 6. 전송
        logger.info(f"프린터 전송 시작: {len(command)} 바이트")
        try:
            with self.transport as transport:
                from .constants import USB_WRITE_TIMEOUT_MS
                transport.write(command, timeout_ms=USB_WRITE_TIMEOUT_MS)
        except TransportError as e:
            logger.error(f"프린터 전송 실패: {e}")
            raise

        logger.info(f"프린터 전송 완료: {len(command)} 바이트")
        return PrintOutcome(sent_bytes=command)
