"""결과 업로더. docs/pi/agent.md 6절, 9절."""

from __future__ import annotations

import logging

from .storage import Storage
from .sync import SyncChannel

logger = logging.getLogger(__name__)


class Uploader:
    """결과 대기열을 서버에 업로드한다."""

    def __init__(self, storage: Storage, sync: SyncChannel):
        self.storage = storage
        self.sync = sync

    def upload_pending_results(self) -> dict:
        """미업로드 결과를 서버에 업로드한다."""
        queued = self.storage.get_queued_results()
        if not queued:
            return {"uploaded": [], "failed": []}

        results = [r["payload_json"] for r in queued]

        try:
            response = self.sync.upload_results(results)
            logger.info(f"Upload results: accepted={len(response.accepted)}, duplicates={len(response.duplicates)}")

            # 업로드 성공한 것 표기
            for result_id in response.accepted + response.duplicates:
                self.storage.mark_result_uploaded(result_id)

            return {"uploaded": response.accepted + response.duplicates, "failed": []}

        except Exception as e:
            logger.error(f"Upload results failed: {e}")
            # 모든 대기 중인 결과의 시도 횟수 증가
            for r in queued:
                self.storage.increment_result_upload_attempts(r["result_id"])
            return {"uploaded": [], "failed": [r["result_id"] for r in queued]}
