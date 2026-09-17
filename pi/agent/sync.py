"""동기화 채널 (SyncChannel 인터페이스 + HTTP 폴링 구현). docs/pi/agent.md 6절, architecture.md 4.3절."""

from __future__ import annotations

import hashlib
import json
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass
from pathlib import Path

import requests

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class PollResponse:
    """POST /api/device/poll 응답."""

    server_time: str
    snapshot_hash: str
    snapshot_changed: bool
    commands: list
    paper_state: dict
    poll_interval_sec: int


@dataclass(frozen=True)
class Snapshot:
    """GET /api/device/snapshot 응답."""

    snapshot_hash: str
    generated_at: str
    schedules: list
    renders: list


@dataclass(frozen=True)
class UploadResponse:
    """POST /api/device/results 응답."""

    accepted: list
    duplicates: list


class SyncChannel(ABC):
    """동기화 채널 인터페이스 (나중에 WebSocket으로 교체 가능)."""

    @abstractmethod
    def poll(self, heartbeat: dict) -> PollResponse:
        """POST /api/device/poll. heartbeat = {agentVersion, printerProfile, printerStatus, paperPolicy, snapshotHash}."""

    @abstractmethod
    def fetch_snapshot(self) -> Snapshot:
        """GET /api/device/snapshot."""

    @abstractmethod
    def download_render(self, render_id: str) -> bytes:
        """GET /api/device/renders/{renderId}.png."""

    @abstractmethod
    def upload_results(self, results: list) -> UploadResponse:
        """POST /api/device/results. results = [{resultId, occurrenceKey, commandId, formatId, renderId, status, detail, scheduledAt, executedAt}]."""


class HttpPollSyncChannel(SyncChannel):
    """HTTP 폴링 구현."""

    def __init__(self, server_url: str, device_token: str):
        self.server_url = server_url.rstrip("/")
        self.device_token = device_token
        self.session = requests.Session()
        # 모든 요청에 Authorization 헤더 추가
        self.session.headers.update({"Authorization": f"Bearer {device_token}"})

    def poll(self, heartbeat: dict) -> PollResponse:
        """POST /api/device/poll."""
        url = f"{self.server_url}/api/device/poll"
        try:
            resp = self.session.post(url, json=heartbeat, timeout=10)
            resp.raise_for_status()
            data = resp.json()
            return PollResponse(
                server_time=data.get("serverTime", ""),
                snapshot_hash=data.get("snapshotHash", ""),
                snapshot_changed=data.get("snapshotChanged", False),
                commands=data.get("commands", []),
                paper_state=data.get("paperState", {}),
                poll_interval_sec=data.get("pollIntervalSec", 30),
            )
        except requests.RequestException as e:
            logger.error(f"Poll request failed: {e}")
            raise

    def fetch_snapshot(self) -> Snapshot:
        """GET /api/device/snapshot."""
        url = f"{self.server_url}/api/device/snapshot"
        try:
            resp = self.session.get(url, timeout=10)
            resp.raise_for_status()
            data = resp.json()
            return Snapshot(
                snapshot_hash=data.get("snapshotHash", ""),
                generated_at=data.get("generatedAt", ""),
                schedules=data.get("schedules", []),
                renders=data.get("renders", []),
            )
        except requests.RequestException as e:
            logger.error(f"Fetch snapshot failed: {e}")
            raise

    def download_render(self, render_id: str) -> bytes:
        """GET /api/device/renders/{renderId}.png."""
        url = f"{self.server_url}/api/device/renders/{render_id}.png"
        try:
            resp = self.session.get(url, timeout=30)
            resp.raise_for_status()
            return resp.content
        except requests.RequestException as e:
            logger.error(f"Download render {render_id} failed: {e}")
            raise

    def upload_results(self, results: list) -> UploadResponse:
        """POST /api/device/results."""
        url = f"{self.server_url}/api/device/results"
        payload = {"results": results}
        try:
            resp = self.session.post(url, json=payload, timeout=10)
            resp.raise_for_status()
            data = resp.json()
            return UploadResponse(
                accepted=data.get("accepted", []),
                duplicates=data.get("duplicates", []),
            )
        except requests.RequestException as e:
            logger.error(f"Upload results failed: {e}")
            raise

    def open_event_stream(self, read_timeout_sec: float):
        """GET /api/device/events 를 stream=True로 연다.

        SyncChannel ABC에는 넣지 않는다(추상 메서드로 강제하면 duck-typed 가짜
        SyncChannel들이 깨진다) — agent.events.PushListener는
        `hasattr(self.sync, "open_event_stream")`로 기능 탐지해서 쓴다.

        실패 시 requests.RequestException(HTTPError 포함)을 그대로 올린다 —
        호출부인 PushListener가 상태 코드를 보고 재연결·백오프를 결정한다. 여기서
        raise_for_status() 전에 상태 코드를 먼저 판단하지 않는 이유: HTTPError도
        response를 들고 있어서(e.response.status_code) 호출부가 그대로 분기할 수
        있다 — 이 메서드에서 분기 로직을 미리 만들어 봐야 책임만 이 쪽으로
        옮겨질 뿐이다.

        Args:
            read_timeout_sec: 서버 하트비트(기본 15초)보다 충분히 길게 잡아야
                한다 — 이 값보다 오래 아무 줄도 안 오면 죽은 연결로 보고
                재연결한다. connect 타임아웃은 5초로 고정한다(연결 자체가 안
                되는 경우는 빨리 포기하고 재시도하는 게 낫다).
        """
        url = f"{self.server_url}/api/device/events"
        resp = self.session.get(
            url,
            stream=True,
            timeout=(5, read_timeout_sec),
            headers={"Accept": "text/event-stream"},
        )
        resp.raise_for_status()
        return resp
