# 렌더링: 포맷 → 그레이스케일 PNG

> 결정 원본: [`../init_plan.md`](../init_plan.md) Q10(서버가 용지 PNG 렌더), 6.3·6.4절, 8.2절.
> 렌더러 구현 방식(Playwright for Java + Chromium), 폰트, 주기·정리 규칙은 **[기본값]**. 브라우저 동작 세부는 **[미검증]**으로 표시하고 M2에서 확인한다.
> **2026-09-18(위젯 그리드 v3)**: 포맷 문서가 위젯 목록으로 바뀌면서 `HtmlTemplateBuilder`는 "각 위젯을 그리고(→ `WidgetRegistry`에 등록된 `Widget` 구현) 4열 그리드로 조립"(→ `WidgetPageBuilder`)하는 역할만 한다. 날씨·에셋 같은 위젯별 데이터 조회는 이 문서가 아니라 [`widgets.md`](widgets.md)(각 위젯 구현·데이터 제공자)로 옮겨졌다 — 이 문서는 그리드 조립·Chromium 파이프라인·폰트·정리 규칙만 다룬다.

## 1. 경계

- 서버가 만드는 것: **`printableWidthPx` 폭의 그레이스케일 PNG**. 높이는 내용 길이(110mm 연속 롤).
- 서버가 하지 않는 것: 좌우 정렬 보정(h-offset), 1304dot 패딩, M832 헤더·꼬리. **Pi(기기) 드라이버 몫**이다.
- **추가됨(2026-09-17 승인, 확인됨·코드)**: 2단계 MCU 기기용으로 같은 렌더의 **1-bpp PBM P4**(서버 Floyd–Steinberg, 1=검정, MSB-first, 행 바이트 패딩)도 낸다 — 규약은 [`../architecture.md`](../architecture.md) 3.4, 구현은 아래 "(6.5) PBM 변환". 디더링만 서버로 오고, 프린터 상수는 여전히 서버에 없다.
- 서버는 프린터 모델을 모른다. Pi가 poll로 보고한 **프린터 프로필**만 쓴다.

```json
{ "model": "m832", "dpi": 300, "paperWidthMm": 110, "printableWidthPx": 1300 }
```

- `printableWidthPx`는 잠정 1300이다. M1에서 detox-printer `07_print_image.py`(WIDTH_DOTS=1304, h-offset 2mm) 기준으로 확정되어 Pi가 보고한다. **서버 코드에 1300을 박지 않는다.** 예외: Pi가 한 번도 보고하지 않았을 때의 기본 프로필([`api.md`](api.md) 4.1절).
- `profile_key` = `{model}-{dpi}-{paperWidthMm}-{printableWidthPx}` (예: `m832-300-110-1300`, 규약 [`../architecture.md`](../architecture.md) 3.4). 프로필이 바뀌면 렌더를 새로 만든다.

**"아무 기기나 하나" 폴백 제거(2026-09-17, [확인됨·코드])**: `PrinterProfileProvider.getCurrentProfile()`(인자 없음, `deviceRepository.findAll().stream().findFirst()`로 아무 기기나 골라 쓰던 메서드)는 **삭제됐다**. 조회 경로는 `getCurrentProfile(ownerUserId)` 하나뿐이다 — `ownerUserId`가 `null`이면 DB 조회 없이 `PrinterProfile.DEFAULT`를 돌려준다. 이 메서드를 부르던 5곳(`HtmlTemplateBuilder.buildHtml`, `RenderServiceImpl`의 `renderEphemeral`/`renderWithCache`/`doRender`, `RenderCleanupScheduler`)이 전부 소유자 기준으로 바뀌었다(`RenderScheduler`는 원래부터 `getCurrentProfile(ownerUserId)`로 기기별 순회를 하고 있어 대상이 아니었다). `HtmlTemplateBuilder.buildHtml(document, targetDate, profile)`도 프로필을 직접 조회하지 않고 호출자(`RenderServiceImpl`)가 이미 구한 값을 인자로 받는다 — 호출자가 쓴 프로필(소유자별)과 스크린샷 폭·CSS px 기준이 어긋나지 않게 하려는 것이다.

