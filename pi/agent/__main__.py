"""진입점: python -m agent. 폴링 루프와 스케줄러 틱을 동시에 실행한다. docs/pi/agent.md 6절, 7절."""

from __future__ import annotations

import hashlib
import json
import logging
import signal
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

from printer import Printer, PrinterStatus
from transport import Transport

from .config import AgentConfig
from .executor import Executor
from .scheduler import calculate_occurrences, filter_executable_occurrences, make_occurrence_key
from .storage import Storage
from .sync import HttpPollSyncChannel
from .uploader import Uploader

# 로깅 설정
logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)

# 한국 시간대
KST = timezone(timedelta(hours=9))


class Agent:
    """Pi 에이전트 메인 루프."""

    def __init__(self, config: AgentConfig):
        self.config = config
        self.running = False

        # 데이터 디렉터리 생성
        self.data_dir = Path(config.data_dir)
        self.data_dir.mkdir(parents=True, exist_ok=True)

        # 모듈 초기화
        self.storage = Storage(self.data_dir / "agent.db")
        self.sync = HttpPollSyncChannel(config.server_url, config.device_token)

        # 프린터 드라이버 로드
        self.printer = self._load_printer(config.printer_driver)

        # Executor & Uploader
        self.executor = Executor(self.storage, self.sync, self.printer, self.data_dir, config.paper_policy)
        self.uploader = Uploader(self.storage, self.sync)

        # 기동 시 1회: 전송 도중 죽은 채로 남은 'attempting' 레코드 정리
        # (docs/pi/agent.md 9절 — 재시작 후 자동으로 다시 인쇄하지 않고 failed로
        # 끝낸다. 같은 내용이 두 번 나오는 것보다 한 번 빠지는 쪽을 택한다.)
        stale_keys = self.storage.cleanup_stale_attempts()
        if stale_keys:
            logger.warning(
                f"Cleaned up {len(stale_keys)} stale 'attempting' occurrence(s) from a previous run "
                f"(likely killed mid-print): {stale_keys}"
            )

        # 폴링 상태
        self.snapshot = None
        self.snapshot_hash = None
        self.poll_interval_sec = config.poll_interval_sec

    def _load_printer(self, driver_name: str) -> Printer:
        """프린터 드라이버 로드."""
        if driver_name == "fake":
            from printer.fake import FakePrinter
            logger.info("Using fake printer")
            return FakePrinter()
        elif driver_name == "m832":
            # ImportError를 fake로 조용히 폴백하지 않는다. HARU_PRINTER_DRIVER=m832인데
            # 드라이버·전송을 못 만들면 예외를 그대로 올려 서비스가 죽게 둔다 —
            # CLAUDE.md 기록 규칙("실패를 성공처럼 보고하지 않는다")과 같은 이유다.
            # 2026-09-17에 고친 import 경로 버그(`from pi.printer...`)가 정확히
            # ImportError였는데, 이 분기가 그걸 삼켜 fake로 내려가는 바람에 실물은
            # 백지인데 서버엔 printed로 보고되는 상태로 오래 숨어 있었다.
            from printer.m832 import M832Printer
            transport = self._load_transport(self.config.transport)
            logger.info(f"Using M832 printer (transport={self.config.transport})")
            return M832Printer(transport=transport, h_offset_mm=self.config.h_offset_mm)
        else:
            raise ValueError(f"Unknown printer driver: {driver_name}")

    def _load_transport(self, transport_name: str) -> Transport:
        """전송 계층 로드 (docs/pi/transport.md).

        이전까지 이 메서드가 없어서 `_load_printer`가 `HARU_TRANSPORT` 문자열을 그대로
        `M832Printer(transport=...)`에 넘기고 있었다 — `HARU_PRINTER_DRIVER=fake`였던
        동안은 이 경로를 타지 않아 드러나지 않았을 뿐, `m832`로 전환하면 드라이버가
        문자열에 `with transport:`를 걸며 즉시 예외가 났을 잠재 버그였다(2026-09-17 발견).

        Raises:
            ValueError: 알 수 없는 transport 이름, 또는 `bt`인데 `HARU_BT_ADDRESS`가 비어 있음
                (설정 오류를 fake로 조용히 감추지 않고 바로 드러낸다)
        """
        if transport_name == "usb":
            from transport.usb import UsbTransport
            return UsbTransport()
        elif transport_name == "bt":
            if not self.config.bt_address:
                raise ValueError("HARU_TRANSPORT=bt 인데 HARU_BT_ADDRESS가 비어 있음")
            from transport.bt import BtTransport
            return BtTransport(address=self.config.bt_address)
        else:
            raise ValueError(f"Unknown transport: {transport_name}")

    def run(self):
        """에이전트 시작."""
        self.running = True
        logger.info("Agent starting")

        # 폴링 루프와 스케줄러 틱을 별도 스레드로 실행
        polling_thread = threading.Thread(target=self._polling_loop, daemon=True)
        scheduler_thread = threading.Thread(target=self._scheduler_loop, daemon=True)

        polling_thread.start()
        scheduler_thread.start()

        # Ctrl+C로 종료할 때까지 대기
        try:
            while self.running:
                time.sleep(0.5)
        except KeyboardInterrupt:
            logger.info("Agent stopping (KeyboardInterrupt)")
            self.running = False

        # 스레드 종료 대기 (최대 5초)
        polling_thread.join(timeout=5)
        scheduler_thread.join(timeout=5)
        logger.info("Agent stopped")

    def _polling_loop(self):
        """30초마다 폴링 (또는 서버가 지시한 주기)."""
        while self.running:
            try:
                self._do_poll()
                time.sleep(self.poll_interval_sec)
            except Exception as e:
                logger.error(f"Polling loop error: {e}", exc_info=True)
                time.sleep(self.poll_interval_sec)

    def _do_poll(self):
        """한 번의 폴링 주기."""
        try:
            # 1. poll 요청
            profile = self.printer.profile()
            status = self.printer.status()

            heartbeat = {
                "agentVersion": "0.1.0",
                "printerProfile": profile.to_dict(),
                "printerStatus": status.to_dict(),
                "paperPolicy": self.config.paper_policy,
                "snapshotHash": self.snapshot_hash,
            }

            poll_response = self.sync.poll(heartbeat)
            logger.debug(f"Poll response: snapshotChanged={poll_response.snapshot_changed}")

            # 2. 스냅샷 변경 감지
            if poll_response.snapshot_changed:
                logger.info("Snapshot changed, fetching...")
                snapshot = self.sync.fetch_snapshot()
                snapshot_json = {
                    "snapshotHash": snapshot.snapshot_hash,
                    "generatedAt": snapshot.generated_at,
                    "schedules": snapshot.schedules,
                    "renders": snapshot.renders,
                }
                self.storage.save_snapshot(snapshot.snapshot_hash, snapshot_json)
                self.snapshot = snapshot_json
                self.snapshot_hash = snapshot.snapshot_hash

                # 렌더 다운로드 & 검증
                self._download_and_verify_renders(snapshot)

            # 3. 명령 처리 (폴링 응답의 commands)
            for cmd in poll_response.commands:
                self._handle_command(cmd)

            # 4. 용지 상태 저장 (manual_flag 정책용)
            if poll_response.paper_state:
                self.storage.set_kv("paperState", json.dumps(poll_response.paper_state))

            # 5. 폴링 주기 업데이트
            self.poll_interval_sec = poll_response.poll_interval_sec

            # 6. 결과 업로드
            upload_result = self.uploader.upload_pending_results()
            if upload_result["uploaded"]:
                logger.info(f"Uploaded {len(upload_result['uploaded'])} results")

        except Exception as e:
            logger.error(f"Poll error: {e}", exc_info=True)

    def _download_and_verify_renders(self, snapshot):
        """스냅샷의 렌더를 다운로드하고 검증."""
        renders = snapshot.renders
        for render_info in renders:
            # snapshot.renders는 서버 원본 JSON(camelCase)이다 — architecture.md 4.3절
            render_id = render_info["renderId"]
            expected_sha256 = render_info["sha256"]

            # 이미 다운로드되었고 검증됐는가?
            existing = self.storage.get_render(render_id)
            if existing and existing["verified"]:
                continue

            logger.info(f"Downloading render {render_id}...")
            try:
                png_bytes = self.sync.download_render(render_id)

                # sha256 검증
                actual_sha256 = hashlib.sha256(png_bytes).hexdigest()
                if actual_sha256 != expected_sha256:
                    logger.error(f"Render {render_id} sha256 mismatch")
                    continue

                # 저장
                render_path = self.data_dir / "renders" / f"{render_id}.png"
                render_path.parent.mkdir(parents=True, exist_ok=True)
                render_path.write_bytes(png_bytes)

                self.storage.save_render(
                    render_id,
                    render_info["formatId"],
                    render_info["targetDate"],
                    expected_sha256,
                    str(render_path),
                    verified=True,
                )
                logger.info(f"Render {render_id} downloaded and verified")
            except Exception as e:
                logger.error(f"Download render {render_id} failed: {e}")

    def _handle_command(self, cmd: dict):
        """명령 처리 (지금 인쇄)."""
        command_id = cmd.get("commandId")

        # 이미 처리된 명령인가?
        existing = self.storage.get_command(command_id)
        if existing:
            return

        # 명령 저장
        self.storage.save_command(
            command_id,
            cmd.get("formatId"),
            cmd.get("renderId"),
            cmd.get("sha256"),
            cmd.get("paperConfirmed", False),
            cmd.get("createdAt"),
        )
        logger.info(f"Received command {command_id}")

    def _scheduler_loop(self):
        """10초마다 스케줄러 틱."""
        tick_interval = 10
        while self.running:
            try:
                self._do_scheduler_tick()
                time.sleep(tick_interval)
            except Exception as e:
                logger.error(f"Scheduler loop error: {e}", exc_info=True)
                time.sleep(tick_interval)

    def _do_scheduler_tick(self):
        """스케줄러 틱: 실행할 occurrence를 찾아 실행한다."""
        logger.debug("Scheduler tick")
        if not self.snapshot:
            # 스냅샷이 없으면 실행 못함
            logger.debug("No snapshot yet")
            return

        now = datetime.now(KST)

        # 1. occurrence 계산
        candidates = calculate_occurrences(self.snapshot, now, self.config.grace_minutes)

        # 2. 실행 가능한 것 필터링
        # executed_occurrences 조회 — candidates가 이미 정확한 occurrence_key를 계산해 뒀으므로
        # 그 key로 직접 조회한다. (예전 코드는 정시(00:00,01:00,...)만 추측 조회해서
        # 10:31처럼 정시가 아닌 예약은 "이미 실행함"을 절대 찾지 못해 매 틱마다 무한 재실행되는
        # 버그가 있었다 — 실사로 확인·수정.)
        existing_occs = {}
        for occ in candidates:
            stored = self.storage.get_executed_occurrence(occ.occurrence_key)
            if stored:
                existing_occs[occ.occurrence_key] = stored

        executable = filter_executable_occurrences(candidates, now, self.config.grace_minutes, existing_occs)

        # 3. 실행
        for occ in executable:
            logger.info(f"Executing {occ.occurrence_key}")
            self.executor.execute_occurrence(occ, self.snapshot)

        # 4. 유예 시간이 지난 것들을 최종 기록
        for occ in candidates:
            existing = existing_occs.get(occ.occurrence_key, {})
            if existing.get("final", False):
                continue

            # 유예 범위를 넘었는가?
            grace_delta = timedelta(minutes=self.config.grace_minutes)
            if now > occ.scheduled_at + grace_delta:
                # 이전에 시도한 적이 있는가?
                if existing:
                    # 마지막 상태를 final로
                    self.storage.save_executed_occurrence(
                        occ.occurrence_key,
                        status=existing.get("status", "missed"),
                        final=True,
                    )
                else:
                    # 처음 본 것인데 유예가 지났으면 missed
                    self.storage.save_executed_occurrence(
                        occ.occurrence_key,
                        status="missed",
                        final=True,
                    )


def main():
    """main entry point.

    설정 로드뿐 아니라 Agent(config) 생성 중에도 실패할 수 있다(드라이버/전송을 못
    만들면 ValueError·ImportError 등이 난다 — `_load_printer`가 더 이상 이를 fake로
    삼키지 않는다). 여기서 사람이 읽을 수 있는 오류로 sys.exit(1)로 끝내되, 오류를
    삼켜 그대로 기동하는 일은 없어야 한다(systemd가 재시작 루프를 돌릴 때 트레이스백만
    반복 출력되는 대신 원인 한 줄이 먼저 보이게 하려는 목적일 뿐이다).
    """
    try:
        config = AgentConfig.from_env()
        agent = Agent(config)
    except Exception as e:
        logger.error(f"Agent failed to start: {e}", exc_info=True)
        sys.exit(1)

    # signal handler (graceful shutdown)
    def signal_handler(sig, frame):
        logger.info(f"Received signal {sig}")
        agent.running = False

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    agent.run()


if __name__ == "__main__":
    main()
