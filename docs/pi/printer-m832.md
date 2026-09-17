# M832 드라이버 (`/pi/printer/m832`)

> Phomemo M832(300dpi, 110mm 연속 롤)용 드라이버. **이 저장소에서 m832 프로토콜을 아는 유일한 코드**다.
> 코드는 `~/Data/detox-printer`에서 실물 검증된 것을 **복사해 정리**한다(의존성·서브모듈로 연결하지 않는다).
> 이 문서는 M1(드라이버 이식 + USB transport)을 시작하는 세션이 이것만 읽고 착수할 수 있게 쓴다.
>
> 표기: [확인됨·실물] 프린터로 인쇄해 눈으로 확인 / [확인됨·필터출력] 벤더 필터 출력 바이트로 확인 / [확인됨·소스] 벤더 필터 소스 분석 / [미검증] / [추정]

## 1. 원칙

- **detox-printer 파일은 읽기만 한다. 절대 수정하지 않는다.** (detox-printer `src/` 번호 스크립트 통합·수정 금지 규칙)
- 상수를 옮길 때마다 옆에 근거 주석을 단다. 형식 예:
  `WIDTH_BYTES = 163  # 110mm 연속 롤 bCmdBMP 실측값. detox-printer m832/docs/protocol.md "110mm(w110h146) 용지 실측" 절, findings D단계·E-3 실물`
- 새 하드웨어 사실(용지 감지, 흐름 제어, BT 등)은 detox-printer에서 먼저 [확인됨]이 된 뒤에만 여기로 옮긴다([hardware-verification.md](hardware-verification.md)).
- 용지가 없을 수 있는 상태에서 래스터를 보내지 않는다. 드라이버는 용지 정책을 판단하지 않고, agent가 판단한 뒤 `print_image()`를 부른다([policy.md](policy.md)).
- USB write에는 타임아웃을 명시하고, 실패 시 예외를 삼키지 않는다.

## 2. 이식할 확정 사실

아래 경로는 모두 `~/Data/detox-printer/` 기준이다.

### 2.1 장치·USB

| 항목 | 값 | 상태 | 근거 |
|---|---|---|---|
| VID:PID | `0483:5740` | [확인됨] | `m832/docs/device-descriptor.md`, findings "0-b", "1단계" |
| 인터페이스 | Interface 0 하나, bInterfaceClass=7(Printer), SubClass 0x01, Protocol 0x02(양방향) | [확인됨] | `m832/docs/device-descriptor.md` |
| 엔드포인트 | BULK OUT `0x02`, BULK IN `0x81`, 둘 다 wMaxPacketSize=64 | [확인됨] | `m832/docs/device-descriptor.md` |
| 커널 드라이버 | `is_kernel_driver_active(0)` = False (WSL 기준) | [확인됨] | `m832/docs/device-descriptor.md` |
| 전송 순서 | (활성 시) `detach_kernel_driver` → `set_configuration` → `claim_interface(0)` → 청크 write → `release_interface` → `dispose_resources` | [확인됨·실물] | `m832/src/07_print_image.py` `do_send`, findings "E-1", "E-3" |
| 청크 크기 | 4096바이트 (libusb가 64바이트 패킷으로 분할). 281,199바이트를 재시도 없이 한 번에 전송 | [확인됨·실물] | `m832/src/07_print_image.py` `USB_CHUNK_SIZE`, findings "E-1" |
| write 타임아웃 | 5000ms | [확인됨·실물] (이 값으로 실패한 적 없음) | `m832/src/07_print_image.py` `USB_WRITE_TIMEOUT_MS` |
| 전송 후 BULK IN | 64바이트 읽기 → **무응답(타임아웃)**. 인쇄는 정상 진행 | [확인됨·무응답] | `m832/src/05_replay.py`, findings "E-1" |

### 2.2 래스터 스트림 (110mm 연속 롤)

