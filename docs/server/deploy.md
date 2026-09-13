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
| `haru-web` | `/app/web` 멀티스테이지(Node 빌드 → `nginx` 안정 태그) | 웹앱 `dist` 서빙 + `/api/` → `haru-api:8080` 프록시 | **`${HARU_WEB_BIND}`만**(예: `127.0.0.1:18080`) |
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
  # [미검증] 명령 형식은 서버의 tailscale 버전에 맞춰 `tailscale serve --help`로 확인
  sudo tailscale serve --bg --https=443 http://127.0.0.1:<포트>
  tailscale serve status
  ```

  → `https://justant-server2.tail2b65d1.ts.net/`

- ⚠️ **`tailscale serve` 적용은 반드시 사용자 승인 후에 한다.** 운영 중인 서버 노드의 Tailscale 설정을 바꾸는 일이다(현재 serve 설정 없음).
- tailnet에서 HTTPS 인증서(MagicDNS HTTPS)가 켜져 있어야 할 수 있다 [미검증]. 안 되면 사용자에게 Tailscale 관리 콘솔 설정을 요청한다.
- PWA 설치에는 HTTPS가 필요하다 — 이 경로가 그 조건을 만족한다.
- Cloudflare Tunnel로 공개하지 않는다(PoC는 Tailscale 전용).

## 4. 비밀값

- `server/.env`는 **서버에만** 두고 커밋하지 않는다(공개 저장소). `chmod 600 server/.env`.
- `server/.env.example`을 복사해 채운다:
  - `HARU_DB_PASSWORD`, `HARU_DB_ROOT_PASSWORD`: 새로 생성(`openssl rand -base64 24`)
  - `HARU_DEVICE_TOKEN`: 새로 생성(`openssl rand -hex 32`). **Pi의 `pi/.env`에도 같은 값**을 넣는다(사용자가 전달)
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

- `haru-db-backup`이 매일 **03:00 KST**에 실행:
  - `mariadb-dump --single-transaction haru_paper | gzip > /backups/db-YYYYMMDD-HHMM.sql.gz`
  - `haru-files`의 `uploads/`를 `tar.gz`로 `/backups/uploads-YYYYMMDD.tar.gz`(`haru-files`를 읽기 전용으로 마운트)
  - 7일(`HARU_BACKUP_RETENTION_DAYS`) 지난 파일 삭제
- 수동 1회 실행 방법도 제공한다(M2 통과 조건: 백업 파일 1회 생성).
- `renders/`는 백업하지 않는다(다시 렌더 가능).

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

- [ ] `docker compose up -d`로 4개 서비스 기동, `haru-db`·`haru-api` healthy
- [ ] `tailscale serve` HTTPS(사용자 승인 후)에서 `GET /api/health` 200
- [ ] [`api.md`](api.md) 7절 curl 시나리오 전부 기대대로
- [ ] 미리보기 PNG 폭 = 프로필 폭, 한글 렌더 정상
- [ ] 백업 파일 1회 생성 확인
- [ ] 호스트에서 `ss -tlnp`로 봤을 때 Haru-Paper가 연 포트는 `127.0.0.1:<포트>` 하나뿐
