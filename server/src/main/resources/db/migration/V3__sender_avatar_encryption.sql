-- §4.5 对象加密。头像对象和正文图片在同一个桶里，pb.s3.object-encrypt=sse-c 之后它也必须落密文，
-- 否则桶里就留下一片明文缺口。下面两列与 media_objects 同名同义，只是描述的是 avatar_key 那一个对象：
--   sse_key_wrapped = 该对象的 SSE-C 客户钥用 pb.s3.master-key 封装后的字节（none 模式为 NULL）；
--   enc_mode        = 这个对象当时是怎么写进去的，读侧只认这一列，所以切换开关不影响历史头像。
ALTER TABLE senders ADD COLUMN sse_key_wrapped VARBINARY(128) NULL AFTER avatar_token;
ALTER TABLE senders ADD COLUMN enc_mode VARCHAR(12) NOT NULL DEFAULT 'none' AFTER sse_key_wrapped;
