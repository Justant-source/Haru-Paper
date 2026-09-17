# 배포 (Docker compose, prod만)

> 결정 원본: [`../init_plan.md`](../init_plan.md) Q11(Tailscale 전용), Q15(Docker, prod만), 8.2절, 12절(서버 세션 시작 절차).
> 서비스 구성·볼륨 이름·백업 방식은 **[기본값]**. 서버 기존 관례를 읽은 뒤 맞춰 조정한다.

## 0. 서버 세션 시작 절차 (처음 한 번)

서버 `justant-server2`(Tailscale `100.81.189.92`)에서:

1. 코드 받기
   ```bash
   cd ~/Data && git clone https://github.com/Justant-source/Haru-Paper.git
   ```
2. **GitHub push 인증**: `~/Data/Again-Spring`에 설정된 GitHub 인증(같은 `Justant-source` 계정)을 확인해 Haru-Paper에서도 push되게 설정한다(사용자가 허용함 — 본인 프로젝트 간 재사용).
3. (이력) `.claude/settings.local.json`은 gitignore라 clone으로 오지 않는다 — 세션이 노트북 담당이던 시절에는 노트북에서 서버로 복사했다. **지금은 서버 세션 하나가 모든 경로를 담당하므로**([../../CLAUDE.md](../../CLAUDE.md) "세션·담당") 이 단계는 더 이상 필요하지 않다.
4. `~/Data/Haru-Paper`에서 Claude Code 실행 → `CLAUDE.md`, `docs/init_plan.md`, `docs/architecture.md`, `docs/server/`, `docs/app/`을 읽고 M2 시작.

## 1. 운영 서버라는 점을 먼저 기억할 것

- 이 서버에는 이미 **Docker 컨테이너 약 45개**(Again-Spring, Green-Forest, Family-Brain 등)와 microk8s, Cloudflare Tunnel이 돌고 있다.
- **compose를 쓰기 전에 기존 관례를 읽기만 한다**(수정 금지):
  - `ls ~/Data/*/docker-compose*.yml ~/Data/*/compose*.y*ml` — 컨테이너 이름 규칙, restart 정책, 로그 설정, 네트워크 방식
  - `docker ps --format '{{.Names}} {{.Ports}}'` — 이름 충돌 확인
  - `ss -tlnp` — 비어 있는 호스트 포트 확인. M0 조사 때 열려 있던 포트(전부가 아님): 22, 80, 3000, 3306, 3308, 3309, 8080, 8090, 8091, 8765, 10250, 16443
- 다른 프로젝트의 컨테이너·네트워크·볼륨·Tailscale·Cloudflare 설정은 건드리지 않는다.

## 2. compose 구성 [기본값]

- 파일 위치: `/server/docker-compose.yml`
- 프로젝트 이름 고정: `name: haru-paper` → 컨테이너·네트워크·볼륨이 `haru-paper` 접두사로 격리됨
- **prod 스택 하나만.** dev 스택은 만들지 않는다(init_plan Q15)

| 서비스 | 이미지·빌드 | 역할 | 호스트 포트 |
|---|---|---|---|
| `haru-db` | `mariadb`(LTS 태그 고정) | DB `haru_paper` | **없음** |
| `haru-api` | `/server` 멀티스테이지(Gradle 빌드 → Playwright Java 런타임 이미지) | Spring Boot, 렌더러 | **없음**(내부 8080) |
| `haru-web` | `/app/web` 멀티스테이지(Node 빌드 → `nginx` 안정 태그) | 웹앱 `dist` 서빙 + `/api/` → `haru-api:8080` 프록시 | `${HARU_WEB_BIND}`(127.0.0.1) + `${HARU_WEB_TAILSCALE_BIND}`(Tailscale IP). 둘 다 `:?`로 비어 있으면 기동 실패 |
| `haru-db-backup` | `mariadb`(같은 태그) | 매일 덤프, 7일 보관 | 없음 |

