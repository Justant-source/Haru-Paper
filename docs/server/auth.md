# 인증·계정·기기 소유 (M6)

> M6에서 구현됨. 결정 원본: `.temp/03-플랫폼-작업지시서-v1.0.md` 4절(Q13·Q14). 마이그레이션:
> `server/src/main/resources/db/migration/V2__users_and_ownership.sql`.
> **API 규약 원본은 [`../architecture.md`](../architecture.md)** — 이 문서는 그 규약을 Spring Security로
> 어떻게 구현했는지를 코드 근거와 함께 적는다.
>
> 표기: **[확인됨·코드]** 아래 명시한 클래스에서 직접 확인 / **[기본값]** 구현 선택

## 1. 세션 인증

- **Spring Security + Spring Session JDBC**. 세션은 DB(`SPRING_SESSION`, `SPRING_SESSION_ATTRIBUTES` 테이블, V2 마이그레이션)에 저장한다 — 서버 재시작·다중 인스턴스에도 로그인이 끊기지 않는다. `spring.session.jdbc.initialize-schema=never`로 두고 Flyway가 유일한 스키마 원본이다 [확인됨·코드: `V2__users_and_ownership.sql` 주석].
- 로그인 성공 시 `HttpSessionSecurityContextRepository`로 `SecurityContext`를 세션에 직접 저장한다(`formLogin`을 쓰지 않고 JSON 로그인 응답을 돌려줘야 해서 수동 배선) — `AuthController.login()`이 `AuthenticationManager.authenticate()` → `SecurityContextHolder` → `securityContextRepository.saveContext(...)` 순으로 호출한다 [확인됨·코드: `SecurityConfig.java`, `AuthController.java`].
- 세션 쿠키 이름은 기본값 `JSESSIONID`. 로그아웃(`POST /api/auth/logout`)이 이 쿠키를 지운다.
- 비밀번호 해시: `PasswordEncoderFactories.createDelegatingPasswordEncoder()`(bcrypt 계열, 알고리즘 접두어 자동 관리).

## 2. CSRF (SPA 더블서밋 쿠키)

`CookieCsrfTokenRepository.withHttpOnlyFalse()` — SPA가 쿠키 값을 읽어 헤더로 되돌려 보내는 패턴 [확인됨·코드: `SecurityConfig.java`]:

| 항목 | 값 |
|---|---|
| 쿠키 이름 | `XSRF-TOKEN` (기본값, JS에서 읽을 수 있게 `HttpOnly=false`) |
| 요청 헤더 | `X-XSRF-TOKEN` |
| 요청 핸들러 | 평문 `CsrfTokenRequestAttributeHandler` — 기본(`XorCsrfTokenRequestAttributeHandler`)은 XOR 마스킹을 기대하는데 쿠키엔 원문이 들어가므로, 평문 핸들러로 바꾸지 않으면 curl·JS가 쿠키 값을 그대로 보내도 403이 난다 |
| 쿠키 심기 | `CsrfFilter` 뒤에 커스텀 `OncePerRequestFilter`(`csrfCookieFilter`)를 추가해 **모든 요청에서 강제로 `CsrfToken.getToken()`을 읽는다.** `CookieCsrfTokenRepository`는 지연 로딩이라, 아무도 토큰을 안 읽으면 첫 GET(`/api/auth/me`)에 쿠키 자체가 안 실려서 다음 POST(로그인·가입)가 403이 나는 문제를 막는다 |
| 제외 경로 | `DEVICE_BEARER_PATHS`(4절) — Bearer 토큰 인증은 CSRF와 무관 |

## 3. 인가 규칙 [확인됨·코드: `SecurityConfig.securityFilterChain()`]

```
/api/health                              permitAll
/api/auth/signup, /api/auth/login        permitAll
/api/device/{poll,snapshot,renders/**,results,pair}   permitAll (Bearer 또는 1회용 코드로 별도 인증, 4절)
/api/admin/**                            hasRole("ADMIN")
/api/**                                  authenticated()  (그 외 전부 — 로그인 필요)
그 외(정적 파일 등)                        permitAll
```

- 401(미인증)·403(권한 없음) 모두 리다이렉트가 아니라 `application/problem+json`으로 응답한다(SPA라 서버가 로그인 페이지로 리다이렉트하면 안 됨) [확인됨·코드].
- 관리자 판정: 가입 시 `request.email`이 `HARU_ADMIN_EMAIL`(서버 `.env`)과 같으면 `role="admin"`으로 생성한다 [확인됨·코드: `AuthController.signup()`]. 그 뒤로는 DB의 `users.role` 컬럼이 원본이고, 환경변수를 다시 읽지 않는다.