**운영 주의(반드시 읽을 것)**: 지금 Pi가 보고하는 프로필 `("m832", 300, 110, 1300)`은 서버 `PrinterProfile.DEFAULT`와 값이 같다. 기기 1대뿐인 지금은 이 변경으로 동작이 바뀌지 않는다. 하지만 Pi의 `printable_width_px=1300`은 `[기본값]`("M1에서 확정")일 뿐이고, **[`deploy.md`](deploy.md) 7.1절의 V4 백필 + claim-legacy를 아직 안 돌렸다면 `renders.owner_user_id`/`formats.owner_user_id`가 NULL인 레거시 행이 남아 있다.** 그 상태에서 Pi 프로필 값(`printableWidthPx` 등)을 바꾸면, NULL 소유자 렌더·포맷은 여전히 `getCurrentProfile(null)` → `PrinterProfile.DEFAULT`(옛 1300)로 비교·렌더되는 반면, 소유자가 있는 렌더·포맷은 새 프로필로 렌더돼 두 값이 어긋난다. **V4 + claim-legacy를 먼저 마쳐 NULL 소유자 행을 없앤 뒤에 프로필 값을 바꿔야 한다** — [`deploy.md`](deploy.md) 7.1절 런북(백업 → rebuild → V4 확인 → claim-legacy → 최종 검증)과 모순되지 않는다.

## 2. 파이프라인

```
포맷 JSON(위젯 목록) + 에셋 + 프린터 프로필
  → (0) FormatDocumentSupport.readDocument() — v1·v2 저장본은 여기서 v3로 up-convert
  → (1) 위젯마다: WidgetRegistry.find(type) → Widget.renderHtml(instance, ctx)
        (변수 치환·동적 데이터 조회·이스케이프는 각 위젯 구현 안에서 일어난다 — widgets.md)
  → (2) WidgetPageBuilder.build() — 위젯 HTML 조각들을 4열 그리드 한 장(HTML)으로 조립
  → (3) Chromium 스크린샷(JS 끔, 네트워크 차단)
  → (4) Java에서 8비트 그레이스케일 변환 + 폭 검증
  → (5) renders/{renderId}.png 저장 + sha256 + renders 행
  → (5.5) 1-bpp PBM(P4) 변환·저장 [확인됨·코드, 2026-09-17 승인] — 아래 참고
```

### (0)·(1) 위젯 조립 (`HtmlTemplateBuilder`, `WidgetPageBuilder`)

- `HtmlTemplateBuilder.buildHtml(document, targetDate, profile, ownerUserId)`가 문서의 `widgets[]`를 순회하며 위젯마다 `WidgetRegistry.find(instance.type())`로 구현을 찾고, `WidgetRenderContext`(대상 날짜·프린터 프로필·소유자·고른 크기·박스 px·기본 글자 크기)를 만들어 `Widget.renderHtml()`을 부른다.
- 등록되지 않은 type(옛 문서 손상 등)이거나 렌더 중 예외가 나면 **그 위젯 칸만** `WidgetHtml.errorBox`로 대체하고 나머지 위젯은 정상 렌더한다 — 위젯 하나의 실패가 인쇄 전체를 막지 않는다([`widgets.md`](widgets.md) 1.1절 구현 규칙 ②).
- 변수 치환(`{{date}}`/`{{weekday}}`/`dateHeader.pattern`)·동적 데이터 조회(날씨·아침편지·증시)·HTML 이스케이프는 전부 **각 위젯 구현 안에서** 일어난다 — 상세는 [`widgets.md`](widgets.md). 이 문서(`HtmlTemplateBuilder`)는 더 이상 그 로직을 갖지 않는다(2026-09-18 위젯 그리드 도입 전에는 여기 있었다).
- `WidgetPageBuilder.build(style, profile, cells)`가 조각들을 4열 CSS 그리드(`grid-auto-flow: row dense`, 간격 `GridSpec.GAP_MM` 고정)로 조립한 완성 HTML 문서를 만든다. 고정 크기 위젯은 `contain: size; overflow: hidden`으로 박스를 넘는 내용을 자른다.
- 줄바꿈·이스케이프·정렬 같은 위젯 내부 CSS는 각 위젯이 스스로 만든다(공통 템플릿 차원의 규칙이 아니다). 포맷 전체 `style`(`fontFamily`/`baseFontSizePt`/`lineHeight`/`marginMm`)만 `WidgetPageBuilder`가 페이지 공통 CSS로 적용한다 — `blockGapMm`·`divider`는 v3에서 무시된다([`format-schema.md`](format-schema.md) 3.1.2절).

