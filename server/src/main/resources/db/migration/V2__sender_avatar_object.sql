-- §4.5 sender_avatar 转存。avatar_url 一直是第三方自报的"来源"（http(s) URL，或内联图的 sha256 指纹），
-- 下面两列才是服务端侧的结果：
--   avatar_token = sha256(sender_id)：sender_id 允许含 / 与中文，所以它在对象键与 URL 里都以摘要出现；
--   avatar_key   = 桶里的对象键，NULL 表示还没转存成功（客户端此时用首字占位，不直连第三方 URL）。
ALTER TABLE senders ADD COLUMN avatar_key VARCHAR(128) NULL AFTER avatar_url;
ALTER TABLE senders ADD COLUMN avatar_token CHAR(64) NULL AFTER avatar_key;
CREATE INDEX idx_senders_avatar_token ON senders (avatar_token);
