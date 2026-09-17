-- 1-bpp PBM(P4) 출력 (docs/architecture.md 3.4, 2026-09-17 사용자 승인 — 2단계 MCU 기기용).
-- 기존 렌더 행은 두 컬럼 다 NULL로 남는다: PBM이 없다는 뜻이고, 스냅샷의 urlPbm/sha256Pbm도
-- 그 렌더에 한해 null이 된다(docs/server/api.md, DeviceSyncService.toRenderDto).
ALTER TABLE `renders`
  ADD COLUMN `pbm_sha256` CHAR(64) NULL AFTER `sha256`,
  ADD COLUMN `pbm_path` VARCHAR(255) NULL AFTER `path`;