| 항목 | 값 | 상태 | 근거 |
|---|---|---|---|
| 해상도 | 300 × 300 dpi (정사각 dot) | [확인됨·실물] 원 패턴이 정원으로 인쇄 | findings "E-3 — 대각선·원" |
| 전송 폭 | **WIDTH_BYTES = 163 (1304 dot)** | [확인됨·실물] | `m832/docs/protocol.md` "110mm(w110h146) 용지 실측", findings "D단계" |
| 높이 | 이미지 실제 높이 그대로(패딩 없음). A4/Letter의 고정 패딩은 110mm에 해당 없음 | [확인됨·소스+실물] | `m832/docs/protocol.md`, findings "E-2 재검증", "E-3" |
| 헤더 1 | `1F 11 0B` — 연속용지 | [확인됨·실물] | `m832/docs/protocol.md` |
| 헤더 2 | `1F 11 35 00` — 압축 Off | [확인됨·실물] | `m832/docs/protocol.md` |
| 비트맵 명령 | `1D 76 30 00 xL xH yL yH` — xL/xH = WIDTH_BYTES, yL/yH = 높이(줄), 리틀엔디언 16bit | [확인됨·실물] | `m832/docs/protocol.md` "bCmdBMP 필드 해석" |
| 비트맵 | 비압축, MSB-first, **1 = 검정**, 반전 없음 | [확인됨·실물] | `m832/docs/protocol.md` "비트 극성" |
| 꼬리 (110mm) | `1B 64 01`(페이지) + `1B 64 02`(작업 종료) + `1F 11 11`(findpaper) = 9바이트 | [확인됨·실물] | `m832/docs/protocol.md` 110mm 표, `m832/docs/filter-source-map.md` (c) |
| 총 길이 | 15(헤더) + 163 × 높이 + 9(꼬리) | [확인됨·실물] | findings "E-3 체커보드" (1304×652dot → 106,300바이트) |

A4(WIDTH_BYTES=288, 3바이트 꼬리)는 하루종이 범위(110mm 고정) 밖이므로 이식하지 않는다.

### 2.3 이미지 변환 (07_print_image.py 파이프라인)

| 단계 | 방식 | 상태 | 근거 |
|---|---|---|---|
| 그레이스케일·리사이즈 | `convert("L")` → 폭 1304로 비율 유지 리사이즈, `Image.LANCZOS` | **[미검증·실물]** — 실물 인쇄한 3종은 1304 폭으로 직접 그린 테스트 패턴이라 이 경로를 안 탔다. 텍스트·사진은 미테스트 | `m832/src/07_print_image.py` `load_and_prepare_image`, findings "E-3" 결론 |
| 좌우 정렬 보정 | `h_offset_mm = 2.0` → `round(2.0 / 25.4 × 300)` = **24 dot**. 흰 캔버스(255)에 x=+24로 paste, 오른쪽 24dot 잘림. 전송 폭 불변 | [확인됨·실물] 좌우 여백 대칭 약 1mm | `apply_h_offset`, findings "E-3 — 수평 정렬 보정" |
| 흑백 변환 | Pillow `convert("1")` 기본값 = Floyd–Steinberg 디더링 | **[미검증·실물]** — 실물 인쇄한 테스트 패턴은 처음부터 순수 흑백이라 디더링 결과가 원본과 같았다(findings "E-3 체커보드": 그레이스케일 없이 1bpp 패킹). 회색조 이미지·텍스트의 디더링 품질은 실물 미확인 | `dither_to_1bit` |
| 패킹 | Pillow `tobytes()`는 1=흰색 → **XOR 0xFF로 뒤집어** 1=검정 | [확인됨·실측] | `pack_bitmap`, 07 docstring "비트 극성" |
| 폭 검증 | 폭이 8의 배수인지, 길이 = WIDTH_BYTES × 높이인지 검사 후 예외 | — | `pack_bitmap`, `build_command` |

실물로 보낸 바이트(detox-printer `m832/captures/sent/`): `0002.bin` 체커보드(보정 0mm), `0004.bin` 체커보드(보정 2mm), `0005.bin` 대각선(2mm), `0006.bin` 원(2mm).

실행 환경(바이트 동일성의 전제): detox-printer `.venv` = Python 3.12.3, **Pillow 12.3.0**, pyusb 1.3.1. 디더링·리샘플링 결과는 Pillow 버전에 따라 달라질 수 있으므로 `/pi`도 **Pillow 12.3.0에 고정**한다 [추정: 버전 차이로 바이트가 달라질 수 있음]. Pi(Debian 12, Python 3.11)에서 이 버전 설치 가능 여부는 [미검증].

## 3. 이식 대상 (detox-printer `m832/src/07_print_image.py`)

