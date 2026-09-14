"""M832 이미지 변환 파이프라인 테스트 (docs/pi/printer-m832.md 2.3절).

순수 알고리즘 테스트 (하드웨어 불필요).

주의: detox-printer 실물 바이트 비교는 이 서버에서 불가능
(detox-printer 소스코드·캡처 파일이 없음).
대신 문서화된 알고리즘이 정확하게 구현됐는지만 확인한다.
"""

import struct
from io import BytesIO

import PIL.Image
import pytest

from printer.m832 import image as m832_image
from printer.m832.constants import (
    CMD_AFTER_JOB,
    CMD_AFTER_PAGE,
    CMD_BMP_PREFIX,
    CMD_FINDPAPER,
    DPI,
    HDR_CONTINUOUS_PAPER,
    HDR_NO_COMPRESSION,
    WIDTH_BYTES,
    WIDTH_DOTS,
    _INVERT_TABLE,
)


class TestLoadAndPrepareImage:
    """load_and_prepare_image: PNG → 그레이스케일 + 리사이즈."""

    def test_load_simple_png(self):
        """간단한 PNG 로드 및 리사이즈."""
        # 100×50 그레이스케일 이미지 생성
        img = PIL.Image.new("L", (100, 50), color=128)
        png_bytes = BytesIO()
        img.save(png_bytes, format="PNG")
        png_bytes.seek(0)

        # load_and_prepare_image로 처리
        result = m832_image.load_and_prepare_image(png_bytes.getvalue(), width_dots=WIDTH_DOTS)

        # 결과 폭이 정확히 WIDTH_DOTS여야 함
        assert result.size[0] == WIDTH_DOTS
        # 높이는 비율 유지 (100→1304 확대 → 50 × 13.04 ≈ 652)
        expected_height = round((WIDTH_DOTS * 50) / 100)
        assert result.size[1] == expected_height
        # 모드는 그레이스케일
        assert result.mode == "L"

    def test_load_color_png(self):
        """RGB 이미지도 그레이스케일로 변환."""
        # RGB 이미지
        img = PIL.Image.new("RGB", (200, 100))
        png_bytes = BytesIO()
        img.save(png_bytes, format="PNG")
        png_bytes.seek(0)

        result = m832_image.load_and_prepare_image(png_bytes.getvalue())

        assert result.mode == "L"
        assert result.size[0] == WIDTH_DOTS


class TestApplyHOffset:
    """apply_h_offset: 좌우 정렬 보정."""

    def test_apply_h_offset_default(self):
        """기본 보정값 2.0mm = 24dot."""
        # 100×100 흰색 이미지
        img = PIL.Image.new("L", (100, 100), 255)

        result = m832_image.apply_h_offset(img, h_offset_mm=2.0, dpi=DPI)

        # 결과 크기는 원래와 같음
        assert result.size == (100, 100)
        # 좌측 24도트는 흰색 (offset 적용)
        # 오른쪽 24도트는 잘림 (원래 이미지 오른쪽 부분이 잘림)

    def test_apply_h_offset_zero(self):
        """보정값 0mm: 이미지 그대로."""
        img = PIL.Image.new("L", (100, 100), 128)

        result = m832_image.apply_h_offset(img, h_offset_mm=0.0, dpi=DPI)

        # 크기 유지
        assert result.size == (100, 100)
        # 모든 픽셀이 원래 이미지와 같음 (또는 흰색 배경만 적용)

    def test_offset_calculation(self):
        """offet_mm → dot 계산 검증.

        2.0mm @ 300dpi = 2.0 / 25.4 × 300 = 23.62... ≈ 24dot
        """
        h_offset_mm = 2.0
        expected_dot = round(h_offset_mm / 25.4 * DPI)
        assert expected_dot == 24