## 4. Pi 기기 토큰 (Bearer, DB 해시)

서버 `.env`의 단일 `HARU_DEVICE_TOKEN`은 **더 이상 없다.** 기기별 토큰을 `devices.token_hash`에 저장한다(SHA-256, `TokenHasher`) [확인됨·코드: `DeviceTokenAuthFilter.java`, `TokenHasher`].

- 보호 경로(정확히 나열, 접두사 아님): `POST /api/device/poll`, `GET /api/device/snapshot`, `GET /api/device/renders/*`, `POST /api/device/results`. `GET /api/device`, `PUT /api/device/paper-state`, `POST /api/device/pair`는 이 필터를 통과한다(각각 세션 인증 또는 1회용 코드) [확인됨·코드: `DeviceTokenAuthFilter.PROTECTED_PATHS`, `RENDERS_PREFIX`].
- `Authorization: Bearer <토큰>` → SHA-256 해시 → `devices.token_hash`로 조회. 없으면 401(`application/problem+json`, `DispatcherServlet` 이전 필터라 `GlobalExceptionHandler`가 못 잡으므로 직접 응답을 쓴다).
- 성공하면 resolved `Device`를 요청 attribute(`DeviceTokenAuthFilter.DEVICE_ATTRIBUTE`)에 담아 `DeviceSyncController`가 어떤 기기·소유자인지 안다.

### 4.1 렌더 다운로드 소유권 검사 — 필터와는 별개의 컨트롤러 레벨 검사

`DeviceTokenAuthFilter`는 **경로 기반**으로만 보호한다 — 유효한 기기 토큰이면 "그 경로에 접근 가능"까지만 보장하고, 그 기기가 요청한 `renderId`가 **자기 소유인지는 검사하지 않는다.** 그래서 `GET /api/device/renders/{renderId}.png`·`.pbm`(둘 다) 컨트롤러 메서드(`DeviceSyncController`)에 **별도로** `assertOwnership(device, render, renderId)`를 추가했다(2026-09-17) [확인됨·코드] — 유효한 기기 토큰 하나로 다른 사용자의 `renderId`를 넣으면(`renderId`는 UUIDv4라 추측은 사실상 불가능하지만, IDOR 자체는 토큰 소유자와 무관하게 성립) 지금까지는 그대로 통과했다. 이제는 `render.ownerUserId`와 `device.ownerUserId`가 다르면 **404**로 응답한다(403이 아니라 404 — 존재 여부를 노출하지 않는다, 7절과 같은 관례).

**NULL 소유자(레거시 렌더) 처리 — `HARU_OWNERSHIP_STRICT` 플래그로 갈린다(2026-09-17)**: `render.ownerUserId`가 NULL이면(V4 백필 마이그레이션 적용 전의 기존 렌더, 또는 포맷이 나중에 삭제된 고아 렌더) 어떻게 할지를 `@Value("${haru.ownership-strict:false}")`(환경변수 `HARU_OWNERSHIP_STRICT`, `server/.env.example`) 값이 결정한다 [확인됨·코드: `DeviceSyncController.ownershipStrict`, `assertOwnership()`].

| 값 | 동작 |
|---|---|
| `false`(기본값) | 소유권 불일치로 보지 않고 **허용**(다운로드 진행, `log.warn`만 남김: "strict=false라 허용") |
| `true` | **404**로 거부하고 `log.error`에 고정 토큰 `RENDER_OWNER_NULL`을 남긴다(`renderId`·`device.getId()` 포함) — 로그를 `grep RENDER_OWNER_NULL`로 감시할 수 있게 하려는 의도 |

기본값이 `false`인 이유: 상시 구동 중인 Pi가 있는 상태에서 기존 렌더가 전부 `owner_user_id IS NULL`이었다(`RenderServiceImpl`이 M6 이후 지금까지 채우지 않다가 커밋 `b762c6c`에서 수정) — 지금 곧바로 `true`로 거부하면 Pi의 렌더 다운로드가 전부 404가 되어 운영이 끊긴다. `true`로 켜도 되는 시점은 `V4__backfill_render_owner.sql` 백필 + `claim-legacy`(6절)를 마쳐 `renders.owner_user_id IS NULL`인 행이 0이 된 뒤부터다. 켜는 절차(사전 조건 확인 포함)는 [`deploy.md`](deploy.md) 7.2절 "소유권 엄격 모드 켜기"가 원본이다 — 이 문서에서는 복제하지 않고 링크만 한다.