### 단위 환산

CSS px = 장치 px로 맞춘다(뷰포트 폭 = `printableWidthPx`, `deviceScaleFactor = 1`).

| 변환 | 식 | 예(300dpi) |
|---|---|---|
| mm → px | `mm / 25.4 × dpi` | 3mm → 35.4px |
| pt → px | `pt / 72 × dpi` | 11pt → 45.8px |

- 본문 폭 px = `printableWidthPx − (marginMm.left + marginMm.right) 환산값`
- 반올림 규칙 [기본값]: 여백·간격은 `Math.round`, 글자 크기는 소수 px 그대로(CSS가 처리)

### (3) Chromium 스크린샷 [기본값]

- **Playwright for Java**. `Browser`는 애플리케이션 시작 시 1개 띄워 재사용하고, 렌더마다 `BrowserContext`를 새로 만들고 닫는다.
- 컨텍스트 옵션: `setJavaScriptEnabled(false)`, `setViewportSize(printableWidthPx, 100)`, `setDeviceScaleFactor(1)`.
- **네트워크 전부 차단**: `context.route("**/*", route -> route.abort())`로 모든 요청을 막고, 콘텐츠는 `page.setContent(html)`로 넣는다. 이미지는 `data:` URI라 요청이 생기지 않는다. `file://`, `http(s)://` 전부 차단. (`data:` URI가 route를 거치는지 여부 [미검증] — M2에서 확인하고 막히면 `data:`만 허용하도록 조정)
- 스크린샷: `page.screenshot(new Page.ScreenshotOptions().setFullPage(true).setType(PNG))`.
- 폰트 로딩: JS를 끈 상태라 `document.fonts.ready`를 기다릴 수 없을 수 있다 [미검증]. 그래서 **폰트는 컨테이너에 시스템 폰트로 설치**(fontconfig)해 로딩 대기가 필요 없게 한다 [기본값].
- 렌더 1건 타임아웃 30초 [기본값]. 동시 렌더는 **1개(직렬 큐)** [기본값] — 사용자 1명 규모, 메모리 절약.
- 최대 높이 [기본값]: 1000mm 환산 px(300dpi 기준 11,811px). 넘으면 렌더 실패(500, `detail`에 이유).

### (4) 그레이스케일 변환

- Chromium PNG는 RGB(A)다. Java에서 `BufferedImage.TYPE_BYTE_GRAY`로 변환해 8비트 그레이스케일 PNG로 저장 [기본값]. 투명 영역은 흰색으로 합성.
- **폭이 정확히 `printableWidthPx`인지 검증**한다. 다르면 실패 처리(조용히 리사이즈하지 않는다).

### (5) 저장

- `haru-files/renders/{renderId}.png`, 파일 sha256, `renders` 행([`data-model.md`](data-model.md)). 파일 쓰기 후 행 커밋.

### (5.5) PBM(1-bpp) 변환 [확인됨·코드, 2026-09-17 승인]