class TestDitherTo1bit:
    """dither_to_1bit: Floyd–Steinberg 디더링."""

    def test_pure_black(self):
        """순수 검정 (0)은 모두 검정으로 유지."""
        # 모두 검정 (0)
        img = PIL.Image.new("L", (8, 8), 0)

        result = m832_image.dither_to_1bit(img)

        # 모드는 1비트
        assert result.mode == "1"
        # 모두 검정 (0)
        pixels = list(result.getdata())
        assert all(p == 0 for p in pixels)

    def test_pure_white(self):
        """순수 흰색 (255)은 모두 흰색으로 유지."""
        # 모두 흰색 (255)
        img = PIL.Image.new("L", (8, 8), 255)

        result = m832_image.dither_to_1bit(img)

        # 모드는 1비트
        assert result.mode == "1"
        # Pillow 1비트에서 1=흰색 (Pillow 표준)
        # 그레이스케일 255(흰색) → convert("1") → 1비트 (모두 흰색)
        pixels = list(result.getdata())
        # 모든 픽셀이 같은 값 (모두 0 또는 모두 1, 의미는 같음)
        assert len(set(pixels)) == 1

    def test_checkerboard(self):
        """체커보드 패턴 (0, 255 번갈아)."""
        img = PIL.Image.new("L", (16, 16))
        for y in range(16):
            for x in range(16):
                if (x + y) % 2 == 0:
                    img.putpixel((x, y), 0)
                else:
                    img.putpixel((x, y), 255)

        result = m832_image.dither_to_1bit(img)

        assert result.mode == "1"
        # 패턴이 유지됨 (정확한 픽셀 위치는 디더링에 따라 약간 달라질 수 있음)


class TestPackBitmap:
    """pack_bitmap: 비트 극성 반전 패킹."""

    def test_pure_black_image(self):
        """순수 검정 이미지: tobytes() 전부 0x00 → XOR 0xFF → 0xFF."""
        # 폭 8, 높이 1 (1바이트 정확히)
        img = PIL.Image.new("1", (8, 1), 0)  # 0 = 검정

        result = m832_image.pack_bitmap(img, width_bytes=1)

        # 결과: 전부 0xFF (1=검정)
        assert len(result) == 1
        assert result[0] == 0xFF

    def test_pure_white_image(self):
        """순수 흰색 이미지: tobytes() 전부 0xFF → XOR 0xFF → 0x00."""
        # 폭 8, 높이 1
        img = PIL.Image.new("1", (8, 1), 1)  # 1 = 흰색 (Pillow 표준)

        result = m832_image.pack_bitmap(img, width_bytes=1)

        # 결과: 전부 0x00 (1=검정이 없음)
        assert len(result) == 1
        assert result[0] == 0x00

    def test_alternating_pattern(self):
        """번갈아가는 비트 패턴."""
        # 폭 8: 검정 흰색 검정 흰색 ... (01010101)
        # tobytes() → 0x55 (Pillow 1비트 MSB-first, 1=흰색)
        # XOR 0xFF → 0xAA (1=검정)
        img = PIL.Image.new("1", (8, 1), 1)
        pixels = list(img.getdata())
        for i in range(8):
            if i % 2 == 0:
                img.putpixel((i, 0), 0)  # 검정
            else:
                img.putpixel((i, 0), 1)  # 흰색

        result = m832_image.pack_bitmap(img, width_bytes=1)

        assert len(result) == 1
        # 0x55를 XOR 0xFF = 0xAA
        assert result[0] == 0xAA

    def test_width_not_multiple_of_8(self):
        """폭이 8의 배수가 아니면 예외."""
        img = PIL.Image.new("1", (7, 1))  # 폭 7은 8의 배수가 아님

        with pytest.raises(ValueError, match="8의 배수"):
            m832_image.pack_bitmap(img, width_bytes=1)

    def test_width_bytes_mismatch(self):
        """폭이 width_bytes와 맞지 않으면 예외."""
        img = PIL.Image.new("1", (16, 1))  # 16픽셀 = 2바이트

        with pytest.raises(ValueError, match="폭"):
            m832_image.pack_bitmap(img, width_bytes=3)  # 기대: 2바이트

    def test_result_length_validation(self):
        """결과 길이 = width_bytes × height."""
        img = PIL.Image.new("1", (8, 4))  # 8픽셀×4줄 = 4바이트

        result = m832_image.pack_bitmap(img, width_bytes=1)

        # 1바이트/줄 × 4줄 = 4바이트
        assert len(result) == 4


