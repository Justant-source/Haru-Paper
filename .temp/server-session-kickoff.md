# 서버 세션 시작 브리핑 (M2 + M3)

> 이 파일은 `justant-server2`에서 사용자가 직접 SSH로 들어가 여는 Claude Code 세션을 위한
> **일회성 인계 문서**다. detox-printer `.temp/PLAN-*.md`와 같은 성격 — 확정 설계가 아니라
> "다음 세션이 무엇부터 하면 되는지"를 적은 작업 메모다. 확정 설계는 전부 `docs/`에 있다.
>
> 작성 배경: 노트북(WSL) 세션에서 grill-me로 전체 계획을 세우고 M0(저장소 뼈대)까지 끝냈다.
> 원래는 노트북 세션이 SSH로 서버에 붙어 M2·M3까지 직접 조율하려 했으나, **노트북을 계속
> 켜 둘 수 없어서** 사용자가 서버에 직접 들어가 그곳 Claude Code로 진행하기로 했다(이 대화
> 자체의 방향 전환). 그래서 여기까지의 맥락과, 사용자가 이미 승인한 실행 범위를 옮겨 적는다.

## 0. 가장 먼저 할 일

1. `hostname`으로 지금 `justant-server2`인지 확인 (CLAUDE.md 관례)
2. 저장소가 없으면: `cd ~/Data && git clone https://github.com/Justant-source/Haru-Paper.git`
3. 저장소가 있으면: `cd ~/Data/Haru-Paper && git pull --ff-only`
4. **`CLAUDE.md`를 먼저 읽는다.** 디렉터리 담당(이 세션 = `/server`, `/app`, `/docs/server`, `/docs/app`), Git 규칙, 절대 금지 7개가 있다
5. `.claude/settings.local.json`이 없으면 노트북 `~/Data/Haru-Paper/.claude/settings.local.json`을 사용자에게 요청해 같은 경로에 복사(gitignore 대상이라 git에는 없다)
6. 이 파일(`server-session-kickoff.md`)은 다 읽었으면 **삭제하고 커밋**한다(일회성 메모, `docs/`가 아니라 `.temp/`에 둔 이유). 삭제 전에 아래 "5. 인수인계 후 정리" 참고

## 1. 지금까지 상태

- GitHub `Justant-source/Haru-Paper` (공개), 기본 브랜치 `main`, 최신 커밋 `b8d4cdb`("M0: Haru-Paper repository skeleton, docs, and init plan")
- M0 통과: 코드 없이 뼈대·문서만 있는 상태. `/server`, `/app/web`은 빈 폴더 + README뿐이고 Gradle/Vite 프로젝트가 아직 없다
- 설계는 grill-me 34라운드로 전부 끝났고, `docs/init_plan.md`(원본 기록)와 `docs/architecture.md`(API 규약 원본) + `docs/server/*.md` 7개 + `docs/app/*.md` 4개에 상세히 적혀 있다. **여기서 다시 설계 질문을 하지 않는다** — 답이 이미 문서에 있다
- 하드웨어 트랙(V1~V4, H4, H5)은 노트북에서 병렬로 진행 중이며 이 세션과 무관하다. `/pi`, `/docs/pi`는 건드리지 않는다

## 2. 사용자가 이미 승인한 실행 범위 (다시 묻지 않아도 됨)

