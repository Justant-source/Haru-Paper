"""PushListener(agent/events.py) 단위 테스트. 네트워크 없이 가짜 스트림 객체로
검증한다. docs/architecture.md의 GET /api/device/events(SSE)에 대응하는
Pi 쪽 수신기 — 이 모듈은 프린터·Storage를 전혀 모른다(events.py 모듈 docstring
참고). 여기서는 오직 "줄 판정 → on_wake 호출"과 "재연결·백오프 분기"만 본다.
"""

from __future__ import annotations

import threading
import time

import pytest
import requests

from agent.events import LONG_RETRY_SEC, PushListener


class FakeResponse:
    """iter_lines()가 주어진 줄 목록을 순서대로 내놓는 가짜 SSE 응답.

    close()가 불리면 이후 iter_lines()는 즉시 멈춘다(실제 요청 취소를 흉내).
    """

    def __init__(self, lines, status_code=200):
        self._lines = lines
        self.status_code = status_code
        self.closed = False

    def iter_lines(self):
        for line in self._lines:
            if self.closed:
                return
            yield line

    def close(self):
        self.closed = True


class BlockingResponse:
    """stop()이 불릴 때까지 끝나지 않는 가짜 스트림(케이스 6 전용).

    실제 SSE 연결처럼 "닫히기 전까지는 계속 열려 있다"를 흉내낸다. 주기적으로
    무해한 주석 줄(`:hb`)을 내놓아 _consume_stream의 for 루프가 살아있게 한다.
    """

    def __init__(self):
        self.status_code = 200
        self.closed = False

    def iter_lines(self):
        while not self.closed:
            time.sleep(0.05)
            yield b":hb"

    def close(self):
        self.closed = True


def _make_is_running(times_true: int):
    """앞의 `times_true`번은 True, 그 뒤로는 False를 돌려주는 is_running_fn.

    run()의 정확한 내부 호출 횟수(줄 수·백오프 대기 청크 수에 따라 달라짐)에
    의존하지 않고, "충분히 여유 있게 True를 주고 그 다음엔 확실히 멈춘다"는
    방식으로 테스트를 유한 시간 안에 끝낸다."""
    state = {"n": 0}

    def fn():
        state["n"] += 1
        return state["n"] <= times_true

    return fn


def _http_error(status_code: int, text: str = "") -> requests.HTTPError:
    class _FakeErrorResponse:
        def __init__(self, status_code, text):
            self.status_code = status_code
            self.text = text

    return requests.exceptions.HTTPError(
        f"{status_code} error", response=_FakeErrorResponse(status_code, text)
    )


class TestWakeLineDetection:
    """줄 판정: event:wake / event: wake 인식, 그 외 줄은 무시."""

    def test_wake_with_and_without_space_triggers_callback(self):
        lines = [b"event:wake", b"event: wake"]
        wake_calls = []
        open_calls = {"n": 0}

        def open_stream_fn():
            open_calls["n"] += 1
            if open_calls["n"] == 1:
                return FakeResponse(lines)
            # 재연결이 일어나도(테스트 종료 조건이 정확히 맞아떨어지지 않는
            # 경우를 대비) 두 번째 연결부터는 빈 스트림이라 on_wake가 다시
            # 늘지 않는다 — wake_calls 개수가 재연결 횟수에 흔들리지 않는다.
            return FakeResponse([])

        listener = PushListener(
            open_stream_fn=open_stream_fn,
            on_wake=lambda: wake_calls.append(1),
            is_running_fn=_make_is_running(30),
            read_timeout_sec=45,
            sleep_fn=lambda _sec: None,
        )

        listener.run()

        assert wake_calls == [1, 1]
        assert open_calls["n"] >= 1

    def test_comment_blank_data_ready_lines_never_wake(self):
        lines = [b":hb", b"", b"data:{}", b"event: ready"]
        wake_calls = []
        open_calls = {"n": 0}

        def open_stream_fn():
            open_calls["n"] += 1
            if open_calls["n"] == 1:
                return FakeResponse(lines)
            return FakeResponse([])

        listener = PushListener(
            open_stream_fn=open_stream_fn,
            on_wake=lambda: wake_calls.append(1),
            is_running_fn=_make_is_running(30),
            read_timeout_sec=45,
            sleep_fn=lambda _sec: None,
        )

        listener.run()

        assert wake_calls == []