- 빌드 컨텍스트: `haru-web`은 `../app/web`, `haru-api`는 `.`(`/server`)
- 공통: `restart: unless-stopped`, 로그 `json-file`(`max-size: 10m`, `max-file: 3`), `TZ=Asia/Seoul`
- `haru-api`: `depends_on: haru-db (healthy)`, `mem_limit: 1.5g`, `shm_size: 1g`, 외부 인터넷 출구 필요(Open-Meteo), 비루트 실행([`rendering.md`](rendering.md) 6절)
- `haru-db`: healthcheck, `utf8mb4`, 루트 비밀번호·앱 계정은 `.env`
- `haru-web` nginx: `client_max_body_size 30m`(가져오기 30MB, 업로드 10MB), SPA 폴백(`try_files $uri /index.html`), `/api/` 프록시에 타임아웃 60초(미리보기 렌더 대기)
- **SSE 전용 location(`app/web/nginx.conf`, `[미검증]`)**: `GET /api/device/events`(기기 깨우기 채널, [`../architecture.md`](../architecture.md) 4.3절)는 `location /api/`와 별도로 `location = /api/device/events`(정확 매칭, prefix 매칭보다 우선)에 둔다. 별도 블록이 필요한 이유는 `location /api/`의 기본값이 SSE와 맞지 않기 때문이다: 기본 `proxy_buffering on`이면 이벤트가 nginx 버퍼에 갇혀 flush되지 않고, 프록시가 업스트림에 HTTP/1.0을 쓰면 chunked 스트리밍(연결을 계속 열어 두는 응답)이 성립하지 않는다. 그래서 이 블록만 `proxy_http_version 1.1`·`proxy_buffering off`·`proxy_cache off`와 `proxy_read_timeout 90s`(서버 하트비트 15초의 6배 여유)를 쓴다. **적용에는 `haru-web` 이미지 재빌드·재기동이 필요하다** — nginx 설정은 이미지 안에 구워지므로 `docker compose build && docker compose up -d haru-web`(또는 전체 재기동) 없이는 반영되지 않는다.
- **`tailscale serve`는 이 저장소에서 설정할 수 없는 불투명한 프록시 홉이다.** 거기서 SSE 연결이 실제로 끊기지 않고 통과하는지는 이 저장소 코드로 보장할 수 없고 `[미검증]`이다 — 15초 하트비트(4.3절 "타임아웃 순서 불변식")가 유일한 방어선이다. 하트비트 없이 더 긴 침묵이 이어지면 중간 홉이 연결을 끊어도 알아챌 방법이 없다.
- **SSE 적용(nginx 재빌드·재기동)은 CLAUDE.md "서버 시스템 변경은 적용 직전 사용자 승인" 규칙 대상이다** — `tailscale serve`·포트 바인딩·compose 서비스 추가와 같은 급이다. 코드·설정 파일(`nginx.conf`, `.env.example`)은 지금 커밋해도, 실제 적용(재빌드·재기동)은 사용자 승인 후 별도 단계로 진행한다.

### 볼륨

| 볼륨 | 마운트 | 내용 | 백업 |
|---|---|---|---|
| `haru-db-data` | `haru-db:/var/lib/mysql` | DB 파일 | 덤프로 백업 |
| `haru-files` | `haru-api:/data/haru-files` | `uploads/`(에셋 원본), `renders/`(PNG) | `uploads/`만 |
| `haru-backups` | `haru-db-backup:/backups` | 덤프·에셋 tar | — |

네트워크: compose 기본 네트워크(`haru-paper_default`)만. 외부 네트워크에 붙지 않는다.

## 3. 노출: `127.0.0.1` + `tailscale serve`

- **DB 포트는 호스트에 노출하지 않는다.** (참고: 이 서버의 다른 프로젝트 MariaDB/MySQL 포트는 `0.0.0.0`에 열려 있다 — Haru-Paper는 따라 하지 않는다)
- `haru-web`만 `127.0.0.1:<빈 포트>`에 바인딩. 포트는 1절에서 확인하고 `server/.env`의 `HARU_WEB_BIND`에 적는다.
- 외부 접근은 **Tailscale 내부망 HTTPS**로만:

  ```bash
  # [확인됨] tailscale 1.102.2, 실제 적용해 동작 확인
  sudo tailscale serve --bg --https=443 http://127.0.0.1:18080
  tailscale serve status
  ```

  → `https://justant-server2.tail2b65d1.ts.net/` **[확인됨, 2026-09-14 적용]**

