-- §5.2 频道接入（订阅）：设备用「频道号 + 密钥」接入本 Space 内的频道。
--
-- 这张表只是**投递过滤器**，不是权限边界：同 Space 的设备凭 token 始终能读该 Space 的历史（§4.1
-- 定的读凭据就是设备 token，密钥只证明"写入方有权往这个频道发"）。接入改变的是"这条内容帧要不要下发给这台设备"。
CREATE TABLE channel_subscriptions (
  device_id   VARCHAR(32) NOT NULL,
  channel_no  CHAR(8) NOT NULL,
  created_at  BIGINT NOT NULL,
  PRIMARY KEY (device_id, channel_no),
  KEY idx_chansub_channel (channel_no),
  FOREIGN KEY (device_id) REFERENCES devices(device_id) ON DELETE CASCADE,
  -- ON UPDATE CASCADE：频道号码重置会改 channels 主键，接入关系必须跟着迁到新号码，
  -- 否则用户换号之后要逐台设备重新接入一遍，而客户端根本没有这个入口。
  FOREIGN KEY (channel_no) REFERENCES channels(channel_no) ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 设备级开关：1=这台设备只收「已接入频道」的内容帧。
-- 刻意不从"有没有订阅行"推导：那样接入第一个频道会立刻把其余频道静默掐断，
-- 而取消最后一个订阅又会静默恢复全收——两个方向都不是用户要求的动作。
ALTER TABLE devices ADD COLUMN subscribed_only TINYINT NOT NULL DEFAULT 0 COMMENT '1=只接收已接入频道的内容帧';
