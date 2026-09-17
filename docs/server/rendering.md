# 렌더링: 포맷 → 그레이스케일 PNG

> 결정 원본: [`../init_plan.md`](../init_plan.md) Q10(서버가 용지 PNG 렌더), 6.3·6.4절, 8.2절.
> 렌더러 구현 방식(Playwright for Java + Chromium), 폰트, 주기·정리 규칙은 **[기본값]**. 브라우저 동작 세부는 **[미검증]**으로 표시하고 M2에서 확인한다.

## 1. 경계

- 서버가 만드는 것: **`printableWidthPx` 폭의 그레이스케일 PNG**. 높이는 내용 길이(110mm 연속 롤).
- 서버가 하지 않는 것: 좌우 정렬 보정(h-offset), 1304dot 패딩, M832 헤더·꼬리. **Pi(기기) 드라이버 몫**이다.
- **추가 예정(2026-09-17 승인, 미구현)**: 2단계 MCU 기기용으로 같은 렌더의 **1-bpp PBM P4**(서버 Floyd–Steinberg, 1=검정, MSB-first, 행 바이트 패딩)도 낸다 — 규약은 [`../architecture.md`](../architecture.md) 3.4. 디더링만 서버로 오고, 프린터 상수는 여전히 서버에 없다.
- 서버는 프린터 모델을 모른다. Pi가 poll로 보고한 **프린터 프로필**만 쓴다.

```json
{ "model": "m832", "dpi": 300, "paperWidthMm": 110, "printableWidthPx": 1300 }
```

- `printableWidthPx`는 잠정 1300이다. M1에서 detox-printer `07_print_image.py`(WIDTH_DOTS=1304, h-offset 2mm) 기준으로 확정되어 Pi가 보고한다. **서버 코드에 1300을 박지 않는다.** 예외: Pi가 한 번도 보고하지 않았을 때의 기본 프로필([`api.md`](api.md) 4.1절).
- `profile_key` = `{model}-{dpi}-{paperWidthMm}-{printableWidthPx}` (예: `m832-300-110-1300`, 규약 [`../architecture.md`](../architecture.md) 3.4). 프로필이 바뀌면 렌더를 새로 만든다.

## 2. 파이프라인

```
포맷 JSON + 에셋 + 설정
  → (1) 변수 치환(targetDate)
  → (2) 동적 데이터 조회(날씨, Java에서)
  → (3) 서버 템플릿으로 HTML 생성(모든 텍스트 이스케이프, 이미지·폰트 인라인)
  → (4) Chromium 스크린샷(JS 끔, 네트워크 차단)
  → (5) Java에서 8비트 그레이스케일 변환 + 폭 검증
  → (6) renders/{renderId}.png 저장 + sha256 + renders 행
```

### (1) 변수 치환

- `text` 블록의 `{{date}}`, `{{weekday}}`, `dateHeader`의 `pattern` 토큰을 **targetDate(KST)** 로 치환([`format-schema.md`](format-schema.md) 6절).
- 예약 렌더는 occurrence 날짜, 지금 인쇄는 KST 오늘, 미리보기는 `?date=` 또는 KST 오늘.

### (2) 동적 데이터

- 날씨는 **Java 코드가 HTML을 만들기 전에** 조회한다([`weather.md`](weather.md)). Chromium은 네트워크를 쓰지 않는다.
- 조회 실패해도 렌더 전체를 실패시키지 않는다(날씨 블록에 실패 표시).

### (3) HTML 생성 [기본값]

- 템플릿 엔진: Thymeleaf(자동 이스케이프) 또는 동등한 이스케이프 보장 방식. **사용자 텍스트를 HTML로 해석하는 경로가 없어야 한다.**
- 줄바꿈: `white-space: pre-wrap`. 한글 줄바꿈: `word-break: keep-all; overflow-wrap: anywhere`.
- 이미지: 에셋 파일을 읽어 `data:` URI로 인라인. 폭 = 본문 폭 × `widthPercent`, 비율 유지, `style.align`으로 정렬.
- 구분선(`divider`): 블록 사이 `border-top`(`line` = 실선, `dashed` = 점선), 두께 [기본값] 0.3mm.
- CSS는 템플릿에 고정된 것만. 포맷의 스타일 값은 화이트리스트 검증을 통과한 숫자·enum만 CSS로 옮긴다.