class TestReadyResetsBackoff:
    def test_ready_then_disconnect_reconnects_with_small_backoff(self):
        """event: ready를 받은 뒤 스트림이 끊기면, 재연결까지 기다리는 시간이
        누적 백오프로 커져 있지 않고 초기값(약 1초, 지터 포함) 근처다 — 간접
        확인: sleep_fn에 전달된 첫 대기 시간이 크게 부풀지 않았음을 본다."""
        sleep_calls = []
        open_calls = {"n": 0}

        def open_stream_fn():
            open_calls["n"] += 1
            if open_calls["n"] == 1:
                return FakeResponse([b"event: ready"])
            return FakeResponse([])

        listener = PushListener(
            open_stream_fn=open_stream_fn,
            on_wake=lambda: None,
            is_running_fn=_make_is_running(30),
            read_timeout_sec=45,
            sleep_fn=lambda sec: sleep_calls.append(sec),
        )

        listener.run()

        assert len(sleep_calls) >= 1
        # INITIAL_BACKOFF_SEC(1.0) ± 20% 지터 근처 — 절대 LONG_RETRY_SEC(600)류로
        # 튀어 있지 않음을 확인한다.
        assert sleep_calls[0] < 5.0


class TestLongRetryOnAuthAndNotFound:
    def test_404_triggers_long_retry_near_600s(self):
        """404(엔드포인트 없음)는 짧은 지수 백오프 대신 600초 근처의 긴 재시도로
        분기된다. sleep_fn은 실제로 기다리지 않는 스텁이라 600번 호출돼도 테스트가
        느려지지 않는다 — is_running_fn을 정확히 "첫 대기가 다 끝날 때까지만"
        True로 유지해 두 번째 open_stream_fn 호출 없이 루프를 멈춘다."""
        sleep_calls = []
        open_calls = {"n": 0}

        def open_stream_fn():
            open_calls["n"] += 1
            raise _http_error(404)

        # _interruptible_sleep(600)은 step=1.0 청크로 총 600번 is_running_fn을
        # 부른다. 그 앞뒤(진입 시 1회, 각 줄 처리 없음)까지 넉넉히 True로 두고,
        # 두 번째 open_stream_fn 호출 직전(바깥 while 재확인)에 False로 바꿔
        # 재시도를 막는다.
        listener = PushListener(
            open_stream_fn=open_stream_fn,
            on_wake=lambda: None,
            is_running_fn=_make_is_running(601),
            read_timeout_sec=45,
            sleep_fn=lambda sec: sleep_calls.append(sec),
        )

        listener.run()

        assert open_calls["n"] == 1
        assert sum(sleep_calls) >= LONG_RETRY_SEC - 1
        assert sum(sleep_calls) <= LONG_RETRY_SEC

    def test_401_triggers_long_retry_near_600s(self):
        sleep_calls = []
        open_calls = {"n": 0}

        def open_stream_fn():
            open_calls["n"] += 1
            raise _http_error(401)

        listener = PushListener(
            open_stream_fn=open_stream_fn,
            on_wake=lambda: None,
            is_running_fn=_make_is_running(601),
            read_timeout_sec=45,
            sleep_fn=lambda sec: sleep_calls.append(sec),
        )

        listener.run()

        assert open_calls["n"] == 1
        assert sum(sleep_calls) >= LONG_RETRY_SEC - 1
        assert sum(sleep_calls) <= LONG_RETRY_SEC


class TestStopTerminatesPromptly:
    def test_stop_unblocks_run_within_5s(self):
        """run()을 별도 스레드로 돌리다가 stop()을 호출하면 그 스레드가 5초
        안에 join된다 — 실제 스레드로 확인하는 케이스라 sleep_fn은 진짜
        time.sleep을 쓴다(값이 작아 테스트가 느려지지 않는다)."""
        running = {"v": True}

        def is_running_fn():
            return running["v"]

        resp = BlockingResponse()

        def open_stream_fn():
            return resp

        listener = PushListener(
            open_stream_fn=open_stream_fn,
            on_wake=lambda: None,
            is_running_fn=is_running_fn,
            read_timeout_sec=45,
            sleep_fn=time.sleep,
        )

        thread = threading.Thread(target=listener.run, daemon=True)
        thread.start()
        time.sleep(0.15)  # 연결·첫 줄 소비가 자리잡을 시간

        running["v"] = False
        listener.stop()

        thread.join(timeout=5)
        assert not thread.is_alive()
