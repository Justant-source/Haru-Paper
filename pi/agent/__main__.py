"""진입점: python -m agent. 폴링 루프와 스케줄러 틱을 동시에 실행한다. docs/pi/agent.md 6절, 7절."""

from __future__ import annotations

import hashlib
import json
import logging
import signal
import sys
import threading
import time
import uuid
from datetime import timedelta
from pathlib import Path

from printer import Printer, PrinterStatus
from transport import Transport

from .clock import now_kst, parse_local_iso
from .config import AgentConfig
from .executor import Executor
from .scheduler import calculate_occurrences, filter_executable_occurrences
from .storage import Storage
from .sync import HttpPollSyncChannel
from .uploader import Uploader

# 로깅 설정
logging.basicConfig(
    level=logging.DEBUG,
    format="%(asctime)s - %(name)s - %(levelname)s - %(message)s",
)
logger = logging.getLogger(__name__)


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
        self.executor = Executor(
            self.storage,
            self.sync,
            self.printer,
            self.data_dir,
            config.paper_policy,
            command_ttl_sec=config.command_ttl_sec,
        )
        self.uploader = Uploader(self.storage, self.sync)

        # 기동 시 1회: 전송 도중 죽은 채로 남은 'attempting' 레코드를 정리한다
        # (docs/pi/agent.md 9절 — 재시작 후 자동으로 다시 인쇄하지 않고 failed로
        # 끝낸다. 같은 내용이 두 번 나오는 것보다 한 번 빠지는 쪽을 택한다).
        # ★2026-09-17 변경(.temp/05 설계 과제 3)★: cleanup_stale_attempts()는 이제
        # 조회만 하고, 종결과 결과 큐잉(results_queue)은 executor.finalize_*를 거쳐
        # 한 곳에서 한다 — 예전에는 여기서 종결만 시키고 서버에 결과가 올라가지
        # 않았다(missed/failed가 앱 이력에 통째로 빠지는 결함).
        stale_occurrences = self.storage.cleanup_stale_attempts()
        for row in stale_occurrences:
            self.executor.finalize_occurrence(row["occurrence_key"], status="failed", detail="전송 중 중단(재시작)")
        if stale_occurrences:
            logger.warning(
                f"Cleaned up {len(stale_occurrences)} stale 'attempting' occurrence(s) from a previous run "
                f"(likely killed mid-print): {[r['occurrence_key'] for r in stale_occurrences]}"
            )

        stale_commands = self.storage.get_stale_attempting_commands()
        for row in stale_commands:
            self.executor.finalize_command(row["command_id"], status="failed", detail="전송 중 중단(재시작)")
        if stale_commands:
            logger.warning(
                f"Cleaned up {len(stale_commands)} stale 'attempting' command(s) from a previous run: "
                f"{[r['command_id'] for r in stale_commands]}"
            )

        # 폴링 상태
        self.snapshot = None
        self.snapshot_hash = None
        self.poll_interval_sec = config.poll_interval_sec

        # ③[중요] heartbeat(printerProfile/printerStatus)에 쓸 캐시. 아직 폴링·
        # 스케줄러 스레드가 시작되기 전이라 여기서는 직접 불러도 안전하다(락 없이
        # 프린터에 접근하는 유일한 곳). 이후 _do_poll은 executor.try_lock_printer()로
        # 락을 못 잡으면(스케줄러가 인쇄 중) 이 값을 그대로 재사용한다.
        self._last_profile = self.printer.profile()
        self._last_status = self.printer.status()

        # 새 명령이 도착하면 스케줄러 틱을 즉시 깨운다(.temp/05 설계 Q5) — poll(최대
        # 30초) + 다음 틱(최대 10초) = 최대 40초를 기다리지 않고 poll 직후 실행되게 한다.
        self._wake = threading.Event()

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
            self.stop()

        # 스레드 종료 대기 (최대 5초)
        polling_thread.join(timeout=5)
        scheduler_thread.join(timeout=5)
        logger.info("Agent stopped")

    def stop(self) -> None:
        """정상 종료: 실행 루프를 멈추고 스케줄러 틱을 즉시 깨운다.

        ⑦[사소, 2026-09-17 Opus 검토로 발견] `self.running = False`만으로는
        `_scheduler_loop`가 `self._wake.wait(tick_interval)`(최대 10초)에서 자고
        있을 수 있다 — `run()`의 `join(timeout=5)`가 그보다 먼저 끝나면, 데몬
        스레드가 (운이 나쁘면) 인쇄 도중에 프로세스 종료로 강제 중단될 수 있다.
        `_wake.set()`으로 즉시 깨워 루프 조건을 다시 검사하게 한다."""
        self.running = False
        self._wake.set()

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
            # 1. poll 요청. ③[중요, 2026-09-17 Opus 검토로 발견] heartbeat용
            # printer.profile()/status() 호출은 executor._print_lock을 논블로킹으로
            # 거친다(자세한 이유는 Executor.try_lock_printer 참고). 스케줄러 스레드가
            # print_image 중이면(최대 60초) 락을 못 잡는데, 그렇다고 폴링을 그만큼
            # 멈추면 안 되므로 그 경우 직전 프린터 상태를 그대로 재사용하고 다음
            # 폴링으로 넘긴다.
            with self.executor.try_lock_printer() as acquired:
                if acquired:
                    self._last_profile = self.printer.profile()
                    self._last_status = self.printer.status()
                else:
                    logger.debug("Printer busy (printing) — heartbeat에 직전 프린터 상태를 재사용")
            profile = self._last_profile
            status = self._last_status

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

            # 3. 렌더 다운로드 & 검증 — snapshot_changed와 분리한다(.temp/05 설계 4.5).
            # 스냅샷이 안 바뀌어도 매 poll마다 시도한다. 이미 verified=1이면 DB 조회
            # 한 번으로 끝나므로(storage.get_render) 비용은 낮다. 이전에는 스냅샷이
            # 안 바뀌면 재시도 기회가 없어 "렌더 없음 → 재시도"가 실제로는 구제되지
            # 않았다.
            if self.snapshot:
                self._sync_renders(self.snapshot.get("renders", []))

            # 4. 명령 처리 (폴링 응답의 commands) — 수신·저장만 하고 실행은 스케줄러
            # 틱이 한다(Q5: 프린터로 나가는 경로를 한 스레드로 직렬화).
            for cmd in poll_response.commands:
                self._handle_command(cmd)

            # 명령 렌더 확보: 새로 받은 것뿐 아니라 아직 검증 안 된 pending 명령
            # 전부에 대해 매 poll마다 재시도한다(다운로드가 한 번 실패했을 수 있다).
            for pending in self.storage.get_pending_commands():
                self._ensure_command_render(pending)

            # 5. 용지 상태 저장 (manual_flag 정책용)
            if poll_response.paper_state:
                self.storage.set_kv("paperState", json.dumps(poll_response.paper_state))

            # 6. 폴링 주기 업데이트
            self.poll_interval_sec = poll_response.poll_interval_sec

            # 7. 결과 업로드
            upload_result = self.uploader.upload_pending_results()
            if upload_result["uploaded"]:
                logger.info(f"Uploaded {len(upload_result['uploaded'])} results")

        except Exception as e:
            logger.error(f"Poll error: {e}", exc_info=True)

    def _sync_renders(self, renders: list) -> None:
        """스냅샷 렌더 목록을 다운로드·검증한다. 이미 verified=1이면 건너뛴다."""
        for render_info in renders:
            # snapshot["renders"]는 서버 원본 JSON(camelCase)이다 — architecture.md 4.3절
            render_id = render_info["renderId"]
            expected_sha256 = render_info["sha256"]

            existing = self.storage.get_render(render_id)
            if existing and existing["verified"]:
                continue

            logger.info(f"Downloading render {render_id}...")
            try:
                png_bytes = self.sync.download_render(render_id)

                actual_sha256 = hashlib.sha256(png_bytes).hexdigest()
                if actual_sha256 != expected_sha256:
                    logger.error(f"Render {render_id} sha256 mismatch")
                    continue

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

    def _ensure_command_render(self, cmd_row: dict) -> bool:
        """명령이 참조하는 렌더를 확보한다.

        스냅샷 렌더 동기화와 달리 **명령 자신의 sha256**으로 검증한다(스냅샷 것이
        아니다) — 명령은 스냅샷에 없는 렌더를 가리킬 수 있다(docs/pi/agent.md 8절).
        sha256이 빈 문자열(서버가 렌더를 못 찾은 경우)이면 검증 불가 = fail-closed로
        다운로드하지 않는다(.temp/05 설계 Q6).

        Returns:
            이미 로컬에 검증된 상태로 있으면(또는 지금 확보했으면) True.
        """
        render_id = cmd_row.get("render_id")
        sha256 = cmd_row.get("sha256")
        if not render_id or not sha256:
            return False

        existing = self.storage.get_render(render_id)
        if existing and existing["verified"] and existing["sha256"] == sha256:
            return True

        try:
            png_bytes = self.sync.download_render(render_id)
            actual_sha256 = hashlib.sha256(png_bytes).hexdigest()
            if actual_sha256 != sha256:
                logger.error(f"Command render {render_id} sha256 mismatch (명령 sha256 기준) — 캐시하지 않음")
                return False

            render_path = self.data_dir / "renders" / f"{render_id}.png"
            render_path.parent.mkdir(parents=True, exist_ok=True)
            render_path.write_bytes(png_bytes)

            self.storage.save_render(
                render_id,
                cmd_row.get("format_id") or "",
                "",  # 명령 렌더는 특정 날짜(targetDate)에 묶이지 않는다
                sha256,
                str(render_path),
                verified=True,
            )
            logger.info(f"Command render {render_id} downloaded and verified")
            return True
        except Exception as e:
            logger.error(f"Download command render {render_id} failed: {e}")
            return False

    def _handle_command(self, cmd: dict):
        """명령 수신 처리 (지금 인쇄). 저장만 한다 — 실행은 스케줄러 틱
        (_do_scheduler_tick)이 한다(Q5: 프린터로 나가는 모든 경로를 한 스레드로
        직렬화)."""
        command_id = cmd.get("commandId")
        if not command_id:
            # 서버 오류 방어: commandId 없는 명령은 무시한다.
            logger.warning(f"commandId 없는 명령 무시: {cmd!r}")
            return

        # 이미 받은 명령인가?
        existing = self.storage.get_command(command_id)
        if existing:
            return

        # B5: `is True` 엄격 비교. null/문자열("false" 등)을 참으로 왜곡하지 않는다
        # (2026-09-17 발견: 이전 코드는 cmd.get("paperConfirmed", False)로 읽어
        # 키가 있고 값이 null이면 None이 그대로 storage.save_command로 넘어가
        # int(None)에서 TypeError가 났다).
        paper_confirmed = cmd.get("paperConfirmed") is True

        self.storage.save_command(
            command_id,
            cmd.get("formatId"),
            cmd.get("renderId"),
            cmd.get("sha256"),
            paper_confirmed,
            cmd.get("createdAt"),
        )
        logger.info(f"Received command {command_id}")
        self._wake.set()

    def _scheduler_loop(self):
        """10초마다 스케줄러 틱. 새 명령이 도착하면(_handle_command의 wake.set())
        다음 틱까지 기다리지 않고 즉시 깨어난다."""
        # 10초 [기본값](docs/pi/agent.md:117 "짧은 주기(예: 10초 [기본값])") — 재시도·
        # 유예 만료 판정의 정확도(짧을수록 좋음)와 SD카드 쓰기·CPU 각성 빈도(길수록
        # 좋음) 사이의 절충. 명령은 이 주기를 기다리지 않고 _wake로 즉시 반응한다.
        tick_interval = 10
        while self.running:
            try:
                self._do_scheduler_tick()
            except Exception as e:
                logger.error(f"Scheduler loop error: {e}", exc_info=True)
            self._wake.wait(tick_interval)
            self._wake.clear()

    def _do_scheduler_tick(self):
        """스케줄러 틱: 실행할 occurrence와 대기 중인 명령을 처리한다.

        순서(.temp/05 설계 4.5):
          1) 스냅샷이 없어도 명령 처리는 계속한다(명령은 스냅샷과 무관하다).
          2) occurrence 계산 → 실행 가능한 것 필터링(재시도 간격 포함) → 실행.
          3) **저장소를 다시 읽어** 유예 만료 처리 → Executor.finalize_occurrence.
             (2단계에서 막 기록한 값을 4단계가 봐야 한다 — 틱 시작 시점의 스냅샷을
             쓰면 재시도 도입 후 방금 기록한 결과를 덮어쓸 수 있다.)
          4) 명령 처리: 재시도 간격을 지킨 pending 명령을 예약 시각 순서 없이(명령은
             먼저 온 순서) execute_command에 넘긴다.
        """
        logger.debug("Scheduler tick")
        now = now_kst()

        if self.snapshot:
            self._run_occurrence_tick(now)
        else:
            logger.debug("No snapshot yet — occurrence 처리는 건너뛰고 명령만 처리한다")

        self._run_command_tick(now)

    def _run_occurrence_tick(self, now) -> None:
        # 1. occurrence 계산
        candidates = calculate_occurrences(self.snapshot, now, self.config.grace_minutes)

        # 2. executed_occurrences 조회 — candidates가 이미 정확한 occurrence_key를
        # 계산해 뒀으므로 그 key로 직접 조회한다.
        existing_occs = {}
        for occ in candidates:
            stored = self.storage.get_executed_occurrence(occ.occurrence_key)
            if stored:
                existing_occs[occ.occurrence_key] = stored

        executable = filter_executable_occurrences(
            candidates, now, self.config.grace_minutes, existing_occs, self.config.retry_interval_sec
        )

        # 3. 실행
        for occ in executable:
            logger.info(f"Executing {occ.occurrence_key}")
            self.executor.execute_occurrence(occ, self.snapshot)

        # 4. 유예 시간이 지난 것들을 최종 기록. 저장소를 다시 읽는다(3단계가 방금
        # 쓴 값을 보기 위해 — existing_occs는 틱 시작 시점 스냅샷이라 쓰지 않는다).
        grace_delta = timedelta(minutes=self.config.grace_minutes)
        for occ in candidates:
            row = self.storage.get_executed_occurrence(occ.occurrence_key)
            if row and row.get("final"):
                continue

            if now <= occ.scheduled_at + grace_delta:
                continue

            if row:
                # 시도한 적이 있으면 마지막 사유를 그대로 최종 status로(policy.md 3절
                # "재시도했지만 조건 미충족이면 마지막 사유"). checking/attempting처럼
                # 비종결 status가 남아 있으면 finalize_occurrence가 서버 ENUM 보호를
                # 위해 failed로 매핑한다.
                self.executor.finalize_occurrence(
                    occ.occurrence_key, status=row.get("status") or "missed", detail=row.get("detail") or ""
                )
            else:
                # 처음 본 것인데 유예가 지났으면 missed(policy.md:46 "시도 기록이
                # 아예 없는 회차"). finalize_occurrence는 저장소 행이 있어야 하므로
                # 먼저 빈 시도를 만든다.
                self.storage.begin_occurrence_attempt(
                    occ.occurrence_key,
                    format_id=occ.format_id,
                    render_id="",
                    scheduled_at=occ.scheduled_at.isoformat(),
                    result_id=str(uuid.uuid4()),
                )
                self.executor.finalize_occurrence(occ.occurrence_key, status="missed", detail="")

    def _run_command_tick(self, now) -> None:
        retry_delta = timedelta(seconds=self.config.retry_interval_sec)
        for cmd in self.storage.get_pending_commands():
            # ①[중요, 2026-09-17 Opus 검토로 실증] 'attempting'(print_image 직전~
            # 반환 전, 바이트가 나갔을 수 있는 시도 — storage.mark_command_attempting
            # 주석과 같은 표식)인 명령은 이 프로세스 안에서 절대 다시 실행하지
            # 않는다. 재현된 시나리오: print_image 성공 → finalize_command의 DB
            # 쓰기가 예외(디스크 꽉 참, `database is locked` 등)로 실패 → 예외가
            # _do_scheduler_tick 바깥 try/except에 삼켜짐 → status가 'attempting'인
            # 채로 남음 → 재시도 간격만 보고 여기서 다시 execute_command를 부르면
            # 두 번째 장이 나간다. scheduler.filter_executable_occurrences의
            # occurrence용 attempting 가드와 같은 원칙 — attempting은 재시작 후
            # get_stale_attempting_commands()의 기동 정리로만 종결시킨다(중복
            # 인쇄보다 "이 명령은 멈춰 있다"가 낫다).
            if cmd.get("status") == "attempting":
                continue
            last_attempt_at = parse_local_iso(cmd.get("last_attempt_at"))
            if last_attempt_at is not None and (now - last_attempt_at) < retry_delta:
                continue
            logger.info(f"Executing command {cmd['command_id']}")
            self.executor.execute_command(cmd)


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
        # ⑦ agent.running = False만으로는 _scheduler_loop가 최대 10초(tick_interval)
        # 동안 self._wake.wait()에서 잠들어 있을 수 있다 — stop()이 즉시 깨운다.
        agent.stop()

    signal.signal(signal.SIGINT, signal_handler)
    signal.signal(signal.SIGTERM, signal_handler)

    agent.run()


if __name__ == "__main__":
    main()
