"""보낸 바이트(`.bin`) 30일 순환 삭제.

CLAUDE.md 코드 규칙: "프린터로 보낸 바이트는 Pi 데이터 디렉터리(`HARU_DATA_DIR`,
기본 `/var/lib/haru-paper`)에 저장하고 30일 순환 보관한다."
docs/pi/policy.md 7절: "보낸 바이트(`.bin`)는 `HARU_DATA_DIR/sent/`에 저장하고
30일이 지난 것은 지운다."
docs/pi/agent.md 4절 디렉터리 구조: `sent/<YYYY-MM-DD>/<resultId>.bin`.

실제 저장 코드(`executor.py::_save_sent_bytes`, 2026-09-17 확인)는 문서와 같은
형식을 쓴다 — `data_dir / "sent" / now_kst().strftime("%Y-%m-%d")` 아래에
`<resultId>.bin`. 날짜는 KST 기준 날짜 문자열이다.

## 호출 계약 (이 작업에서는 연결하지 않음 — 상위 세션이 나중에 연결한다)

- 공개 함수는 순수 함수 `prune_sent_bytes(sent_dir, retention_days, today)`
  하나뿐이다. 시계를 직접 읽지 않는다 — `today`는 반드시 호출자가 주입한다.
- Orange Pi Zero 2W에는 RTC가 없다(docs/pi/policy.md 4절). 재부팅 직후·오프라인
  상태에서는 시스템 시각이 과거값(부팅 시각)으로 멈춰 있거나 틀릴 수 있다.
  **호출자는 시각이 동기화됐다고 판단된 뒤에만** `today`를 계산해 이 함수를
  불러야 한다(예: `skipped_clock_unsynced` 판정과 같은 기준). 동기화 전 값을
  넘기면 최악의 경우 아래 "폭주 방지"가 막아 주지만, 그것은 마지막 방어선이지
  정상 경로가 아니다.
- 호출 주기는 **하루 1회**로 충분하다 — 대상 단위가 날짜 디렉터리라 더 자주
  불러도 득이 없다. 폴링 루프(30초)마다 부르지 않는다. 기동 시 1회 +
  자정을 넘겼을 때 1회, 정도가 적당하다.
- 실제 경로(`/var/lib/haru-paper` 등)와 실제 시계를 이 함수에 직접 연결하는
  책임은 호출자에게 있다. 이 함수 자체는 `sent_dir` 인자로 받은 경로 밖으로는
  절대 나가지 않는다(아래 안전장치 참고).
"""

from __future__ import annotations

import logging
import re
import shutil
from dataclasses import dataclass, field
from datetime import date, timedelta
from pathlib import Path

logger = logging.getLogger(__name__)

# 날짜 디렉터리 이름 형식. executor.py::_save_sent_bytes가 strftime("%Y-%m-%d")로
# 만드는 형식과 정확히 같아야 한다 — 자리수가 다른 "2026-9-1" 같은 이름은
# 사람이 만든 다른 무언가일 수 있으므로 건드리지 않는다.
_DATE_DIR_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")

# 시계 폭주(RTC 없음 → 재부팅 직후 시각이 미래로 튈 수 있음, 예: 2038년) 방지
# 배수. today가 "실제로 존재하는 가장 최근 날짜 디렉터리"보다
# retention_days * RUNAWAY_MULTIPLIER 일 넘게 앞서 있으면, 정상적인 30일
# 순환 보관 범위를 크게 벗어난 것으로 보고 삭제를 통째로 거부한다.
# 3배로 잡은 근거: 정상 운영(30일 연속 운영 목표, CLAUDE.md)에서 이 함수가
# 하루 이상 안 불린 채 방치될 일은 없다고 가정해도, Pi가 오래 꺼져 있다가
# 돌아온 정상 케이스(예: retention_days*2일 방치)까지는 폭주로 오판하지 않도록
# 여유를 둔 값이다. [기본값] — 운영 중 오탐/누락이 관찰되면 조정한다.
RUNAWAY_MULTIPLIER = 3


