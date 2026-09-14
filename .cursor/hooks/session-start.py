#!/usr/bin/env python3
"""Inject hostname + directory ownership at Cursor session start (CLAUDE.md)."""

from __future__ import annotations

import json
import socket

SERVER_HOST = "justant-server2"
SERVER_PATHS = "/server, /app, /docs/server, /docs/app"
NOTEBOOK_PATHS = "/pi, /docs/pi"


def context_for(hostname: str) -> str:
    if hostname == SERVER_HOST:
        owned, other = SERVER_PATHS, NOTEBOOK_PATHS
        role = "서버 세션"
    else:
        owned, other = NOTEBOOK_PATHS, SERVER_PATHS
        role = "노트북 세션"

    return (
        f"hostname={hostname} → {role}. 담당 경로: {owned}. "
        f"담당 밖({other})은 사용자가 요청하지 않으면 수정하지 않는다. "
        "정본은 CLAUDE.md. 공통 파일은 수정 직전 git pull --ff-only."
    )


def main() -> None:
    hostname = socket.gethostname()
    print(
        json.dumps(
            {
                "additional_context": context_for(hostname),
                "env": {"HARU_HOSTNAME": hostname},
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    main()