### 단위 환산

CSS px = 장치 px로 맞춘다(뷰포트 폭 = `printableWidthPx`, `deviceScaleFactor = 1`).

| 변환 | 식 | 예(300dpi) |
|---|---|---|
| mm → px | `mm / 25.4 × dpi` | 3mm → 35.4px |
| pt → px | `pt / 72 × dpi` | 11pt → 45.8px |

- 본문 폭 px = `printableWidthPx − (marginMm.left + marginMm.right) 환산값`
- 반올림 규칙 [기본값]: 여백·간격은 `Math.round`, 글자 크기는 소수 px 그대로(CSS가 처리)

### (4) Chromium 스크린샷 [기본값]

- **Playwright for Java**. `Browser`는 애플리케이션 시작 시 1개 띄워 재사용하고, 렌더마다 `BrowserContext`를 새로 만들고 닫는다.
- 컨텍스트 옵션: `setJavaScriptEnabled(false)`, `setViewportSize(printableWidthPx, 100)`, `setDeviceScaleFactor(1)`.
- **네트워크 전부 차단**: `context.route("**/*", route -> route.abort())`로 모든 요청을 막고, 콘텐츠는 `page.setContent(html)`로 넣는다. 이미지는 `data:` URI라 요청이 생기지 않는다. `file://`, `http(s)://` 전부 차단. (`data:` URI가 route를 거치는지 여부 [미검증] — M2에서 확인하고 막히면 `data:`만 허용하도록 조정)
- 스크린샷: `page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setType(PNG))`.
- 폰트 로딩: JS를 끈 상태라 `document.fonts.ready`를 기다릴 수 없을 수 있다 [미검증]. 그래서 **폰트는 컨테이너에 시스템 폰트로 설치**(fontconfig)해 로딩 대기가 필요 없게 한다 [기본값].
- 렌더 1건 타임아웃 30초 [기본값]. 동시 렌더는 **1개(직렬 큐)** [기본값] — 사용자 1명 규모, 메모리 절약.
- 최대 높이 [기본값]: 1000mm 환산 px(300dpi 기준 11,811px). 넘으면 렌더 실패(500, `detail`에 이유).

### (5) 그레이스케일 변환

- Chromium PNG는 RGB(A)다. Java에서 `BufferedImage.TYPE_BYTE_GRAY`로 변환해 8비트 그레이스케일 PNG로 저장 [기본값]. 투명 영역은 흰색으로 합성.
- **폭이 정확히 `printableWidthPx`인지 검증**한다. 다르면 실패 처리(조용히 리사이즈하지 않는다).

### (6) 저장

- `haru-files/renders/{renderId}.png`, 파일 sha256, `renders` 행([`data-model.md`](data-model.md)). 파일 쓰기 후 행 커밋.

## 3. 폰트 [기본값]

- **Pretendard**, **Noto Sans KR** — 둘 다 SIL Open Font License 1.1. 포맷 `style.fontFamily` enum과 1:1.
- Docker 이미지 빌드 때 **버전 고정된 릴리스 파일**을 받아 설치하고, 라이선스 파일을 이미지 안에 함께 둔다.
- 이모지·컬러 폰트는 넣지 않는다(감열지 흑백, v1 날씨는 텍스트만).

## 4. 렌더 스케줄러 [기본값]

`@Scheduled(fixedDelay = 5분)` + 포맷·예약·설정·프로필 변경 시 즉시 1회 트리거.

매 실행:

1. 현재 `profile_key` 결정(없으면 기본 프로필)
2. **켜진 예약**의 occurrence 중 **지금부터 36시간 안**의 것을 KST로 계산
3. 각 occurrence의 `(formatId, targetDate)`에 대해
   - 최신 렌더가 없거나, `format_updated_at < formats.updated_at`(포맷이 바뀜)이거나, `profile_key`가 다르면 → 렌더
4. **동적 포맷**(`has_dynamic_blocks = true`)은 occurrence **약 60분 전**에 한 번 더 렌더
   - 조건: occurrence까지 60분 이하로 남았고, 최신 렌더의 `rendered_at`이 `occurrence − 60분`보다 이전
   - 날씨 설정(위치)이 바뀌어도 동적 포맷은 다시 렌더

