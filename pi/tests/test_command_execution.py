""""지금 인쇄" 명령 실행 테스트(.temp/05 설계 6.3, 과제 2).

용지 게이트가 핵심이다: 예약 경로에서 paper_confirmed로 우회되면 CLAUDE.md
절대 금지 1(용지를 눈으로 확인하기 전 래스터 금지)을 정면으로 어긴다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from agent import __main__ as main_module
from agent.config import AgentConfig
from agent.executor import Executor
from agent.scheduler import Occurrence
from agent.storage import Storage
from printer.fake import FakePrinter

KST = timezone(timedelta(hours=9))


@pytest.fixture(autouse=True)
def _isolate_fake_printer_data_dir(tmp_path, monkeypatch):
    """FakePrinter는 생성 시점에 HARU_DATA_DIR을 직접 읽는다(agent.data_dir과
    무관) — 설정하지 않으면 공용 /tmp/haru-paper-fake에 파일을 남긴다. 테스트마다
    독립된 tmp_path로 고정한다."""
    monkeypatch.setenv("HARU_DATA_DIR", str(tmp_path / "fake-printer-data"))


class ExplodingPrinter(FakePrinter):
    """print_image가 호출되면 즉시 테스트를 실패시킨다."""

    def print_image(self, png_bytes):
        raise AssertionError("print_image가 호출되면 안 되는 경로에서 호출됨")


def make_executor(tmp_path, paper_policy="unverified", printer=None, command_ttl_sec=600) -> Executor:
    storage = Storage(tmp_path / "agent.db")
    if printer is None:
        printer = FakePrinter(connected=True)
    return Executor(
        storage=storage,
        sync=None,
        printer=printer,
        data_dir=tmp_path,
        paper_policy=paper_policy,
        command_ttl_sec=command_ttl_sec,
    )


def save_command_and_get(
    ex: Executor,
    command_id="c1",
    format_id="fmt1",
    render_id="r1",
    sha256="deadbeef",
    paper_confirmed=False,
    created_at=None,
) -> dict:
    if created_at is None:
        created_at = datetime.now(KST).isoformat()
    ex.storage.save_command(command_id, format_id, render_id, sha256, paper_confirmed, created_at)
    return ex.storage.get_command(command_id)


def save_ready_render(ex: Executor, render_id="r1", format_id="fmt1", sha256="deadbeef") -> None:
    png_path = ex.data_dir / "renders" / f"{render_id}.png"
    png_path.parent.mkdir(parents=True, exist_ok=True)
    png_path.write_bytes(b"fake-png-bytes")
    ex.storage.save_render(render_id, format_id, "2026-09-17", sha256, str(png_path), verified=True)


def ttl_expired_created_at(command_ttl_sec=600) -> str:
    """TTL을 확실히 넘긴 생성 시각(기본 TTL 600초보다 100초 더 지난 시각)."""
    return (datetime.now(KST) - timedelta(seconds=command_ttl_sec + 100)).isoformat()


class TestCommandPaperGate:
    def test_t13_unverified_unconfirmed_never_prints_and_ends_skipped_no_paper(self, tmp_path):
        """T13: unverified + paperConfirmed=false → print_image 호출 없음,
        (TTL 만료 후) skipped_no_paper로 종결."""
        printer = ExplodingPrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="unverified", printer=printer)
        save_ready_render(ex)
        cmd = save_command_and_get(ex, paper_confirmed=False, created_at=ttl_expired_created_at())

        result = ex.execute_command(cmd)

        assert result is not None
        assert result["status"] == "skipped_no_paper"
        assert result["commandId"] == "c1"
        assert result["occurrenceKey"] is None

    def test_t13b_unverified_unconfirmed_finalizes_immediately_without_waiting_ttl(self, tmp_path):
        """⑤[사소, 2026-09-17 Opus 검토로 발견]: paper_confirmed는 명령 생명주기
        동안 바뀌지 않는다(생성 시 고정). unverified 정책의 결과는 paper_confirmed만
        으로 정해지므로, 확인 안 된 명령은 몇 번을 재시도해도 결과가 같다 — TTL
        10분을 기다리지 않고 즉시 종결해야 한다(이전에는 TTL까지 60초 간격으로
        10분간 무의미한 재시도를 했고, 앱 이력에도 10분 뒤에야 떴다)."""
        printer = ExplodingPrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="unverified", printer=printer)
        save_ready_render(ex)
        cmd = save_command_and_get(ex, paper_confirmed=False)  # created_at=now, TTL 안 지남

        result = ex.execute_command(cmd)

        assert result is not None
        assert result["status"] == "skipped_no_paper"

    def test_manual_flag_unconfirmed_still_waits_for_ttl_not_immediate(self, tmp_path):
        """⑤와 대비: manual_flag의 paperState.loaded는 서버 push로 언제든 바뀔 수
        있으므로(사용자가 앱에서 "용지 확인" 토글), unverified와 달리 즉시 종결하지
        않고 TTL까지 재시도를 유지해야 한다."""
        printer = ExplodingPrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="manual_flag", printer=printer)
        save_ready_render(ex)
        cmd = save_command_and_get(ex, paper_confirmed=False)  # TTL 안 지남, paperState 없음

        result = ex.execute_command(cmd)

        assert result is None
        stored = ex.storage.get_command("c1")
        assert stored["status"] == "checking"

    def test_t14_unverified_confirmed_prints(self, tmp_path):
        """T14: unverified + paperConfirmed=true → printed, commandId 있고
        occurrenceKey는 None."""
        printer = FakePrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="unverified", printer=printer)
        save_ready_render(ex)
        cmd = save_command_and_get(ex, paper_confirmed=True)

        result = ex.execute_command(cmd)

        assert result is not None
        assert result["status"] == "printed"
        assert result["commandId"] == "c1"
        assert result["occurrenceKey"] is None

    def test_t15_status_query_ignores_paper_confirmed(self, tmp_path):
        """T15: status_query + paperConfirmed=true → 인쇄하지 않는다(용지 게이트
        우회 고정, policy.md:30)."""
        printer = ExplodingPrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="status_query", printer=printer)
        save_ready_render(ex)
        cmd = save_command_and_get(ex, paper_confirmed=True, created_at=ttl_expired_created_at())

        result = ex.execute_command(cmd)

        assert result is not None
        assert result["status"] == "skipped_no_paper"

    def test_t16_scheduled_path_never_passes_paper_confirmed_true(self, tmp_path):
        """T16: 예약 경로는 어떤 경우에도 _check_paper_policy에 paper_confirmed=True를
        넘기지 않는다(spy로 인자 검사) — 용지 게이트 우회 고정."""
        printer = FakePrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="manual_flag", printer=printer)
        ex.storage.set_kv("paperState", '{"loaded": true}')

        png_path = ex.data_dir / "renders" / "r1.png"
        png_path.parent.mkdir(parents=True, exist_ok=True)
        png_path.write_bytes(b"fake-png-bytes")
        today = datetime.now(KST).strftime("%Y-%m-%d")
        ex.storage.save_render("r1", "fmt1", today, "deadbeef", str(png_path), verified=True)

        occ = Occurrence(
            occurrence_key=f"s1@{today}T{datetime.now(KST).strftime('%H:%M')}",
            schedule_id="s1",
            format_id="fmt1",
            scheduled_at=datetime.now(KST).replace(second=0, microsecond=0),
        )
        snapshot = {"renders": [{"renderId": "r1", "formatId": "fmt1", "targetDate": today, "sha256": "deadbeef"}]}

        calls = []
        original = Executor._check_paper_policy

        def spy(self, render, paper_confirmed=False):
            calls.append(paper_confirmed)
            return original(self, render, paper_confirmed)

        Executor._check_paper_policy = spy
        try:
            result = ex.execute_occurrence(occ, snapshot)
        finally:
            Executor._check_paper_policy = original

        assert result is not None
        assert result["status"] == "printed"
        assert calls == [False]  # 절대 True가 넘어가지 않는다

    def test_t17_sha256_mismatch_does_not_cache_or_print(self, tmp_path):
        """T17: 렌더 sha256 불일치 → 캐시 저장 안 함, 인쇄 안 함(__main__의 명령
        렌더 확보 경로)."""

        class RecordingSync:
            def download_render(self, render_id):
                return b"wrong-bytes"  # sha256("wrong-bytes") != "deadbeef"

        config = make_agent_config(tmp_path)
        agent = main_module.Agent(config)
        agent.sync = RecordingSync()

        cmd_row = {"render_id": "r1", "sha256": "deadbeef", "format_id": "fmt1"}
        ok = agent._ensure_command_render(cmd_row)

        assert ok is False
        assert agent.storage.get_render("r1") is None

    def test_t18_same_command_id_prints_once(self, tmp_path):
        """T18: 같은 commandId가 두 poll에 실려도 한 번만 처리된다(중복 인쇄 고정).
        _handle_command은 이미 존재하는 command_id를 무시하고, 실행 완료된
        명령(status='done')은 get_pending_commands()에 다시 나타나지 않는다."""
        config = make_agent_config(tmp_path)
        agent = main_module.Agent(config)

        cmd_dto = {
            "commandId": "c1",
            "formatId": "fmt1",
            "renderId": "r1",
            "sha256": "deadbeef",
            "paperConfirmed": True,
            "createdAt": datetime.now(KST).isoformat(),
        }
        agent._handle_command(cmd_dto)
        agent._handle_command(cmd_dto)  # 두 번째 poll에서 같은 명령이 다시 옴

        pending = agent.storage.get_pending_commands()
        assert len(pending) == 1  # 저장은 한 번만 됐다(existing 체크)

        save_ready_render_for_agent(agent, "r1", "fmt1", "deadbeef")
        cmd = agent.storage.get_command("c1")
        result = agent.executor.execute_command(cmd)
        assert result["status"] == "printed"

        # 종결된 명령은 더 이상 pending이 아니다 → 다음 틱에서 다시 실행되지 않는다
        assert agent.storage.get_pending_commands() == []

    def test_t18b_done_command_resent_by_server_does_not_reprint(self, tmp_path):
        """⑧[중요, 2026-09-17 Opus 검토로 발견]: 원래 T18은 '같은 poll 안에서
        두 번 수신'만 재현해서, `_handle_command`의 `if existing: return` 가드를
        지워도(실제로 확인) 통과했다 — `save_command`가 REPLACE INTO(PK=command_id)라
        중복을 지워도 `len(pending)==1`이 그대로 성립하기 때문이다. 실제로 위험한
        순서는 **실행 완료(done) → 같은 commandId 재수신 → REPLACE가 행을 pending
        으로 되돌림 → 재인쇄**다. 이 테스트는 그 순서를 그대로 재현한다."""
        config = make_agent_config(tmp_path)
        agent = main_module.Agent(config)

        cmd_dto = {
            "commandId": "c1",
            "formatId": "fmt1",
            "renderId": "r1",
            "sha256": "deadbeef",
            "paperConfirmed": True,
            "createdAt": datetime.now(KST).isoformat(),
        }
        agent._handle_command(cmd_dto)
        save_ready_render_for_agent(agent, "r1", "fmt1", "deadbeef")

        cmd = agent.storage.get_command("c1")
        result = agent.executor.execute_command(cmd)
        assert result["status"] == "printed"
        assert agent.storage.get_command("c1")["status"] == "done"

        # 서버가 같은 commandId를 나중 poll 응답에 다시 실어 보낸다(네트워크 재시도,
        # 서버 재시작 등으로 인한 재전송을 흉내낸다).
        agent._handle_command(cmd_dto)

        assert agent.storage.get_pending_commands() == []  # done으로 되돌아가지 않는다
        assert agent.storage.get_command("c1")["status"] == "done"

    def test_t19b_ttl_checked_again_right_before_print_even_if_printer_back_online(self, tmp_path):
        """②[중요, 2026-09-17 Opus 검토로 발견]: TTL은 함수 진입 시점뿐 아니라
        print_image 직전에도 다시 확인돼야 한다. 이전에는 렌더·용지 정책·프린터
        상태가 전부 통과하면(성공 경로) TTL을 아예 보지 않고 그냥 인쇄했다 — 프린터가
        TTL(10분)보다 오래 꺼져 있다가 다시 켜지면, 사용자가 10분 전에 확인한 용지가
        더 이상 유효하지 않을 수 있는데도 인쇄됐다. printer.status()는 성공하되
        print_image가 호출되면 실패하는 ExplodingPrinter로, '오프라인이라 못 감'과
        구분해 성공 경로에서만 TTL이 걸리는지 확인한다."""
        printer = ExplodingPrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="unverified", printer=printer, command_ttl_sec=600)
        save_ready_render(ex)
        cmd = save_command_and_get(ex, paper_confirmed=True, created_at=ttl_expired_created_at())

        result = ex.execute_command(cmd)

        assert result is not None
        assert result["status"] == "missed"
        assert "TTL" in result["detail"]

    def test_t19_ttl_expired_printer_offline_ends_missed(self, tmp_path):
        """T19: TTL(command_ttl_sec) 지난 명령은 인쇄하지 않고 missed 결과를
        남긴다(프린터가 끝까지 오프라인이었던 경우 — Q6 "TTL 만료, 그 밖")."""
        printer = ExplodingPrinter(connected=False)
        ex = make_executor(tmp_path, paper_policy="manual_flag", printer=printer, command_ttl_sec=600)
        ex.storage.set_kv("paperState", '{"loaded": true}')
        save_ready_render(ex)
        cmd = save_command_and_get(ex, paper_confirmed=False, created_at=ttl_expired_created_at())

        result = ex.execute_command(cmd)

        assert result is not None
        assert result["status"] == "missed"

    def test_render_not_ready_but_ttl_not_expired_stays_pending(self, tmp_path):
        """TTL 전에는 렌더가 아직 없어도 종결하지 않는다(다음 poll에서 도착할 수
        있다) — 3.3절 '렌더 미수신/미검증, TTL 안 → pending 유지'."""
        printer = ExplodingPrinter(connected=True)
        ex = make_executor(tmp_path, paper_policy="unverified", printer=printer)
        cmd = save_command_and_get(ex, paper_confirmed=True)  # 렌더를 아예 저장하지 않음

        result = ex.execute_command(cmd)

        assert result is None
        stored = ex.storage.get_command("c1")
        assert stored["status"] == "checking"


def save_ready_render_for_agent(agent, render_id, format_id, sha256) -> None:
    png_path = agent.data_dir / "renders" / f"{render_id}.png"
    png_path.parent.mkdir(parents=True, exist_ok=True)
    png_path.write_bytes(b"fake-png-bytes")
    agent.storage.save_render(render_id, format_id, "2026-09-17", sha256, str(png_path), verified=True)


def make_agent_config(tmp_path, **overrides) -> AgentConfig:
    defaults = dict(
        server_url="http://127.0.0.1:18080",
        device_token="test-token",
        poll_interval_sec=30,
        printer_driver="fake",
        transport="usb",
        bt_address="",
        paper_policy="unverified",
        grace_minutes=30,
        retry_interval_sec=60,
        h_offset_mm=2.0,
        data_dir=str(tmp_path),
        sent_retention_days=30,
        command_ttl_sec=600,
    )
    defaults.update(overrides)
    return AgentConfig(**defaults)


class TestHandleCommandRobustness:
    def test_t20_paper_confirmed_null_does_not_crash(self, tmp_path):
        """T20: paperConfirmed가 null이어도 _handle_command가 예외 없이 False로
        저장한다(1.2의 int(None) TypeError 회귀 방지)."""
        config = make_agent_config(tmp_path)
        agent = main_module.Agent(config)

        cmd_dto = {
            "commandId": "c-null",
            "formatId": "fmt1",
            "renderId": "r1",
            "sha256": "deadbeef",
            "paperConfirmed": None,
            "createdAt": datetime.now(KST).isoformat(),
        }
        agent._handle_command(cmd_dto)  # 예외가 나면 테스트 실패

        stored = agent.storage.get_command("c-null")
        assert stored["paper_confirmed"] == 0

    def test_missing_command_id_is_ignored(self, tmp_path):
        config = make_agent_config(tmp_path)
        agent = main_module.Agent(config)
        agent._handle_command({"formatId": "fmt1"})  # commandId 없음 — 예외 없이 무시
        assert agent.storage.get_pending_commands() == []


class TestStaleAttemptingCommandCleanup:
    def test_t21_stale_attempting_command_finalized_as_failed_without_reprint(self, tmp_path):
        """T21: 기동 시 attempting으로 남은 명령 → failed 결과 + 재인쇄 없음."""
        data_dir = tmp_path / "haru-data"
        data_dir.mkdir(parents=True, exist_ok=True)

        pre_storage = Storage(data_dir / "agent.db")
        pre_storage.save_command("c-stale", "fmt1", "r1", "deadbeef", False, datetime.now(KST).isoformat())
        pre_storage.begin_command_attempt("c-stale", result_id="r-stale")
        pre_storage.mark_command_attempting("c-stale")

        config = make_agent_config(tmp_path, data_dir=str(data_dir))
        agent = main_module.Agent(config)  # 기동 정리가 여기서 일어난다(print_image 없이)

        stored = agent.storage.get_command("c-stale")
        assert stored["status"] == "done"

        queued = agent.storage.get_queued_results()
        assert len(queued) == 1
        assert queued[0]["payload_json"]["status"] == "failed"
        assert queued[0]["payload_json"]["commandId"] == "c-stale"

        # 다시 pending으로 나타나지 않는다(재인쇄 없음)
        assert agent.storage.get_pending_commands() == []


class TestCommandAttemptingNeverReexecutedSameProcess:
    """①[중요, 2026-09-17 Opus 검토로 실증]: __main__._run_command_tick은
    'attempting'(바이트가 나갔을 수 있는 시도) 명령을 재시도 간격이 지나도 같은
    프로세스 안에서 다시 실행하지 않는다.

    실증된 시나리오: mark_command_attempting → print_image 성공 →
    finalize_command의 DB 쓰기가 예외(디스크 꽉 참 등)로 실패 → 예외가
    _scheduler_loop에 삼켜짐 → status가 'attempting'인 채로 남음 → 60초 뒤 같은
    명령이 재시도 간격만 보고 다시 실행되어 두 번째 장이 나간다. 이 테스트는 그
    '남겨진 attempting 행'을 직접 만들어(재현이 아니라 결과 상태를 흉내내) 가드가
    실제로 재실행을 막는지 확인한다."""

    def test_attempting_command_is_skipped_forever_in_this_process(self, tmp_path):
        config = make_agent_config(tmp_path, retry_interval_sec=60)
        agent = main_module.Agent(config)

        save_ready_render_for_agent(agent, "r1", "fmt1", "deadbeef")
        agent.storage.save_command("c1", "fmt1", "r1", "deadbeef", True, datetime.now(KST).isoformat())
        agent.storage.begin_command_attempt("c1", result_id="r-1")
        agent.storage.mark_command_attempting("c1")

        # 재시도 간격(60초)을 훌쩍 지난 것으로 backdate — 가드가 없으면 이 시각
        # 차이만으로 다시 실행 대상이 된다.
        import sqlite3

        backdated = (datetime.now(KST) - timedelta(seconds=999)).isoformat()
        with sqlite3.connect(agent.storage.db_path) as conn:
            conn.execute("UPDATE commands SET last_attempt_at = ? WHERE command_id = ?", (backdated, "c1"))
            conn.commit()

        # print_image가 호출되면 즉시 테스트가 실패한다 — 가드가 없으면(mutation)
        # execute_command가 다시 print_image까지 도달해 이 예외가 새어 나온다.
        agent.executor.printer = ExplodingPrinter(connected=True)

        agent._run_command_tick(datetime.now(KST))

        stored = agent.storage.get_command("c1")
        assert stored["status"] == "attempting"  # 여전히 멈춰 있다 — 재실행되지 않았다
