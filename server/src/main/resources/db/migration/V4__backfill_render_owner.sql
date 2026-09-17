-- renders.owner_user_id 백필 (server/render/RenderServiceImpl 버그 수정 동반).
--
-- 배경: RenderServiceImpl.savRender()가 Render 엔티티를 저장할 때 ownerUserId를 한 번도 채우지 않았다.
-- FormatService·ScheduleService·PrintNowController·AssetService·ResultIngestService는 전부
-- owner_user_id를 설정하는데 렌더만 빠져 있었다 — V2 마이그레이션이 컬럼을 만든 뒤로 지금까지
-- renders.owner_user_id는 항상 NULL이었다.
--
-- 렌더는 포맷에서 파생되므로, 렌더 시점 포맷의 소유자를 복사해 백필한다(RenderServiceImpl이 새 렌더에
-- 대해서도 이제 이렇게 한다). 포맷이 없는 "고아 렌더"는 생길 수 없다 — V1의 fk_renders_format이
-- ON DELETE CASCADE라 포맷이 지워지면 렌더 행도 함께 지워진다. 따라서 이 UPDATE 이후 남는 NULL은
-- `formats.owner_user_id IS NULL`인 레거시 포맷(claim-legacy 전)에서 나온 것뿐이다.
-- DeviceSyncController.assertOwnership은 그 NULL을 레거시로 보고 다운로드를 허용한다(경고 로그).
--
-- 주의(운영): 이 마이그레이션 적용 전에는 AdminService.claimLegacyResources("claim-legacy")를
-- 실행하지 않는다 — 지금 실행하면 renders.owner_user_id가 전부 NULL이라 모든 사용자의 렌더가
-- 관리자 소유로 넘어간다.
UPDATE `renders` r
  INNER JOIN `formats` f ON r.format_id = f.id
  SET r.owner_user_id = f.owner_user_id
  WHERE r.owner_user_id IS NULL
    AND f.owner_user_id IS NOT NULL;
