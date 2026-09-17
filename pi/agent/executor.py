"""실행기 (렌더 선택 → 용지 정책 → 인쇄). docs/pi/agent.md 8절.

.temp/05 설계서(재시도·"지금 인쇄"·결과 업로드 잔여 과제)를 구현한다. 핵심 불변식
두 가지는 이 파일 전체에 걸려 있다:

1. **중복 인쇄 금지**: `print_image()`가 호출될 수 있었던 시도(= `mark_*_attempting`을
   지난 시도)는 성공이든 실패든 반드시 final=True로 종결한다. 그 전 단계(렌더 없음,
   용지 미확인, 프린터 오프라인)에서 끝난 시도만 재시도 대상(final=False)이 된다.
2. **용지 게이트 우회 금지**(CLAUDE.md 절대 금지 1): 예약 경로(`execute_occurrence`)는
   `_check_paper_policy`에 `paper_confirmed`를 절대 넘기지 않는다(기본값 False).
   `paper_confirmed=True`가 실제로 의미를 가지는 경로는 "지금 인쇄"(`execute_command`)
   뿐이고, `status_query` 정책에서는 그 값조차 보지 않는다(docs/pi/policy.md 2절).
"""

from __future__ import annotations

import json
import logging
import threading
import uuid
from contextlib import contextmanager
from datetime import timedelta
from pathlib import Path
from typing import Iterator, Optional

from printer import Printer, PrinterStatus

from .clock import KST, now_iso, now_kst, parse_local_iso
from .scheduler import Occurrence, parse_occurrence_key
from .storage import Storage
from .sync import SyncChannel

logger = logging.getLogger(__name__)

# 서버가 "지금 인쇄" 명령을 10분 뒤 expired 처리한다
# (server/.../device/DeviceSyncService.java의 만료 스캔, Instant.now().minusSeconds(600)).
# Pi도 독립적으로 같은 값을 기본 TTL로 쓴다 — 서버가 안 보냈을 거라고 가정하지 않고
# Pi 스스로 판단한다(.temp/05 설계 Q4 B2).
DEFAULT_COMMAND_TTL_SEC = 600

# 서버 results.status ENUM 7종(server/src/main/resources/db/migration/V1__init.sql:77).
# 이 목록 밖의 값(checking/attempting 같은 로컬 내부 상태)을 그대로 올리면 서버
# INSERT가 깨진다 — finalize_occurrence/finalize_command가 여기로 강제 매핑한다.
VALID_RESULT_STATUSES = frozenset(
    {
        "printed",
        "dry_run",
        "missed",
        "failed",
        "skipped_no_paper",
        "skipped_clock_unsynced",
        "skipped_printer_offline",
    }
)


