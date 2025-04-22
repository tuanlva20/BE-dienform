-- Tạo schema
CREATE DATABASE IF NOT EXISTS `fill_form`;
USE `fill_form`;

-- Tạo bảng form
CREATE TABLE IF NOT EXISTS `form` (
            `id` VARCHAR(36) NOT NULL,
            `name` VARCHAR(255) NOT NULL,
            `edit_link` VARCHAR(512) NOT NULL,
            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            `updated_at` DATETIME NULL,
            `status` VARCHAR(50) NOT NULL,
            PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tạo bảng question
CREATE TABLE IF NOT EXISTS `question` (
            `id` VARCHAR(36) NOT NULL,
            `form_id` VARCHAR(36) NOT NULL,
            `title` TEXT NOT NULL,
            `description` TEXT NULL,
            `type` VARCHAR(50) NOT NULL,
            `required` BOOLEAN NOT NULL DEFAULT FALSE,
            `position` INT NOT NULL DEFAULT 0,
            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (`id`),
            CONSTRAINT `fk_question_form` FOREIGN KEY (`form_id`)
                REFERENCES `form` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tạo bảng question_option
CREATE TABLE IF NOT EXISTS `question_option` (
            `id` VARCHAR(36) NOT NULL,
            `question_id` VARCHAR(36) NOT NULL,
            `option_text` TEXT NOT NULL,
            `option_value` VARCHAR(255) NULL,
            `position` INT NOT NULL DEFAULT 0,
            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (`id`),
            CONSTRAINT `fk_option_question` FOREIGN KEY (`question_id`)
                REFERENCES `question` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tạo bảng form_statistic
CREATE TABLE IF NOT EXISTS `form_statistic` (
            `id` VARCHAR(36) NOT NULL,
            `form_id` VARCHAR(36) NOT NULL,
            `total_survey` INT NOT NULL DEFAULT 0,
            `completed_survey` INT NOT NULL DEFAULT 0,
            `failed_survey` INT NOT NULL DEFAULT 0,
            `error_question` INT NOT NULL DEFAULT 0,
            `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (`id`),
            UNIQUE KEY `uk_form_statistic_form` (`form_id`),
            CONSTRAINT `fk_statistic_form` FOREIGN KEY (`form_id`)
                REFERENCES `form` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tạo bảng fill_request
CREATE TABLE IF NOT EXISTS `fill_request` (
            `id` VARCHAR(36) NOT NULL,
            `form_id` VARCHAR(36) NOT NULL,
            `survey_count` INT NOT NULL DEFAULT 0,
            `price_per_survey` DECIMAL(10,2) NOT NULL DEFAULT 0.00,
            `total_price` DECIMAL(10,2) NOT NULL DEFAULT 0.00,
            `is_human_like` BOOLEAN NOT NULL DEFAULT FALSE,
            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            `status` VARCHAR(50) NOT NULL,
            PRIMARY KEY (`id`),
            CONSTRAINT `fk_fill_request_form` FOREIGN KEY (`form_id`)
                REFERENCES `form` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tạo bảng fill_schedule
CREATE TABLE IF NOT EXISTS `fill_schedule` (
            `id` VARCHAR(36) NOT NULL,
            `fill_request_id` VARCHAR(36) NOT NULL,
            `is_dynamic_by_time` BOOLEAN NOT NULL DEFAULT FALSE,
            `min_interval` INT NULL,
            `max_interval` INT NULL,
            `start_date` DATE NULL,
            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            `updated_at` DATETIME NULL,
            PRIMARY KEY (`id`),
            UNIQUE KEY `uk_schedule_fill_request` (`fill_request_id`),
            CONSTRAINT `fk_schedule_fill_request` FOREIGN KEY (`fill_request_id`)
                REFERENCES `fill_request` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tạo bảng answer_distribution
CREATE TABLE IF NOT EXISTS `answer_distribution` (
            `id` VARCHAR(36) NOT NULL,
            `fill_request_id` VARCHAR(36) NOT NULL,
            `question_id` VARCHAR(36) NOT NULL,
            `option_id` VARCHAR(36) NOT NULL,
            `percentage` INT NOT NULL DEFAULT 0,
            `value_string` LONGTEXT NULL,
            `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (`id`),
            CONSTRAINT `fk_distribution_fill_request` FOREIGN KEY (`fill_request_id`)
                REFERENCES `fill_request` (`id`) ON DELETE CASCADE,
            CONSTRAINT `fk_distribution_question` FOREIGN KEY (`question_id`)
                REFERENCES `question` (`id`) ON DELETE CASCADE,
            CONSTRAINT `fk_distribution_option` FOREIGN KEY (`option_id`)
                REFERENCES `question_option` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tạo bảng survey_execution
CREATE TABLE IF NOT EXISTS `survey_execution` (
            `id` VARCHAR(36) NOT NULL,
            `fill_request_id` VARCHAR(36) NOT NULL,
            `execution_time` DATETIME NOT NULL,
            `status` VARCHAR(50) NOT NULL,
            `error_message` TEXT NULL,
            `response_data` JSON NULL,
            PRIMARY KEY (`id`),
            CONSTRAINT `fk_execution_fill_request` FOREIGN KEY (`fill_request_id`)
                REFERENCES `fill_request` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tạo indexes để tăng hiệu suất truy vấn
CREATE INDEX `idx_question_form` ON `question` (`form_id`);
CREATE INDEX `idx_option_question` ON `question_option` (`question_id`);
CREATE INDEX `idx_fill_request_form` ON `fill_request` (`form_id`);
CREATE INDEX `idx_fill_schedule_request` ON `fill_schedule` (`fill_request_id`);
CREATE INDEX `idx_answer_distribution_request` ON `answer_distribution` (`fill_request_id`);
CREATE INDEX `idx_answer_distribution_question` ON `answer_distribution` (`question_id`);
CREATE INDEX `idx_answer_distribution_option` ON `answer_distribution` (`option_id`);
CREATE INDEX `idx_survey_execution_request` ON `survey_execution` (`fill_request_id`);
CREATE INDEX `idx_survey_execution_time` ON `survey_execution` (`execution_time`);