- **`tailscale serve` 적용은 사용자 승인 후 이 세션이 sudo 비밀번호를 입력할 TTY가 없어, 사용자가 서버에 직접 SSH로 접속한 터미널에서 위 명령을 실행했다.** 적용 후 `GET /api/health`가 200으로 응답하는 것을 확인했다(첫 요청은 인증서 준비로 타임아웃될 수 있음 — 재시도하면 된다).
- tailnet의 MagicDNS HTTPS는 이미 켜져 있었다(추가 콘솔 설정 없이 바로 됨) **[확인됨]**.
- PWA 설치에는 HTTPS가 필요하다 — 이 경로가 그 조건을 만족한다.
- Cloudflare Tunnel로 공개하지 않는다(PoC는 Tailscale 전용).

### 3.1 IP:포트 직접 접속(추가 경로, HTTP) [기본값, 2026-09-14 추가]

hostname(`https://justant-server2.tail2b65d1.ts.net`) 대신 **Tailscale IP로 직접** 접속해야 하는
경우를 위한 두 번째 경로. `tailscale serve`는 SNI 기반이라 IP로 직접 붙으면 TLS 핸드셰이크
자체가 실패한다(`TLS alert, internal error` [확인됨, 실사]) — 그래서 이 경로는 **HTTPS가 아니라
HTTP**이고, hostname 경로와 별도로 존재한다(hostname+HTTPS 경로는 그대로 유지).

- `haru-web`을 `${HARU_WEB_TAILSCALE_BIND}`(반드시 `<이 서버의 tailscale IP>:<포트>`, 예
  `100.81.189.92:18080`)에도 바인딩한다. **`0.0.0.0`은 여기서도 절대 쓰지 않는다** — tailscale IP는
  Tailscale 오버레이 네트워크 안에서만 라우팅되므로 이렇게 해도 "Tailscale 전용" 경계는 그대로
  유지된다(같은 tailnet 안에서만 도달 가능, hostname 경로와 도달 가능 범위가 동일).
  `docker-compose.yml`은 이 값이 비어 있으면 `${VAR:?...}` 문법으로 기동을 실패시켜, 실수로
  호스트 주소 없이 포트만 남아 모든 인터페이스에 열리는 사고를 막는다.
- `http://100.81.189.92:18080/`에서 `GET /api/health` 200 **[확인됨, 2026-09-14]**.
- 이 경로가 필요 없어지면 `.env`의 `HARU_WEB_TAILSCALE_BIND`와 `docker-compose.yml`의 해당
  `ports` 항목을 함께 지운다.
- 이 경로는 PWA 설치 조건(HTTPS)을 만족하지 않는다 — PWA는 계속 hostname 경로로 설치한다.

## 4. 비밀값

- `server/.env`는 **서버에만** 두고 커밋하지 않는다(공개 저장소). `chmod 600 server/.env`.
- `server/.env.example`을 복사해 채운다:
  - `HARU_DB_PASSWORD`, `HARU_DB_ROOT_PASSWORD`: 새로 생성(`openssl rand -base64 24`)
  - `HARU_ADMIN_EMAIL`: 이 이메일로 가입한 계정이 관리자가 된다([`auth.md`](auth.md) 3절). **M6부터 서버 `.env`에 기기 토큰이 없다** — 로그인한 사용자가 앱 "기기" 화면에서 `POST /api/devices/me/token`으로 직접 발급한다([`auth.md`](auth.md) 4절)
  - `HARU_POLL_INTERVAL_SEC`: 기본 30. poll 응답 `pollIntervalSec`로 Pi에 전달된다([`api.md`](api.md) 6절)
  - `HARU_OWNERSHIP_STRICT`: 기본 `false`(비밀값은 아니지만 운영에 영향을 주는 플래그라 여기 같이 적는다). 렌더 다운로드·예약 생성의 소유권 검사를 엄격하게 할지 — `false`=`owner_user_id`가 NULL인 레거시 렌더·포맷 허용, `true`=거부(404). `server/.env.example`에 이미 있다. V4 백필 + `claim-legacy`를 마치기 전에는 `true`로 두지 않는다(7.1·7.2절)
- 토큰·비밀번호를 로그·문서·커밋 메시지에 남기지 않는다.

## 5. 배포 절차

```bash
cd ~/Data/Haru-Paper
git pull --ff-only            # 실패하면 멈추고 사용자에게 보고(CLAUDE.md Git 규칙)
cd server
docker compose build
docker compose up -d
docker compose ps
curl -fsS http://127.0.0.1:<포트>/api/health
```

