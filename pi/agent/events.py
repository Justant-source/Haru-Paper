"""SSE 깨우기 채널 수신기. 서버 GET /api/device/events에 연결을 유지하다가
event: wake 를 받으면 콜백을 한 번 호출한다. 이 모듈은 프린터·Storage를 전혀
알지 못한다 — 콜백이 하는 일을 이 모듈이 통제하지 않는다는 뜻이고, 그래서
"리스너가 실수로 인쇄 경로를 건드린다"는 부류의 버그가 이 모듈 안에서는
구조적으로 불가능하다.
"""
from __future__ import annotations

import logging
import random
import threading
import time

import requests

logger = logging.getLogger(__name__)

# 백오프 상한(초). 폴링 주기(기본 30초)보다 느리게 재시도할 이유가 없다 —
# 그 지점부터는 어차피 폴링이 보장선이다.
MAX_BACKOFF_SEC = 30.0
# 서버가 SSE를 모르는 옛 버전이거나(404) 인증이 거부됐을 때(401/403)의 재시도 간격.
# 짧게 재시도해 봐야 회복 확률이 낮고 로그만 더럽힌다 — 서버 배포·토큰 재발급으로
# 회복되면 이 간격 안에 자동으로 붙는다.
LONG_RETRY_SEC = 600.0
# 이 시간 이상 연결이 살아 있다가 끊긴 경우엔 "잠깐 끊긴 정상 연결"로 보고
# 백오프를 초기값으로 리셋한다.
STABLE_CONNECTION_SEC = 60.0
INITIAL_BACKOFF_SEC = 1.0


