-- M6: 계정·기기 소유
-- .temp/03-플랫폼-작업지시서-v1.0.md 4절, Q13·Q14 근거
-- Character set: utf8mb4, collation: utf8mb4_unicode_ci

CREATE TABLE `users` (
  `id` CHAR(36) NOT NULL PRIMARY KEY,
  `email` VARCHAR(255) NOT NULL,
  `password_hash` VARCHAR(100) NOT NULL,
  `handle` VARCHAR(20) NOT NULL,
  `display_name` VARCHAR(50) NOT NULL,
  `bio` VARCHAR(300),
  `role` ENUM('user','admin') NOT NULL DEFAULT 'user',
  `status` ENUM('active','suspended') NOT NULL DEFAULT 'active',
  `must_change_password` BOOLEAN NOT NULL DEFAULT FALSE,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  UNIQUE KEY `uk_users_email` (`email`),
  UNIQUE KEY `uk_users_handle` (`handle`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 기존 device 테이블(항상 id=1인 단일 행)을 소유자 있는 다중 기기 테이블로 대체한다.
-- PoC 데이터는 1행뿐이고 재사용 가치가 없어 보존하지 않는다 — 작업지시서 Q25:
-- "기존 Pi가 새로 발급한 토큰으로 poll·인쇄 성공"이 통과 조건이므로 재가입이 전제다.
DROP TABLE IF EXISTS `device`;

CREATE TABLE `devices` (
  `id` CHAR(36) NOT NULL PRIMARY KEY,
  `owner_user_id` CHAR(36) NOT NULL,
  `name` VARCHAR(50) NOT NULL DEFAULT '내 프린터',
  `token_hash` CHAR(64) NOT NULL,
  `token_issued_at` DATETIME(3) NOT NULL,
  `last_poll_at` DATETIME(3),
  `last_seen_ip` VARCHAR(45),
  `agent_version` VARCHAR(50),
  `printer_profile` JSON CHECK (JSON_VALID(`printer_profile`)),
  `printer_status` JSON CHECK (JSON_VALID(`printer_status`)),
  `paper_policy` VARCHAR(20),
  `paper_state_manual` BOOLEAN NOT NULL DEFAULT FALSE,
  `paper_state_updated_at` DATETIME(3),
  `paper_state_updated_by` ENUM('app','server'),
  `created_at` DATETIME(3) NOT NULL,
  CONSTRAINT `fk_devices_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  UNIQUE KEY `uk_devices_owner` (`owner_user_id`),
  UNIQUE KEY `uk_devices_token_hash` (`token_hash`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `pairing_codes` (
  `code` CHAR(8) NOT NULL PRIMARY KEY,
  `user_id` CHAR(36) NOT NULL,
  `expires_at` DATETIME(3) NOT NULL,
  `used_at` DATETIME(3),
  `created_at` DATETIME(3) NOT NULL,
  CONSTRAINT `fk_pairing_codes_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Spring Session JDBC 표준 스키마 (org.springframework.session:spring-session-jdbc
-- schema-mysql.sql과 동일). spring.session.jdbc.initialize-schema=never로 두고
-- Flyway가 유일한 스키마 원본이 되게 한다.
CREATE TABLE SPRING_SESSION (
  PRIMARY_ID CHAR(36) NOT NULL,
  SESSION_ID CHAR(36) NOT NULL,
  CREATION_TIME BIGINT NOT NULL,
  LAST_ACCESS_TIME BIGINT NOT NULL,
  MAX_INACTIVE_INTERVAL INT NOT NULL,
  EXPIRY_TIME BIGINT NOT NULL,
  PRINCIPAL_NAME VARCHAR(100),
  CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
  SESSION_PRIMARY_ID CHAR(36) NOT NULL,
  ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
  ATTRIBUTE_BYTES BLOB NOT NULL,
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
  CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
) ENGINE=InnoDB ROW_FORMAT=DYNAMIC;

-- 기존 리소스에 소유자 컬럼 추가 (NULL 허용). 관리자가 로그인 후
-- POST /api/admin/claim-legacy로 NULL인 행을 전부 자기 것으로 가져온다(4.2절).
ALTER TABLE `formats` ADD COLUMN `owner_user_id` CHAR(36) NULL AFTER `id`;
ALTER TABLE `formats` ADD CONSTRAINT `fk_formats_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;
ALTER TABLE `formats` ADD INDEX `idx_formats_owner` (`owner_user_id`);

ALTER TABLE `schedules` ADD COLUMN `owner_user_id` CHAR(36) NULL AFTER `id`;
ALTER TABLE `schedules` ADD COLUMN `device_id` CHAR(36) NULL AFTER `owner_user_id`;
ALTER TABLE `schedules` ADD CONSTRAINT `fk_schedules_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;
ALTER TABLE `schedules` ADD CONSTRAINT `fk_schedules_device` FOREIGN KEY (`device_id`) REFERENCES `devices` (`id`) ON DELETE SET NULL;
ALTER TABLE `schedules` ADD INDEX `idx_schedules_owner` (`owner_user_id`);

ALTER TABLE `assets` ADD COLUMN `owner_user_id` CHAR(36) NULL AFTER `id`;
ALTER TABLE `assets` ADD CONSTRAINT `fk_assets_owner` FOREIGN KEY (`owner_user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE;
ALTER TABLE `assets` ADD INDEX `idx_assets_owner` (`owner_user_id`);

-- renders/commands/results는 기존 관례대로 FK를 걸지 않는다(이력 보존, 포맷·기기 삭제 후에도 남음).
ALTER TABLE `renders` ADD COLUMN `owner_user_id` CHAR(36) NULL AFTER `id`;
ALTER TABLE `renders` ADD INDEX `idx_renders_owner` (`owner_user_id`);

ALTER TABLE `commands` ADD COLUMN `owner_user_id` CHAR(36) NULL AFTER `id`;
ALTER TABLE `commands` ADD COLUMN `device_id` CHAR(36) NULL AFTER `owner_user_id`;
ALTER TABLE `commands` ADD INDEX `idx_commands_owner` (`owner_user_id`);
ALTER TABLE `commands` ADD INDEX `idx_commands_device` (`device_id`);

ALTER TABLE `results` ADD COLUMN `owner_user_id` CHAR(36) NULL AFTER `id`;
ALTER TABLE `results` ADD COLUMN `device_id` CHAR(36) NULL AFTER `owner_user_id`;
ALTER TABLE `results` ADD INDEX `idx_results_owner` (`owner_user_id`);

-- settings는 전역 키-값 1벌이었다. 사용자별로 바뀌므로 새로 만든다.
-- 기존 값(날씨 위치 등)은 PoC 규모라 보존하지 않는다 — claim-legacy 이후 설정 화면에서 다시 저장한다.
DROP TABLE IF EXISTS `settings`;

CREATE TABLE `user_settings` (
  `user_id` CHAR(36) NOT NULL,
  `setting_key` VARCHAR(50) NOT NULL,
  `value` JSON NOT NULL CHECK (JSON_VALID(`value`)),
  `updated_at` DATETIME(3) NOT NULL,
  PRIMARY KEY (`user_id`, `setting_key`),
  CONSTRAINT `fk_user_settings_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
