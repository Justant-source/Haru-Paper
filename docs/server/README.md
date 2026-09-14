# docs/server — 서버 컨텍스트

하루종이 백엔드(`/server`)를 개발하는 **서버 세션**을 위한 문서다.
이 폴더와 공통 문서만 읽고 M2를 시작할 수 있게 쓴다.

> 표기: **[확인됨]** 실제로 확인 / **[미검증]** 확인 전 / **[추정]** 자료 기반 추론 / **[기본값]** 사용자에게 따로 묻지 않고 정한 값(바꿔도 됨, 바꾸면 이 문서를 고친다)

## 읽는 순서

1. [`../../CLAUDE.md`](../../CLAUDE.md): 규칙, 디렉터리 담당, Git 규칙
2. [`../init_plan.md`](../init_plan.md): 최초 결정 원본(Q1~Q34), 마일스톤
3. [`../architecture.md`](../architecture.md): 전체 구조, 경계, **API 규약 원본**
4. [`deploy.md`](deploy.md): **서버 세션 시작 절차**, compose, 노출, 백업
5. [`format-schema.md`](format-schema.md): 포맷 스키마 v1 원본
6. [`data-model.md`](data-model.md): MariaDB 테이블, Flyway, 파일 저장
7. [`api.md`](api.md): 컨트롤러, 인증, 에러 형식, 멱등, snapshotHash, curl 시나리오
8. [`rendering.md`](rendering.md): 포맷 → HTML → Chromium → PNG, 렌더 스케줄러
9. [`weather.md`](weather.md): Open-Meteo, 날씨 블록

## 서버가 맡는 것 / 모르는 것

| 맡는 것 | 모르는 것 |
|---|---|
| 포맷·에셋·예약·명령·결과 저장 | M832 프로토콜(헤더, 래스터, 디더링) |
| 프린터 프로필 폭으로 **그레이스케일 PNG** 렌더 | Pi의 transport(USB/BT) |
| 날씨 조회 | 용지 감지 방식의 세부 |
| 앱 API, Pi 동기화 API(폴링) | |

흑백 변환(디더링), 좌우 정렬 보정, 1304dot 패딩, 비트 패킹은 **Pi 드라이버 몫**이다.
서버는 `printerProfile.printableWidthPx` 폭의 그레이스케일 PNG만 만든다.

## M2 범위와 통과 조건

범위: 포맷 CRUD·가져오기/내보내기·미리보기, 에셋 업로드, 예약 CRUD, 렌더러·렌더 스케줄러, 날씨, device API, Flyway, compose prod, 백업.

통과 조건(`init_plan.md` 10절):
- `docker compose up`으로 기동
- `tailscale serve` HTTPS에서 `/api/health`가 200 (적용 전 사용자 승인)
- API가 curl 시나리오([`api.md`](api.md) 마지막 절)대로 동작
- 미리보기 PNG 폭이 프로필 폭과 같음
- 한글 렌더 정상
- 백업 파일 1회 생성

## 디렉터리 담당

- 서버 세션: `/server`, `/app`, `/docs/server`, `/docs/app`
- 노트북 세션: `/pi`, `/docs/pi`
- 공통(수정 직전 `git pull --ff-only`): `CLAUDE.md`, `AGENTS.md`, `README.md`, `docs/architecture.md`, `.gitignore`, `.cursor/`
