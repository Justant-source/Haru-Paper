"""KST 시각 유틸. CLAUDE.md 코드 규칙 "시간대 Asia/Seoul 고정".

scheduler.py·executor.py에 각각 있던 KST 상수를 한 곳으로 모은다(.temp/05 설계
4.1). storage.py도 재시도·유예 판정에 aware 시각이 필요해져 세 번째 복사가
생기는 것을 막기 위해 신설했다.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from typing import Optional

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