- **멀티에이전트 병렬 작업**: Agent 도구로 최대 6개까지 동시에 띄워 작업해도 된다(사용자가 명시적으로 "최대 6개씩 멀티에이전트로 병렬로 진행해줘"라고 요청). 다만 서로 다른 파일을 건드리도록 작업을 나눠야 한다 — 아래 4절 분배안 참고
- **코드 작성 위치**: 이 세션(서버)에서 직접 파일을 쓴다. (원래 "노트북에서 작성→push, 서버는 pull"로 정했었지만, 이제 이 세션이 직접 코딩하는 쪽으로 바뀌었다.) 커밋·push는 이 세션이 한다
- **Docker 배포**: 코드가 빌드되고 나면 **`docker compose up -d`까지 자동으로 해도 된다.** 단 아래 조건을 반드시 지킨다:
  - 포트는 `127.0.0.1`에만 바인딩한다(외부·0.0.0.0 노출 금지). 서버에 다른 프로젝트가 많아 `0.0.0.0`에 뭔가를 열면 그것만으로 외부 노출이 될 수 있다
  - DB(MariaDB)는 호스트 포트를 열지 않고 Docker 내부 네트워크로만 연결한다(아래 3절 포트 참고 — 기존 프로젝트들이 3306/3307/3308/3309를 이미 쓰고 있다)
  - **`tailscale serve`(또는 그 밖의 외부 노출 변경)는 절대 자동으로 하지 않는다.** 여기서 멈추고 사용자에게 승인을 받는다(`docs/server/deploy.md`에도 같은 규칙이 있다)
- **승인이 필요 없는 것들**: 로컬(127.0.0.1) Docker 기동/재기동, Flyway 마이그레이션 적용, 의존성 설치, 코드 커밋·push(단 fast-forward 실패 시는 멈춤)

## 3. 서버 recon (노트북 세션이 읽기 전용으로 확인한 값, 2026-09-13 기준 — 다시 조회해서 최신값으로 갱신할 것)

- 이미 쓰이는 포트(겹치면 안 됨): `22 53 80 1338 3000 3306 3307 3308 3309 5432 5433 6010 6379 8080 8090 8091 8099 8200 8765 9090 9099` + `10248~10259`(k8s) + `16443 19001 20241 20242 25000 33060 35539 41019 42438 44843 53439`
- 추천 web 바인딩: `127.0.0.1:18080` (비어 있음 확인) — `server/.env.example`의 `HARU_WEB_BIND` 값. 실제 배포 직전에 `ss -tlnp`로 다시 확인할 것(그 사이 다른 프로젝트가 포트를 새로 쓸 수 있음)
- 기존 Docker 네트워크: `againspring*`, `cryptoengine*`, `family-brain-dashboard_default`, `fb-net`, `green-forest-*_default`, `kospimania_default` 등 — Haru-Paper 전용 네트워크(예: `haru-paper_default`, compose가 자동 생성하는 이름 그대로 써도 충돌 없음)를 새로 만들면 됨. 기존 네트워크에 얹을 필요 없음
- 기존 MariaDB 컨테이너 3개(`againspring-mariadb`, `-prod`, `-dev`) 모두 `mariadb:lts` 이미지 — Haru-Paper도 같은 이미지 태그를 쓰면 이미지 레이어를 공유해 디스크를 아낌 [기본값, 강제 아님]
- Java 21(OpenJDK), Node v20.20.2 설치돼 있음. `gradle` CLI는 없음 — Gradle Wrapper(`./gradlew`)를 프로젝트에 커밋해서 쓴다
- 리소스: RAM 31Gi 중 1.6Gi free(그러나 buff/cache 16Gi라 실제 여유는 넉넉함), 디스크 1.6T 여유
- 기존 프로젝트 compose 관례를 파악하려면 **읽기 전용으로** `~/Data/Again-Spring`(또는 다른 `-prod` 스택)의 `docker-compose*.yml`을 한 번 열어보고 네이밍·라벨 스타일만 참고할 것(수정 금지, 다른 프로젝트 파일이다)

## 4. M2(서버) + M3(앱) 작업 분배안 — 참고용, 그대로 따를 필요는 없음

병렬 에이전트를 쓰더라도 **기반 골격은 먼저 순서대로 만들고**, 그다음에야 파일이 겹치지 않는 단위로 나눠 병렬화하는 게 안전하다(M0에서 이 방식으로 문서 4묶음을 병렬로 만든 전례가 있음 — `docs/` 각 하위 폴더를 서로 다른 fork가 동시에 썼고 마지막에 정합성 검증 fork 1개로 필드명을 맞췄다). Spring Boot/React 프로젝트는 공유 파일(빌드 설정, 엔티티, API 클라이언트)이 많아 M0보다 결합도가 높으므로 더 주의해야 한다.

### M2 — 서버 (`docs/server/*.md`가 원본)

