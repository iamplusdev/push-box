-- 定时任务自带的发送者身份：sender_id 决定客户端把它归到哪个会话，sender_avatar 决定头像来源。
-- 老任务默认 'scheduled'，就是升级前写死的那个常量——迁移后投递结果逐字节不变。
-- 头像列与 senders.avatar_url 同宽（1024）：这里只存第三方给的来源 URL，转存仍走 §4.5 那条链路。

ALTER TABLE scheduled_tasks
  ADD COLUMN sender_id VARCHAR(64) NOT NULL DEFAULT 'scheduled'
    COMMENT '发送者标识，决定客户端会话归属；留空按 scheduled 落库',
  ADD COLUMN sender_avatar VARCHAR(1024) NULL
    COMMENT '头像来源 URL（仅 http(s)），触发时由服务端转存；NULL 表示这条任务不带头像';
