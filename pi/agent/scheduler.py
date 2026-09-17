"""스케줄러 (occurrence 계산). docs/pi/agent.md 7절."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timedelta

from .clock import KST, parse_local_iso

logger = logging.getLogger(__name__)


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
    existing_occurrences: dict,  # {occurrence_key: {final: bool, status, last_attempt_at, ...}}
    retry_interval_sec: int,
) -> list[Occurrence]:
    """
    후보 occurrence 중에서 실행 가능한 것만 필터링한다.

    ★인자 추가(.temp/05 설계 4.4)★: retry_interval_sec은 기본값을 두지 않는다 —
    기본값을 주면 "읽히기만 하고 안 쓰이는" 상황이 조용히 재현될 수 있다
    (2026-09-17 발견: HARU_RETRY_INTERVAL_SEC가 config.py에만 있고 사용처가 0건이었다).

    Args:
        candidates: calculate_occurrences()의 결과
        now: 현재 시각 (KST)
        grace_minutes: 유예 시간(분)
        existing_occurrences: storage에서 조회한 executed_occurrences (key -> {final, ...})
        retry_interval_sec: 마지막 시도 후 이만큼(초) 지나야 다시 후보가 된다
            (docs/pi/policy.md 3절 "60초 간격으로 재시도")

    Returns:
        실행 가능한 occurrence 목록
    """
    now_kst = now.astimezone(KST) if now.tzinfo else now.replace(tzinfo=KST)
    grace_delta = timedelta(minutes=grace_minutes)
    retry_delta = timedelta(seconds=retry_interval_sec)

    executable = []
    for occ in candidates:
        # 이미 최종 종결됐는가 (printed/dry_run/missed/전송 중 실패 등 — final=1)
        existing = existing_occurrences.get(occ.occurrence_key, {})
        if existing.get("final", False):
            continue

        # 예약 시각 이후인가
        if occ.scheduled_at > now_kst:
            # 아직 오지 않음
            continue

        # 유예 범위 안인가
        if now_kst > occ.scheduled_at + grace_delta:
            # 유예가 지남 - 실행하지 않음 (별도로 'missed' 기록)
            continue

        # 'attempting'은 print_image() 호출 직전~반환 전이라 바이트가 나갔을 수
        # 있다(executor.mark_occurrence_attempting 주석과 같은 불변식) — 이
        # 프로세스 안에서는 절대 다시 후보로 올리지 않는다. 재시작 후에만
        # cleanup_stale_attempts()가 종결시킨다(중복 인쇄보다 재정리가 낫다, R1).
        if existing.get("status") == "attempting":
            continue

        # ④[중요, 2026-09-17 Opus 검토로 발견] 'checking'(바이트가 나가기 전
        # 단계 — 렌더 선택·용지 판단·프린터 상태 조회 중)은 여기서 무조건 걸러지지
        # 않는다. 이전에는 'attempting'과 함께 영구히 건너뛰어, begin_occurrence_attempt
        # 직후 프로세스가 죽거나 _check_paper_policy가 예외를 내는 경우(status_query
        # 분기) 그 회차가 유예 만료까지 다시는 시도되지 않았다. 'checking'은 바이트가
        # 나가지 않았으므로 재시도해도 중복 인쇄가 없다 — 아래 재시도 간격만 지키면
        # 된다("같은 틱 재진입 방어"는 이 함수가 틱마다 한 번만 호출되고 existing_occurrences가
        # 그 틱 시작 시점 스냅샷이라 실제로는 발생하지 않는다 — 순수 필터 함수라 재진입
        # 여지 자체가 없다).

        # 재시도 간격이 지났는가. 시도 기록이 없거나 파싱 실패면 "시도한 적 없다"로
        # 보고 즉시 허용한다(clock.parse_local_iso가 옛 naive 행에도 KST를 붙인다).
        last_attempt_at = parse_local_iso(existing.get("last_attempt_at"))
        if last_attempt_at is not None and (now_kst - last_attempt_at) < retry_delta:
            continue

        executable.append(occ)

    return executable