class PushListener:
    """SSE `/api/device/events`를 상시 연결로 열고 `event: wake`를 콜백으로 전달한다.

    이 클래스는 재연결·백오프·상태 코드 분기만 담당한다. 실제 HTTP 연결은
    `open_stream_fn`이, 깨어난 뒤 무엇을 할지는 `on_wake`가 담당한다 — 이
    클래스는 프린터·Storage·Executor를 import조차 하지 않는다.
    """

    def __init__(
        self,
        open_stream_fn,
        on_wake,
        is_running_fn,
        read_timeout_sec: float,
        sleep_fn=time.sleep,
    ):
        """
        Args:
            open_stream_fn: () -> requests.Response (stream=True로 연 응답). 실패
                시 requests.RequestException(HTTPError 포함)을 던진다.
            on_wake: () -> None. event:wake 를 받았을 때 호출할 콜백. **인쇄
                경로를 절대 직접 부르면 안 된다** — 호출부(Agent)가 Event.set()
                정도만 하도록 강제한다(중복 인쇄 방지의 핵심).
            is_running_fn: () -> bool. False가 되면 루프를 정상 종료한다.
            read_timeout_sec: open_stream_fn이 내부적으로 쓰는 값과 맞춰서
                로깅에만 쓴다(실제 타임아웃은 open_stream_fn 쪽 세션 설정이
                담당한다).
            sleep_fn: 백오프 대기에 쓴다. 테스트에서 실제로 기다리지 않도록
                주입하는 지점(Agent.__init__의 clock_synced_check DI와 같은 패턴).
        """
        self._open_stream_fn = open_stream_fn
        self._on_wake = on_wake
        self._is_running_fn = is_running_fn
        self._read_timeout_sec = read_timeout_sec
        self._sleep_fn = sleep_fn

        self._current_response = None
        self._response_lock = threading.Lock()

        # 재연결 백오프 카운터(인스턴스 상태) — event: ready 수신 시 여기서
        # 바로 리셋해야 하므로(_handle_line에서 접근) 지역 변수가 아니라
        # 인스턴스 속성으로 둔다.
        self._backoff = INITIAL_BACKOFF_SEC
        # 마지막으로 로그를 남긴 "긴 재시도" 상태(404/405 vs 401/403 vs None).
        # 상태가 바뀔 때만 로그를 남기기 위한 기억.
        self._last_long_retry_kind = None

    def run(self):
        """백오프 재연결 루프. is_running_fn()이 False가 될 때까지 돈다.

        최상위를 try/except로 감싸 파싱 오류 등 예상 밖 예외가 나도 리스너
        스레드가 죽지 않고 재연결을 계속 시도하게 한다.
        """
        while self._is_running_fn():
            connected_at = None
            try:
                resp = self._open_stream_fn()
                with self._response_lock:
                    self._current_response = resp
                connected_at = time.monotonic()
                self._last_long_retry_kind = None
                logger.info("Push 이벤트 스트림 연결됨")

                self._consume_stream(resp)

            except requests.HTTPError as e:
                status = e.response.status_code if e.response is not None else None
                wait_sec = self._handle_http_error(status, e)
                if wait_sec is not None:
                    self._interruptible_sleep(wait_sec)
                else:
                    self._sleep_backoff()
            except requests.RequestException as e:
                logger.warning(f"Push 이벤트 스트림 연결 실패: {e}")
                self._backoff_after_disconnect(connected_at)
            except Exception as e:  # noqa: BLE001 - 리스너 스레드는 절대 죽으면 안 된다
                logger.error(f"Push 이벤트 리스너 예외(재연결 계속): {e}", exc_info=True)
                self._backoff_after_disconnect(connected_at)
            finally:
                with self._response_lock:
                    if self._current_response is not None:
                        try:
                            self._current_response.close()
                        except Exception:
                            pass
                        self._current_response = None

        logger.info("Push 이벤트 리스너 종료")

    def _consume_stream(self, resp) -> None:
        """열린 스트림에서 줄 단위로 읽으며 wake/ready를 처리한다.

        연결이 끊기거나(iter_lines 종료) is_running_fn()이 False가 되면
        반환한다. 정상적으로 한 번이라도 붙었던 연결이므로, 반환 시(또는 읽기
        중 예외 시) _backoff_after_disconnect로 다음 백오프를 계산한다.
        """
        connected_at = time.monotonic()
        try:
            for line in resp.iter_lines():
                if not self._is_running_fn():
                    break
                if not line:
                    # SSE 이벤트 구분용 빈 줄 — 무시
                    continue
                self._handle_line(line)
        except requests.RequestException as e:
            logger.warning(f"Push 이벤트 스트림 읽기 실패: {e}")

        self._backoff_after_disconnect(connected_at)

    def _handle_line(self, line) -> None:
        """SSE 한 줄을 관대하게 판정한다.

        `event:wake`(공백 없음)와 `event: wake`(공백 있음) 둘 다 인식한다.
        SSE 주석(`:`로 시작, 예: 서버 하트비트), `data:` 줄에는 반응하지 않는다.
        `event: ready`는 연결이 실제로 성립했다는 증거이므로 백오프 카운터를
        초기화한다.
        """
        text = line.decode("utf-8", "replace").strip() if isinstance(line, bytes) else line.strip()
        if not text or text.startswith(":"):
            return
        if not text.startswith("event:"):
            return

        event_name = text[len("event:"):].strip()
        if event_name == "wake":
            logger.info("Push wake 수신 — 폴링을 즉시 깨움")
            self._on_wake()
        elif event_name == "ready":
            logger.debug("Push 이벤트 스트림 ready — 백오프 초기화")
            self._backoff = INITIAL_BACKOFF_SEC

    def _handle_http_error(self, status, exc: requests.HTTPError):
        """404/405/401/403을 긴 재시도로 분기한다. 그 외 상태 코드는 None을
        반환해 일반 백오프로 처리하게 한다."""
        if status in (404, 405):
            kind = "not_found"
            if self._last_long_retry_kind != kind:
                logger.warning(
                    f"Push 이벤트 엔드포인트 없음(status={status}) — 서버가 구버전일 수 있다. "
                    f"{LONG_RETRY_SEC:.0f}초마다 재시도(그동안은 30초 폴링만으로 동작)"
                )
                self._last_long_retry_kind = kind
            return LONG_RETRY_SEC
        if status in (401, 403):
            kind = "unauthorized"
            if self._last_long_retry_kind != kind:
                body = exc.response.text[:200] if exc.response is not None else ""
                logger.error(
                    f"Push 이벤트 인증 거부(status={status}) — 토큰 폐기 또는 서버 설정 누락 "
                    f"가능성. body={body!r}. {LONG_RETRY_SEC:.0f}초마다 재시도"
                )
                self._last_long_retry_kind = kind
            return LONG_RETRY_SEC
        return None

    def _backoff_after_disconnect(self, connected_at) -> None:
        """연결이 끊긴 뒤 다음 백오프를 계산하고 대기한다.

        60초 이상 연결이 살아 있었으면(STABLE_CONNECTION_SEC) 정상적으로 잘
        붙던 연결이 잠깐 끊긴 것으로 보고 백오프를 초기값으로 리셋한다.
        """
        if connected_at is not None and (time.monotonic() - connected_at) >= STABLE_CONNECTION_SEC:
            self._backoff = INITIAL_BACKOFF_SEC
        self._sleep_backoff()

    def _sleep_backoff(self) -> None:
        """지수 백오프: 1, 2, 4, 8, 16, 30(상한)초, ±20% 지터로 대기한 뒤,
        다음 백오프 값(2배, 상한 클램프)을 인스턴스 상태에 반영한다."""
        backoff = self._backoff
        jitter = backoff * random.uniform(-0.2, 0.2)
        wait_sec = max(0.0, backoff + jitter)
        logger.debug(f"Push 이벤트 재연결 대기: {wait_sec:.1f}초")
        self._interruptible_sleep(wait_sec)
        self._backoff = min(backoff * 2, MAX_BACKOFF_SEC)

    def _interruptible_sleep(self, wait_sec: float) -> None:
        """is_running_fn()을 주기적으로 체크하며 대기한다 — stop() 직후 최대
        1초 이내에 루프가 종료 조건을 재확인하게 한다(전체 대기를 한 번에
        sleep_fn(wait_sec)로 하면 그 시간만큼 stop()이 지연될 수 있다)."""
        remaining = wait_sec
        step = 1.0
        while remaining > 0 and self._is_running_fn():
            self._sleep_fn(min(step, remaining))
            remaining -= step

    def stop(self):
        """실행 중인 응답 스트림을 닫아 run()의 iter_lines()를 최대한 빨리 깨운다.

        다른 스레드에서의 close() 호출이 iter_lines()를 즉시 깨우는지는 보장
        못 한다(urllib3 동작에 따라 즉시 안 깨어날 수 있다 — read_timeout_sec가
        최종 보장선이다). is_running_fn()을 루프 조건과 내부 대기 지점
        (_interruptible_sleep) 양쪽에서 체크하므로, close()가 안 먹혀도 최악의
        경우 read_timeout_sec 안에는 풀린다.
        """
        with self._response_lock:
            if self._current_response is not None:
                try:
                    self._current_response.close()
                except Exception:
                    pass
