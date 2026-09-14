"""가짜 프린터 (FakePrinter) 테스트.

프린터 없이 에이전트를 개발·테스트하기 위한 구현 검증.
"""

import os
import tempfile
from io import BytesIO
from pathlib import Path

import PIL.Image
import pytest

from printer.fake import FakePrinter


class TestFakePrinterProfile:
    """프로필 반환."""

    def test_profile_returns_m832_compatible(self):
        """M832와 동일한 프로필."""
        printer = FakePrinter()
        profile = printer.profile()

        assert profile.model == "m832"
        assert profile.dpi == 300
        assert profile.paper_width_mm == 110
        assert profile.printable_width_px == 1300

    def test_profile_is_consistent(self):
        """같은 프린터에서 여러 번 호출해도 동일."""
        printer = FakePrinter()
        profile1 = printer.profile()
        profile2 = printer.profile()

        assert profile1 == profile2


class TestFakePrinterStatus:
    """상태 조회."""

    def test_status_connected_ok(self):
        """connected=True일 때 status 'ok'."""
        printer = FakePrinter(connected=True)
        status = printer.status()

        assert status.state == "ok"

    def test_status_disconnected_offline(self):
        """connected=False일 때 status 'offline'."""
        printer = FakePrinter(connected=False)
        status = printer.status()

        assert status.state == "offline"

    def test_status_paper_state_irrelevant(self):
        """현재(H4 전) paper_state는 status에 영향 없음."""
        # 용지 상태가 뭐든 connected=True면 "ok"
        for paper_state in ("present", "absent", "unknown"):
            printer = FakePrinter(paper_state=paper_state, connected=True)
            status = printer.status()
            assert status.state == "ok"

    def test_invalid_paper_state_rejected(self):
        """유효하지 않은 paper_state는 초기화 시 예외."""
        with pytest.raises(ValueError, match="유효하지 않은"):
            FakePrinter(paper_state="invalid")


class TestFakePrinterPrintImage:
    """이미지 인쇄 (파일 저장)."""

    @pytest.fixture
    def temp_data_dir(self):
        """임시 HARU_DATA_DIR."""
        with tempfile.TemporaryDirectory() as tmpdir:
            original_env = os.environ.get("HARU_DATA_DIR")
            os.environ["HARU_DATA_DIR"] = tmpdir
            yield tmpdir
            if original_env is not None:
                os.environ["HARU_DATA_DIR"] = original_env
            elif "HARU_DATA_DIR" in os.environ:
                del os.environ["HARU_DATA_DIR"]

    def test_print_image_saves_png(self, temp_data_dir):
        """PNG를 HARU_DATA_DIR/fake/에 저장."""
        printer = FakePrinter()

        # PNG 생성
        img = PIL.Image.new("L", (100, 100), 128)
        png_bytes = BytesIO()
        img.save(png_bytes, format="PNG")
        png_data = png_bytes.getvalue()

        # 인쇄
        outcome = printer.print_image(png_data)

        # fake 디렉터리에 파일 생성됨
        fake_dir = Path(temp_data_dir) / "fake"
        assert fake_dir.exists()
        files = list(fake_dir.glob("*.png"))
        assert len(files) == 1

        # 저장된 파일 내용 확인
        saved_data = files[0].read_bytes()
        assert saved_data == png_data

    def test_print_image_returns_outcome(self, temp_data_dir):
        """PrintOutcome 반환."""
        printer = FakePrinter()

        img = PIL.Image.new("RGB", (50, 50))
        png_bytes = BytesIO()
        img.save(png_bytes, format="PNG")
        png_data = png_bytes.getvalue()

        outcome = printer.print_image(png_data)

        # PrintOutcome의 sent_bytes는 png_data (실제 명령이 아님, transport 미사용)
        assert outcome.sent_bytes == png_data
        assert outcome.byte_count == len(png_data)

    def test_print_multiple_images(self, temp_data_dir):
        """여러 이미지 인쇄."""
        printer = FakePrinter()

        for i in range(3):
            img = PIL.Image.new("L", (50, 50), i * 50)
            png_bytes = BytesIO()
            img.save(png_bytes, format="PNG")
            printer.print_image(png_bytes.getvalue())

        # 3개 파일 생성
        fake_dir = Path(temp_data_dir) / "fake"
        files = list(fake_dir.glob("*.png"))
        assert len(files) == 3

    def test_fake_dir_created_automatically(self):
        """HARU_DATA_DIR이 없어도 fake 디렉터리 자동 생성."""
        with tempfile.TemporaryDirectory() as tmpdir:
            original_env = os.environ.get("HARU_DATA_DIR")
            try:
                os.environ["HARU_DATA_DIR"] = tmpdir
                printer = FakePrinter()
                fake_dir = Path(tmpdir) / "fake"
                assert fake_dir.exists()
            finally:
                if original_env is not None:
                    os.environ["HARU_DATA_DIR"] = original_env
                elif "HARU_DATA_DIR" in os.environ:
                    del os.environ["HARU_DATA_DIR"]

    def test_print_image_with_different_sizes(self, temp_data_dir):
        """다양한 크기의 이미지 저장."""
        printer = FakePrinter()

        sizes = [(10, 10), (100, 50), (200, 300)]
        for width, height in sizes:
            img = PIL.Image.new("RGB", (width, height))
            png_bytes = BytesIO()
            img.save(png_bytes, format="PNG")
            printer.print_image(png_bytes.getvalue())

        fake_dir = Path(temp_data_dir) / "fake"
        files = list(fake_dir.glob("*.png"))
        assert len(files) == len(sizes)


class TestFakePrinterIntegration:
    """통합 테스트."""

    @pytest.fixture
    def temp_data_dir(self):
        with tempfile.TemporaryDirectory() as tmpdir:
            original_env = os.environ.get("HARU_DATA_DIR")
            os.environ["HARU_DATA_DIR"] = tmpdir
            yield tmpdir
            if original_env is not None:
                os.environ["HARU_DATA_DIR"] = original_env
            elif "HARU_DATA_DIR" in os.environ:
                del os.environ["HARU_DATA_DIR"]

    def test_fake_printer_workflow(self, temp_data_dir):
        """에이전트 관점에서 프린터 워크플로우."""
        printer = FakePrinter(connected=True)

        # 1. 프로필 조회
        profile = printer.profile()
        assert profile.model == "m832"

        # 2. 상태 확인
        status = printer.status()
        assert status.state == "ok"

        # 3. 이미지 인쇄
        img = PIL.Image.new("L", (300, 200), 128)
        png_bytes = BytesIO()
        img.save(png_bytes, format="PNG")
        outcome = printer.print_image(png_bytes.getvalue())

        # 4. 결과 확인
        assert outcome.byte_count > 0

        # 5. 파일 저장 확인
        fake_dir = Path(temp_data_dir) / "fake"
        assert len(list(fake_dir.glob("*.png"))) == 1

    def test_fake_printer_error_scenarios(self):
        """다양한 시나리오 테스트."""
        # 시나리오 1: 미연결
        printer_offline = FakePrinter(connected=False)
        assert printer_offline.status().state == "offline"

        # 시나리오 2: 용지 없음 (현재는 무시, H4 후 활용)
        printer_no_paper = FakePrinter(paper_state="absent", connected=True)
        assert printer_no_paper.status().state == "ok"

        # 시나리오 3: 용지 상태 미지정
        printer_unknown = FakePrinter(paper_state="unknown", connected=True)
        assert printer_unknown.status().state == "ok"
