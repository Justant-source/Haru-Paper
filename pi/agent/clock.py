"""KST 시각 유틸 + NTP 동기화 게이트. CLAUDE.md 코드 규칙 "시간대 Asia/Seoul 고정".

scheduler.py·executor.py에 각각 있던 KST 상수를 한 곳으로 모은다(.temp/05 설계
4.1). storage.py도 재시도·유예 판정에 aware 시각이 필요해져 세 번째 복사가
생기는 것을 막기 위해 신설했다.

★2026-09-17 추가★: 시계 동기화 게이트(docs/pi/policy.md 4절, docs/pi/agent.md:135-137
"재부팅·중단 후 되돌아보기"). RTC가 없는 Pi가 정전 후 인터넷 없이 재부팅하면 부팅
직후 시각을 믿을 수 없다 — `ClockGate`가 그 판정을 담당한다.
"""

from __future__ import annotations

import logging
import subprocess
from datetime import datetime, timedelta, timezone
from typing import Callable, Optional

logger = logging.getLogger(__name__)

# 한국 시간대. CLAUDE.md "시간대는 Asia/Seoul 고정"
KST = timezone(timedelta(hours=9))


def now_kst() -> datetime:
    """현재 시각(KST, aware)."""
    return datetime.now(KST)


def now_iso() -> str:
    """현재 시각의 KST aware ISO-8601 문자열(오프셋 +09:00 포함).

    서버 결과 업로드 검증은 오프셋이 있는 ISO-8601만 받는다
    (DeviceSyncService.java "must be ISO-8601 with offset").
    """
    return now_kst().isoformat()


def parse_local_iso(value: Optional[str]) -> Optional[datetime]:
    """저장소에 기록된 로컬 시각 문자열을 aware datetime으로 되돌린다.

    마이그레이션 전 코드는 `datetime.now().isoformat()`(naive)을 썼다 — Pi는
    Asia/Seoul 고정이므로 tzinfo가 없는 값에는 KST를 그대로 붙인다. 파싱 실패
    (None, 빈 문자열, 형식 오류)는 None을 돌려주고 호출부가 "시도 기록 없음"으로
    처리한다(재시도 판정에서는 즉시 실행 허용, 유예 만료 판정에서는 missed로 이어진다
    — 둘 다 "모르면 안전한 쪽"이다).
    """
    if not value:
        return None
    try:
        dt = datetime.fromisoformat(value)
    except ValueError:
        return None
    if dt.tzinfo is None:
        return dt.replace(tzinfo=KST)
    return dt.astimezone(KST)


# timedatectl 호출 타임아웃(초) [기본값]. CLAUDE.md 코드 규칙 "모든 USB/BT write는
# 타임아웃을 명시" — 외부 프로세스 호출 전반에 같은 원칙을 적용한다. 5초는
# `timedatectl show`가 로컬 systemd에 D-Bus로 묻는 가벼운 조회라 넉넉한 상한이다.
TIMEDATECTL_TIMEOUT_SEC = 5.0


def check_ntp_synchronized(timeout_sec: float = TIMEDATECTL_TIMEOUT_SEC) -> bool:
    """`timedatectl show -p NTPSynchronized --value`로 NTP 동기화 여부를 확인한다.

    판정 근거는 docs/pi/policy.md:94 "동기화 판정은 timedatectl의 NTPSynchronized
    [기본값]"이다.

    **fail-closed**(과제 1 지시문): 명령 실패·타임아웃·`timedatectl` 자체가 없는
    환경(개발 컨테이너, 일부 배포판)은 전부 "미동기"로 취급한다. 그 반대(실패 시
    "동기화됨"으로 취급)를 택하면 `timedatectl`이 고장 난 Pi에서 시계 게이트가
    아예 없는 것과 같아져 CLAUDE.md 절대 금지 1(용지 확인 전 래스터 금지)과 같은
    급의 안전장치가 조용히 사라진다.

    **매번 경고 로그를 남긴다** — `ClockGate`가 이 함수를 동기화 확인 전까지만
    반복 호출하고 한 번 성공하면 다시 부르지 않으므로(sticky, 아래 `ClockGate`
    참고), 이 로그가 "왜 인쇄가 멈췄는지" 운영자가 알아낼 유일한 단서가 된다 —
    로그 없이 조용히 미동기로 떨어지면 인쇄가 영영 멈춘 원인을 journald에서
    찾을 수 없다.
    """
    try:
        result = subprocess.run(
            ["timedatectl", "show", "-p", "NTPSynchronized", "--value"],
            capture_output=True,
            text=True,
            timeout=timeout_sec,
        )
    except FileNotFoundError:
        logger.warning("timedatectl 실행 파일을 찾을 수 없음 — 시계 미동기(fail-closed)로 취급")
        return False
    except subprocess.TimeoutExpired:
        logger.warning(f"timedatectl 호출이 {timeout_sec}초 안에 끝나지 않음 — 시계 미동기(fail-closed)로 취급")
        return False
    except OSError as e:
        # 예외를 삼키지 않는다(CLAUDE.md 코드 규칙) — 원인을 로그에 남긴다.
        logger.warning(f"timedatectl 호출 실패({e!r}) — 시계 미동기(fail-closed)로 취급")
        return False

    if result.returncode != 0:
        logger.warning(
            f"timedatectl 종료 코드 {result.returncode}"
            f"(stderr={result.stderr.strip()!r}) — 시계 미동기(fail-closed)로 취급"
        )
        return False

    value = result.stdout.strip()
    synced = value == "yes"
    if not synced:
        logger.info(f"NTPSynchronized={value!r} — 시계 아직 미동기")
    return synced