같은 플래그가 `ScheduleService`의 포맷 소유권 검사도 지배한다 — 7절 참고.

`Cache-Control`도 `public, max-age=31536000` → **`private, max-age=31536000`**으로 바꿨다(개인 인쇄물이라 CDN·프록시 같은 공유 캐시에 남으면 안 된다) [확인됨·코드: `DeviceSyncController.getRenderImage()`/`getRenderPbm()`].

### 토큰 발급 — `POST /api/devices/me/token` (세션 인증)

로그인한 사용자가 자기 기기의 토큰을 발급·재발급한다 [확인됨·코드: `DeviceManagementController.issueToken()`]. 기존 기기가 없으면 새로 만든다(`owner_user_id` UNIQUE — 1인 1기기). 응답에 **평문 토큰이 이번 한 번만** 표시된다(`Instant tokenIssuedAt`도 함께). 재발급하면 이전 토큰은 즉시 무효화(같은 행의 `token_hash`를 덮어씀).

### 페어링 코드 — `POST /api/devices/pairing-codes` (세션 인증) → `POST /api/device/pair` (무인증)

기기(Pi)에 로그인 UI 없이 사용자 계정과 연결하는 두 번째 경로 [확인됨·코드: `DeviceManagementController.createPairingCode()`, `DeviceController.pair()`]:

1. 사용자가 앱에서 코드 발급 요청 → 서버가 8자 코드(대문자+숫자, `I/O/0/1` 제외) 생성, `pairing_codes`에 저장, **10분 유효**.
2. Pi(또는 기기 설정 도구)가 `POST /api/device/pair {code, printerProfile?}`를 무인증으로 호출 → 코드가 유효하면 그 사용자의 기기에 새 토큰을 발급해 응답(`{deviceId, token, printerProfile}`)하고 코드를 1회용으로 소모. `printerProfile`(선택, JSON 문자열)을 같이 보내면 그대로 저장된다 [확인됨·코드: `DeviceDto.PairRequest`].
3. 없음·만료·이미 사용됨을 굳이 구분하지 않고 **셋 다 404**로 응답한다(재사용 공격 표면을 줄이려는 의도) [확인됨·코드].

### 기기 조회·이름 변경 (세션 인증)

- `GET /api/devices/me`: 현재 사용자의 기기 요약. 실제 응답 DTO는 `DeviceManagementDto.GetDeviceInfoResponse(deviceId, name, paired)`(`docs/app/screens.md` 267행과 일치) [확인됨·코드]. 이 레코드는 `@JsonInclude(NON_NULL)`이라, 페어링 전에는 `deviceId`·`name`이 `null`이 아니라 **키 자체가 응답 JSON에서 빠진다** — `paired`(boolean 기본형)만 `false`로 내려온다.
- `PATCH /api/devices/me {name}`: 기기 이름 변경. 페어링 전이면 오류.

> 위 3개(`/api/devices/*`, `me/token`, `pairing-codes`)는 **`DeviceManagementController`**(웹앱용, 세션 인증)다. **`DeviceController`**(`/api/device`, 단수)는 별개 클래스로, 세션 인증인 `GET /api/device`·`PUT /api/device/paper-state`와 무인증인 `POST /api/device/pair`를 가진다. 이름이 비슷해 헷갈리기 쉽다 — 규약은 [`../architecture.md`](../architecture.md) 4.2·4.3, 구현 세부는 [`api.md`](api.md).

## 5. 계정 API

### `POST /api/auth/signup` (무인증)

`{email, password, handle, displayName}` → `201`. 검증(전부 모아서 422로 반환): 이메일 형식·중복, 비밀번호 10자 이상, 핸들 3~20자 `[a-z0-9](-[a-z0-9])*` 패턴 + 예약어(`haru, admin, api, studio, market, auth, device, devices`) 금지 + 중복, `displayName` 1~50자 [확인됨·코드: `AuthController.HANDLE_PATTERN`, `RESERVED_HANDLES`].

### `POST /api/auth/login` (무인증)

`{email, password}` → `200` + 세션 쿠키. 실패 시 422(이메일 불일치도 "invalid email or password"로 뭉뚱그림 — 계정 존재 여부 노출 방지).

### `GET /api/auth/me` (세션 인증)

현재 사용자 조회. 응답 DTO(`AuthResponseDto`)는 signup·login과 동일: `{userId, email, handle, displayName, bio, role, status, mustChangePassword}`.

### `PATCH /api/account` (세션 인증)

`{displayName?, bio?, currentPassword?, newPassword?}`. 비밀번호를 바꾸려면 `currentPassword`가 맞아야 한다. `displayName` ≤50자, `bio` ≤300자 [확인됨·코드: `AccountController.java`].

