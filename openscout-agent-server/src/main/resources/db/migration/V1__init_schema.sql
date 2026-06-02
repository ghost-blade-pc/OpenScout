-- V1: Baseline schema — mirrors deploy/init.sql table definitions.
-- Flyway connects to an existing database; CREATE DATABASE / USE are handled by Docker init.

CREATE TABLE IF NOT EXISTS repo_info (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  owner VARCHAR(120) NOT NULL,
  repo_name VARCHAR(160) NOT NULL,
  full_name VARCHAR(300) NOT NULL,
  html_url VARCHAR(500) NULL,
  description VARCHAR(1000) NULL,
  language VARCHAR(80) NULL,
  stars BIGINT NOT NULL DEFAULT 0,
  forks BIGINT NOT NULL DEFAULT 0,
  topics_json JSON NULL,
  license VARCHAR(120) NULL,
  open_issues BIGINT NOT NULL DEFAULT 0,
  default_branch VARCHAR(120) NULL,
  archived TINYINT(1) NOT NULL DEFAULT 0,
  pushed_at DATETIME NULL,
  updated_at DATETIME NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  modified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_repo_full_name (full_name),
  KEY idx_repo_language (language),
  KEY idx_repo_updated_at (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS repo_analysis (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  repo_id BIGINT NULL,
  full_name VARCHAR(300) NOT NULL,
  summary TEXT NULL,
  tech_stack_json JSON NULL,
  learning_value TEXT NULL,
  difficulty VARCHAR(80) NULL,
  module_summary JSON NULL,
  total_score INT NOT NULL DEFAULT 0,
  score_breakdown_json JSON NULL,
  evidence_json JSON NULL,
  analyzed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_repo_analysis_full_name (full_name),
  KEY idx_repo_analysis_repo_id (repo_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_goal (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  goal_text VARCHAR(1000) NOT NULL,
  target_stack VARCHAR(500) NULL,
  duration_days INT NOT NULL DEFAULT 7,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS learning_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  goal_id BIGINT NOT NULL,
  day_no INT NOT NULL,
  task_title VARCHAR(300) NOT NULL,
  task_detail TEXT NULL,
  expected_output TEXT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'TODO',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_learning_task_goal_day (goal_id, day_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_trace (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id VARCHAR(80) NOT NULL,
  conversation_id VARCHAR(80) NULL,
  user_question VARCHAR(1000) NOT NULL,
  tool_calls_json JSON NULL,
  score_summary JSON NULL,
  final_answer TEXT NULL,
  latency_ms BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(40) NOT NULL DEFAULT 'SUCCESS',
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_agent_trace_id (trace_id),
  KEY idx_agent_trace_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