class ClockGate:
    """NTP 동기화 게이트. docs/pi/policy.md 4절, docs/pi/agent.md:135-137.

    Pi에는 RTC가 없다. 정전 후 재부팅했는데 인터넷도 없으면 부팅 직후 시각을
    믿을 수 없으므로, 동기화가 확인되기 전에는 예약·명령 실행을 전부 보류한다
    (`Agent._do_scheduler_tick`이 이 게이트를 먼저 확인하고 미동기면 그 틱을
    통째로 건너뛴다 — 실행뿐 아니라 occurrence 계산 자체와 `kv.last_tick_at`
    갱신도 보류한다. 신뢰할 수 없는 `now`를 되돌아보기 기준점으로 남기면 안
    되기 때문이다).

    ★sticky 판정(설계 선택, 근거)★: 한 번 `True`를 관측하면 이 프로세스가 사는
    동안 다시 `check_fn`(=`timedatectl` 호출)을 부르지 않는다.
    - 동기화 이후 시스템 시계는 RTC 없이도 커널 타이머로 정상 진행한다 — "동기화가
      풀리는" 시나리오는 규약에 없다(docs/pi/policy.md 4절은 "미동기 상태"만
      다룬다).
    - 매 스케줄러 틱(10초)마다 서브프로세스를 fork하는 비용을 동기화 전 짧은
      구간으로만 제한한다.
    - 대가: NTP 데몬이 몇 시간 뒤에 죽어도 이 프로세스는 감지하지 못한다. 수용
      가능하다고 판단했다 — "시스템 시계 drift"와 "재부팅 직후 믿을 수 없는
      시각"은 이 설계가 막으려는 위험의 성격이 다르다(전자는 초 단위, 후자는
      임의의 값일 수 있다).

    ★missed vs skipped_clock_unsynced 구분 규칙★: `expired_during_unsynced_window()`
    참고. "이 프로세스가 동기화 전에 미동기 상태를 관측했는지"를 기준으로 삼되,
    그 관측이 끝난 시각(`_first_synced_at`) 이후에 놓친 회차까지 전부 시계
    탓으로 돌리지 않도록 시각 경계로 한 번 더 좁힌다 — 그렇지 않으면 부팅 직후
    잠깐 미동기였던 프로세스가 그 뒤 수십 일(30일 연속 운영) 동안 겪는 모든
    진짜 `missed`까지 `skipped_clock_unsynced`로 영구히 오분류하게 된다.
    """

    def __init__(self, check_fn: Callable[[], bool] = check_ntp_synchronized):
        self._check_fn = check_fn
        self._synced = False
        self._ever_unsynced = False
        self._first_synced_at: Optional[datetime] = None

    def is_synced(self) -> bool:
        """지금 동기화 상태인가. 위 클래스 docstring의 sticky 설계를 따른다."""
        if self._synced:
            return True
        result = self._check_fn()
        if result:
            self._synced = True
            self._first_synced_at = now_kst()
        else:
            self._ever_unsynced = True
        return result

    @property
    def ever_unsynced(self) -> bool:
        """이 프로세스가 동기화 확인 전에 미동기 상태를 실제로 관측한 적이 있는가."""
        return self._ever_unsynced

    def expired_during_unsynced_window(self, expiry_at: datetime) -> bool:
        """`expiry_at`(occurrence의 유예 만료 시각)이 "이 프로세스가 미동기를 거쳐
        처음 동기화를 확인한 시각"(`_first_synced_at`) 이전이면 True.

        True면 호출부가 `missed` 대신 `skipped_clock_unsynced`를 써야 한다는 뜻.
        `_ever_unsynced`가 False(이 프로세스는 한 번도 미동기를 겪지 않음 — 대부분의
        정상 기동)면 항상 False — 클래스 docstring의 "missed vs
        skipped_clock_unsynced 구분 규칙" 참고.
        """
        if not self._ever_unsynced or self._first_synced_at is None:
            return False
        return expiry_at <= self._first_synced_at