- 로그: `docker compose logs -f haru-api`
- 중지: `docker compose down` — **`down -v`는 금지**(볼륨 = DB·업로드 삭제)
- Flyway 마이그레이션은 `haru-api` 시작 시 자동 적용된다. 실패하면 컨테이너가 뜨지 않으므로 로그 확인

## 6. 백업·복구 [기본값]

### 백업

- `haru-db-backup` 서비스가 `backup-loop.sh`로 매일 **03:00 KST**에 자동 실행:
  - DB 덤프: `mariadb-dump --single-transaction haru_paper | gzip > /backups/db-YYYYMMDD-HHMM.sql.gz`
  - 파일 백업: `haru-files`의 `uploads/`를 `tar.gz`로 `/backups/uploads-YYYYMMDD.tar.gz` (읽기 전용 마운트)
  - 7일(`HARU_BACKUP_RETENTION_DAYS`) 이상 지난 파일 자동 삭제
- 수동 1회 실행 (M2 통과 조건: 백업 파일 생성 확인):
  ```bash
  cd ~/Data/Haru-Paper/server
  docker compose run --rm haru-db-backup /scripts/backup.sh
  # 완료 후 백업 파일 확인
  docker run --rm -v haru-paper_haru-backups:/backups busybox ls -lh /backups/
  ```
- `renders/`는 백업하지 않는다(다시 렌더 가능).
- 스크립트: `/server/scripts/backup.sh` (백업 로직), `/server/scripts/backup-loop.sh` (03:00 루프)

### 복구

```bash
cd ~/Data/Haru-Paper/server
docker compose stop haru-api
gunzip -c <백업경로>/db-YYYYMMDD-HHMM.sql.gz \
  | docker compose exec -T haru-db sh -c 'mariadb -u root -p"$MARIADB_ROOT_PASSWORD" haru_paper'
# uploads 복원: tar를 haru-files 볼륨의 uploads/에 풀기
docker compose start haru-api
```

- 복구 후 렌더는 스케줄러가 다시 만든다.

## 7. 일회성 운영 작업

> 정기 배포 절차(5절)에 포함되지 않는, **순서가 중요한 1회성 절차**를 여기 모은다. 백업·복구(6절)와 같은 문서 안에 있어야 절차 사이 참조가 끊기지 않는다. 계획 원본: `.temp/06-서버-앱-잔여과제-설계.md` 3절(런북 C) — 이 문서로 옮긴 뒤 지운다(CLAUDE.md "진행 방식").

### 7.1 V4 백필 + claim-legacy (1회)

> **되돌리는 마이그레이션이 없다. 6절의 백업이 유일한 롤백 수단이다.**
> `claim-legacy`를 V4보다 먼저 돌리면 **모든 사용자의 렌더가 관리자 소유로 넘어가고, 코드로는 되돌릴 수 없다**(`V4__backfill_render_owner.sql` 주석, [`auth.md`](auth.md) 6절 "운영 주의").
> 전 구간 `~/Data/Haru-Paper/server`에서 실행한다. 다른 프로젝트 컨테이너는 건드리지 않는다.

**배경**: `renders.owner_user_id`는 `RenderServiceImpl`이 렌더 저장 시 한 번도 채우지 않아 M6 이후 지금까지 항상 NULL이었다(커밋 `b762c6c`에서 수정, 이제 `format.getOwnerUserId()`를 복사한다). `server/src/main/resources/db/migration/V4__backfill_render_owner.sql`이 기존 NULL 행을 `formats.owner_user_id`에서 백필하도록 작성돼 있지만, **아직 적용되지 않았다.** Flyway는 `spring.flyway.enabled: true`(`application.yml:23-24`)로 `haru-api` 기동 시 자동 적용되므로, 재빌드·재기동 전까지는 V4 파일이 저장소에 있을 뿐 DB에는 반영되지 않은 상태다.

#### 0단계 — 사전 확인

```bash
cd ~/Data/Haru-Paper
git pull --ff-only          # 실패하면 멈춘다 (CLAUDE.md Git 규칙)
git log --oneline -1        # b762c6c 이상인지
cd server
docker compose ps           # haru-db / haru-api / haru-web / haru-db-backup 상태
```