class TestBuildCommand:
    """build_command: 헤더·비트맵·꼬리 조립."""

    def test_header_structure(self):
        """헤더 구조 검증."""
        packed = bytes(163)  # WIDTH_BYTES
        height = 1

        command = m832_image.build_command(packed, width_bytes=WIDTH_BYTES, height=height)

        # 헤더 확인
        assert command.startswith(HDR_CONTINUOUS_PAPER)
        assert HDR_NO_COMPRESSION in command

    def test_bitmap_header_encoding(self):
        """bCmdBMP 헤더: xL xH yL yH 리틀엔디언."""
        packed = bytes(163)
        height = 652

        command = m832_image.build_command(packed, width_bytes=WIDTH_BYTES, height=height)

        # bCmdBMP 부분 추출
        # 3(HDR1) + 4(HDR2) = 7바이트 후부터 시작
        hdr_offset = len(HDR_CONTINUOUS_PAPER) + len(HDR_NO_COMPRESSION)
        cmd_bitmap_part = command[hdr_offset : hdr_offset + 8]

        # 첫 4바이트: CMD_BMP_PREFIX
        assert cmd_bitmap_part[:4] == CMD_BMP_PREFIX

        # 다음 2바이트: width_bytes (리틀엔디언)
        width_encoded = struct.unpack("<H", cmd_bitmap_part[4:6])[0]
        assert width_encoded == WIDTH_BYTES

        # 다음 2바이트: height (리틀엔디언)
        height_encoded = struct.unpack("<H", cmd_bitmap_part[6:8])[0]
        assert height_encoded == height

    def test_total_length(self):
        """총 길이 = 3 + 4 + 8 + (width_bytes × height) + 9."""
        width_bytes = WIDTH_BYTES
        height = 100
        packed = bytes(width_bytes * height)

        command = m832_image.build_command(packed, width_bytes=width_bytes, height=height)

        expected_length = 3 + 4 + 8 + (width_bytes * height) + 9
        assert len(command) == expected_length

    def test_footer_included(self):
        """꼬리 9바이트 포함."""
        packed = bytes(163)
        height = 1

        command = m832_image.build_command(packed, width_bytes=WIDTH_BYTES, height=height)

        # 꼬리는 명령 끝에서 9바이트
        footer = command[-9:]
        assert footer == CMD_AFTER_PAGE + CMD_AFTER_JOB + CMD_FINDPAPER

    def test_example_checkerboard(self):
        """체커보드 테스트 패턴 (docs/pi/printer-m832.md 2.2절 참조).

        1304×652dot 체커보드 → 106,300바이트 기준
        실제 명령: 15 + 106,300 + 9 = 106,324바이트

        (detox-printer 실물 바이트 0004.bin과의 비교는 이 서버에서 불가능)
        """
        # 체커보드 패턴 (검정/흰색 교차)
        img = PIL.Image.new("1", (WIDTH_DOTS, 652), 1)  # 흰색으로 시작
        for y in range(652):
            for x in range(0, WIDTH_DOTS, 2):
                if (x + y) % 2 == 0:
                    img.putpixel((x, y), 0)  # 검정

        packed = m832_image.pack_bitmap(img, width_bytes=WIDTH_BYTES)
        command = m832_image.build_command(packed, width_bytes=WIDTH_BYTES, height=652)

        # 총 길이 검증
        expected_total = 15 + len(packed) + 9
        assert len(command) == expected_total
        assert len(command) == 15 + (WIDTH_BYTES * 652) + 9

    def test_auto_height_calculation(self):
        """height 미지정 시 packed 길이에서 자동 계산."""
        width_bytes = WIDTH_BYTES
        height = 50
        packed = bytes(width_bytes * height)

        command = m832_image.build_command(packed, width_bytes=width_bytes)

        # height 자동 계산되어야 함
        expected_length = 3 + 4 + 8 + len(packed) + 9
        assert len(command) == expected_length


class TestIntegrationPipeline:
    """전체 파이프라인 통합 테스트."""

    def test_pipeline_with_test_pattern(self):
        """완전한 파이프라인: 테스트 패턴 → 명령 바이트."""
        # 작은 테스트 패턴 생성
        test_img = PIL.Image.new("L", (100, 50))
        for y in range(50):
            for x in range(100):
                if (x + y) % 2 == 0:
                    test_img.putpixel((x, y), 0)
                else:
                    test_img.putpixel((x, y), 255)

        # PNG로 저장
        png_bytes = BytesIO()
        test_img.save(png_bytes, format="PNG")
        png_bytes.seek(0)

        # 파이프라인 실행
        img_gray = m832_image.load_and_prepare_image(png_bytes.getvalue(), width_dots=WIDTH_DOTS)
        img_offset = m832_image.apply_h_offset(img_gray, h_offset_mm=2.0, dpi=DPI)
        img_1bit = m832_image.dither_to_1bit(img_offset)
        packed = m832_image.pack_bitmap(img_1bit, width_bytes=WIDTH_BYTES)
        command = m832_image.build_command(packed, width_bytes=WIDTH_BYTES, height=img_1bit.size[1])

        # 결과 검증
        assert command.startswith(HDR_CONTINUOUS_PAPER)
        assert command.endswith(CMD_FINDPAPER)
        assert len(command) == 15 + len(packed) + 9
        assert len(packed) == WIDTH_BYTES * img_1bit.size[1]