즉시 렌더(스케줄러를 거치지 않음):
- **미리보기** `GET /api/formats/{id}/preview.png` → `kind=preview`
- **편집본 미리보기** `POST /api/formats/preview` → 렌더 결과를 바로 응답하고 **행·파일을 남기지 않는다** [기본값]
- **지금 인쇄** `POST /api/print-now` → `kind=command`, targetDate = KST 오늘

## 5. 렌더 파일 정리 [기본값]

하루 한 번(예: 03:30 KST) 실행:

- `kind=preview`: 24시간 지난 것 삭제
- `kind=command`: 해당 명령이 `done`/`expired`가 되고 24시간 지난 것 삭제
- `kind=scheduled`: `target_date < KST 오늘 − 7일` 삭제
- **단, 포맷마다 현재 `profile_key`의 가장 최근 렌더 1개는 항상 남긴다**(Pi 오프라인 폴백, 스냅샷의 "날짜 무관 최신 렌더")
- 행과 파일을 같이 지운다. 파일만 남거나 행만 남은 고아는 로그 경고 후 정리

## 6. 컨테이너 주의

- Playwright for Java는 기본 동작에서 **실행 시점에 브라우저를 내려받으려 한다.** 운영에서는 막아야 한다.
  - [기본값] 런타임 베이스 이미지를 공식 `mcr.microsoft.com/playwright/java`(Playwright 의존성 버전과 **같은 태그로 고정**)로 쓰고, `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`로 추가 다운로드를 막는다.
- 이미지 크기: Chromium 포함 약 1~2GB [추정]. 서버 디스크 여유는 충분(1.8TB 중 12% 사용).
- 메모리: 렌더 중 Chromium이 수백 MB 사용 [추정]. `haru-api`에 `mem_limit` [기본값] 1.5g, `shm_size` 1g(Chromium `/dev/shm` 부족 크래시 방지).
- 컨테이너 안 Chromium 샌드박스: 공식 이미지 권장 설정을 따르되, 가능하면 샌드박스를 끄지 않는다 [미검증 — M2에서 확인].
- 비루트 사용자로 실행.
- `haru-api`는 날씨 조회를 위해 **외부 인터넷 출구가 필요**하다. Chromium의 네트워크 차단은 컨텍스트 route로 하므로 컨테이너 네트워크와 무관하다.

## 7. M2에서 확인할 것

- [x] 미리보기 PNG 폭 = 프로필 `printableWidthPx` **[확인됨, 2026-09-15]** 1300px 고정 확인(Pretendard/Noto Sans KR/dateHeader 3종 포맷)
- [x] 한글(Pretendard/Noto Sans KR) 정상, 줄바꿈 자연스러움 **[확인됨, 2026-09-15]** 깨진 글자 없음, `word-break: keep-all` 정상 동작
- [x] `{{date}}`/`{{weekday}}`/`dateHeader`가 targetDate 기준 **[확인됨, 2026-09-15]** 여러 날짜(월·연 경계 포함)로 교차검증
- [x] 외부 URL을 억지로 넣은 HTML이 템플릿 경로로 들어갈 수 없음(텍스트에 `<img src=http://...>` 입력 → 문자 그대로 인쇄) **[확인됨, 2026-09-15]** `HtmlTemplateBuilder.escapeHtml()`이 이스케이프
- [x] 네트워크 차단 route 동작, `data:` 이미지 표시 **[확인됨, 2026-09-15]** `PlaywrightRenderer`가 `data:`만 통과시키고 나머지 스킴은 차단
- [x] JS 끈 상태에서 폰트가 스크린샷에 반영됨 **[확인됨, 2026-09-15]** 컨테이너 시스템 폰트 설치 방식으로 해결, 폰트 로딩 대기 불필요함 확인
- [x] 동적 포맷 60분 전 재렌더가 스냅샷 `renders`에 반영되어 `snapshotChanged` **[확인됨, 2026-09-15]** 경계조건 단위테스트 8종 + 통합 시나리오로 검증(`RenderScheduler`에 `ClockProvider` 도입해 시간 고정 테스트 가능하게 함)