- **확인할 것**: 지금 떠 있는 `haru-api` 이미지가 `b762c6c` 이전이면, 이번 배포에 V4뿐 아니라 "렌더 소유자 기입 + 다운로드 IDOR 차단 + poll 명령 스코핑"이 **함께** 들어간다. 로그를 볼 때 이 점을 감안한다.
- Pi가 지금 인쇄 대기 중인 예약이 임박했으면(예: 07:00 직전) 뒤로 미룬다. 재기동 중 poll이 몇 번 실패한다.

#### 1단계 — 백업 (필수, 먼저)

6절의 기존 절차를 **그대로** 쓴다. 새로 만들지 않는다.

```bash
cd ~/Data/Haru-Paper/server
docker compose run --rm haru-db-backup /scripts/backup.sh
docker run --rm -v haru-paper_haru-backups:/backups busybox ls -lh /backups/
```

- **검증**: 방금 시각의 `db-YYYYMMDD-HHMM.sql.gz`가 목록에 있고 크기가 0이 아니다. 그 파일 이름을 메모해 둔다 — 이하 `$DUMP`.
- **실패하면 여기서 멈춘다.** 백업 없이 2단계로 넘어가지 않는다.

#### 2단계 — 재빌드·재기동 (Flyway가 V4를 자동 적용)

```bash
cd ~/Data/Haru-Paper/server
docker compose build            # V4 SQL은 jar 안 리소스라 rebuild가 필요하다
docker compose up -d
docker compose ps
docker compose logs --tail=200 haru-api | grep -i -E "flyway|migrat|error"
curl -fsS http://127.0.0.1:<포트>/api/health      # 포트는 .env의 HARU_WEB_BIND
```

- **왜 rebuild인가**: `haru-api`는 `docker-compose.yml`의 `build: .`(멀티스테이지, `server/Dockerfile`)로 빌드된다. 빌더 스테이지가 `./gradlew build`로 만든 jar를 런타임 이미지에 `COPY`하고, `V4__backfill_render_owner.sql`은 `server/src/main/resources/db/migration/`에 있어 그 jar 안 리소스로 들어간다. `docker compose up -d`만 실행하면 이미 떠 있는 옛 이미지가 그대로 재시작될 뿐이라 V4가 컨테이너 안에 없다 — 반드시 `docker compose build`를 먼저 한다.
- **검증**: 로그에 `Migrating schema ... to version "4 - backfill render owner"`와 `Successfully applied 1 migration` 류의 줄. `/api/health` 200.
- **실패 시**: Flyway가 실패하면 `haru-api`가 아예 뜨지 않는다(5절 "Flyway 마이그레이션은 ... 실패하면 컨테이너가 뜨지 않으므로 로그 확인"). 로그를 읽고, 스키마가 반쯤 바뀌었다고 판단되면 6단계 롤백.

#### 3단계 — V4 결과 확인 (claim-legacy **전에**)

```bash
cd ~/Data/Haru-Paper/server
docker compose exec haru-db sh -c \
  'mariadb -u root -p"$MARIADB_ROOT_PASSWORD" haru_paper -e "
     SELECT (SELECT COUNT(*) FROM renders  WHERE owner_user_id IS NULL) AS null_renders,
            (SELECT COUNT(*) FROM formats  WHERE owner_user_id IS NULL) AS null_formats,
            (SELECT COUNT(*) FROM users)                                AS users,
            (SELECT COUNT(DISTINCT owner_user_id) FROM renders
              WHERE owner_user_id IS NOT NULL)                          AS render_owners;"'
```

- 기대: `null_renders`가 크게 줄었고, 남은 값은 `null_formats`에서 파생된 것뿐이다.
- **`users`가 2 이상이면 여기서 멈추고 생각한다.** claim-legacy는 NULL인 모든 행을 관리자 한 명에게 몰아준다. 다른 사용자의 레거시 리소스까지 가져가도 되는지 판단한 뒤 진행한다.
- `null_renders`와 `null_formats`가 이미 0이면 4단계를 건너뛰어도 된다.

#### 4단계 — `claim-legacy` 실행

