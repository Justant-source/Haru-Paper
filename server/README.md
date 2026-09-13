# server — 하루종이 백엔드

- **담당**: 서버 세션(`justant-server2`의 `~/Data/Haru-Paper`). 노트북 세션은 이 디렉터리를 수정하지 않는다.
- **스택**: Spring Boot 4 + Java 21 + Gradle(Groovy DSL), MariaDB(프로젝트 전용 컨테이너) + Flyway, Docker compose(**prod만**)
- **버전 메모**: `init_plan.md` Q15 원안은 Spring Boot 3이었으나, M2 스캐폴딩 시점(2026-09)에 Spring Boot 4.1.1이 안정 릴리스로 나와 있어 사용자 승인 하에 4.1.1로 진행했다(`build.gradle` 참고). `init_plan.md`는 원본 기록이라 고치지 않는다.
- **하는 일**: 포맷·예약 저장, 프린터 프로필 폭으로 PNG 렌더, 날씨, Pi 동기화 API
- **모르는 것**: M832 프로토콜. 서버는 "폭 N px, dpi D의 종이"만 안다.

## 현재 상태

M2 스캐폴드 완료: Gradle 프로젝트, `common` 패키지(예외 처리·시간 유틸·ObjectMapper), Flyway `V1__init.sql`(테이블 8개), Dockerfile, `docker-compose.yml` 뼈대. 도메인 로직(format/asset/schedule/command/device/render/weather/settings/history)은 비어 있고 병렬 작업으로 채운다.

## 문서

작업 전에 [`docs/server/README.md`](../docs/server/README.md)의 읽는 순서대로 읽는다.

- [API 구현](../docs/server/api.md): 규약 원본은 [`docs/architecture.md`](../docs/architecture.md)
- [데이터 모델](../docs/server/data-model.md)
- [포맷 스키마 v1](../docs/server/format-schema.md)
- [렌더링](../docs/server/rendering.md)
- [날씨](../docs/server/weather.md)
- [배포](../docs/server/deploy.md)

## 구현 규칙

- **ID 생성**: 모든 엔티티 PK는 UUID 문자열(CHAR(36)). 서비스 계층에서 저장 전에 `UUID.randomUUID().toString()`으로 채운다. 자동 생성 전략 사용 금지.
- **JSON 컬럼**: MariaDB `JSON` 컬럼(formats.body, device.printer_profile 등)은 엔티티 필드를 **String**으로 매핑(`columnDefinition = "JSON"`). 서비스 계층에서 Jackson `ObjectMapper`로 DTO ↔ String 변환. Hibernate JSON 매핑 기능 사용 금지.
- **ObjectMapper**: `com.harupaper.server.common.config.JsonConfig`에 공용 빈 등록됨. JavaTimeModule 포함.
- **시간**: UTC 저장, KST 표시. DB는 `hibernate.jdbc.time_zone=UTC` 고정. API 응답은 TimeUtils로 ISO-8601 +09:00 변환.
- **예외**: 도메인 패키지가 예외를 던지면 `GlobalExceptionHandler`가 RFC 9457 `ProblemDetail`로 처리한다.

## 환경변수

`.env.example`을 `.env`로 복사해 채운다. `.env`는 커밋하지 않는다(공개 저장소).
