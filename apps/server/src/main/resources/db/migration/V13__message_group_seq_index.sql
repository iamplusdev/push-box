-- 补拉与在线判断都按 (group_id, seq) 取：
--   SELECT ... WHERE group_id = ? AND seq > ? ORDER BY seq          （PushboxWsHandler.replay / REST 补拉）
--   SELECT COALESCE(MAX(seq), 0) FROM messages WHERE group_id = ?    （latestSeq，每次 WS 握手一次）
-- 既有索引里只有 (group_id, created_at)：它能收敛到"这个组的行"，但 seq 不在索引里，
-- 于是两条都要把整个组读一遍、排序也只可能 filesort。一个组攒到上千条之后，
-- 每一次断线重连都会付这一笔，而重连恰好是最需要快的时刻。
CREATE INDEX idx_msg_group_seq ON messages(group_id, seq);