> **경로 버그 이력**: 원래 `AuthController`(`@RequestMapping("/api/auth")`) 안에 있어서 `@PatchMapping("/account")`가 `/api/auth/account`로 매핑되는 버그였다. 별도 `AccountController`(`@RequestMapping("/api/account")`)로 분리해 고쳤다(e2e 스모크 테스트로 발견) — 새 계정 관련 엔드포인트를 추가할 때 같은 실수를 반복하지 않는다.

## 6. 관리자 API — `/api/admin/**` (ADMIN 역할)

[확인됨·코드: `AdminController.java`]

| 경로 | 메서드 | 용도 |
|---|---|---|
| `/api/admin/users` | GET | 전체 사용자 목록 |
| `/api/admin/users/{id}/temp-password` | POST | 임시 비밀번호 발급(8자 랜덤) → 응답에 **평문 1회만** 노출, `mustChangePassword=true`로 설정 |
| `/api/admin/users/{id}/suspend` | POST | 정지/활성화 토글, 또는 본문 `{status: "active"\|"suspended"}`로 지정 |
| `/api/admin/claim-legacy` | POST | 로그인한 관리자가 `owner_user_id=NULL`인 리소스(포맷·예약·에셋·렌더·명령·결과) 전부를 자신의 것으로 이전 |

`claim-legacy`는 M6 배포 시 **1회성 마이그레이션 도구**다 — M2~M5의 PoC 데이터(소유자 없음)를 관리자 계정으로 흡수한다.

> **운영 주의**: `V4__backfill_render_owner.sql`(`renders.owner_user_id` 백필, 7절·[`data-model.md`](data-model.md))이 적용되기 **전에는 `claim-legacy`를 실행하지 않는다.** `RenderServiceImpl`이 렌더 생성 시 `owner_user_id`를 채우기 시작한 것도 2026-09-17부터라(그 전에는 항상 NULL), V4 적용 전에는 기존 렌더의 `owner_user_id`가 전부 NULL이다 — 이 상태에서 `claim-legacy`를 돌리면 **모든 사용자의 렌더가 관리자 소유로 넘어간다.**
>
> **순서**: V4 백필 적용 → `claim-legacy` 실행 → (검증 통과 후) `HARU_OWNERSHIP_STRICT=true`로 소유권 엄격 모드 켜기(4.1절 "NULL 소유자 처리", [`deploy.md`](deploy.md) 7.1·7.2절). 이 세 단계 전부가 순서대로 끝나기 전에는 플래그를 켜지 않는다 — `claim-legacy`가 소유자를 채워도, V4 백필이 이미 적용돼 있지 않으면 `claim-legacy` 자체가 (위 경고대로) 모든 렌더를 관리자에게 몰아주는 사고가 먼저 난다.

## 7. 소유권 스코핑 (owner_user_id)

V2 마이그레이션이 기존 테이블에 `owner_user_id CHAR(36) NULL`을 추가했다(레거시 행은 NULL, 6절 `claim-legacy`로 이전):

| 테이블 | 추가 컬럼 | FK |
|---|---|---|
| `formats` | `owner_user_id` | `→ users.id ON DELETE CASCADE` |
| `schedules` | `owner_user_id`, `device_id` | `owner_user_id → users`, `device_id → devices ON DELETE SET NULL` |
| `assets` | `owner_user_id` | `→ users.id ON DELETE CASCADE` |
| `renders` | `owner_user_id` | FK 없음(이력 보존 관례, [`data-model.md`](data-model.md)) |
| `commands` | `owner_user_id`, `device_id` | FK 없음 |
| `results` | `owner_user_id`, `device_id` | FK 없음 |

컨트롤러 계층의 규칙: **목록은 현재 사용자 것만, 단건 조회·수정·삭제는 소유자가 아니면 404**(403이 아니라 404 — 존재 여부를 노출하지 않는다) [확인됨·코드: `FormatController`, `ScheduleController`, `AssetController`, `PrintNowController`, `HistoryController` 전부 이 패턴].

### 7.1 예약 생성·수정의 포맷 소유권 검사 — 이것도 `HARU_OWNERSHIP_STRICT`가 지배한다

`POST /api/schedules`·`PUT /api/schedules/{id}`가 부르는 `ScheduleService.createWithOwner()`/`updateWithOwnerCheck()`는 요청한 `formatId`가 현재 사용자 소유인지를 `assertFormatOwnership()`으로 검사한다(2026-09-17 추가) [확인됨·코드: `ScheduleService.java`]. `PrintNowController.printNow()`(6절 위, `PrintNowController.java:72-73`)는 이미 `owner_user_id`가 NULL이든 다른 사용자든 무조건 404였는데, `ScheduleService`만 예외가 있었다:

