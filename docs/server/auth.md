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

### 토큰 발급 — `POST /api/devices/me/token` (세션 인증)

로그인한 사용자가 자기 기기의 토큰을 발급·재발급한다 [확인됨·코드: `DeviceManagementController.issueToken()`]. 기존 기기가 없으면 새로 만든다(`owner_user_id` UNIQUE — 1인 1기기). 응답에 **평문 토큰이 이번 한 번만** 표시된다(`Instant tokenIssuedAt`도 함께). 재발급하면 이전 토큰은 즉시 무효화(같은 행의 `token_hash`를 덮어씀).

### 페어링 코드 — `POST /api/devices/pairing-codes` (세션 인증) → `POST /api/device/pair` (무인증)

기기(Pi)에 로그인 UI 없이 사용자 계정과 연결하는 두 번째 경로 [확인됨·코드: `DeviceManagementController.createPairingCode()`, `DeviceController.pair()`]:

1. 사용자가 앱에서 코드 발급 요청 → 서버가 8자 코드(대문자+숫자, `I/O/0/1` 제외) 생성, `pairing_codes`에 저장, **10분 유효**.
2. Pi(또는 기기 설정 도구)가 `POST /api/device/pair {code}`를 무인증으로 호출 → 코드가 유효하면 그 사용자의 기기에 새 토큰을 발급해 응답(`{deviceId, token, printerProfile}`)하고 코드를 1회용으로 소모.
3. 없음·만료·이미 사용됨을 굳이 구분하지 않고 **셋 다 404**로 응답한다(재사용 공격 표면을 줄이려는 의도) [확인됨·코드].

### 기기 조회·이름 변경 (세션 인증)

- `GET /api/devices/me`: 현재 사용자의 기기 요약(`{deviceId, name, hasToken}`). 페어링 전이면 전부 `null`/`false`.
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

## 8. 기기 테이블 교체 (`device` → `devices`)

옛 `device` 테이블(항상 `id=1`인 단일 행)은 **DROP**됐다 — PoC 데이터는 재사용 가치가 없다고 판단했다(작업지시서 Q25: "기존 Pi가 새 토큰으로 poll 성공"이 통과 조건이라 재가입이 전제) [확인됨·코드: `V2__users_and_ownership.sql`]. 새 `devices` 테이블은 `owner_user_id UNIQUE`(1인 1기기), `token_hash UNIQUE`. 컬럼 상세는 [`data-model.md`](data-model.md).

`settings`(전역 키-값)도 같은 이유로 DROP하고 `user_settings(user_id, setting_key, value)`로 대체했다 — 날씨 기본 위치 등 기존 값은 보존하지 않는다.

## 9. 앱 쪽 구현

로그인·가입·계정·기기 화면은 [`../app/web.md`](../app/web.md)·[`../app/screens.md`](../app/screens.md) 참고. 요지: `credentials: 'include'`로 세션 쿠키를 자동 전송하고, `XSRF-TOKEN` 쿠키 값을 `X-XSRF-TOKEN` 헤더로 되돌려 보낸다. `401` 응답이면 `/login`으로 이동한다.