엔드포인트: `POST /api/admin/claim-legacy` — **세션 로그인 + ADMIN 역할 + CSRF 헤더**가 필요하다
(`AdminController.java:135-148`, `SecurityConfig.java`의 `.requestMatchers("/api/admin/**").hasRole("ADMIN")`, [`auth.md`](auth.md) 6절).
관리자는 `.env`의 `HARU_ADMIN_EMAIL`로 가입한 계정이다(`server/.env.example`, [`auth.md`](auth.md) 3절).

[`api.md`](api.md) 7절의 curl 관례를 그대로 따른다:

```bash
BASE=https://justant-server2.tail2b65d1.ts.net      # 또는 http://127.0.0.1:<HARU_WEB_BIND 포트>
JAR=$(mktemp)

# CSRF 쿠키를 먼저 받는다. /api/auth/login은 permitAll이지만 CSRF 면제는 아니다
# (SecurityConfig의 면제 경로는 기기 Bearer 경로뿐) — 이 GET 없이 POST하면 403이다.
curl -sS -c "$JAR" "$BASE/api/health" >/dev/null
CSRF=$(grep XSRF-TOKEN "$JAR" | awk '{print $NF}')

# 관리자 로그인. 비밀번호는 프롬프트로 받고, curl argv가 아니라 stdin으로 넘긴다
# (argv는 같은 호스트의 ps에 그대로 보인다 — CLAUDE.md 절대금지 4)
read -rsp 'admin password: ' PW; echo
printf '{"email":"%s","password":"%s"}' "<HARU_ADMIN_EMAIL>" "$PW" |
  curl -sS -c "$JAR" -b "$JAR" -H "X-XSRF-TOKEN: $CSRF" -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' --data-binary @-
unset PW

# 로그인 응답으로 세션이 바뀌면 CSRF 토큰도 새로 발급된다 — 다시 읽는다
CSRF=$(grep XSRF-TOKEN "$JAR" | awk '{print $NF}')

curl -sS -b "$JAR" -H "X-XSRF-TOKEN: $CSRF" -X POST "$BASE/api/admin/claim-legacy"
rm -f "$JAR"
```

- **기대 응답**(`AdminController.ClaimLegacyResponse`):
  `{"updatedFormatCount":N,"updatedScheduleCount":N,"updatedAssetCount":N,"updatedRenderCount":N,"updatedCommandCount":N,"updatedResultCount":N}`
- **403이 나면**: 로그인 계정이 admin이 아니거나(`users.role`), CSRF 헤더가 빠졌다. `SecurityConfig.java` 클래스 Javadoc의 함정 두 가지(평문 `CsrfTokenRequestAttributeHandler`·쿠키를 강제로 심는 `csrfCookieFilter`)는 이미 코드에 반영돼 있으므로, `XSRF-TOKEN` 쿠키 값을 **그대로** `X-XSRF-TOKEN` 헤더에 넣으면 된다.
- **401이 나면**: 로그인 실패. 응답 본문의 ProblemDetail을 읽는다.

#### 5단계 — 최종 검증

```bash
cd ~/Data/Haru-Paper/server
docker compose exec haru-db sh -c \
  'mariadb -u root -p"$MARIADB_ROOT_PASSWORD" haru_paper -e "
     SELECT COUNT(*) AS null_renders FROM renders WHERE owner_user_id IS NULL;
     SELECT COUNT(*) AS null_formats FROM formats WHERE owner_user_id IS NULL;
     -- 소유권 엄격 모드(아래 7.2) 전환의 두 번째 사전 조건:
     -- 활성 예약이 자기 소유가 아닌 포맷을 가리키면 그 렌더는 엄격 모드에서 404가 된다.
     SELECT s.id AS schedule_id, s.owner_user_id AS sched_owner, f.owner_user_id AS fmt_owner
       FROM schedules s JOIN formats f ON s.format_id = f.id
      WHERE s.enabled = 1
        AND (f.owner_user_id IS NULL OR s.owner_user_id IS NULL
             OR f.owner_user_id <> s.owner_user_id);"'
```

- **통과 조건**: `null_renders = 0`, `null_formats = 0`, 세 번째 쿼리 **0행**.
- Pi 정상 확인:
  ```bash
  docker compose logs --tail=100 haru-api | grep -E "Poll received|Render saved|no owner_user_id"
  ssh haru-pi 'journalctl -u haru-agent -n 50 --no-pager'
  ```
  `no owner_user_id`류 WARN이 더는 안 나와야 한다.
