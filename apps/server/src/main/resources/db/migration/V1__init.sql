CREATE TABLE `groups` (
  group_id           VARCHAR(32) PRIMARY KEY,
  group_secret_hash  CHAR(64) NOT NULL,
  created_at         BIGINT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE devices (
  device_id      VARCHAR(32) PRIMARY KEY,
  group_id       VARCHAR(32) NOT NULL,
  device_pubkey  CHAR(44) NOT NULL,
  enc_pubkey     CHAR(44) NOT NULL,
  name           VARCHAR(64),
  platform       VARCHAR(16),
  token          CHAR(43) NOT NULL,
  created_at     BIGINT,
  last_seen_at   BIGINT,
  revoked_at     BIGINT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE UNIQUE INDEX uk_dev_token ON devices(token);
CREATE INDEX idx_dev_group ON devices(group_id);

CREATE TABLE channels (
  channel_no       CHAR(8) PRIMARY KEY,
  secret_hash      CHAR(64) NOT NULL,
  group_id         VARCHAR(32) NOT NULL,
  name             VARCHAR(64),
  max_level        VARCHAR(8) NOT NULL DEFAULT 'error',
  rate_limit       INT NOT NULL DEFAULT 60,
  max_body_bytes   INT NOT NULL DEFAULT 32768,
  max_images       TINYINT NOT NULL DEFAULT 4,
  strict_auth      TINYINT NOT NULL DEFAULT 0,
  fail_count       INT NOT NULL DEFAULT 0,
  locked_until     BIGINT NOT NULL DEFAULT 0,
  muted            TINYINT NOT NULL DEFAULT 0,
  created_at       BIGINT,
  archived_at      BIGINT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_chan_group ON channels(group_id);

CREATE TABLE senders (
  channel_no     CHAR(8) NOT NULL,
  sender_id      VARCHAR(64) NOT NULL,
  display_name   VARCHAR(64),
  avatar_url     VARCHAR(1024),
  avatar_seed    VARCHAR(64),
  msg_count      INT NOT NULL DEFAULT 0,
  last_msg_at    BIGINT,
  PRIMARY KEY (channel_no, sender_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE messages (
  seq             BIGINT AUTO_INCREMENT PRIMARY KEY,
  msg_id          VARCHAR(32) NOT NULL,
  channel_no      CHAR(8) NOT NULL,
  sender_id       VARCHAR(64) NOT NULL,
  msg_type        VARCHAR(16) NOT NULL,
  title           VARCHAR(512),
  body            TEXT NOT NULL,
  summary         VARCHAR(255),
  link_url        VARCHAR(1024),
  link_title      VARCHAR(255),
  link_desc       VARCHAR(512),
  media_keys      TEXT,
  media_status    VARCHAR(12) NOT NULL DEFAULT 'none',
  level           VARCHAR(8),
  created_at      BIGINT NOT NULL,
  client_msg_id   VARCHAR(64),
  deleted_at      BIGINT,
  group_id        VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_msg_id (msg_id),
  -- 幂等键按频道维度唯一（同 channel 重复 msg_key 不重复入库；不带 msg_key 时为 NULL，MySQL 允许重复 NULL）
  UNIQUE KEY uk_msg_idem (channel_no, client_msg_id),
  KEY idx_msg_chan_sender (channel_no, sender_id, seq),
  KEY idx_msg_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
ALTER TABLE messages ADD FULLTEXT INDEX ft_msg (title, summary, body) WITH PARSER ngram;

CREATE TABLE media_objects (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  msg_id           VARCHAR(32) NOT NULL,
  idx              TINYINT NOT NULL,
  source_url       VARCHAR(1024) NOT NULL,
  object_key       VARCHAR(128),
  thumb_key        VARCHAR(128),
  status           VARCHAR(12) NOT NULL DEFAULT 'pending',
  sse_key_wrapped  VARBINARY(128),
  enc_mode         VARCHAR(12) NOT NULL DEFAULT 'none',
  mime             VARCHAR(64),
  width            INT,
  height           INT,
  size             BIGINT,
  sha256           CHAR(64),
  error            VARCHAR(255),
  fetch_attempts   TINYINT NOT NULL DEFAULT 0,
  channel_no       VARCHAR(32) NOT NULL,
  group_id         VARCHAR(32) NOT NULL,
  created_at       BIGINT,
  ready_at         BIGINT,
  deleted_at       BIGINT,
  UNIQUE KEY uk_media (msg_id, idx)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
CREATE INDEX idx_media_status ON media_objects(status, created_at);

CREATE TABLE read_state (
  group_id        VARCHAR(32) NOT NULL,
  channel_no      CHAR(8) NOT NULL,
  sender_id       VARCHAR(64) NOT NULL,
  last_read_seq   BIGINT NOT NULL,
  updated_at      BIGINT,
  device_id       VARCHAR(32),
  PRIMARY KEY (channel_no, sender_id, group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE app_state (
  state_key   VARCHAR(32) PRIMARY KEY,
  state_value TEXT NOT NULL,
  updated_at  BIGINT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