- `PbmConverter`(`server/render`)가 (5)에서 저장한 그레이스케일 PNG를 다시 디코드해 Floyd–Steinberg 오차확산으로 흑백 양자화하고, PBM P4(`"P4\n{width} {height}\n"` 헤더 + raw 비트맵, 1=검정 MSB-first, 행마다 `ceil(width/8)` 바이트)로 패킹한다.
- `haru-files/renders/{renderId}.pbm`에 저장하고 sha256을 `renders.pbm_sha256`·`renders.pbm_path`에 함께 커밋한다(`V3__render_pbm.sql`). **PBM 생성이 실패해도 PNG 렌더 자체는 실패시키지 않는다** [기본값] — 실패하면 두 컬럼이 `null`로 남고, 스냅샷의 `urlPbm`·`sha256Pbm`도 그 렌더에 한해 `null`이 된다.
- `RenderCleanupScheduler`가 렌더를 정리할 때 `.pbm` 파일도 같이 지운다.
- **편집본 미리보기**(`POST /api/formats/preview`, `renderEphemeral`)는 PNG 바이트만 즉시 반환하고 파일·행을 남기지 않으므로 PBM도 만들지 않는다. 저장된 포맷 미리보기(`kind=preview`, `GET /api/formats/{id}/preview.png`)는 다른 kind와 같은 저장 경로(`doRender`/`savRender`)를 타므로 PBM도 같이 만들어진다 — 용도상 불필요하지만 해는 없다.

## 3. 폰트 [기본값]

- **Pretendard**, **Noto Sans KR** — 둘 다 SIL Open Font License 1.1. 포맷 `style.fontFamily` enum과 1:1.
- Docker 이미지 빌드 때 **버전 고정된 릴리스 파일**을 받아 설치하고, 라이선스 파일을 이미지 안에 함께 둔다.
- 이모지·컬러 폰트는 넣지 않는다(감열지 흑백, v1 날씨는 텍스트만).

## 4. 렌더 스케줄러 [기본값]

`@Scheduled(fixedDelay = 5분)` + 포맷·예약·설정·프로필 변경 시 즉시 1회 트리거.

매 실행:

1. 기기를 하나씩 순회하며 **그 기기 소유자의** `profile_key` 결정(`getCurrentProfile(ownerUserId)`, 기기가 없으면 기본 프로필)
2. **켜진 예약**의 occurrence 중 **지금부터 36시간 안**의 것을 KST로 계산
3. 각 occurrence의 `(formatId, targetDate)`에 대해
   - 최신 렌더가 없거나, `format_updated_at < formats.updated_at`(포맷이 바뀜)이거나, `profile_key`가 다르면 → 렌더
4. **동적 포맷**(`has_dynamic_blocks = true`)은 occurrence **약 60분 전**에 한 번 더 렌더
   - 조건: occurrence까지 60분 이하로 남았고, 최신 렌더의 `rendered_at`이 `occurrence − 60분`보다 이전
   - `has_dynamic_blocks`의 판정 원본은 **(2026-09-18)** `WidgetRegistry.hasDynamic(document.widgets())`다 — 위젯 중 `dynamic=true`(`morningLetter`·`stockChart`·`weather`, [`widgets.md`](widgets.md) 3절)가 하나라도 있으면 동적 포맷이다. 날씨 위치는 이제 포맷 자체(`weather` 위젯의 `props.location`)에 들어 있으므로, 위치를 바꾸려면 포맷을 수정해야 하고(그러면 `formats.updated_at`이 바뀌어 3단계 조건으로도 재렌더된다) 별도의 "설정 변경 시 재렌더" 트리거는 없다(레거시 `/api/settings`는 렌더에 영향을 주지 않는다, [`weather.md`](weather.md) 2절)