class Executor:
    """occurrence와 명령을 실행한다."""

    def __init__(
        self,
        storage: Storage,
        sync: SyncChannel,
        printer: Printer,
        data_dir: Path,
        paper_policy: str,
        command_ttl_sec: int = DEFAULT_COMMAND_TTL_SEC,
    ):
        self.storage = storage
        self.sync = sync
        self.printer = printer
        self.data_dir = data_dir
        self.paper_policy = paper_policy
        self.command_ttl_sec = command_ttl_sec
        # 프린터로 나가는 구간(print_image 호출)은 한 번에 하나만(.temp/05 설계 Q5).
        # ③[중요, 2026-09-17 Opus 검토로 발견] 이 락은 이제 스케줄러 스레드만이
        # 아니라 폴링 스레드의 heartbeat(printer.profile()/status())도 지킨다 —
        # M832Printer.status()는 `with self.transport:`로 실제 BT 연결을 열고,
        # BtTransport는 소켓 하나를 공유하며 자체 락이 없다. 스케줄러 스레드가
        # print_image 중(전체 데드라인 60초)일 때 30초 주기 heartbeat가 겹치면
        # 소켓을 가로채 전송 중 send()가 실패할 수 있다(부분 인쇄 + failed).
        # try_lock_printer()가 그 접근 지점이다.
        self._print_lock = threading.Lock()

    @contextmanager
    def try_lock_printer(self) -> Iterator[bool]:
        """프린터 접근을 논블로킹으로 잠가본다(③, heartbeat용).

        `with executor.try_lock_printer() as acquired:` 형태로 쓴다. 성공하면
        `acquired=True`를 주고 블록이 끝나면 자동으로 풀어 준다. 실패하면(스케줄러
        스레드가 인쇄 중이라 이미 잠겨 있음) `acquired=False`만 주고 아무 것도
        잠그지 않는다 — **절대 블록하지 않는다**. `print_image`의 전체 데드라인이
        60초라, 폴링 스레드가 그동안 `self._print_lock`을 기다리며 멈추면 heartbeat가
        최대 60초 밀리고 다음 poll도 그만큼 늦어진다. 인쇄 정확도(60초 안에 끝남)보다
        heartbeat 정확도가 훨씬 덜 중요하므로, 락을 못 잡으면 호출부(Agent._do_poll)가
        직전 프린터 상태를 재사용하고 다음 폴링으로 넘기는 쪽을 택했다(설계는 "직전
        값 재사용 또는 상태 보고 생략" 중 전자 — 서버가 printerProfile/printerStatus를
        heartbeat 필수 필드로 기대하므로 생략보다 안전하다).
        """
        acquired = self._print_lock.acquire(blocking=False)
        try:
            yield acquired
        finally:
            if acquired:
                self._print_lock.release()

    # ------------------------------------------------------------------
    # occurrence(예약) 실행
    # ------------------------------------------------------------------

    def execute_occurrence(self, occurrence: Occurrence, snapshot: dict) -> Optional[dict]:
        """occurrence를 실행한다.

        종결(final=True)되면 결과 payload를 반환·저장한다. 아직 재시도 여지가 있는
        실패(렌더 없음, 용지 미확인, 프린터 오프라인 — 전부 print_image 호출 전)면
        None을 반환한다. 다음 재시도는 scheduler.filter_executable_occurrences가
        재시도 간격을 지나서 다시 후보로 올릴 때 일어난다.
        """
        result_id = str(uuid.uuid4())
        scheduled_at = self._parse_scheduled_at(occurrence)
        key = occurrence.occurrence_key

        render = self._select_render(occurrence, snapshot)
        render_id = render.get("renderId", "") if render else ""

        self.storage.begin_occurrence_attempt(
            key,
            format_id=occurrence.format_id,
            render_id=render_id,
            scheduled_at=scheduled_at,
            result_id=result_id,
        )

        if not render:
            self._retry_occurrence(key, status="failed", detail="No render found")
            return None

        # 예약 경로는 paper_confirmed를 절대 넘기지 않는다(기본값 False) — CLAUDE.md
        # 절대 금지 1의 구조적 안전장치(.temp/05 설계 Q4 B7).
        should_print, skip_reason = self._check_paper_policy(render)
        if not should_print:
            if skip_reason == "dry_run":
                return self.finalize_occurrence(key, status="dry_run", detail="")
            self._retry_occurrence(key, status=skip_reason, detail="")
            return None

        printer_ok, printer_reason = self._preflight_printer()
        if not printer_ok:
            self._retry_occurrence(key, status=printer_reason, detail="")
            return None

        try:
            render_bytes = self._load_render_bytes(render)
        except Exception as e:
            # ⑥[사소] 예외를 삼키지 않는다(CLAUDE.md 코드 규칙) — 이전에는
            # detail=str(e)로 서버 결과에만 남고 로컬 로그가 전혀 없었다.
            logger.warning(f"Occurrence {key} render load failed: {e}", exc_info=True)
            self._retry_occurrence(key, status="failed", detail=str(e))
            return None

        # ★여기서부터 바이트가 나갈 수 있다★ — mark_occurrence_attempting 이후의
        # 실패는 성공 여부를 알 수 없으므로 무조건 종결한다(재시도 금지, 중복 인쇄 방지).
        self.storage.mark_occurrence_attempting(key)
        try:
            with self._print_lock:
                outcome = self.printer.print_image(render_bytes)
            self._save_sent_bytes(result_id, outcome.sent_bytes)
        except Exception as e:
            logger.error(f"Print failed: {e}", exc_info=True)
            return self.finalize_occurrence(key, status="failed", detail=str(e))

        logger.info(f"Executed {key}: printed")
        return self.finalize_occurrence(key, status="printed", detail="")

    def _retry_occurrence(self, occurrence_key: str, *, status: str, detail: str) -> None:
        """이번 시도를 재시도 대상으로 남기며 종료한다(final=False). print_image를
        호출하지 않은 시도에서만 쓴다."""
        self.storage.finish_occurrence_attempt(occurrence_key, status=status, detail=detail, final=False)
        logger.info(f"Occurrence {occurrence_key} retryable: {status} ({detail})")

    def finalize_occurrence(self, occurrence_key: str, status: str, detail: str) -> dict:
        """저장소 행만 가지고 occurrence를 종결한다(occurrence 객체 없이).

        기동 정리(cleanup_stale_attempts)와 유예 만료 처리, execute_occurrence의
        종결 경로가 전부 이 메서드 하나를 거친다 — "결과는 final 전이 때 정확히
        한 번"이라는 불변식을 지키는 유일한 통로다.
        """
        status, detail = self._coerce_result_status(status, detail)

        row = self.storage.get_executed_occurrence(occurrence_key)
        if row is None:
            raise ValueError(f"occurrence {occurrence_key!r}를 찾을 수 없음")

        result_id = row.get("result_id") or str(uuid.uuid4())
        scheduled_at = row.get("scheduled_at") or self._recover_scheduled_at(occurrence_key)

        self.storage.finish_occurrence_attempt(occurrence_key, status=status, detail=detail, final=True)

        payload = self._build_result_payload(
            result_id=result_id,
            occurrence_key=occurrence_key,
            command_id=None,
            format_id=row.get("format_id") or "",
            render_id=row.get("render_id") or "",
            status=status,
            detail=detail,
            scheduled_at=scheduled_at,
        )
        self.storage.save_result(result_id, payload)
        logger.info(f"Finalized occurrence {occurrence_key}: {status}")
        return payload

    # ------------------------------------------------------------------
    # command("지금 인쇄") 실행
    # ------------------------------------------------------------------

    def execute_command(self, command: dict) -> Optional[dict]:
        """"지금 인쇄" 명령을 실행한다. occurrence와 같은 단계를 타되 다음이 다르다:

        - 렌더는 command['render_id'] 고정(스냅샷 폴백 없음). 명령의 sha256으로
          검증된 로컬 캐시가 없으면(아직 도착 안 함) TTL 전까지는 종결하지 않는다.
        - 용지 정책에 paper_confirmed를 넘긴다(사용자가 "지금 인쇄" 화면에서 눈으로
          용지를 확인했다는 명시적 신호 — CLAUDE.md 절대 금지 1의 유일한 예외 경로).
        - TTL(command_ttl_sec) 초과면 래스터를 보내지 않고 종결한다. ②[중요,
          2026-09-17 Opus 검토로 발견]: 이 확인은 함수 상단뿐 아니라 print_image
          직전에도 다시 한다 — 그 전 단계(용지 정책·`_preflight_printer`)는
          "실패 + TTL 만료"일 때만 TTL을 보므로, 렌더·용지 정책이 전부 통과하고
          프린터가 (TTL 만료 이후에) 다시 온라인이 된 경우 TTL을 넘긴 채로 그냥
          인쇄해 버리는 결함이 있었다. 예: 프린터가 11분간 꺼져 있다가 켜지면,
          사용자가 10분짜리 "용지 확인"을 보고 자리를 떴을 수 있는데도 인쇄된다.

        결과 payload는 occurrenceKey=None, commandId=<id>,
        scheduledAt=<명령 createdAt>(architecture.md 3.6 "정확히 하나",
        "scheduledAt은 명령일 때 명령 생성 시각").
        """
        command_id = command["command_id"]
        render_id = command.get("render_id") or ""
        sha256 = command.get("sha256") or ""
        paper_confirmed = bool(command.get("paper_confirmed"))

        ttl_expired = self._command_ttl_expired(command)
        result_id = command.get("result_id") or str(uuid.uuid4())
        self.storage.begin_command_attempt(command_id, result_id=result_id)

        if not sha256:
            # Q6: sha256이 빈 문자열 = 검증 불가 = fail-closed. __main__이 이미
            # sha256 없는 명령의 렌더는 내려받지 않으므로 여기서도 진행하지 않는다.
            if ttl_expired:
                return self.finalize_command(command_id, status="failed", detail="명령 렌더 sha256 없음")
            return None

        stored_render = self.storage.get_render(render_id) if render_id else None
        render_ready = bool(
            stored_render and stored_render.get("verified") and stored_render.get("sha256") == sha256
        )
        if not render_ready:
            if ttl_expired:
                return self.finalize_command(
                    command_id, status="failed", detail="명령 렌더 sha256 불일치 또는 미수신"
                )
            return None

        # "지금 인쇄" 경로에서만 paper_confirmed가 의미를 가진다(status_query는 무시한다).
        should_print, skip_reason = self._check_paper_policy(stored_render, paper_confirmed=paper_confirmed)
        if not should_print:
            # unverified 미확인은 예약에서는 dry_run이지만, 명령에서는 "인쇄를
            # 시도했지만 용지 미확인"이므로 skipped_no_paper로 옮긴다(policy.md 2절 표).
            mapped_status = "skipped_no_paper" if skip_reason == "dry_run" else skip_reason
            # ⑤[사소, 2026-09-17 Opus 검토로 발견] paper_confirmed는 명령 생명주기
            # 동안 바뀌지 않는다(생성 시 고정값) — unverified 정책의 결과는
            # paper_confirmed만으로 결정되므로, 확인 안 된 명령은 몇 번을 재시도해도
            # 항상 같은 결과다. TTL(기본 10분)을 기다리지 않고 즉시 종결해 앱
            # 이력에도 바로 반영되게 한다. manual_flag의 paperState.loaded는 서버가
            # 언제든 push로 바꿀 수 있으므로(사용자가 앱에서 "용지 확인" 토글) 여기
            # 포함하지 않는다 — 그 경우는 여전히 TTL까지 재시도를 유지해야 한다.
            if self.paper_policy == "unverified" and not paper_confirmed:
                return self.finalize_command(command_id, status=mapped_status, detail="")
            if ttl_expired:
                return self.finalize_command(command_id, status=mapped_status, detail="")
            return None

        printer_ok, printer_reason = self._preflight_printer()
        if not printer_ok:
            if ttl_expired:
                return self.finalize_command(
                    command_id, status="missed", detail=f"명령 유예 초과(TTL {self.command_ttl_sec}초)"
                )
            return None

        try:
            render_bytes = self._read_stored_render_bytes(stored_render)
        except Exception as e:
            # ⑥[사소] 예외를 삼키지 않는다(CLAUDE.md 코드 규칙) — detail=str(e)로
            # 서버에만 남기고 로컬 로그가 없으면 원인 추적이 안 된다.
            logger.warning(f"Command {command_id} render load failed: {e}", exc_info=True)
            if ttl_expired:
                return self.finalize_command(command_id, status="failed", detail=str(e))
            return None

        # ②[중요, 2026-09-17 Opus 검토로 발견] print_image 직전에 TTL을 다시 확인한다.
        # 위의 ttl_expired는 함수 진입 시점 값이고, 그 사이(용지 정책·_preflight_printer의
        # printer.status() 호출 — BT는 콜드 ACL 워크어라운드로 최대 15초 걸릴 수 있다,
        # R5)에도 시간은 계속 흐른다. 여기서 다시 계산해, "TTL은 지났지만 렌더·용지
        # 정책 통과 + 프린터가 마침 이 순간 온라인"인 경우에도 인쇄하지 않고 종결한다.
        if self._command_ttl_expired(command):
            return self.finalize_command(
                command_id, status="missed", detail=f"명령 유예 초과(TTL {self.command_ttl_sec}초)"
            )

        # ★여기서부터 바이트가 나갈 수 있다★
        self.storage.mark_command_attempting(command_id)
        try:
            with self._print_lock:
                outcome = self.printer.print_image(render_bytes)
            self._save_sent_bytes(result_id, outcome.sent_bytes)
        except Exception as e:
            logger.error(f"Command print failed: {e}", exc_info=True)
            return self.finalize_command(command_id, status="failed", detail=str(e))

        logger.info(f"Executed command {command_id}: printed")
        return self.finalize_command(command_id, status="printed", detail="")

    def finalize_command(self, command_id: str, status: str, detail: str) -> dict:
        """저장소 행만 가지고 명령을 종결한다. occurrence판 finalize_occurrence와
        같은 이유로 기동 정리·TTL 만료·execute_command의 종결 경로가 공유한다."""
        status, detail = self._coerce_result_status(status, detail)

        row = self.storage.get_command(command_id)
        if row is None:
            raise ValueError(f"command {command_id!r}를 찾을 수 없음")

        result_id = row.get("result_id") or str(uuid.uuid4())
        # architecture.md 3.6: "scheduledAt은 명령일 때 명령 생성 시각" [기본값]
        scheduled_at = row.get("created_at")

        self.storage.finish_command(command_id, detail=detail)

        payload = self._build_result_payload(
            result_id=result_id,
            occurrence_key=None,
            command_id=command_id,
            format_id=row.get("format_id") or "",
            render_id=row.get("render_id") or "",
            status=status,
            detail=detail,
            scheduled_at=scheduled_at,
        )
        self.storage.save_result(result_id, payload)
        logger.info(f"Finalized command {command_id}: {status}")
        return payload

    def _command_ttl_expired(self, command: dict) -> bool:
        """min(createdAt, received_at) + command_ttl_sec을 넘겼는가(.temp/05 설계 Q4 B2).

        서버 만료(10분)에 기대지 않고 Pi가 독립적으로 판단한다. 시각을 전혀 모르면
        (파싱 실패) 안전한 쪽 — 즉시 만료로 취급해 무한정 pending으로 남지 않게 한다.
        """
        created_at = parse_local_iso(command.get("created_at"))
        received_at = parse_local_iso(command.get("received_at"))
        candidates = [dt for dt in (created_at, received_at) if dt is not None]
        if not candidates:
            return True
        deadline = min(candidates) + timedelta(seconds=self.command_ttl_sec)
        return now_kst() > deadline

    # ------------------------------------------------------------------
    # 공용
    # ------------------------------------------------------------------

    def _coerce_result_status(self, status: str, detail: str) -> tuple[str, str]:
        """서버 results.status ENUM 7종 밖의 값(checking/attempting 같은 로컬 내부
        상태)이 실수로 들어오면 failed로 강제 매핑한다(V1__init.sql:77 보호)."""
        if status not in VALID_RESULT_STATUSES:
            logger.warning(f"내부 상태 {status!r}는 서버 results.status ENUM에 없음 — failed로 매핑")
            detail = detail or f"내부 상태 {status!r}에서 강제 종결"
            status = "failed"
        return status, detail

    def _recover_scheduled_at(self, occurrence_key: str) -> Optional[str]:
        """옛 행(스키마 마이그레이션 이전, scheduled_at이 NULL)의 예약 시각을
        occurrence_key에서 복원한다(.temp/05 설계 5.3 — key 형식의 존재 이유).
        복원도 실패하면 None을 돌려준다 — 서버 검증은 scheduledAt NULL을 허용한다
        (DeviceSyncService.java, 값이 있을 때만 형식을 본다)."""
        try:
            from datetime import datetime

            _, date_str, time_str = parse_occurrence_key(occurrence_key)
            dt = datetime.strptime(f"{date_str}T{time_str}", "%Y-%m-%dT%H:%M").replace(tzinfo=KST)
            return dt.isoformat()
        except Exception as e:
            # ⑥[사소] 예외를 삼키지 않는다(CLAUDE.md 코드 규칙) — 복원 실패 자체는
            # 정상 동작(서버는 scheduledAt NULL을 허용)이지만, 원인 없이 조용히
            # None만 돌려주면 "왜 옛 행만 scheduledAt이 비는지" 나중에 추적할 수 없다.
            logger.warning(f"occurrence_key {occurrence_key!r}에서 scheduled_at 복원 실패: {e}")
            return None

    def _build_result_payload(
        self,
        *,
        result_id: str,
        occurrence_key: Optional[str],
        command_id: Optional[str],
        format_id: str,
        render_id: str,
        status: str,
        detail: str,
        scheduled_at: Optional[str],
    ) -> dict:
        """architecture.md 3.6/4.3의 필드 그대로. occurrenceKey와 commandId 중
        하나는 반드시 None이다(서버 검증 "정확히 하나")."""
        return {
            "resultId": result_id,
            "occurrenceKey": occurrence_key,
            "commandId": command_id,
            "formatId": format_id,
            "renderId": render_id,
            "status": status,
            "detail": detail,
            "scheduledAt": scheduled_at,
            "executedAt": now_iso(),
        }

    def _select_render(self, occurrence: Occurrence, snapshot: dict) -> Optional[dict]:
        """렌더 선택. docs/pi/agent.md 8절 1번:
        (formatId, 오늘 KST 날짜)이고 verified=1인 것 → 없으면 그 formatId의 가장 최근
        target_date(같으면 가장 최근 다운로드) 렌더 → 그것도 없으면 None(호출부가 failed 처리).
        """
        renders = snapshot.get("renders", [])
        today_str = now_kst().strftime("%Y-%m-%d")

        candidates_for_format = []
        for r in renders:
            if r.get("formatId") != occurrence.format_id:
                continue
            render_id = r.get("renderId")
            if not render_id:
                continue
            stored = self.storage.get_render(render_id)
            if stored and stored["verified"]:
                candidates_for_format.append(r)

        if not candidates_for_format:
            return None

        # 1순위: 오늘 날짜(targetDate)인 것
        for r in candidates_for_format:
            if r.get("targetDate") == today_str:
                return r

        # 2순위(오프라인 폴백): 그 포맷의 가장 최근 렌더(renderedAt 최신순, 없으면 목록 마지막)
        candidates_for_format.sort(key=lambda r: r.get("renderedAt") or "", reverse=True)
        return candidates_for_format[0]

    def _check_paper_policy(self, render: dict, paper_confirmed: bool = False) -> tuple[bool, str]:
        """용지 정책 확인. docs/pi/agent.md 8절 2번, policy.md 2절.

        ★시그니처 변경(.temp/05 설계 Q4)★: paper_confirmed 기본값 False가 예약
        경로의 안전장치다 — execute_occurrence는 이 인자를 절대 넘기지 않는다.
        "지금 인쇄"(execute_command)만 사용자가 명시적으로 확인한 값을 넘긴다.

        fail-closed가 원칙이다(CLAUDE.md 절대 금지 1: 용지를 눈으로 확인하기 전에는
        래스터를 보내지 않는다). "용지가 있는지 확실히 모른다"는 모두 인쇄를 막는
        쪽으로 떨어져야 하며, 어느 분기에서도 "확인 안 됨"이 기본 허용으로 새면
        안 된다.
        """
        if self.paper_policy == "unverified":
            if paper_confirmed is True:
                return True, ""
            # dry_run으로 끝냄(명령 경로에서는 호출부가 skipped_no_paper로 옮긴다)
            return False, "dry_run"
        elif self.paper_policy == "status_query":
            # policy.md:30 — paperConfirmed는 이 정책에서 아직 참고되지 않는다.
            # 용지 게이트 우회 방지를 위해 paper_confirmed 인자를 절대 읽지 않는다.
            #
            # ④[중요, 2026-09-17 Opus 검토로 발견] printer.status()를 try로 감싼다.
            # _preflight_printer는 이미 이렇게 하는데 여기는 안 했다 — 예외가 그대로
            # execute_occurrence 밖까지 새 나가면 begin_occurrence_attempt가 이미
            # 찍어 둔 'checking' 행이 종결되지 않은 채 남고(스케줄러 루프의 바깥
            # try/except가 로그만 남기고 틱을 통째로 버린다), 유예 만료 전까지는
            # scheduler.filter_executable_occurrences의 재시도 간격 판정으로만
            # 구제된다. fail-closed 원칙(CLAUDE.md 절대 금지 1)에 맞춰 예외도
            # "프린터 상태를 모른다" = 인쇄 안 함으로 떨어뜨린다.
            try:
                status = self.printer.status()
            except Exception as e:
                logger.error(f"Printer status check failed (status_query 용지 정책): {e}", exc_info=True)
                return False, "skipped_printer_offline"
            if status.state != "ok":
                return False, "skipped_printer_offline"
            # PrinterStatus(printer/__init__.py)에는 용지 필드가 없다 — M832는 아직
            # 용지 유무를 감지하지 못한다(H4 미통과, docs/pi/printer-m832.md 5절).
            # "명시적으로 있음"을 확인할 방법이 없으므로 연결이 살아 있어도
            # fail-closed로 인쇄하지 않는다. H4가 통과해 PrinterStatus에 용지
            # 필드가 생기면 그때 이 분기를 그 필드로 판단하도록 바꾼다.
            return False, "skipped_no_paper"
        elif self.paper_policy == "manual_flag":
            # paperState 확인. 키가 없거나 파싱 실패해도 "확인 안 됨" = 인쇄 금지.
            loaded = False
            paper_state_json = self.storage.get_kv("paperState")
            if paper_state_json:
                try:
                    paper_state = json.loads(paper_state_json)
                    loaded = bool(paper_state.get("loaded", False))
                except (json.JSONDecodeError, TypeError) as e:
                    logger.error(f"paperState JSON 파싱 실패, fail-closed로 skipped_no_paper: {e}")
            if loaded or paper_confirmed is True:
                return True, ""
            return False, "skipped_no_paper"
        else:
            # "unknown_policy"는 docs/pi/policy.md 5절 결과 status 목록에 없는 값이라
            # 쓰지 않는다. 원인(설정 오류)은 로그로 남기고, 가장 안전한 쪽인
            # skipped_no_paper로 fail-closed 처리한다.
            logger.error(f"Unknown paper policy (설정 오류): {self.paper_policy!r} — fail-closed로 skipped_no_paper")
            return False, "skipped_no_paper"

    def _preflight_printer(self) -> tuple[bool, str]:
        """전송 전에 printer.status()를 확인한다. state != 'ok'면
        (False, 'skipped_printer_offline').

        이 단계가 있어야 "07:00에 프린터가 꺼져 있음"이 전송 실패(종결)가 아니라
        전송 전 건너뜀(재시도)이 된다. print_image가 연결 실패와 전송 중 실패를
        구분하지 않고 같은 예외로 올릴 수 있어(printer/m832/driver.py) 실행기가
        사후에 구분할 수 없으므로, 드라이버를 고치지 않고 경계를 앞당기는 쪽을
        택한다(구성요소 경계 유지 — m832를 아는 코드는 printer/m832뿐).

        주의: BT는 여기서 연결이 한 번 더 일어날 수 있다(콜드 ACL 워크어라운드
        경로, transport/bt.py). 연결 타임아웃에 상한이 있어(connect_timeout_sec=10 +
        wake_timeout_sec=5) 틱이 밀리지는 않지만, 연속 연결이 M832/BlueZ에 미치는
        영향은 [미검증] — 30일 운영 전 실물로 확인할 것(.temp/05 설계 7절 R5).
        """
        try:
            status = self.printer.status()
        except Exception as e:
            logger.error(f"Printer status check failed: {e}", exc_info=True)
            return False, "skipped_printer_offline"
        if status.state != "ok":
            return False, "skipped_printer_offline"
        return True, ""

    def _load_render_bytes(self, render: dict) -> bytes:
        """(occurrence 경로) 렌더 PNG 바이트 로드.

        render는 스냅샷의 서버 원본 JSON(RenderDto, camelCase)이라 로컬 파일 경로가
        없다 — "path" 키는 존재하지 않는다. 로컬 경로는
        storage.get_render(renderId)에만 있다(다운로드 시
        __main__.py._download_and_verify_renders가 storage.save_render로 저장).
        """
        render_id = render.get("renderId", "")
        stored = self.storage.get_render(render_id)
        if not stored:
            raise FileNotFoundError(f"Render not found in local cache: {render_id!r}")
        return self._read_stored_render_bytes(stored)

    def _read_stored_render_bytes(self, stored_render: dict) -> bytes:
        """(command 경로) storage.get_render()가 돌려준 행(snake_case: "path" 키
        있음)에서 바로 읽는다. execute_command는 sha256 검증을 위해 이미
        storage.get_render()를 호출해 뒀으므로 renderId로 다시 찾지 않는다
        (2026-09-17 발견: execute_command가 이 값을 _load_render_bytes에 그대로
        넘겨 "renderId" 키를 못 찾고 매번 FileNotFoundError → 재시도로 빠지던 버그)."""
        render_path = Path(stored_render["path"])
        if not render_path.exists():
            raise FileNotFoundError(f"Render file not found: {render_path}")
        return render_path.read_bytes()

    def _save_sent_bytes(self, result_id: str, sent_bytes: bytes):
        """보낸 바이트 저장. docs/pi/policy.md 7절."""
        today_str = now_kst().strftime("%Y-%m-%d")
        sent_dir = self.data_dir / "sent" / today_str
        sent_dir.mkdir(parents=True, exist_ok=True)
        sent_path = sent_dir / f"{result_id}.bin"
        sent_path.write_bytes(sent_bytes)
        logger.info(f"Saved sent bytes to {sent_path}")

    def _parse_scheduled_at(self, occurrence: Occurrence) -> str:
        """occurrence의 scheduled_at을 ISO-8601로."""
        return occurrence.scheduled_at.isoformat()