- 그 다음 예약 인쇄가 **실제로 종이에 나오는 것까지** 확인한 뒤에야 7.2(소유권 엄격 모드 켜기)로 넘어간다(CLAUDE.md 기록 규칙: 전송 성공은 [미검증], 눈으로 봐야 [확인됨]).

#### 6단계 — 롤백

| 상황 | 대응 |
|---|---|
| 2단계에서 Flyway 실패 / `haru-api`가 안 뜸 | 로그 확인 → `docker compose stop haru-api` → 위 "복구" 절차(6절)로 `$DUMP` 복원 → `git checkout <이전 커밋>` 후 `docker compose build && up -d` |
| 4단계를 **잘못된 순서로** 실행함(V4 전에 claim-legacy) | **코드 롤백 불가.** 위 "복구" 절차(6절)로 `$DUMP` 복원이 유일한 수단. 복원 후 2단계부터 다시 |
| 5단계에서 교차 소유 예약이 나옴 | 롤백하지 않는다. 그 예약을 앱에서 지우거나 올바른 포맷으로 다시 만든 뒤 재확인. **7.2(소유권 엄격 모드)는 그 전까지 보류** |
| 인쇄가 안 됨 | `docker compose logs haru-api | grep -i render`, Pi의 `journalctl`. 엄격 모드는 아직 안 켰으므로 이번 배포의 소유권 코드가 원인일 가능성은 낮다 |

`renders/` 파일은 백업 대상이 아니다(6절) — DB 복원 후 렌더 스케줄러가 다시 만든다.

### 7.2 소유권 엄격 모드 켜기 (`HARU_OWNERSHIP_STRICT`)

7.1의 5단계 검증(`null_renders = 0`, `null_formats = 0`, 교차 소유 예약 0행)이 **전부 통과한 뒤에만** 켠다. 이 플래그는 렌더 다운로드 소유권 검사(`DeviceSyncController`)뿐 아니라 예약 생성의 포맷 소유권 검사(`ScheduleService`)까지 지배한다.

```bash
# server/.env에 추가
HARU_OWNERSHIP_STRICT=true
```

```bash
cd ~/Data/Haru-Paper/server
docker compose up -d     # 재빌드 불필요 — env_file: .env로 컨테이너 환경변수만 바뀐다
```

- **재빌드가 필요 없는 이유**: `docker-compose.yml`의 `haru-api`가 `env_file: .env`로 `.env` 전체를 컨테이너 환경변수로 주입하고, Spring의 완화 바인딩이 `HARU_OWNERSHIP_STRICT` → `haru.ownership-strict`로 매핑한다(`DeviceSyncController`의 `@Value("${haru.ownership-strict:false}")`). 코드는 이미 배포돼 있으므로 `.env` 값만 바꾸고 컨테이너를 재시작하면 된다.
- **문제가 생기면**: `server/.env`에서 `HARU_OWNERSHIP_STRICT=false`로 되돌리고 `docker compose up -d`만 하면 수십 초 안에 이전 동작(NULL 허용)으로 복구된다. 재빌드도, DB 복원도 필요 없다.
- 켠 뒤에는 `docker compose logs haru-api | grep RENDER_OWNER_NULL`로 감시한다. 이 로그가 나오면 7.1의 사전 조건이 실은 만족되지 않았다는 뜻이다 — 즉시 `false`로 되돌린다.

## 8. M2 통과 조건

- [x] `docker compose up -d`로 haru-db/haru-api/haru-web/haru-db-backup 기동
- [x] `tailscale serve` HTTPS(사용자 승인 후)에서 `GET /api/health` 200 — 2026-09-14 확인
- [x] [`api.md`](api.md) 7절 curl 시나리오 전부 기대대로
- [x] 미리보기 PNG 폭 = 프로필 폭, 한글 렌더 정상
- [x] 백업 파일 1회 생성 확인
- [x] 호스트에서 `ss -tlnp`로 봤을 때 Haru-Paper가 연 포트는 `127.0.0.1:18080`과
      `<tailscale IP>:18080`(3.1절, 둘 다 사용자 요청으로 추가된 IP:포트 직접 접속 경로)뿐 —
      **`0.0.0.0`에 열린 것은 없어야 한다**
