"""kv.last_tick_at 되돌아보기 테스트(과제 2, docs/pi/agent.md:135-137).

두 부분:
1. `calculate_occurrences`의 lookback 범위 계산 — 순수 함수, 합성 `now`로
   날짜 경계·24시간 상한을 정확히 검증한다(실제 시각에 의존하지 않는다).
2. `Agent._do_scheduler_tick` 통합 — 자정을 넘긴 되돌아보기가 실제로 missed를
   정확히 한 번 남기는지, 이미 final인 회차를 건드리지 않는지, last_tick_at
   쓰기 간격이 지켜지는지, attempting 회차를 재실행하지 않는지.

`now_kst`는 `agent.__main__` 모듈 네임스페이스로 들어와 있으므로
`monkeypatch.setattr(main_module, "now_kst", ...)`로 주입해 날짜 경계 테스트를
실시간에 의존하지 않게 한다(요청된 "시간 의존 테스트는 now를 주입" 지침).
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

from agent import __main__ as main_module
from agent.clock import now_iso
from agent.config import AgentConfig
from agent.scheduler import calculate_occurrences
from printer.fake import FakePrinter

KST = timezone(timedelta(hours=9))


# ----------------------------------------------------------------------
# 1. calculate_occurrences — 순수 함수, 합성 시각
# ----------------------------------------------------------------------


def _recurring(sched_id: str, fmt_id: str, days_of_week: list[str], time_str: str) -> dict:
    return {
        "id": sched_id,
        "formatId": fmt_id,
        "type": "recurring",
        "daysOfWeek": days_of_week,
        "time": time_str,
        "enabled": True,
    }


def _once(sched_id: str, fmt_id: str, date_str: str, time_str: str) -> dict:
    return {
        "id": sched_id,
        "formatId": fmt_id,
        "type": "once",
        "date": date_str,
        "time": time_str,
        "enabled": True,
    }


class TestCalculateOccurrencesNoLastTick:
    def test_none_last_tick_keeps_today_only_behavior(self):
        """last_tick_at이 없으면(최초 기동) 기존 동작(오늘만) 그대로."""
        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)  # Friday
        snapshot = {"schedules": [_recurring("s1", "f1", ["THU", "FRI"], "07:00")]}
        occs = calculate_occurrences(snapshot, now, 30, storage_kv_last_tick=None)
        assert [o.occurrence_key for o in occs] == ["s1@2026-09-18T07:00"]

    def test_unparseable_last_tick_falls_back_to_today(self):
        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        snapshot = {"schedules": [_recurring("s1", "f1", ["FRI"], "07:00")]}
        occs = calculate_occurrences(snapshot, now, 30, storage_kv_last_tick="not-a-timestamp")
        assert [o.occurrence_key for o in occs] == ["s1@2026-09-18T07:00"]


class TestCalculateOccurrencesLookback:
    def test_crosses_midnight_recurring(self):
        """last_tick_at이 어제였으면 어제치 recurring occurrence도 만든다.

        시각은 (now − last_tick_at) < 24시간이 되도록 고른다 — 24시간 상한은
        `test_lookback_max_24h_cap`에서 따로 검증한다(자정을 넘는 것과 24시간을
        넘는 것은 서로 다른 조건이라 한 테스트에서 섞으면 어느 쪽이 실패 원인인지
        불분명해진다)."""
        now = datetime(2026, 9, 18, 2, 0, 0, tzinfo=KST)  # Friday 02:00
        last_tick = datetime(2026, 9, 17, 20, 0, 0, tzinfo=KST)  # Thursday 20:00 (6시간 전)
        snapshot = {"schedules": [_recurring("s1", "f1", ["THU"], "23:30")]}

        occs = calculate_occurrences(snapshot, now, 30, storage_kv_last_tick=last_tick.isoformat())
        keys = {o.occurrence_key for o in occs}
        assert keys == {"s1@2026-09-17T23:30"}

    def test_crosses_midnight_once(self):
        """once 예약도 되돌아보기 범위 안이면 어제 날짜 그대로 계산된다."""
        now = datetime(2026, 9, 18, 2, 0, 0, tzinfo=KST)
        last_tick = datetime(2026, 9, 17, 23, 0, 0, tzinfo=KST)  # 3시간 전, 어제
        snapshot = {"schedules": [_once("s2", "f1", "2026-09-17", "23:30")]}

        occs = calculate_occurrences(snapshot, now, 30, storage_kv_last_tick=last_tick.isoformat())
        assert [o.occurrence_key for o in occs] == ["s2@2026-09-17T23:30"]

    def test_excludes_time_before_last_tick_on_boundary_day(self):
        """last_tick_at 당일에도, last_tick_at 이전 시각의 occurrence는 만들지
        않는다(이미 그 전 틱에서 처리됐어야 할 몫 — 여기서 새로 만들면 중복
        판단의 여지를 늘린다)."""
        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        last_tick = datetime(2026, 9, 18, 6, 0, 0, tzinfo=KST)  # 오늘 06:00
        snapshot = {"schedules": [_recurring("s1", "f1", ["FRI"], "05:00")]}  # last_tick보다 이전

        occs = calculate_occurrences(snapshot, now, 30, storage_kv_last_tick=last_tick.isoformat())
        assert occs == []

    def test_lookback_max_24h_cap(self):
        """last_tick_at이 3일 전이어도 24시간 넘은 회차는 만들지 않는다."""
        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        last_tick = now - timedelta(days=3)
        snapshot = {
            "schedules": [
                _recurring("old", "f1", ["TUE", "WED", "THU", "FRI", "SAT", "SUN", "MON"], "07:00"),
            ]
        }
        occs = calculate_occurrences(snapshot, now, 30, storage_kv_last_tick=last_tick.isoformat())
        keys = {o.occurrence_key for o in occs}
        # now - 24h = 2026-09-17T08:00 이후만 남는다. 매일 07:00은 09-17 07:00이
        # 그 경계보다 먼저라 빠지고, 09-18 07:00만 남는다.
        assert keys == {"old@2026-09-18T07:00"}

    def test_lookback_floor_excludes_older_than_24h_even_when_not_capped_by_last_tick(self):
        """last_tick_at 자체가 24시간을 살짝 넘겨도(예: 25시간 전), 24시간
        상한이 그보다 더 최근으로 시작점을 당긴다."""
        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        last_tick = now - timedelta(hours=25)  # 2026-09-17 07:00
        snapshot = {"schedules": [_recurring("s1", "f1", ["THU"], "07:30")]}  # 2026-09-17 07:30

        occs = calculate_occurrences(snapshot, now, 30, storage_kv_last_tick=last_tick.isoformat())
        # floor = now - 24h = 2026-09-17T08:00. 07:30은 그 이전이라 제외된다.
        assert occs == []

    def test_disabled_schedule_excluded_from_lookback_too(self):
        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        last_tick = datetime(2026, 9, 17, 6, 0, 0, tzinfo=KST)
        snapshot = {
            "schedules": [
                {
                    "id": "s1",
                    "formatId": "f1",
                    "type": "recurring",
                    "daysOfWeek": ["THU", "FRI"],
                    "time": "07:00",
                    "enabled": False,
                }
            ]
        }
        occs = calculate_occurrences(snapshot, now, 30, storage_kv_last_tick=last_tick.isoformat())
        assert occs == []


# ----------------------------------------------------------------------
# 2. Agent 통합 — 자정을 넘긴 되돌아보기, final 보존, 쓰기 간격, attempting 보호
# ----------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _isolate_fake_printer_data_dir(tmp_path, monkeypatch):
    monkeypatch.setenv("HARU_DATA_DIR", str(tmp_path / "fake-printer-data"))


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


def make_agent(tmp_path, **overrides) -> main_module.Agent:
    config = make_agent_config(tmp_path, **overrides)
    # 시계는 항상 동기화된 것으로 취급 — 이 파일은 과제 2(되돌아보기)만 다룬다.
    return main_module.Agent(config, clock_synced_check=lambda: True)


class TestRebootAcrossMidnight:
    def test_4_missed_across_midnight_recorded_exactly_once(self, tmp_path, monkeypatch):
        """에이전트가 자정을 넘겨 꺼져 있었다(last_tick_at = 어제 저녁, now = 오늘
        새벽) → 어제 저녁 회차가 missed로 정확히 한 번.

        (now − last_tick_at)을 24시간 밑으로 잡는다 — 24시간 상한 자체는
        `TestCalculateOccurrencesLookback.test_lookback_max_24h_cap`에서 따로
        검증한다."""
        agent = make_agent(tmp_path, grace_minutes=30)

        now = datetime(2026, 9, 18, 2, 0, 0, tzinfo=KST)
        yesterday_scheduled = datetime(2026, 9, 17, 23, 0, 0, tzinfo=KST)
        last_tick = datetime(2026, 9, 17, 20, 0, 0, tzinfo=KST)
        agent.storage.set_kv("last_tick_at", last_tick.isoformat())

        weekday_map = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
        agent.snapshot = {
            "schedules": [
                {
                    "id": "s1",
                    "formatId": "fmt1",
                    "type": "recurring",
                    "daysOfWeek": [weekday_map[yesterday_scheduled.weekday()]],
                    "time": "23:00",
                    "enabled": True,
                }
            ],
            "renders": [],
        }

        monkeypatch.setattr(main_module, "now_kst", lambda: now)
        agent._do_scheduler_tick()

        key = "s1@2026-09-17T23:00"
        row = agent.storage.get_executed_occurrence(key)
        assert row is not None
        assert row["status"] == "missed"
        assert row["final"] == 1

        queued = agent.storage.get_queued_results()
        missed = [q for q in queued if q["payload_json"]["occurrenceKey"] == key]
        assert len(missed) == 1
        assert missed[0]["payload_json"]["status"] == "missed"

        # 한 번 더 틱을 돌려도 중복되지 않는다.
        agent._do_scheduler_tick()
        queued2 = agent.storage.get_queued_results()
        assert len(queued2) == len(queued)

    def test_5_already_final_occurrence_untouched(self, tmp_path, monkeypatch):
        """이미 final인 회차는 되돌아보기가 건드리지 않는다."""
        agent = make_agent(tmp_path, grace_minutes=30)

        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        yesterday_scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        last_tick = datetime(2026, 9, 17, 6, 0, 0, tzinfo=KST)
        agent.storage.set_kv("last_tick_at", last_tick.isoformat())

        key = "s1@2026-09-17T07:00"
        # 이미 이전 틱에서 종결된 것처럼 미리 심어 둔다(printed로 이미 끝남).
        agent.storage.begin_occurrence_attempt(
            key, format_id="fmt1", render_id="r1", scheduled_at=yesterday_scheduled.isoformat(), result_id="r-old"
        )
        agent.storage.finish_occurrence_attempt(key, status="printed", detail="", final=True)
        agent.storage.save_result("r-old", {"resultId": "r-old", "status": "printed"})
        agent.storage.mark_result_uploaded("r-old")

        weekday_map = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
        agent.snapshot = {
            "schedules": [
                {
                    "id": "s1",
                    "formatId": "fmt1",
                    "type": "recurring",
                    "daysOfWeek": [weekday_map[yesterday_scheduled.weekday()]],
                    "time": "07:00",
                    "enabled": True,
                }
            ],
            "renders": [],
        }

        monkeypatch.setattr(main_module, "now_kst", lambda: now)
        agent._do_scheduler_tick()

        row = agent.storage.get_executed_occurrence(key)
        assert row["status"] == "printed"  # missed로 덮어써지지 않았다
        assert row["final"] == 1

        # 새 결과가 추가로 쌓이지 않았다(이미 업로드된 r-old 하나뿐).
        queued = agent.storage.get_queued_results()  # 미업로드만 조회
        assert queued == []

    def test_8_attempting_not_reexecuted_by_lookback(self, tmp_path, monkeypatch):
        """되돌아보기가 attempting 회차를 재실행하지 않는다(전송 도중 죽은 것과
        동일 취급 — 재시작 시 기동 정리만 종결시킨다)."""

        class ExplodingPrinter(FakePrinter):
            def print_image(self, png_bytes):
                raise AssertionError("attempting 회차가 되돌아보기에서 재실행되면 안 됨")

        agent = make_agent(tmp_path, grace_minutes=30)
        agent.printer = ExplodingPrinter(connected=True)
        agent.executor.printer = agent.printer

        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        yesterday_scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        last_tick = datetime(2026, 9, 17, 6, 0, 0, tzinfo=KST)
        agent.storage.set_kv("last_tick_at", last_tick.isoformat())

        key = "s1@2026-09-17T07:00"
        # 전송 도중 죽은 것처럼 attempting·final=0으로 남겨 둔다(cleanup 대상이지만
        # 여기서는 Agent.__init__의 기동 정리가 지난 뒤 시점을 흉내낸다 — attempting
        # 행이 어떤 이유로든 남아 있는 상태).
        agent.storage.begin_occurrence_attempt(
            key, format_id="fmt1", render_id="r1", scheduled_at=yesterday_scheduled.isoformat(), result_id="r-1"
        )
        agent.storage.mark_occurrence_attempting(key)

        weekday_map = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
        agent.snapshot = {
            "schedules": [
                {
                    "id": "s1",
                    "formatId": "fmt1",
                    "type": "recurring",
                    "daysOfWeek": [weekday_map[yesterday_scheduled.weekday()]],
                    "time": "07:00",
                    "enabled": True,
                }
            ],
            "renders": [],
        }

        monkeypatch.setattr(main_module, "now_kst", lambda: now)
        agent._do_scheduler_tick()

        # 주의: 이 테스트는 `renders: []`라 인쇄 자체가 불가능하고, 실행기가
        # print_image 예외를 잡아 failed로 바꾸므로 ExplodingPrinter로는 "재실행되지
        # 않았음"을 증명하지 못한다. 그 불변식은
        # TestNeverPrintsWithRenderAvailable.test_attempting_row_never_reprinted가
        # 렌더를 실제로 둔 채 호출 횟수로 고정한다. 여기서는 종결 status만 본다.
        row = agent.storage.get_executed_occurrence(key)
        # attempting은 finalize_expired_occurrences가 "이미 시도된 적 있음" 경로로
        # 마지막 사유를 그대로 종결한다(정책: 바이트가 나갔을 수 있는 시도는 절대
        # 재실행하지 않고, 유예가 지나면 그 상태 그대로 종결한다). 서버 ENUM에
        # 없는 내부 상태(attempting)는 failed로 강제 매핑된다.
        assert row["final"] == 1
        assert row["status"] == "failed"


class TestOrphanedScheduleFinalization:
    def test_orphaned_attempt_still_finalizes_when_schedule_removed(self, tmp_path, monkeypatch):
        """스냅샷에서 사라진(삭제·비활성화) 예약이라도, 이미 시도 이력(row)이 있는
        occurrence는 get_unfinished_occurrences 경로로 반드시 종결된다(고아 행이
        영원히 final=0으로 남지 않는다)."""
        agent = make_agent(tmp_path, grace_minutes=30, paper_policy="manual_flag")

        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 18, 7, 0, 0, tzinfo=KST)
        key = "s1@2026-09-18T07:00"

        # 한 번 시도됐지만(용지 없음 등) 끝나지 않은 상태.
        agent.storage.begin_occurrence_attempt(
            key, format_id="fmt1", render_id="", scheduled_at=scheduled.isoformat(), result_id="r-1"
        )
        agent.storage.finish_occurrence_attempt(key, status="skipped_no_paper", detail="", final=False)

        # 스냅샷에는 이 예약이 아예 없다(삭제됨).
        agent.snapshot = {"schedules": [], "renders": []}

        monkeypatch.setattr(main_module, "now_kst", lambda: now)
        agent._do_scheduler_tick()

        row = agent.storage.get_executed_occurrence(key)
        assert row["final"] == 1
        assert row["status"] == "skipped_no_paper"  # 마지막 사유 보존, missed로 뭉개지 않음

    def test_never_attempted_orphan_not_fabricated(self, tmp_path, monkeypatch):
        """한 번도 시도된 적 없는 occurrence는, 그 예약이 스냅샷에 없으면 아예
        만들어지지 않는다(모르는 예약을 missed로 지어내지 않는다)."""
        agent = make_agent(tmp_path, grace_minutes=30)
        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        agent.storage.set_kv("last_tick_at", (now - timedelta(hours=2)).isoformat())
        agent.snapshot = {"schedules": [], "renders": []}  # 삭제된 예약은 후보에도 없다

        monkeypatch.setattr(main_module, "now_kst", lambda: now)
        agent._do_scheduler_tick()

        queued = agent.storage.get_queued_results()
        assert queued == []


class TestLastTickWriteInterval:
    def test_7_write_interval_is_respected(self, tmp_path, monkeypatch):
        """last_tick_at 쓰기 간격이 지켜진다(매 틱 쓰지 않음)."""
        agent = make_agent(tmp_path, grace_minutes=30)
        agent.snapshot = {"schedules": [], "renders": []}

        base = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        clock = {"now": base}
        monkeypatch.setattr(main_module, "now_kst", lambda: clock["now"])

        agent._do_scheduler_tick()
        first_value = agent.storage.get_kv("last_tick_at")
        assert first_value is not None

        # 30초 뒤(간격 60초 미만) — 갱신되지 않아야 한다.
        clock["now"] = base + timedelta(seconds=30)
        agent._do_scheduler_tick()
        assert agent.storage.get_kv("last_tick_at") == first_value

        # 61초 뒤(간격 초과) — 갱신돼야 한다.
        clock["now"] = base + timedelta(seconds=61)
        agent._do_scheduler_tick()
        second_value = agent.storage.get_kv("last_tick_at")
        assert second_value != first_value
        assert second_value == clock["now"].isoformat()

    def test_write_interval_survives_process_restart_via_memory_cache(self, tmp_path, monkeypatch):
        """새 Agent 인스턴스(재시작을 흉내)는 메모리 캐시가 없으므로 첫 틱에는
        무조건 쓴다 — kv에 이미 최근 값이 있어도 메모리 캐시(_last_tick_write_at)가
        None이면 다시 쓴다(간격 판정의 기준은 kv가 아니라 이 프로세스의 메모리)."""
        agent = make_agent(tmp_path, grace_minutes=30)
        agent.snapshot = {"schedules": [], "renders": []}
        now = datetime(2026, 9, 18, 8, 0, 0, tzinfo=KST)
        agent.storage.set_kv("last_tick_at", now.isoformat())

        monkeypatch.setattr(main_module, "now_kst", lambda: now + timedelta(seconds=1))
        agent._do_scheduler_tick()
        assert agent.storage.get_kv("last_tick_at") == (now + timedelta(seconds=1)).isoformat()


class CountingPrinter(FakePrinter):
    """print_image 호출을 **세기만** 하고 예외를 던지지 않는다.

    예외를 던지는 프린터로 "호출되지 않았음"을 증명하면 무효다 — 실행기가
    print_image 예외를 잡아 `failed`로 바꾸므로 호출돼도 테스트가 통과한다.
    호출 횟수를 직접 단언해야 한다.
    """

    def __init__(self):
        super().__init__(connected=True)
        self.print_calls = 0

    def print_image(self, png_bytes):
        self.print_calls += 1
        return super().print_image(png_bytes)


def _seed_render(agent, target_date: str, render_id: str, format_id: str) -> dict:
    """인쇄가 **실제로 가능한** 상태를 만든다 — 렌더 파일과 저장소 행을 모두 둔다.

    렌더가 없으면 가드를 지워도 인쇄할 수 없어서, "인쇄하지 않았다"는
    단언이 가드가 아니라 렌더 부재 때문에 성립한다(무효 테스트).
    """
    png_path = agent.data_dir / "renders" / f"{render_id}.png"
    png_path.parent.mkdir(parents=True, exist_ok=True)
    png_path.write_bytes(b"fake-png-bytes")
    agent.storage.save_render(render_id, format_id, target_date, "deadbeef", str(png_path), verified=True)
    return {
        "renderId": render_id,
        "formatId": format_id,
        "targetDate": target_date,
        "sha256": "deadbeef",
        "renderedAt": f"{target_date}T00:00:00+09:00",
    }


class TestNeverPrintsWithRenderAvailable:
    """렌더가 준비된 상태에서도 유예를 넘긴 회차·attempting 회차는 인쇄하지 않는다.

    같은 불변식을 다루는 test_4·test_8은 `renders: []`라 인쇄 자체가 불가능해서
    가드를 지워도 통과했다(2026-09-17 검증). 여기서는 렌더를 실제로 두고
    print_image 호출 횟수를 직접 센다.
    """

    def _agent(self, tmp_path, monkeypatch, now):
        # paper_policy=manual_flag + loaded=true: 용지 게이트는 통과시켜서,
        # "인쇄하지 않았다"가 오직 유예/attempting 가드 때문이도록 만든다.
        agent = make_agent(tmp_path, grace_minutes=30, paper_policy="manual_flag")
        agent.storage.set_kv("paperState", json.dumps({"loaded": True}))
        printer = CountingPrinter()
        agent.printer = printer
        agent.executor.printer = printer
        monkeypatch.setattr(main_module, "now_kst", lambda: now)
        return agent, printer

    def _snapshot(self, scheduled: datetime, render: dict) -> dict:
        weekday_map = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
        return {
            "schedules": [{
                "id": "s1", "formatId": "fmt1", "type": "recurring",
                "daysOfWeek": [weekday_map[scheduled.weekday()]],
                "time": scheduled.strftime("%H:%M"), "enabled": True,
            }],
            "renders": [render],
        }

    def test_sanity_within_grace_does_print(self, tmp_path, monkeypatch):
        """대조군: 같은 구성에서 유예 안이면 실제로 인쇄된다.

        이게 통과해야 아래 두 테스트의 "0회"가 설정 실수가 아니라 가드 때문임이 증명된다."""
        scheduled = datetime(2026, 9, 18, 7, 0, 0, tzinfo=KST)
        now = scheduled + timedelta(minutes=5)
        agent, printer = self._agent(tmp_path, monkeypatch, now)
        agent.snapshot = self._snapshot(scheduled, _seed_render(agent, "2026-09-18", "r1", "fmt1"))

        agent._do_scheduler_tick()

        assert printer.print_calls == 1

    def test_past_grace_across_midnight_never_prints(self, tmp_path, monkeypatch):
        scheduled = datetime(2026, 9, 17, 7, 0, 0, tzinfo=KST)
        now = datetime(2026, 9, 18, 2, 0, 0, tzinfo=KST)
        agent, printer = self._agent(tmp_path, monkeypatch, now)
        agent.storage.set_kv("last_tick_at", datetime(2026, 9, 17, 6, 0, 0, tzinfo=KST).isoformat())
        agent.snapshot = self._snapshot(scheduled, _seed_render(agent, "2026-09-17", "r1", "fmt1"))

        agent._do_scheduler_tick()

        assert printer.print_calls == 0
        row = agent.storage.get_executed_occurrence("s1@2026-09-17T07:00")
        assert row["status"] == "missed" and row["final"]

    def test_attempting_row_never_reprinted(self, tmp_path, monkeypatch):
        scheduled = datetime(2026, 9, 18, 7, 0, 0, tzinfo=KST)
        now = scheduled + timedelta(minutes=5)  # 유예 안 — 유예 가드가 아니라 attempting 가드만 막아야 한다
        agent, printer = self._agent(tmp_path, monkeypatch, now)
        agent.snapshot = self._snapshot(scheduled, _seed_render(agent, "2026-09-18", "r1", "fmt1"))
        key = "s1@2026-09-18T07:00"
        agent.storage.begin_occurrence_attempt(
            key, format_id="fmt1", render_id="r1", scheduled_at=scheduled.isoformat(), result_id="r-1"
        )
        agent.storage.mark_occurrence_attempting(key)

        agent._do_scheduler_tick()

        assert printer.print_calls == 0