**0단계 (순차, 먼저 혼자 또는 에이전트 1개로)**
- Gradle 프로젝트 스캐폴드: Spring Boot 3 + Java 21 + Gradle Groovy DSL(`docs/init_plan.md` Q15). `./gradlew` 커밋
- 기본 패키지 구조, `application.yml`(`.env` 읽기), Flyway 베이스라인, `Dockerfile`, `docker-compose.yml` 뼈대(서비스 이름만, 3절 포트 반영)
- `docs/server/data-model.md`의 테이블 8개에 대한 Flyway `V1__init.sql`

**병렬 단계 (여기서부터 최대 6개, 파일 겹침 없이 아래처럼 나누는 걸 권장)**
1. Format: `FormatController`, `FormatService`, 엔티티/리포지토리, import/export/preview — `docs/server/format-schema.md`, `docs/server/api.md`
2. Schedule: `ScheduleController`, 엔티티/리포지토리 — `docs/server/api.md`, `docs/architecture.md` 4.2
3. Device: `DeviceController`(poll/snapshot/renders/results), 엔티티, Bearer 토큰 인증 필터 — `docs/server/api.md`
4. Rendering: HTML 템플릿 생성 → Playwright+Chromium 스크린샷 → 그레이스케일 PNG, 렌더 스케줄러 — `docs/server/rendering.md`
5. Weather + Settings: Open-Meteo 클라이언트, WMO 코드→한국어 매핑, 캐시, `SettingsController` — `docs/server/weather.md`
6. Deploy: Docker compose 완성(볼륨·네트워크·백업), DB 백업 스크립트 — `docs/server/deploy.md`

**통합 단계 (병렬 끝난 뒤)**
- 전체 빌드(`./gradlew build`), `docs/server/api.md`의 curl 시나리오 실행해 검증
- `docker compose up -d`(3절 조건 지켜서), `/api/health` 확인

### M3 — 앱 (`docs/app/*.md`가 원본)

**0단계 (순차)**
- Vite + React + TS + PWA 스캐폴드, API 클라이언트 베이스, 라우팅 뼈대 — `docs/app/web.md`

**병렬 단계 (최대 5~6개)**
- `docs/app/screens.md`의 화면 7개를 의미 단위로 묶어서 나눈다: (1) 포맷 목록+가져오기/내보내기 (2) 포맷 편집기+미리보기 (3) 예약 목록·편집 (4) 지금 인쇄+이력 (5) 기기+설정 등. 공유 컴포넌트(레이아웃, API 클라이언트, 타입)는 0단계에서 먼저 만들어 두어야 병렬 단계에서 충돌이 없다

**통합 단계**
- `npm run build` 통과 확인, M2 API에 실제로 붙여서 화면 하나씩 수동 확인(포맷 만들기→미리보기→예약→지금 인쇄 명령 생성까지)
- nginx로 서빙(`docs/server/deploy.md`의 `haru-web` 서비스)

## 5. 멈추고 물어봐야 하는 것

- `tailscale serve` 또는 그 밖의 외부 노출 변경
- `git pull --ff-only` 실패(충돌) — 스스로 합치지 않는다(merge/rebase는 settings.local.json이 거부)
- 포트·컨테이너 이름이 기존 프로젝트와 겹치는데 3절 recon 값으로 해결이 안 될 때
- `docs/`에 없는 새로운 설계 결정이 필요할 때(예: 화이트리스트 스타일 속성을 더 늘려야 하는지, 인증 방식을 바꿔야 하는지 등)
- `/pi`, `/docs/pi`를 건드려야 할 것 같은 상황(원칙적으로 발생하면 안 됨 — 발생하면 설계가 어딘가 어긋났다는 신호)

## 6. 인수인계 후 정리

작업을 시작해서 이 문서 내용을 다 소화했으면, 이 파일은 **역할이 끝난 것**이다. 지우고 커밋해도 되고, 남겨두고 싶으면 남겨도 된다(강제 아님) — 단 `docs/`로 승격하지는 말 것(`docs/`는 계속 유지되는 설계 문서, 이 파일은 1회성 인계 메모).
