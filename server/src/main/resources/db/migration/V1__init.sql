-- Haru-Paper initial schema
-- Character set: utf8mb4, collation: utf8mb4_unicode_ci

CREATE TABLE `formats` (
  `id` CHAR(36) NOT NULL PRIMARY KEY,
  `name` VARCHAR(100) NOT NULL,
  `schema_version` INT NOT NULL,
  `body` JSON NOT NULL CHECK (JSON_VALID(`body`)),
  `has_dynamic_blocks` BOOLEAN NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  INDEX `idx_formats_updated_at` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `assets` (
  `id` CHAR(36) NOT NULL PRIMARY KEY,
  `content_type` VARCHAR(50) NOT NULL,
  `size_bytes` INT NOT NULL,
  `width_px` INT NOT NULL,
  `height_px` INT NOT NULL,
  `sha256` CHAR(64) NOT NULL,
  `path` VARCHAR(255) NOT NULL,
  `created_at` DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `schedules` (
  `id` CHAR(36) NOT NULL PRIMARY KEY,
  `format_id` CHAR(36) NOT NULL,
  `type` ENUM('recurring','once') NOT NULL,
  `days_of_week` VARCHAR(50),
  `time` TIME NOT NULL,
  `date` DATE,
  `enabled` BOOLEAN NOT NULL DEFAULT TRUE,
  `created_at` DATETIME(3) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL,
  CONSTRAINT `fk_schedules_format` FOREIGN KEY (`format_id`) REFERENCES `formats` (`id`) ON DELETE RESTRICT,
  INDEX `idx_schedules_format` (`format_id`),
  INDEX `idx_schedules_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `renders` (
  `id` CHAR(36) NOT NULL PRIMARY KEY,
  `format_id` CHAR(36) NOT NULL,
  `target_date` DATE NOT NULL,
  `profile_key` VARCHAR(100) NOT NULL,
  `width_px` INT NOT NULL,
  `height_px` INT NOT NULL,
  `sha256` CHAR(64) NOT NULL,
  `path` VARCHAR(255) NOT NULL,
  `kind` ENUM('scheduled','preview','command') NOT NULL,
  `format_updated_at` DATETIME(3) NOT NULL,
  `weather_fetched_at` DATETIME(3),
  `rendered_at` DATETIME(3) NOT NULL,
  CONSTRAINT `fk_renders_format` FOREIGN KEY (`format_id`) REFERENCES `formats` (`id`) ON DELETE CASCADE,
  INDEX `idx_renders_lookup` (`format_id`, `target_date`, `profile_key`, `rendered_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `commands` (
  `id` CHAR(36) NOT NULL PRIMARY KEY,
  `type` ENUM('print_now') NOT NULL,
  `format_id` CHAR(36) NOT NULL,
  `render_id` CHAR(36),
  `paper_confirmed` BOOLEAN NOT NULL,
  `status` ENUM('pending','delivered','done','expired') NOT NULL,
  `created_at` DATETIME(3) NOT NULL,
  `delivered_at` DATETIME(3),
  `completed_at` DATETIME(3),
  INDEX `idx_commands_status` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `results` (
  `id` CHAR(36) NOT NULL PRIMARY KEY,
  `occurrence_key` VARCHAR(80),
  `command_id` CHAR(36),
  `format_id` CHAR(36),
  `render_id` CHAR(36),
  `status` ENUM('printed','dry_run','missed','failed','skipped_no_paper','skipped_clock_unsynced','skipped_printer_offline') NOT NULL,
  `detail` TEXT,
  `scheduled_at` DATETIME(3),
  `executed_at` DATETIME(3) NOT NULL,
  `received_at` DATETIME(3) NOT NULL,
  INDEX `idx_results_executed` (`executed_at`),
  INDEX `idx_results_occurrence` (`occurrence_key`),
  INDEX `idx_results_command` (`command_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `device` (
  `id` TINYINT NOT NULL PRIMARY KEY,
  `last_poll_at` DATETIME(3),
  `agent_version` VARCHAR(50),
  `printer_profile` JSON CHECK (JSON_VALID(`printer_profile`)),
  `printer_status` JSON CHECK (JSON_VALID(`printer_status`)),
  `paper_policy` VARCHAR(20),
  `paper_state_manual` BOOLEAN NOT NULL DEFAULT FALSE,
  `paper_state_updated_at` DATETIME(3),
  `paper_state_updated_by` ENUM('app','server')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE `settings` (
  `setting_key` VARCHAR(50) NOT NULL PRIMARY KEY,
  `value` JSON NOT NULL CHECK (JSON_VALID(`value`)),
  `updated_at` DATETIME(3) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
