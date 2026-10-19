-- §14 abuse control: sender allowlist, daily counters, capacity tracking

CREATE TABLE sender_allowlist (
  channel_no     CHAR(8) NOT NULL,
  sender_id      VARCHAR(64) NOT NULL,
  enabled        TINYINT NOT NULL DEFAULT 1,
  created_at     BIGINT NOT NULL,
  PRIMARY KEY (channel_no, sender_id),
  FOREIGN KEY (channel_no) REFERENCES channels(channel_no) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE daily_counters (
  channel_no     CHAR(8) NOT NULL,
  day            DATE NOT NULL,
  msg_count      INT NOT NULL DEFAULT 0,
  image_count    INT NOT NULL DEFAULT 0,
  total_bytes    BIGINT NOT NULL DEFAULT 0,
  updated_at     BIGINT NOT NULL,
  PRIMARY KEY (channel_no, day),
  FOREIGN KEY (channel_no) REFERENCES channels(channel_no) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 频道级配置列：日配额上限、是否开启 sender 白名单模式
ALTER TABLE channels ADD COLUMN daily_msg_limit INT NOT NULL DEFAULT 0 COMMENT '0=不限制';
ALTER TABLE channels ADD COLUMN daily_image_limit INT NOT NULL DEFAULT 0 COMMENT '0=不限制';
ALTER TABLE channels ADD COLUMN sender_whitelist_mode TINYINT NOT NULL DEFAULT 0 COMMENT '1=只接受白名单发送者';

-- 容量统计索引：按 group 聚合查最早消息时间、总字节数
CREATE INDEX idx_msg_group_created ON messages(group_id, created_at);
