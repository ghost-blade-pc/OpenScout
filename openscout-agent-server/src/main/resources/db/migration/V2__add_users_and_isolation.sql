-- V2: 用户体系与数据隔离
-- 新建 users 表, 为现有业务表添加 user_id 以支持多用户

CREATE TABLE IF NOT EXISTS users (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  api_key VARCHAR(128) NOT NULL,
  name VARCHAR(120) NOT NULL DEFAULT '',
  role VARCHAR(40) NOT NULL DEFAULT 'user',
  enabled TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_users_api_key (api_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 业务表新增 user_id (NULLABLE 兼容已有数据)
ALTER TABLE learning_goal ADD COLUMN IF NOT EXISTS user_id BIGINT NULL AFTER id;
ALTER TABLE learning_task ADD COLUMN IF NOT EXISTS user_id BIGINT NULL AFTER id;
ALTER TABLE agent_trace ADD COLUMN IF NOT EXISTS user_id BIGINT NULL AFTER id;

-- 用户隔离查询索引
CREATE INDEX IF NOT EXISTS idx_learning_goal_user ON learning_goal(user_id);
CREATE INDEX IF NOT EXISTS idx_learning_task_user ON learning_task(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_trace_user ON agent_trace(user_id);
