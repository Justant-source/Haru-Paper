"""실행기 (렌더 선택 → 용지 정책 → 인쇄). docs/pi/agent.md 8절."""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Optional

from printer import Printer, PrinterStatus

from .scheduler import Occurrence
from .storage import Storage
from .sync import SyncChannel

logger = logging.getLogger(__name__)

# 한국 시간대
KST = timezone(timedelta(hours=9))


class Executor:
    """occurrence와 명령을 실행한다."""

    def __init__(
        self,
        storage: Storage,
        sync: SyncChannel,
        printer: Printer,
        data_dir: Path,
        paper_policy: str,
    ):
        self.storage = storage
        self.sync = sync
        self.printer = printer
        self.data_dir = data_dir
        self.paper_policy = paper_policy

    def execute_occurrence(self, occurrence: Occurrence, snapshot: dict) -> dict:
        """occurrence를 실행한다. 결과를 반환하고 저장한다."""
        result_id = str(uuid.uuid4())
        now_kst = datetime.now(KST)

        try:
            # 1. 렌더 선택
            render = self._select_render(occurrence, snapshot)
            if not render:
                status = "failed"
                detail = "No render found"
                scheduled_at = self._parse_scheduled_at(occurrence)
                render_id = ""
            else:
                render_id = render.get("renderId", "")

                # 2. 용지 정책 판단
                should_print, skip_reason = self._check_paper_policy(render)

                if not should_print:
                    # dry_run 또는 skip
                    status = skip_reason if skip_reason != "dry_run" else "dry_run"
                    detail = ""
                    scheduled_at = self._parse_scheduled_at(occurrence)
                else:
                    # 3. 인쇄 시도 전 상태 저장 (중복 방지)
                    self.storage.save_executed_occurrence(
                        occurrence.occurrence_key,
                        status="attempting",
                        result_id=result_id,
                        first_attempt_at=now_kst.isoformat(),
                        attempts=1,
                    )

                    # 4. 인쇄 실행
                    try:
                        render_bytes = self._load_render_bytes(render)
                        outcome = self.printer.print_image(render_bytes)
                        status = "printed"
                        detail = ""

                        # 보낸 바이트 보관
                        self._save_sent_bytes(result_id, outcome.sent_bytes)
                    except Exception as e:
                        logger.error(f"Print failed: {e}", exc_info=True)
                        status = "failed"
                        detail = str(e)

                    scheduled_at = self._parse_scheduled_at(occurrence)

            # 5. 최종 결과 저장
            payload = {
                "resultId": result_id,
                "occurrenceKey": occurrence.occurrence_key,
                "commandId": None,
                "formatId": occurrence.format_id,
                "renderId": render_id,
                "status": status,
                "detail": detail,
                "scheduledAt": scheduled_at,
                "executedAt": now_kst.isoformat(),
            }

            # DB에 기록
            self.storage.save_executed_occurrence(
                occurrence.occurrence_key,
                status=status,
                result_id=result_id,
                final=True,
            )

            # 결과 대기열에 추가
            self.storage.save_result(result_id, payload)

            logger.info(f"Executed {occurrence.occurrence_key}: {status}")
            return payload

        except Exception as e:
            logger.error(f"Execute occurrence {occurrence.occurrence_key} failed: {e}", exc_info=True)
            payload = {
                "resultId": result_id,
                "occurrenceKey": occurrence.occurrence_key,
                "commandId": None,
                "formatId": occurrence.format_id,
                "renderId": "",
                "status": "failed",
                "detail": str(e),
                "scheduledAt": self._parse_scheduled_at(occurrence),
                "executedAt": now_kst.isoformat(),
            }
            self.storage.save_executed_occurrence(occurrence.occurrence_key, status="failed", result_id=result_id, final=True)
            self.storage.save_result(result_id, payload)
            return payload

    def _select_render(self, occurrence: Occurrence, snapshot: dict) -> Optional[dict]:
        """렌더 선택. docs/pi/agent.md 8절 1번:
        (formatId, 오늘 KST 날짜)이고 verified=1인 것 → 없으면 그 formatId의 가장 최근
        target_date(같으면 가장 최근 다운로드) 렌더 → 그것도 없으면 None(호출부가 failed 처리).
        """
        renders = snapshot.get("renders", [])
        today_str = datetime.now(KST).strftime("%Y-%m-%d")

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

    def _check_paper_policy(self, render: dict) -> tuple[bool, str]:
        """용지 정책 확인. docs/pi/agent.md 8절 2번, policy.md 2절."""
        if self.paper_policy == "unverified":
            # dry_run으로 끝냄
            return False, "dry_run"
        elif self.paper_policy == "status_query":
            # 프린터 상태 조회
            status = self.printer.status()
            if status.state != "ok":
                return False, "skipped_printer_offline"
            # ok면 인쇄 진행
            return True, ""
        elif self.paper_policy == "manual_flag":
            # paperState 확인
            paper_state_json = self.storage.get_kv("paperState")
            if paper_state_json:
                import json

                paper_state = json.loads(paper_state_json)
                if not paper_state.get("loaded", False):
                    return False, "skipped_no_paper"
            return True, ""
        else:
            logger.error(f"Unknown paper policy: {self.paper_policy}")
            return False, "unknown_policy"

    def _load_render_bytes(self, render: dict) -> bytes:
        """렌더 PNG 바이트 로드."""
        render_path = Path(render["path"])
        if not render_path.exists():
            raise FileNotFoundError(f"Render file not found: {render_path}")
        return render_path.read_bytes()

    def _save_sent_bytes(self, result_id: str, sent_bytes: bytes):
        """보낸 바이트 저장. docs/pi/policy.md 7절."""
        today_str = datetime.now(KST).strftime("%Y-%m-%d")
        sent_dir = self.data_dir / "sent" / today_str
        sent_dir.mkdir(parents=True, exist_ok=True)
        sent_path = sent_dir / f"{result_id}.bin"
        sent_path.write_bytes(sent_bytes)
        logger.info(f"Saved sent bytes to {sent_path}")

    def _parse_scheduled_at(self, occurrence: Occurrence) -> str:
        """occurrence의 scheduled_at을 ISO-8601로."""
        return occurrence.scheduled_at.isoformat()
