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
3. 노트북의 `.claude/settings.local.json`을 서버 `~/Data/Haru-Paper/.claude/`로 복사한다(gitignore라 clone으로 오지 않음).
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

## 7. M2 통과 조건

- [x] `docker compose up -d`로 haru-db/haru-api/haru-web/haru-db-backup 기동
- [x] `tailscale serve` HTTPS(사용자 승인 후)에서 `GET /api/health` 200 — 2026-09-14 확인
- [x] [`api.md`](api.md) 7절 curl 시나리오 전부 기대대로
- [x] 미리보기 PNG 폭 = 프로필 폭, 한글 렌더 정상
- [x] 백업 파일 1회 생성 확인
- [x] 호스트에서 `ss -tlnp`로 봤을 때 Haru-Paper가 연 포트는 `127.0.0.1:18080`과
      `<tailscale IP>:18080`(3.1절, 둘 다 사용자 요청으로 추가된 IP:포트 직접 접속 경로)뿐 —
      **`0.0.0.0`에 열린 것은 없어야 한다**
