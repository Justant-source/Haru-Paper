"""설정 로드 (.env에서). docs/pi/agent.md 3절."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def load_env_file(env_path: Path) -> dict[str, str]:
    """간단한 .env 파서. python-dotenv 없이 표준 라이브러리만 사용."""
    env_vars = {}
    if env_path.exists():
        with open(env_path, "r") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#"):
                    continue
                if "=" in line:
                    key, value = line.split("=", 1)
                    env_vars[key.strip()] = value.strip()
    return env_vars


@dataclass(frozen=True)
class AgentConfig:
    """Pi 에이전트 설정. 모든 환경변수는 여기서 로드된다."""

    server_url: str
    device_token: str
    poll_interval_sec: int
    printer_driver: str
    transport: str
    bt_address: str
    paper_policy: str
    grace_minutes: int
    retry_interval_sec: int
    h_offset_mm: float
    data_dir: str
    sent_retention_days: int

    @classmethod
    def from_env(cls, env_path: Path = None) -> AgentConfig:
        """환경변수 로드. env_path가 없으면 현재 디렉터리의 .env를 찾음."""
        if env_path is None:
            env_path = Path(__file__).parent.parent / ".env"

        # .env 파일에서 로드
        env_vars = load_env_file(env_path)

        # 시스템 환경변수로 덮어쓰기 (우선순위: 시스템 > .env)
        for key in env_vars:
            if key not in os.environ:
                os.environ[key] = env_vars[key]

        # 필수 환경변수
        required_keys = [
            "HARU_SERVER_URL",
            "HARU_DEVICE_TOKEN",
            "HARU_POLL_INTERVAL_SEC",
            "HARU_PRINTER_DRIVER",
            "HARU_TRANSPORT",
            "HARU_PAPER_POLICY",
            "HARU_GRACE_MINUTES",
            "HARU_RETRY_INTERVAL_SEC",
            "HARU_H_OFFSET_MM",
            "HARU_DATA_DIR",
            "HARU_SENT_RETENTION_DAYS",
        ]

        missing = [k for k in required_keys if not os.environ.get(k)]
        if missing:
            raise ValueError(f"Missing required env vars: {', '.join(missing)}")

        return cls(
            server_url=os.environ["HARU_SERVER_URL"],
            device_token=os.environ["HARU_DEVICE_TOKEN"],
            poll_interval_sec=int(os.environ["HARU_POLL_INTERVAL_SEC"]),
            printer_driver=os.environ["HARU_PRINTER_DRIVER"],
            transport=os.environ["HARU_TRANSPORT"],
            bt_address=os.environ.get("HARU_BT_ADDRESS", ""),
            paper_policy=os.environ["HARU_PAPER_POLICY"],
            grace_minutes=int(os.environ["HARU_GRACE_MINUTES"]),
            retry_interval_sec=int(os.environ["HARU_RETRY_INTERVAL_SEC"]),
            h_offset_mm=float(os.environ["HARU_H_OFFSET_MM"]),
            data_dir=os.environ["HARU_DATA_DIR"],
            sent_retention_days=int(os.environ["HARU_SENT_RETENTION_DAYS"]),
        )
