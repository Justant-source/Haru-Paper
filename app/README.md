# /app — 하루종이 클라이언트

폰에서 포맷을 만들고, 예약하고, 지금 인쇄를 요청하고, 이력과 기기 상태를 보는 클라이언트 모음이다.

| 폴더 | 내용 | 상태 |
|---|---|---|
| `web/` | PoC 웹앱 (React + TypeScript + Vite + PWA) | M0: 빈 폴더. **M3에서 코드 생성** |
| `android/` | 네이티브 Android 앱 자리 (기술 미정) | 빈 폴더 예약만 |
| `ios/` | 네이티브 iOS 앱 자리 (기술 미정) | 빈 폴더 예약만 |

## 담당

- **서버 세션**(`justant-server2`의 `~/Data/Haru-Paper`)이 담당한다.
- 노트북 세션은 `/app`을 수정하지 않는다. Git 규칙은 루트 `CLAUDE.md` 참고.

## 원칙

- 앱은 **프린터를 모른다**. 서버 API와 서버가 렌더한 미리보기 PNG만 다룬다.
- 인증 없음 — Tailscale 내부망이 인증 역할을 한다(PoC).

## 문서

- [docs/app/README.md](../docs/app/README.md) — 읽는 순서
- [docs/app/web.md](../docs/app/web.md) — 웹앱 스택·구조·PWA·M3 통과 조건
- [docs/app/screens.md](../docs/app/screens.md) — 화면 7개 명세
- [docs/app/native.md](../docs/app/native.md) — 네이티브 앱(미정) 메모
- [docs/architecture.md](../docs/architecture.md) — API 규약 원본
