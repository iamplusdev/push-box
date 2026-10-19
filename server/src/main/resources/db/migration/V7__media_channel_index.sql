-- 为 media_objects 表添加 channel_no + status 复合索引，优化频道维度的媒体查询
-- 典型场景：按频道列出待抓取/失败的媒体对象（MediaFetchService 的重试队列）
CREATE INDEX idx_media_channel_status ON media_objects(channel_no, status, created_at);
