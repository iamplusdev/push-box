-- 定时任务自带时区：cron 的"每天 09:00"按任务时区解释，正文里的时间变量也按它渲染。
-- 默认值刻意写死 Asia/Shanghai 而不是跟服务端时区走：一条业务消息落在几点该由任务说了算，
-- 换个部署机器就整体漂 8 小时是没人能预期的。老任务升级后行为不变。

ALTER TABLE scheduled_tasks
  ADD COLUMN timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' AFTER cron_expr;
