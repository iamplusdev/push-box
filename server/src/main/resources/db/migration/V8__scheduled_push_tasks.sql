-- 管理台定时推送任务：运维在 Web 管理台建任务，服务端按 cron 定时把消息灌进指定频道。
-- 语义与外部系统调 POST /message 一致（同一条入库+下发链路），区别只在凭据：库里只存 secret_hash，
-- 定时任务拿不到明文密钥，所以它走 IngestService 的内部入口、由管理台 JWT 这一侧授权，不重放 secret。

CREATE TABLE scheduled_tasks (
  task_id      VARCHAR(32) PRIMARY KEY,
  group_id     VARCHAR(32) NOT NULL,
  channel_no   CHAR(8) NOT NULL,
  name         VARCHAR(64),
  title        VARCHAR(512),
  content      TEXT NOT NULL,
  level        VARCHAR(8) NOT NULL DEFAULT 'info',
  sender_name  VARCHAR(64),
  images_json  TEXT COMMENT '图片外链数组的 JSON 序列化；抓取转存复用现有媒体链路',
  cron_expr    VARCHAR(64) NOT NULL COMMENT 'Spring CronExpression，必须 6 段（秒 分 时 日 月 周），5 段 unix 写法会被服务端拒',
  enabled      TINYINT NOT NULL DEFAULT 1,
  next_fire_at BIGINT NOT NULL COMMENT '轮询游标：到期即触发，触发后按 cron 重算，CAS 更新防双发',
  last_fire_at BIGINT,
  last_result  VARCHAR(16) COMMENT 'ok / failed，上次触发结论',
  last_error   VARCHAR(255),
  last_msg_id  VARCHAR(32),
  created_at   BIGINT NOT NULL,
  updated_at   BIGINT NOT NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE INDEX idx_sched_due ON scheduled_tasks(enabled, next_fire_at);
CREATE INDEX idx_sched_channel ON scheduled_tasks(channel_no);