@dataclass
class PruneReport:
    """`prune_sent_bytes` 실행 결과.

    - deleted: 실제로 지운 날짜 디렉터리 이름 목록
    - skipped: (이름, 사유) — 형식 불일치/심볼릭 링크/보관 기간 내/미래 날짜/
      최신 디렉터리 보존 등 "의도적으로" 건드리지 않은 항목
    - failed: (이름, 에러 메시지) — 지우려 했지만 실패한 항목(로그에도 남는다)
    - aborted: 폭주 방지 또는 retention_days<=0으로 전체 삭제를 거부했는가
    - abort_reason: aborted가 True일 때 사유 문자열
    """

    deleted: list[str] = field(default_factory=list)
    skipped: list[tuple[str, str]] = field(default_factory=list)
    failed: list[tuple[str, str]] = field(default_factory=list)
    aborted: bool = False
    abort_reason: str | None = None


def prune_sent_bytes(sent_dir: Path, retention_days: int, today: date) -> PruneReport:
    """`sent_dir` 아래 `YYYY-MM-DD` 날짜 디렉터리 중 오래된 것을 지운다.

    보관 기준(경계 명확화, off-by-one 방지): 디렉터리 이름의 날짜를 d,
    나이를 `age = (today - d).days`라 하면
    - `age <= retention_days` 이면 **남긴다** (retention_days=30이면 정확히
      30일 전 날짜까지는 남는다)
    - `age > retention_days` 이면 **지운다** (31일 전부터 지운다)
    즉 "오늘부터 거슬러 retention_days일치(오늘 포함 총 retention_days+1개
    날짜)는 항상 남는다"는 규칙이다.

    날짜 판정은 **디렉터리 이름**만 쓴다(파일 mtime 사용 금지 — RTC 없는 Pi는
    부팅 직후 mtime이 틀린 시각으로 찍힐 수 있다).

    안전장치:
    1. 이름이 정확히 `YYYY-MM-DD` 형식이고 실제 날짜로 파싱되는 디렉터리만
       대상으로 한다. 그 밖의 파일·디렉터리는 절대 건드리지 않는다.
    2. 심볼릭 링크는 이름이 형식에 맞아도 절대 따라가거나 지우지 않는다
       (링크 디렉터리를 지우면 링크가 가리키는 실제 디렉터리가 지워질 수
       있다).
    3. 지우기 직전 `resolve()`로 대상이 `sent_dir`의 바로 아래(직계 하위)인지
       다시 확인한다 — `sent_dir` 밖으로 나가는 어떤 경로도 지우지 않는다.
    4. 시계 폭주 방지: `today`가 실제로 존재하는 가장 최근 날짜 디렉터리보다
       `retention_days * RUNAWAY_MULTIPLIER`일 넘게 앞서 있으면 삭제를 전부
       거부하고 경고 로그만 남긴다(`aborted=True`). 또한 정상 범위 안이어도
       **가장 최근 날짜 디렉터리는 항상 남긴다** — 이 둘을 합치면 "이 함수를
       한 번도 성공적으로 못 부른 채 오래 방치된 Pi"에서도 최소 하나의 증거는
       남고, 시계가 미래로 튄 단일 호출이 전체를 지우는 일은 없다.
    5. 미래 날짜(`d > today`) 디렉터리는 지우지 않는다.
    6. `retention_days <= 0`이면 설정 실수로 전부 삭제되는 것을 막기 위해
       아무것도 지우지 않고 경고 로그만 남긴다.
    7. 개별 삭제 실패는 로그(`logger.error`)를 남기고 나머지 항목 처리를
       계속한다 — 예외를 조용히 삼키지 않는다.

    Args:
        sent_dir: 보낸 바이트 루트(`HARU_DATA_DIR/sent`). 존재하지 않으면
            빈 결과를 돌려준다(에러 아님 — 아직 한 번도 인쇄하지 않은 Pi에서
            정상적으로 발생한다).
        retention_days: 보관 일수. 보통 `HARU_SENT_RETENTION_DAYS`(기본 30).
        today: 기준 날짜(KST). 호출자가 판단해서 주입한다 — 이 함수는 시계를
            읽지 않는다.

    Returns:
        PruneReport.
    """
    report = PruneReport()

    if retention_days <= 0:
        logger.warning(
            "prune_sent_bytes: retention_days=%s <= 0, 설정 오류로 간주해 "
            "아무것도 지우지 않는다",
            retention_days,
        )
        report.aborted = True
        report.abort_reason = f"retention_days<=0 ({retention_days})"
        return report

    if not sent_dir.exists():
        return report

    sent_dir_resolved = sent_dir.resolve()

    # 1차 분류: 형식이 맞는 날짜 디렉터리만 골라낸다. 나머지는 손대지 않는다.
    candidates: list[tuple[str, Path, date]] = []
    for entry in sorted(sent_dir.iterdir()):
        name = entry.name

        if entry.is_symlink():
            report.skipped.append((name, "symlink"))
            continue

        if not entry.is_dir():
            report.skipped.append((name, "not_a_directory"))
            continue

        if not _DATE_DIR_RE.match(name):
            report.skipped.append((name, "name_format"))
            continue

        try:
            parsed = date.fromisoformat(name)
        except ValueError:
            report.skipped.append((name, "invalid_date"))
            continue

        candidates.append((name, entry, parsed))

    if not candidates:
        return report

    most_recent_date = max(d for _, _, d in candidates)

    # 안전장치 4: 시계 폭주 방지.
    runaway_threshold = timedelta(days=retention_days * RUNAWAY_MULTIPLIER)
    if today - most_recent_date > runaway_threshold:
        logger.warning(
            "prune_sent_bytes: today=%s가 가장 최근 디렉터리(%s)보다 "
            "%d일*%d(RUNAWAY_MULTIPLIER) 넘게 앞서 있어(시계 폭주 의심) "
            "삭제를 전부 거부한다",
            today.isoformat(),
            most_recent_date.isoformat(),
            retention_days,
            RUNAWAY_MULTIPLIER,
        )
        report.aborted = True
        report.abort_reason = (
            f"today({today.isoformat()})가 가장 최근 디렉터리"
            f"({most_recent_date.isoformat()})보다 비정상적으로 앞서 있음"
        )
        for name, _, _ in candidates:
            report.skipped.append((name, "runaway_clock_abort"))
        return report

    for name, entry, parsed in candidates:
        if parsed > today:
            report.skipped.append((name, "future_date"))
            continue

        if parsed == most_recent_date:
            # 안전장치 4: 가장 최근 디렉터리는 나이와 무관하게 항상 남긴다.
            report.skipped.append((name, "most_recent_kept"))
            continue

        age_days = (today - parsed).days
        if age_days <= retention_days:
            report.skipped.append((name, "within_retention"))
            continue

        # 안전장치 3: sent_dir 밖으로 나가지 않는지 마지막으로 확인.
        resolved = entry.resolve()
        if resolved.parent != sent_dir_resolved:
            logger.error(
                "prune_sent_bytes: %s의 resolve() 부모가 sent_dir 밖이라 "
                "건너뜀 (resolved=%s)",
                name,
                resolved,
            )
            report.skipped.append((name, "outside_sent_dir"))
            continue

        try:
            shutil.rmtree(entry)
        except OSError as exc:
            logger.error("prune_sent_bytes: %s 삭제 실패: %s", name, exc)
            report.failed.append((name, str(exc)))
            continue

        logger.info("prune_sent_bytes: %s 삭제(%d일 경과, retention=%d일)", name, age_days, retention_days)
        report.deleted.append(name)

    return report
