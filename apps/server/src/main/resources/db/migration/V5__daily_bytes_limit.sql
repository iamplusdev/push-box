-- §14 收尾：把已经有累计值却从未被比对的 daily_counters.total_bytes 变成一条真上限。
-- 上限落在频道治理位上，与 daily_msg_limit / daily_image_limit 同一语义：0 = 不限制。
-- 其余治理列（rate_limit / max_body_bytes / strict_auth / muted）V1、V4 已建，缺的一直只是写入口，
-- 写入口在 PUT /api/v1/channels/{no}，不需要改表。

ALTER TABLE channels ADD COLUMN daily_bytes_limit BIGINT NOT NULL DEFAULT 0 COMMENT '0=不限制';
