"""스케줄러 (occurrence 계산). docs/pi/agent.md 7절."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from datetime import datetime, timedelta

from .clock import KST, parse_local_iso

logger = logging.getLogger(__name__)

# 되돌아보는 범위의 최대 폭(시간). agent.md:135 "되돌아보는 범위는 최대
# 24시간 [기본값]" — Pi가 며칠씩 꺼져 있던 뒤 되살아나도, occurrence 계산이
# 무한정 과거로 자라지 않게 상한을 둔다.
LOOKBACK_MAX_HOURS = 24


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


def _iter_dates(start_date, end_date):
    """start_date부터 end_date까지(양끝 포함) 날짜를 하루씩 돌려준다."""
    d = start_date
    one_day = timedelta(days=1)
    while d <= end_date:
        yield d
        d += one_day


def calculate_occurrences(
    snapshot: dict, now: datetime, grace_minutes: int, storage_kv_last_tick: str = None
) -> list[Occurrence]:
    """
    스냅샷의 예약 목록에서 실행할 occurrence를 계산한다.

    Args:
        snapshot: {schedules: [...], renders: [...]}
        now: 현재 시각 (KST로 간주)
        grace_minutes: 유예 시간(분) — 이 함수는 참고하지 않는다(재시도·유예 판정은
            filter_executable_occurrences 몫). 호출부 호환을 위해 시그니처만 유지한다.
        storage_kv_last_tick: 마지막 scheduler tick 시각(kv.last_tick_at, ISO-8601).

    ★2026-09-17 구현(agent.md:135 "재부팅·중단 후 되돌아보기", 과제 2)★:
    `storage_kv_last_tick`이 있으면(파싱 성공) "오늘"만이 아니라
    `[max(last_tick_at, now - LOOKBACK_MAX_HOURS시간), now]` 범위 전체의 날짜를
    훑어 occurrence를 만든다. 자정을 넘겨 꺼져 있었어도 그 전날 회차가 여기서
    잡힌다. `storage_kv_last_tick`이 없거나 파싱에 실패하면(최초 기동, 옛 값
    형식 오류) **기존 동작을 그대로 유지한다** — 오늘 00:00부터만 본다(이전
    동작과 동일, 과거로 훑지 않음).

    범위 밖(예: 24시간 상한을 넘긴 부분)의 occurrence는 아예 만들지 않는다 —
    "생성되지 않음"과 "생성됐지만 유예를 넘겨 missed"는 다르다. 전자는 앱
    이력에 흔적을 남기지 않고, 후자는 남긴다.

    Returns:
        실행할(또는 되돌아볼) occurrence 목록. 예약 시각이 아직 오지 않은
        오늘치 occurrence도 포함한다(filter_executable_occurrences가 시간
        조건으로 거른다) — 기존 동작과 동일.
    """
    now_kst_val = now.astimezone(KST) if now.tzinfo else now.replace(tzinfo=KST)

    last_tick_dt = parse_local_iso(storage_kv_last_tick)
    if last_tick_dt is not None:
        lookback_floor = now_kst_val - timedelta(hours=LOOKBACK_MAX_HOURS)
        range_start = max(last_tick_dt, lookback_floor)
    else:
        # 최초 기동 등 되돌아볼 기준점이 없으면 기존 동작(오늘 00:00부터)을 유지한다.
        range_start = now_kst_val.replace(hour=0, minute=0, second=0, microsecond=0)

    occurrences = []
    # Python weekday: Monday=0, Sunday=6. 스냅샷은 MON~SUN 문자열을 쓴다.
    py_weekdays = ["MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN"]

    schedules = snapshot.get("schedules", [])
    for sched in schedules:
        if not sched.get("enabled", False):
            continue

        sched_id = sched.get("id", "")
        fmt_id = sched.get("formatId", "")
        sched_type = sched.get("type", "")
        time_str = sched.get("time", "")  # HH:mm

        try:
            h_str, m_str = time_str.split(":")
            hour, minute = int(h_str), int(m_str)
        except ValueError:
            logger.error(f"Invalid time format: {time_str}")
            continue

        for day in _iter_dates(range_start.date(), now_kst_val.date()):
            if sched_type == "recurring":
                days_of_week = sched.get("daysOfWeek", [])
                if py_weekdays[day.weekday()] not in days_of_week:
                    continue
            elif sched_type == "once":
                date_str = sched.get("date", "")  # YYYY-MM-DD
                if date_str != day.isoformat():
                    continue
            else:
                continue

            scheduled_dt = datetime(day.year, day.month, day.day, hour, minute, tzinfo=KST)
            if scheduled_dt < range_start:
                # 되돌아보기 범위(24시간 상한 포함)보다 이른 시각 — 만들지 않는다.
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