| 07 함수·상수 | 옮길 곳 | 비고 |
|---|---|---|
| `VID`, `PID`, `EP_OUT`, `EP_IN`, `USB_INTERFACE`, `USB_CHUNK_SIZE`, `USB_WRITE_TIMEOUT_MS` | `transport/usb` | 근거 주석 유지 |
| `do_send`의 find/detach/set_configuration/claim/write/release/dispose | `transport/usb` | `input()` 용지 확인 프롬프트는 **옮기지 않는다**(agent의 용지 정책으로 대체) |
| `WIDTH_BYTES`, `WIDTH_DOTS`, `HDR_*`, `CMD_BMP_PREFIX`, `CMD_AFTER_*`, `FOOTER_FINDPAPER`, `_INVERT_TABLE` | `printer/m832` | 110mm 고정이므로 `page_size_is_a4_or_letter` 분기는 제거하고 9바이트 꼬리만 [기본값] |
| `load_and_prepare_image` | `printer/m832` | 입력이 파일 경로가 아니라 PNG 바이트 |
| `apply_h_offset` | `printer/m832` | 보정값은 `HARU_H_OFFSET_MM` |
| `dither_to_1bit`, `pack_bitmap`, `build_command` | `printer/m832` | 그대로 |
| `make_test_pattern` | `tests/` | 바이트 동일성 테스트 입력 |
| `next_sent_number`, `do_dry_run`, argparse `main` | 옮기지 않음 | detox-printer 파일 배치 전용. 하루종이는 agent가 `HARU_DATA_DIR/sent/`에 보관 |

detox-printer `m832/src/05_replay.py`(BULK IN 읽기, 청크 축소 로직)와 `08_print_daily.py`(fail-safe 용지 확인 자리)는 참고만 한다.

## 4. `printableWidthPx` 확정 (M1)

서버가 그릴 PNG 폭이다. 지금은 잠정 1300이고 M1에서 확정해 [printer.md](printer.md) 3절과 [../architecture.md](../architecture.md)의 프로필 예시를 고친다.

알려진 사실: 전송 폭은 1304dot(≈110.4mm)이지만, 보정 2mm를 적용해도 좌우에 약 1mm씩 여백이 남는다. **실제 인쇄 가능 폭은 1304dot보다 약 2mm(≈24dot) 좁은 것으로 보인다** [확인됨·실물 관찰, 정확한 폭은 미측정]. (findings "E-3 — 수평 정렬 보정")

후보 (둘 다 [미검증], M1에서 선택):

| 후보 | 드라이버 처리 | 장점 | 단점 |
|---|---|---|---|
| **A. 1300** | 07과 똑같이 1304로 리사이즈 → 24dot 오른쪽 이동(오른쪽 24dot 잘림) | 07 파이프라인과 동일해 검증 부담이 적음 | 오른쪽 끝 약 2mm가 잘리고, 1300→1304 리샘플링으로 텍스트가 약간 흐려질 수 있음 |
| **B. 1280** (= 1304 − 24) | 리사이즈 없이 x=24에 붙이고 오른쪽 흰색 채움 | 잘림·리샘플링 없음, 서버가 그린 픽셀 그대로 | 07과 다른 경로라 별도 실물 확인 필요 |

확정 방법: 전체 폭 테두리와 mm 눈금이 있는 패턴을 후보별로 실물 인쇄해 좌우 끝이 보이는지 확인한다. **이 측정은 하드웨어 사실이므로 detox-printer 규칙(findings 기록, 보낸 바이트 저장)으로 먼저 기록**하고 결과를 여기로 옮긴다.

## 5. 용지 상태 (`status()`)

- 현재 M832는 용지 유무를 알 방법이 없다. `1F 11 11`(findpaper)를 대용량 전송 **후** 읽었을 때 무응답이었다 [확인됨·무응답].
- 조회 **직후** 읽기, 다른 상태 조회 명령 후보(M835 사례 `A8/A9`, `98/99`)는 `~/Data/detox-printer/m832/docs/findings.md`의 H4 실험([hardware-verification.md](hardware-verification.md), `.temp/01-orangepi-poc-작업지시서-v1.4.md` §3.3)에서 확인한다 [미검증].
- **H4 통과 전 `status()`의 용지 상태는 항상 `unknown`**을 돌려준다. H4 결과로 명령·응답 바이트가 [확인됨]이 되면 여기에 표로 추가하고 구현한다.

## 6. 미검증 목록

