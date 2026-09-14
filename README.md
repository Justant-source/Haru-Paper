# 하루종이 (Haru-Paper)

**디지털 디톡스를 종이로.** 아침에 폰을 여는 대신, 전날 폰에서 골라 둔 콘텐츠가 정해진 시각에
작은 감열 프린터로 한 장 인쇄되어 나온다.

- 폰 웹앱에서 **포맷**(텍스트·이미지·날짜·날씨 블록을 쌓은 종이 한 장)을 만들고
- **예약**(요일별 반복, 일회성, 지금 인쇄)을 걸면
- 집에 있는 작은 보드(Orange Pi Zero 2W)가 **인터넷이 끊겨도** 그 시각에 프린터로 인쇄한다

> 상태: **PoC — M2/M3 코드는 있음.** 서버(Spring Boot 4 + Docker)와 웹앱(PWA)이 `main`에 있다.
> M1 통과 조건(detox-printer dry-run 바이트 동일 + 실물 1회)은 아직이다. M4 Pi 에이전트는 초안만.
> 최초 결정 기록은 [`docs/init_plan.md`](docs/init_plan.md).

## 구조

```
폰 웹앱(PWA) ──HTTPS── 서버(Spring Boot, Docker) ──폴링── Pi 에이전트(Python) ──BT/USB── 감열 프린터
                         (전 구간 사설 VPN 내부망)
```

| 구성 | 디렉터리 | 하는 일 |
|---|---|---|
| 앱 | [`app/`](app/) | 포맷 편집·미리보기, 예약, 지금 인쇄, 이력·기기 상태 (React + TypeScript + Vite PWA) |
| 서버 | [`server/`](server/) | 포맷·예약 저장, 프린터 폭에 맞춘 PNG 렌더, 날씨, Pi 동기화 API (Spring Boot 4 + Java 21 + MariaDB) |
| Pi | [`pi/`](pi/) | 서버 폴링, 예약 로컬 계산, PNG 캐시, 프린터 드라이버·전송, 결과 업로드 (Python) |

- 프린터 프로토콜을 아는 코드는 `pi/printer/<모델>`뿐이다. 서버와 앱은 "폭 N px, dpi D의 종이"만 안다
- 지원 프린터: Phomemo M832(300dpi), 110mm 연속 롤. 프로토콜은 선행 프로젝트
  [detox-printer](https://github.com/Justant-source/detox-printer)에서 리버싱했다

## 문서

- [`docs/architecture.md`](docs/architecture.md) — 전체 구조, 도메인 모델, API 규약, 동기화 흐름
- [`docs/init_plan.md`](docs/init_plan.md) — 최초 결정 기록과 마일스톤
- [`docs/pi/`](docs/pi/) — Pi 에이전트, 프린터 드라이버, 운영 정책, 설치
- [`docs/server/`](docs/server/) — API 구현, 데이터 모델, 포맷 스키마, 렌더링, 배포
- [`docs/app/`](docs/app/) — 웹앱, 화면, 네이티브 계획

## 마일스톤

| | 내용 | 상태 |
|---|---|---|
| M0 | 저장소 뼈대, 문서 | 완료 |
| M1 | M832 드라이버 이식 + USB 전송 | 코드 초안 있음, 통과 조건 미달 |
| M2 | 서버 | 코드·compose 있음 |
| M3 | 웹앱 | 코드 있음 |
| M4 | Pi 에이전트 | 초안 |
| M5 | Pi 실물 설치, 연결 방식 결정 | 대기 |

PoC 완료 기준: 인터넷을 끊은 상태에서도 예약한 07:00에 인쇄되고, 복구 후 앱에 이력이 뜨는 것을 3일 연속.
