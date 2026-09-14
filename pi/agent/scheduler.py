"""스케줄러 (occurrence 계산). docs/pi/agent.md 7절."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

logger = logging.getLogger(__name__)

# 한국 시간대
KST = timezone(timedelta(hours=9))


@dataclass(frozen=True)
class Occurrence:
    """실행할 occurrence."""

    occurrence_key: str  # {scheduleId}@{YYYY-MM-DD}T{HH:mm}
    schedule_id: str
    format_id: str
    scheduled_at: datetime  # KST
    is_command: bool = False
    command_id: str = ""


def parse_occurrence_key(key: str) -> tuple[str, str, str]:
    """occurrence_key를 파싱: schedule_id, date(YYYY-MM-DD), time(HH:mm)."""
    # format: {scheduleId}@{YYYY-MM-DD}T{HH:mm}
    parts = key.split("@")
    if len(parts) != 2:
        raise ValueError(f"Invalid occurrence key: {key}")
    schedule_id = parts[0]
    datetime_part = parts[1]  # YYYY-MM-DDTHH:mm
    date_parts = datetime_part.split("T")
    if len(date_parts) != 2:
        raise ValueError(f"Invalid occurrence key: {key}")
    date_str = date_parts[0]
    time_str = date_parts[1]
    return schedule_id, date_str, time_str


def make_occurrence_key(schedule_id: str, dt: datetime) -> str:
    """datetime를 occurrence_key로 변환. KST를 YYYY-MM-DD, HH:mm으로."""
    kst_dt = dt.astimezone(KST)
    return f"{schedule_id}@{kst_dt.strftime('%Y-%m-%d')}T{kst_dt.strftime('%H:%M')}"


def calculate_occurrences(
    snapshot: dict, now: datetime, grace_minutes: int, storage_kv_last_tick: str = None
) -> list[Occurrence]:
    """
    스냅샷의 예약 목록에서 실행할 occurrence를 계산한다.

    Args:
        snapshot: {schedules: [...], renders: [...]}
        now: 현재 시각 (KST로 간주)
        grace_minutes: 유예 시간(분)
        storage_kv_last_tick: 마지막 scheduler tick 시각 (ISO-8601). 없으면 되돌아보기 안 함.

    Returns:
        실행할 occurrence 목록
    """
    now_kst = now.astimezone(KST) if now.tzinfo else now.replace(tzinfo=KST)
    occurrences = []

    schedules = snapshot.get("schedules", [])
    for sched in schedules:
        if not sched.get("enabled", False):
            continue

        sched_id = sched.get("id", "")
        fmt_id = sched.get("formatId", "")
        sched_type = sched.get("type", "")
        time_str = sched.get("time", "")  # HH:mm

        if sched_type == "recurring":
            # 요일이 오늘 포함되는가
            days_of_week = sched.get("daysOfWeek", [])
            today_weekday = now_kst.strftime("%a").upper()
            weekday_map = {"MON": "MON", "TUE": "TUE", "WED": "WED", "THU": "THU", "FRI": "FRI", "SAT": "SAT", "SUN": "SUN"}
            # Python weekday: Monday=0, Sunday=6
            # 스냅샷은 MON~SUN 문자열 사용
            py_weekdays = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]
            today_weekday_str = py_weekdays[now_kst.weekday()]

            if today_weekday_str in days_of_week:
                # 오늘 예약 시각에 실행
                try:
                    h, m = time_str.split(":")
                    scheduled_dt = now_kst.replace(hour=int(h), minute=int(m), second=0, microsecond=0)
                except ValueError:
                    logger.error(f"Invalid time format: {time_str}")
                    continue

                occ_key = make_occurrence_key(sched_id, scheduled_dt)
                occurrences.append(Occurrence(occ_key, sched_id, fmt_id, scheduled_dt))

        elif sched_type == "once":
            # 특정 날짜
            date_str = sched.get("date", "")  # YYYY-MM-DD
            if date_str == now_kst.strftime("%Y-%m-%d"):
                try:
                    h, m = time_str.split(":")
                    scheduled_dt = now_kst.replace(hour=int(h), minute=int(m), second=0, microsecond=0)
                except ValueError:
                    logger.error(f"Invalid time format: {time_str}")
                    continue

                occ_key = make_occurrence_key(sched_id, scheduled_dt)
                occurrences.append(Occurrence(occ_key, sched_id, fmt_id, scheduled_dt))

    return occurrences


def filter_executable_occurrences(
    candidates: list[Occurrence],
    now: datetime,
    grace_minutes: int,
    existing_occurrences: dict,  # {occurrence_key: {final: bool, ...}}
) -> list[Occurrence]:
    """
    후보 occurrence 중에서 실행 가능한 것만 필터링한다.

    Args:
        candidates: calculate_occurrences()의 결과
        now: 현재 시각 (KST)
        grace_minutes: 유예 시간(분)
        existing_occurrences: storage에서 조회한 executed_occurrences (key -> {final, ...})

    Returns:
        실행 가능한 occurrence 목록
    """
    now_kst = now.astimezone(KST) if now.tzinfo else now.replace(tzinfo=KST)
    grace_delta = timedelta(minutes=grace_minutes)

    executable = []
    for occ in candidates:
        # 이미 실행했는가
        existing = existing_occurrences.get(occ.occurrence_key, {})
        if existing.get("final", False):
            # 최종이면 건너뜀
            continue

        # 예약 시각 이후인가
        if occ.scheduled_at > now_kst:
            # 아직 오지 않음
            continue

        # 유예 범위 안인가
        if now_kst > occ.scheduled_at + grace_delta:
            # 유예가 지남 - 실행하지 않음 (별도로 'missed' 기록)
            continue

        executable.append(occ)

    return executable
