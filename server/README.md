# server — 하루종이 백엔드

- **담당**: 서버 세션(`justant-server2`의 `~/Data/Haru-Paper`). 노트북 세션은 이 디렉터리를 수정하지 않는다.
- **스택**: Spring Boot 3 + Java 21 + Gradle(Groovy DSL), MariaDB(프로젝트 전용 컨테이너) + Flyway, Docker compose(**prod만**)
- **하는 일**: 포맷·예약 저장, 프린터 프로필 폭으로 PNG 렌더, 날씨, Pi 동기화 API
- **모르는 것**: M832 프로토콜. 서버는 "폭 N px, dpi D의 종이"만 안다.

## 현재 상태

M0: 코드 없음. 이 디렉터리에는 README와 `.env.example`만 있다.
M2에서 Spring Initializr로 생성한다(Gradle Groovy, Java 21).

## 문서

작업 전에 [`docs/server/README.md`](../docs/server/README.md)의 읽는 순서대로 읽는다.

- [API 구현](../docs/server/api.md): 규약 원본은 [`docs/architecture.md`](../docs/architecture.md)
- [데이터 모델](../docs/server/data-model.md)
- [포맷 스키마 v1](../docs/server/format-schema.md)
- [렌더링](../docs/server/rendering.md)
- [날씨](../docs/server/weather.md)
- [배포](../docs/server/deploy.md)

## 환경변수

`.env.example`을 `.env`로 복사해 채운다. `.env`는 커밋하지 않는다(공개 저장소).