즉시 렌더(스케줄러를 거치지 않음):
- **미리보기** `GET /api/formats/{id}/preview.png` → `kind=preview`
- **편집본 미리보기** `POST /api/formats/preview` → 렌더 결과를 바로 응답하고 **행·파일을 남기지 않는다** [기본값]. **(2026-09-17)** `renderEphemeral(document, targetDate, ownerUserId)`가 세션 로그인한 요청자 자신의 기기 프로필로 렌더한다(이전의 "아무 기기나 하나" 폴백 제거) — [`api.md`](api.md) 참고
- **지금 인쇄** `POST /api/print-now` → `kind=command`, targetDate = KST 오늘

## 5. 렌더 파일 정리 [기본값]

하루 한 번(예: 03:30 KST) 실행:

- `kind=preview`: 24시간 지난 것 삭제
- `kind=command`: 해당 명령이 `done`/`expired`가 되고 24시간 지난 것 삭제
- `kind=scheduled`: `target_date < KST 오늘 − 7일` 삭제
- **단, 포맷마다 "현재 `profile_key`"의 가장 최근 렌더 1개는 항상 남긴다**(Pi 오프라인 폴백, 스냅샷의 "날짜 무관 최신 렌더")
- 행과 파일을 같이 지운다. 파일만 남거나 행만 남은 고아는 로그 경고 후 정리

**멀티유저 수정(2026-09-17, `RenderCleanupScheduler`, [확인됨·코드])**: "현재 `profile_key`"는 기기 전체에서 공통인 값이 아니다 — 사용자마다 기기(프로필)가 다를 수 있으므로, 렌더 하나하나에 대해 **"그 렌더를 만들 당시의 소유자(`Render.ownerUserId`)가 지금 쓰는 기기 프로필"**을 기준으로 "현재"를 판단한다(`currentProfileKeyFor`, `printerProfileProvider.getCurrentProfile(ownerUserId)`를 소유자별로 캐시해 실행 1회당 기기 수만큼만 조회). `RenderScheduler`(4절)가 기기별로 순회하며 소유자별 프로필을 쓰는 것과 같은 원칙이다. `owner_user_id`가 `NULL`인 레거시 렌더(claim-legacy 전)는 `PrinterProfileProviderImpl.getCurrentProfile(null)`이 DB 조회 없이 `PrinterProfile.DEFAULT`로 폴백해 처리하므로, 그 렌더는 `profileKey`가 `DEFAULT.profileKey()`와 같을 때만 "최신 유지" 대상이 된다.

**알려진 제약 2건(둘 다 [확인됨·코드], 아직 안 고침)**:

- **`kind=command` 정리는 비활성**이다. `RenderCleanupScheduler.cleanupRenders()`의 2단계(`kind=command`: 명령이 `done`/`expired`가 되고 24시간 지난 것 삭제)는 코드에 있지만 호출부가 주석 처리돼 있다(`// cleanupCommandRenders(now);` — 메서드 자체가 구현되지 않음). "지금 인쇄"(`kind=command`) 렌더는 지금 스케줄러 정리 대상에서 빠져 있고, PBM이 무압축이라 디스크 사용량 증가가 preview·scheduled보다 더 빠르게 쌓인다.
- **`RenderCleanupScheduler.FILES_DIR`이 `"/data/haru-files"`로 하드코딩**돼 있다. 같은 파일 저장 경로를 쓰는 다른 클래스(`RenderServiceImpl`, `DeviceSyncController`, `DeviceSyncService`, `HtmlTemplateBuilder`, `AssetService`, `FormatService`, `AssetCleanupScheduler`)는 전부 `@Value("${haru.files-dir:/data/haru-files}")`(`application.yml`의 `haru.files-dir`)로 읽는다. 운영에서 `haru.files-dir`(=`HARU_FILES_DIR`가 아니라 `application.yml`의 고정값, 환경변수로 노출돼 있지 않음)을 바꾸면 `RenderCleanupScheduler`만 옛 경로를 계속 보고 있어 파일을 못 찾아 지우지 못한다(`Render file not found (orphaned)` 경고만 남고 DB 행은 지워짐 — 파일이 고아로 남는다).

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
