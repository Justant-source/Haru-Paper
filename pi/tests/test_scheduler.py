"""스케줄러 테스트."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from agent.scheduler import calculate_occurrences, filter_executable_occurrences, make_occurrence_key, parse_occurrence_key

KST = timezone(timedelta(hours=9))


class TestOccurrenceKey:
    """occurrence_key 파싱/생성 테스트."""

    def test_make_occurrence_key(self):
        """datetime을 occurrence_key로."""
        dt = datetime(2026, 9, 14, 7, 0, 0, tzinfo=KST)
        key = make_occurrence_key("s1", dt)
        assert key == "s1@2026-09-14T07:00"

    def test_parse_occurrence_key(self):
        """occurrence_key를 파싱."""
        key = "s1@2026-09-14T07:00"
        sched_id, date_str, time_str = parse_occurrence_key(key)
        assert sched_id == "s1"
        assert date_str == "2026-09-14"
        assert time_str == "07:00"

    def test_round_trip(self):
        """왕복 변환."""
        original_dt = datetime(2026, 9, 14, 7, 0, 0, tzinfo=KST)
        key = make_occurrence_key("s1", original_dt)
        sched_id, date_str, time_str = parse_occurrence_key(key)
        reconstructed = datetime.strptime(f"{date_str}T{time_str}", "%Y-%m-%dT%H:%M").replace(tzinfo=KST)
        assert original_dt == reconstructed


class TestCalculateOccurrences:
    """occurrence 계산 테스트."""

    def test_recurring_schedule_on_matching_weekday(self):
        """요일이 맞는 recurring 예약."""
        # Monday 2026-09-14
        now = datetime(2026, 9, 14, 6, 30, 0, tzinfo=KST)

        snapshot = {
            "schedules": [
                {
                    "id": "s1",
                    "formatId": "f1",
                    "type": "recurring",
                    "daysOfWeek": ["MON", "WED", "FRI"],
                    "time": "07:00",
                    "enabled": True,
                }
            ],
            "renders": [],
        }

        occurrences = calculate_occurrences(snapshot, now, 30)
        assert len(occurrences) == 1
        assert occurrences[0].occurrence_key == "s1@2026-09-14T07:00"
        assert occurrences[0].schedule_id == "s1"
        assert occurrences[0].format_id == "f1"

    def test_recurring_schedule_on_non_matching_weekday(self):
        """요일이 안 맞는 recurring 예약."""
        # Monday 2026-09-14
        now = datetime(2026, 9, 14, 6, 30, 0, tzinfo=KST)

        snapshot = {
            "schedules": [
                {
                    "id": "s1",
                    "formatId": "f1",
                    "type": "recurring",
                    "daysOfWeek": ["TUE", "THU"],  # Monday 제외
                    "time": "07:00",
                    "enabled": True,
                }
            ],
            "renders": [],
        }

        occurrences = calculate_occurrences(snapshot, now, 30)
        assert len(occurrences) == 0

    def test_once_schedule_on_matching_date(self):
        """날짜가 맞는 once 예약."""
        now = datetime(2026, 9, 14, 6, 30, 0, tzinfo=KST)

        snapshot = {
            "schedules": [
                {
                    "id": "s2",
                    "formatId": "f1",
                    "type": "once",
                    "date": "2026-09-14",
                    "time": "08:30",
                    "enabled": True,
                }
            ],
            "renders": [],
        }

        occurrences = calculate_occurrences(snapshot, now, 30)
        assert len(occurrences) == 1
        assert occurrences[0].occurrence_key == "s2@2026-09-14T08:30"

    def test_once_schedule_on_non_matching_date(self):
        """날짜가 안 맞는 once 예약."""
        now = datetime(2026, 9, 14, 6, 30, 0, tzinfo=KST)

        snapshot = {
            "schedules": [
                {
                    "id": "s2",
                    "formatId": "f1",
                    "type": "once",
                    "date": "2026-09-15",  # 내일
                    "time": "08:30",
                    "enabled": True,
                }
            ],
            "renders": [],
        }

        occurrences = calculate_occurrences(snapshot, now, 30)
        assert len(occurrences) == 0

    def test_disabled_schedule(self):
        """꺼진 예약은 무시."""
        now = datetime(2026, 9, 14, 6, 30, 0, tzinfo=KST)

        snapshot = {
            "schedules": [
                {
                    "id": "s1",
                    "formatId": "f1",
                    "type": "recurring",
                    "daysOfWeek": ["MON"],
                    "time": "07:00",
                    "enabled": False,
                }
            ],
            "renders": [],
        }

        occurrences = calculate_occurrences(snapshot, now, 30)
        assert len(occurrences) == 0


class TestFilterExecutableOccurrences:
    """실행 가능 필터링 테스트."""

    def test_before_scheduled_time(self):
        """예약 시각 이전."""
        now = datetime(2026, 9, 14, 6, 30, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 14, 7, 0, 0, tzinfo=KST)

        from agent.scheduler import Occurrence

        candidates = [Occurrence(f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}", "s1", "f1", scheduled)]
        executable = filter_executable_occurrences(candidates, now, 30, {}, 60)
        assert len(executable) == 0

    def test_at_scheduled_time(self):
        """예약 시각."""
        now = datetime(2026, 9, 14, 7, 0, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 14, 7, 0, 0, tzinfo=KST)

        from agent.scheduler import Occurrence

        candidates = [Occurrence(f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}", "s1", "f1", scheduled)]
        executable = filter_executable_occurrences(candidates, now, 30, {}, 60)
        assert len(executable) == 1

    def test_within_grace_period(self):
        """유예 시간 내."""
        now = datetime(2026, 9, 14, 7, 15, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 14, 7, 0, 0, tzinfo=KST)

        from agent.scheduler import Occurrence

        candidates = [Occurrence(f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}", "s1", "f1", scheduled)]
        executable = filter_executable_occurrences(candidates, now, 30, {}, 60)
        assert len(executable) == 1

    def test_after_grace_period(self):
        """유예 시간 이후."""
        now = datetime(2026, 9, 14, 7, 35, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 14, 7, 0, 0, tzinfo=KST)

        from agent.scheduler import Occurrence

        candidates = [Occurrence(f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}", "s1", "f1", scheduled)]
        executable = filter_executable_occurrences(candidates, now, 30, {}, 60)
        assert len(executable) == 0

    def test_already_executed(self):
        """이미 최종 실행됨."""
        now = datetime(2026, 9, 14, 7, 15, 0, tzinfo=KST)
        scheduled = datetime(2026, 9, 14, 7, 0, 0, tzinfo=KST)

        from agent.scheduler import Occurrence

        candidates = [Occurrence(f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}", "s1", "f1", scheduled)]
        existing = {
            f"s1@{scheduled.strftime('%Y-%m-%d')}T{scheduled.strftime('%H:%M')}": {"final": True}
        }
        executable = filter_executable_occurrences(candidates, now, 30, existing, 60)
        assert len(executable) == 0
