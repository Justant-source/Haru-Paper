# 프린터 공통 인터페이스 (`/pi/printer`)

> 하루종이가 여러 프린터를 지원할 수 있게 하는 경계. 지금 구현은 M832(`printer/m832`)와 가짜 프린터(`printer/fake`) 둘뿐이다.
> 최초 결정: [../init_plan.md](../init_plan.md) 6.4, 8.1 (Q10·Q21). 코드는 M1에서 작성(현재 없음).

## 1. 책임 경계

```
서버 ──(그레이스케일 PNG, 폭 = printableWidthPx)──▶ agent ──▶ Printer.print_image(png) ──▶ Transport.write(bytes) ──▶ 프린터
```

| 계층 | 아는 것 | 모르는 것 |
|---|---|---|
| 서버 | 프린터 프로필(폭 px, dpi, 용지 폭 mm), 레이아웃·콘텐츠 | 프로토콜, 디더링, 정렬 보정 |
| agent (`/pi/agent`) | 언제 무엇을 인쇄할지, 용지 정책, 결과 기록 | 프로토콜 바이트 |
| 드라이버 (`/pi/printer/<model>`) | **PNG → 프린터 바이트 변환 전부**: 리사이즈/흑백 변환(디더링)/좌우 정렬 보정/전송 폭 패딩/비트 패킹/헤더·꼬리 조립, 상태 조회 해석 | 콘텐츠, 예약, 네트워크 |
| transport (`/pi/transport`) | 바이트를 USB/BT로 보내고 받기, 청크·타임아웃 | 바이트의 의미 |

- 서버가 보내는 PNG는 **그레이스케일**이고 흑백이 아니다. 디더링 방식·임계값은 프린터(헤드·용지) 특성이므로 드라이버가 정한다.
- 좌우 정렬 보정(M832는 `HARU_H_OFFSET_MM=2.0`)도 프린터 개체 특성이라 드라이버 몫이다.
- 드라이버는 transport를 주입받는다. 드라이버가 USB인지 BT인지 알 필요가 없게 한다(단, 흐름 제어가 필요해지면 `read`를 쓸 수 있다 — [transport.md](transport.md)).

## 2. 인터페이스 초안

M1에서 확정한다. 아래는 이름·책임 수준의 초안이다 [기본값].

| 메서드 | 반환 | 설명 |
|---|---|---|
| `profile()` | `PrinterProfile` | 서버에 보고할 프로필. 폴링 요청의 `printerProfile`로 그대로 나간다 |
| `status()` | `PrinterStatus` | 연결 가능 여부, 용지 상태(`present` / `absent` / `unknown`), 커버, 오류. **H4 통과 전 M832는 용지 상태가 항상 `unknown`** |
| `print_image(png_bytes)` | `PrintOutcome` | PNG를 변환해 전송. 보낸 바이트(또는 그 경로)와 크기를 돌려줘 agent가 `sent/`에 보관할 수 있게 한다. 전송 오류는 **삼키지 않고** 예외로 올린다 |

- `print_image`는 용지 정책을 판단하지 않는다. 용지 정책은 agent가 `status()`와 서버 상태를 보고 판단한 뒤 호출한다([policy.md](policy.md)).
- **서버 보고용 요약**: agent는 `PrinterStatus`를 poll 요청의 `printerStatus` `{state, detail}`로 줄여 보낸다. `state` 값의 원본은 [../architecture.md](../architecture.md) 4.3(`ok | offline | error | unknown`). 매핑 [기본값]:

  | `PrinterStatus` | `state` |
  |---|---|
  | 연결 불가(장치 없음, BT 연결 실패) | `offline` |
  | 연결은 되지만 오류 보고 | `error` |
  | 연결 가능, 오류 없음 | `ok` (용지 상태가 `unknown`이어도 `ok` — H4 통과 전 M832는 항상 이 경우) |
  | 아직 확인하지 않음(시작 직후 등) | `unknown` |

  H4 통과 후 용지 `absent`는 `no_paper`, 커버 열림은 `cover_open`으로 보고하도록 규약에 추가할 예정이다.
- 드라이버 선택은 설정으로 한다. PoC는 `m832` 고정, 개발 중에는 `fake`.

## 3. 프린터 프로필

Pi가 폴링할 때마다 보고하고, 서버는 이 값으로만 렌더 폭을 정한다. 필드 정의의 원본은 [../architecture.md](../architecture.md).

```json
{ "model": "m832", "dpi": 300, "paperWidthMm": 110, "printableWidthPx": 1300 }
```

| 필드 | 의미 | M832 값 |
|---|---|---|
| `model` | 표시용 모델명. 서버는 이 값으로 분기하지 않는다 | `m832` |
| `dpi` | 서버가 mm/pt 단위를 px로 환산할 때 쓴다 | 300 [확인됨·실물: 원 패턴이 정원으로 인쇄] |
| `paperWidthMm` | 용지 폭 | 110 (110mm 연속 롤 고정) |
| `printableWidthPx` | 서버 PNG 폭 | **잠정 1300 — M1에서 확정** ([printer-m832.md](printer-m832.md) 4절) |

## 4. 가짜 프린터 (`printer/fake`)

프린터 없이 에이전트(M4)를 개발·테스트하기 위한 구현.

- `profile()`: M832와 같은 프로필을 돌려준다(서버 렌더 폭을 실제와 맞추기 위해) [기본값]
- `status()`: 설정으로 용지 `present`/`absent`/`unknown`, 연결 실패를 흉내 낼 수 있게 한다 — M4 통과 조건의 실패 경로 테스트용
- `print_image()`: 받은 PNG와, M832 드라이버의 변환 결과 bin(선택)을 `HARU_DATA_DIR/fake/` 아래 파일로 저장만 한다. **transport를 열지 않는다**
- 결과는 실제 인쇄와 똑같이 이력·`sent/` 보관 흐름을 탄다

## 5. 다른 프린터를 추가하는 방법

1. **실험실 먼저**: 새 프린터의 프로토콜은 detox-printer와 같은 방식(별도 실험 프로젝트 또는 detox-printer 하위)으로 실물 검증한다. 다른 기종에서 되니까 될 것이라고 가정하지 않는다.
2. `/pi/printer/<model>/`을 만들고 2절 인터페이스를 구현한다. 상수마다 실험 기록 근거 주석을 단다.
3. `profile()`에 그 프린터의 `dpi`, `paperWidthMm`, `printableWidthPx`를 넣는다. 서버·앱은 수정할 필요가 없어야 한다(203dpi 프린터면 서버가 같은 포맷을 203dpi 폭으로 렌더).
4. 필요한 transport가 없으면 `/pi/transport/`에 추가한다.
5. `docs/pi/printer-<model>.md`를 이 문서의 M832 문서와 같은 구조로 작성한다.
6. 서버의 포맷 스키마는 mm/pt 단위라 dpi와 무관하다. 용지 폭이 크게 다르면 레이아웃이 달라질 수 있다는 점만 [../architecture.md](../architecture.md)에 기록한다.

PoC 범위는 **Pi 1대 = 프린터 1대, 프로필 1개**다. 한 Pi에 여러 프린터를 붙이는 구조는 만들지 않는다.
