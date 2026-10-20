-- 管理台定时任务的分类标签：给任务按用途分组（公告 / 巡检 / 营销……），列表可按它筛选。
-- 语义是自由标签，不是字典表：值由建站者自己写，服务端只做 trim 与长度收口，
-- 空串就是"未分类"。所以这里没有外键、也没有 CHECK 约束——建一张分类字典表要连带
-- 一组增删改端点与一个管理页，远超"给列表加个筛选"需要的东西。
-- 列加在末尾而不是 AFTER name：ALTER 追加列在 MySQL 8 上一定能走 INSTANT，指定位置则不然。

ALTER TABLE scheduled_tasks ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT '' COMMENT '自由标签，空串=未分类';