| `ownershipStrict` | `formatOwnerId == null` | `formatOwnerId != userId`(둘 다 NULL 아님) |
|---|---|---|
| `false`(기본값) | **허용** — 알려진 구멍: 소유자가 없는(claim-legacy 전) 레거시 포맷은 **아무 로그인 사용자나** 자기 예약에 가져다 쓸 수 있다 | 404 |
| `true` | 404 — `PrintNowController`와 동일하게 맞춰진다 | 404 |

`false`가 기본값인 이유와 `true`로 켜는 절차는 4.1절·[`deploy.md`](deploy.md) 7.2절과 같다(같은 플래그, 같은 사전 조건). **2026-09-19 `claim-legacy` 실행으로 `null_formats=0`이 됐다**([`deploy.md`](deploy.md) 7.1절) — 지금은 소유자 없는 포맷 자체가 없어 이 구멍이 실질적으로 닫혀 있다. 다만 `HARU_OWNERSHIP_STRICT`는 아직 `false`인 채로 남아 있어(전환은 실물 인쇄 확인이 선행 조건, 7.2절과 같은 게이트), 앞으로 소유자 없는 포맷이 다시 생기면(예: 데이터 이관 실수) 이 표의 동작이 그대로 재현된다.

### 7.2 위젯 `asset` 필드의 에셋 소유권 검사 (2026-09-19 추가)

이미지 위젯이 앱 편집기에 노출되면서([`widgets.md`](widgets.md) 3.3절) `WidgetPropsValidator.validateAsset`이 존재 여부만 보던 것을 소유권까지 보도록 넓혔다 [확인됨·코드] — 그전에는 assetId(uuid)를 알면(짐작이 아니라, 예를 들어 공유 예시 포맷 JSON에서) 남의 이미지를 자기 포맷에 렌더할 수 있었다(서버가 `data:` URI로 그대로 내장하므로 실제로 보인다). 규칙은 4.1절 `assertOwnership`과 동일:

| `ownershipStrict` | `assetOwnerId == null`(claim-legacy 전 레거시) | `assetOwnerId != requestUserId` |
|---|---|---|
| `false`(기본값) | 허용(경고 로그) | 항상 거부 |
| `true` | 거부 | 항상 거부 |

거부 메시지는 존재하지 않을 때와 똑같이 `"asset not found: {id}"`다 — 소유·존재 여부를 구분해 알려주지 않는다.

**`POST /api/formats/import`만 예외**: 리매핑 전 1차 검증(`FormatController.importFormat`)은 이 검사를 건너뛴다(`requestUserId=null`로 호출) — 그 시점의 `assetId`는 원본 내보내기 쪽 값이고, 곧 `decodeAndSaveAsset`으로 가져오는 사람 소유의 새 에셋으로 재발급(remap)된다. 강제하면 "친구가 내보낸 포맷을 가져오기"(`forkedFrom`)가 원본 소유자가 나 자신이 아닌 한 항상 실패한다. 재발급 뒤 두 번째 검증(`FormatService.importFormat`의 `validator.validate(withForkedFrom, userId)`)은 새 에셋이 이미 `userId` 소유이므로 정상적으로 통과한다.

## 8. 기기 테이블 교체 (`device` → `devices`)

옛 `device` 테이블(항상 `id=1`인 단일 행)은 **DROP**됐다 — PoC 데이터는 재사용 가치가 없다고 판단했다(작업지시서 Q25: "기존 Pi가 새 토큰으로 poll 성공"이 통과 조건이라 재가입이 전제) [확인됨·코드: `V2__users_and_ownership.sql`]. 새 `devices` 테이블은 `owner_user_id UNIQUE`(1인 1기기), `token_hash UNIQUE`. 컬럼 상세는 [`data-model.md`](data-model.md).

`settings`(전역 키-값)도 같은 이유로 DROP하고 `user_settings(user_id, setting_key, value)`로 대체했다 — 날씨 기본 위치 등 기존 값은 보존하지 않는다.

## 9. 앱 쪽 구현

로그인·가입·계정·기기 화면은 [`../app/web.md`](../app/web.md)·[`../app/screens.md`](../app/screens.md) 참고. 요지: `credentials: 'include'`로 세션 쿠키를 자동 전송하고, `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 되돌려 보낸다. `401` 응답이면 `/login`으로 이동한다.
