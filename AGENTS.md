# 하루종이 — 에이전트 진입점

**정본은 [`CLAUDE.md`](CLAUDE.md)다.** 이 파일은 Cursor·Codex 등이 찾는 라우터일 뿐, 규칙을 다시 정의하지 않는다. 충돌하면 `CLAUDE.md`를 따른다.

## Cursor ↔ Claude Code

| Claude Code | Cursor |
|---|---|
| `CLAUDE.md` | 동일 파일 (Cursor도 워크스페이스 규칙으로 로드) |
| `.claude/skills/` | 동일 경로 (프로젝트 스킬 공유 — `.cursor/skills`에 복사하지 않음) |
| `.claude/settings.local.json` deny | [`.cursor/cli.json`](.cursor/cli.json) + [`.cursor/hooks.json`](.cursor/hooks.json) |
| 세션 시작 `hostname` | `.cursor/hooks/session-start.py` |

머신별 Claude 허용 목록(프린터 ping·SSH 등)은 gitignore된 `.claude/settings.local.json`에만 둔다. Cursor 공유 deny는 `.cursor/cli.json`에 커밋한다.

## 세션 시작

1. `hostname`으로 `justant-server2`인지 확인한다. 다른 머신이면 멈추고 사용자에게 보고한다 — 노트북 WSL은 더 이상 이 저장소를 담당하지 않는다.
2. 작업 시작 전 `git pull --ff-only`. fast-forward 실패 시 멈추고 보고한다. merge/rebase/reset으로 합치지 않는다.
3. 병렬 에이전트를 쓸 때는 파일이 겹치지 않는 단위로만 나눈다.

상세·절대 금지·마일스톤은 `CLAUDE.md`와 `docs/`다.

## 문서 라우팅 (범위 → 진입 문서만)

| 작업 범위 | 진입 |
|---|---|
| 문서 색인·현재 상태 | `docs/README.md` |
| 접속·환경(Pi SSH, sudo, detox-printer 위치) | `docs/environment.md` |
| 전체 구조·API 규약 | `docs/architecture.md` |
| 최초 결정·마일스톤(M0~M5) | `docs/init_plan.md` (원본 기록 — 고치지 않음) |
| Pi / 프린터 | `docs/pi/README.md` |
| 서버 | `docs/server/README.md` |
| 앱 | `docs/app/README.md` |
| 진행 중인 계획(하드웨어 PoC, ESP32, 플랫폼, 편집기) | `.temp/` |
| 스킬 트리거 | `.claude/skills/*/SKILL.md` |
