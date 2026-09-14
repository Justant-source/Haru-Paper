"""M832 이미지 변환 파이프라인 (docs/pi/printer-m832.md 2.3절).

07_print_image.py에서 포팅. 입력은 PNG 바이트, 출력은 M832 명령 바이트.
"""

from __future__ import annotations

import struct
from io import BytesIO

import PIL.Image

from .constants import (
    CMD_BMP_PREFIX,
    DPI,
    FOOTER_110MM,
    HDR_CONTINUOUS_PAPER,
    HDR_NO_COMPRESSION,
    WIDTH_BYTES,
    WIDTH_DOTS,
    _INVERT_TABLE,
)


def load_and_prepare_image(png_bytes: bytes, width_dots: int = WIDTH_DOTS) -> PIL.Image.Image:
    """PNG 바이트를 로드하고 그레이스케일·리사이즈한다.

    입력: PNG 바이트
    출력: 그레이스케일 (mode "L"), 폭 width_dots, 높이는 비율 유지

    [미검증·실물] docs/pi/printer-m832.md 2.3절, findings "E-3 결론"
    (실물 인쇄한 3종은 1304 폭으로 직접 그린 테스트 패턴이라 이 경로를 안 탔음)
    """
    with PIL.Image.open(BytesIO(png_bytes)) as img:
        # 그레이스케일 변환
        img = img.convert("L")

        # 폭을 width_dots로 맞추고 높이는 비율 유지
        original_width, original_height = img.size
        new_height = round((width_dots * original_height) / original_width)
        img = img.resize((width_dots, new_height), PIL.Image.Resampling.LANCZOS)

        return img.copy()


def apply_h_offset(
    image: PIL.Image.Image, h_offset_mm: float, dpi: int = DPI
) -> PIL.Image.Image:
    """좌우 정렬 보정을 적용한다.

    h_offset_mm를 도트로 환산하고, 흰 캔버스에 x=+offset으로 붙인 후
    오른쪽을 원래 폭만큼 잘라낸다 (좌측 안전 여백 확보).

    입력: PIL Image (mode "L")
    출력: 같은 폭의 Image, 좌측 offset_dots만큼 흰색으로 채워짐

    [확인됨·실물] docs/pi/printer-m832.md 2.3절, findings "E-3 — 수평 정렬 보정"
    """
    offset_dots = round(h_offset_mm / 25.4 * dpi)
    original_width, original_height = image.size

    # 흰 캔버스 (255)를 원래 크기로 생성
    canvas = PIL.Image.new("L", (original_width, original_height), 255)

    # 이미지를 x=+offset_dots 위치에 paste (오른쪽 offset_dots 부분은 자동 잘림)
    canvas.paste(image, (offset_dots, 0))

    return canvas


def dither_to_1bit(image: PIL.Image.Image) -> PIL.Image.Image:
    """그레이스케일을 흑백 1비트로 변환 (Floyd–Steinberg 디더링).

    Pillow convert("1")의 기본값 = Floyd–Steinberg 디더링.

    입력: PIL Image (mode "L")
    출력: PIL Image (mode "1", 1=검정은 아니고 Pillow 표준 1=흰색)

    [미검증·실물] docs/pi/printer-m832.md 2.3절, findings "E-3 체커보드"
    (실물 인쇄한 테스트 패턴은 처음부터 순수 흑백이라 디더링 결과가 원본과 같음)
    """
    return image.convert("1")


def pack_bitmap(image: PIL.Image.Image, width_bytes: int = WIDTH_BYTES) -> bytes:
    """흑백 1비트 이미지를 비트 극성을 뒤집어 패킹한다.

    Pillow tobytes()는 1=흰색인데, M832는 1=검정이므로 각 바이트를 XOR 0xFF로 뒤집는다.
    (비트 극성 테이블 _INVERT_TABLE 사용)

    입력: PIL Image (mode "1")
    출력: 비트 극성 변환된 바이트열

    검증:
    - 이미지 폭(픽셀 수)이 8의 배수여야 하고, 폭_bytes = 폭_pixels / 8
    - 결과 길이 = width_bytes × height
    - 1=흰색 입력 → 0x00 출력
    - 0=검정 입력 → 0xFF 출력

    [확인됨·실측] docs/pi/printer-m832.md 2.3절
    """
    image_width, image_height = image.size

    # 폭 검증: 8의 배수여야 하고, width_bytes와 일치해야 함
    if image_width % 8 != 0:
        raise ValueError(f"이미지 폭이 8의 배수가 아닙니다: {image_width}")
    if image_width // 8 != width_bytes:
        raise ValueError(
            f"이미지 폭({image_width}px)이 width_bytes({width_bytes})와 맞지 않습니다"
        )

    # tobytes()로 비트스트림 얻기 (1=흰색 극성)
    raw_bytes = image.tobytes()

    # 비트 극성 반전: XOR 0xFF (1=검정으로 변환)
    inverted = bytes(_INVERT_TABLE[b] for b in raw_bytes)

    # 길이 검증
    expected_length = width_bytes * image_height
    if len(inverted) != expected_length:
        raise ValueError(
            f"패킹 결과 길이가 맞지 않습니다: "
            f"예상 {expected_length}, 실제 {len(inverted)}"
        )

    return inverted


def build_command(
    packed_bitmap: bytes, width_bytes: int = WIDTH_BYTES, height: int | None = None
) -> bytes:
    """래스터 명령을 조립한다.

    구조:
    1. 헤더 1 (3바이트): 연속용지 선언
    2. 헤더 2 (4바이트): 압축 Off
    3. bCmdBMP (4+4=8바이트): "1D 76 30 00" + xL xH yL yH (리틀엔디언 16bit)
       - xL xH = width_bytes
       - yL yH = height (이미지 실제 높이, 패딩 없음)
    4. 비트맵 (width_bytes × height 바이트)
    5. 꼬리 (9바이트): 페이지·작업·용지찾기

    총 길이 = 3 + 4 + 8 + (width_bytes × height) + 9
           = 15 + (width_bytes × height) + 9
           = 23 + (width_bytes × height)

    [확인됨·실물] docs/pi/printer-m832.md 2.2절, findings "E-3 체커보드"
    """
    if height is None:
        height = len(packed_bitmap) // width_bytes

    # 높이를 리틀엔디언 16bit로 인코딩
    height_bytes = struct.pack("<H", height)

    # bCmdBMP 필드: xL, xH(width_bytes), yL, yH(height)
    cmd_bitmap_header = (
        CMD_BMP_PREFIX
        + struct.pack("<H", width_bytes)
        + height_bytes
    )

    # 전체 명령 조립
    command = (
        HDR_CONTINUOUS_PAPER
        + HDR_NO_COMPRESSION
        + cmd_bitmap_header
        + packed_bitmap
        + FOOTER_110MM
    )

    return command