| 항목 | 현재 | 확인 위치 |
|---|---|---|
| 용지 있음/없음 감지 | 방법 없음 | H4 ([hardware-verification.md](hardware-verification.md)) |
| 자동 꺼짐 (충전기만 연결 시) | USB 호스트 연결 시 1시간+ 안 꺼짐만 관찰(V0), 충전기만 연결 8시간 생존은 [확인됨·실물] V1 | — 해소됨(V1) |
| **Bluetooth 전송 (SPP/RFCOMM 채널 1)** | **[확인됨·실물] V2 통과 — USB와 같은 바이트로 같은 출력물, 상세는 [transport.md](transport.md) 3절** | — 해소됨(V2) |
| 빽빽한 텍스트 줄 누락 / 흐름 제어 | 미테스트 | H5 |
| 파일 이미지(텍스트·사진) 경로 인쇄 품질 | [확인됨·실물, 2026-09-17] 텍스트+그레이데이션+체커보드 혼합 PNG, `M832Printer` 전체 파이프라인(디더링·h-offset 포함)으로 BT 전송, 왜곡·반전 없이 정상 인쇄 확인(M5 실물 인쇄, `setup.md` 9절). detox-printer 기준 바이트 단위 비교(M1 통과 조건 1)는 별개로 여전히 미실시 | — |
| 실제 인쇄 가능 폭 | 약 1304 − 24dot로 추정 | 4절 |
| 높이 제약(최소 높이, 8의 배수 정렬, 최대 길이) | 652줄은 정상. 그 외 미확인 | M1·M4 중 필요 시 |
| 농도 명령(`1F 11 02 xx`) | 필터 출력에 없음(기본 농도로 인쇄됨) | 필요해지면 detox-printer에서 |

## 7. M1 절차와 통과 조건

### 절차

1. `printer/m832`, `transport/usb`, `printer/fake`, 단위 테스트를 작성한다(3절 표대로). 의존성: Python, Pillow 12.3.0, pyusb [기본값].
2. **기준 바이트 만들기**: detox-printer에서 `07_print_image.py`를 dry-run으로 실행한다(USB 미접촉, 출력은 detox-printer `m832/captures/filter/dryrun_<이름>.bin`).
   - 입력 1: `--test-pattern checkerboard` (기본 보정 2.0mm). 먼저 이 dry-run 출력이 실물로 보낸 `captures/sent/0004.bin`과 같은지 확인한다 — 같으면 "실물로 인쇄된 바이트"를 기준으로 삼을 수 있다 [미검증: 07이 그 뒤 수정됐는지 모름].
   - 입력 2: 임의 PNG 1장(텍스트가 들어간 그레이스케일 이미지 권장).
3. 기준 bin과 입력 PNG를 `pi/tests/fixtures/`에 복사하고, 생성 명령·sha256·Pillow 버전을 같은 폴더의 README에 적는다 [기본값].
4. `/pi` 드라이버로 같은 입력을 변환해 기준 bin과 **바이트 단위로 비교**하는 테스트를 만든다.
5. **M5에서 BT로 실물 1회 인쇄** ([transport.md](transport.md) 3절 확정값 — SPP/RFCOMM 채널 1) — **완료(2026-09-17)**: Pi에서 M832 페어링·`trust` → `pi/transport/bt.py` 작성 → `.env`를 `HARU_TRANSPORT=bt`로 전환 → **사용자가 용지 장착을 눈으로 확인한 뒤** `M832Printer`+`BtTransport`를 직접 호출해 전송, 육안 확인. 보낸 바이트는 `HARU_DATA_DIR/sent/`에 보관. **단, 앱 "지금 인쇄"(에이전트 실행기 → 서버 결과 업로드 체인)를 통한 경로는 아니다 — 그 체인은 여전히 미검증**([agent.md](agent.md) 11절)
6. 4절 방법으로 `printableWidthPx`를 확정하고 문서를 고친다.

### 통과 조건 (init_plan 10절)

1. 같은 입력(체커보드 테스트 패턴 + 임의 PNG 1장)에 대해 detox-printer `07_print_image.py` dry-run 출력과 `/pi` 출력이 **바이트 단위 동일** — 미실시
2. 용지 확인 후 **Pi에서 BT로 실물 1회 인쇄 육안 확인**(위 5) — **완료(2026-09-17)**, 보낸 bin 보관(단, 드라이버·전송 계층 직접 호출 경로 — 에이전트 실행기 경로는 별개로 미검증)
3. `printableWidthPx` 확정·문서화 — 미실시(현재 잠정 1300 유지)

통과 전에는 M4(에이전트)에서 실제 프린터를 쓰지 않는다. 에이전트 개발은 `printer/fake`로 먼저 한다.
