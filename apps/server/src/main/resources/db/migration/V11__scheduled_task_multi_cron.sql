-- 一个任务可以带多条 cron（每行一条，最多 8 条），触发取所有行里最近的那一次。
-- 典型用法是"工作日 09:00 / 周末 12:00"：一条任务、一套正文，而不是建两个只差 cron 的任务。
-- 512 是 8 条 × 单行上限留的余量；老数据都是单行，升级后原样可读。

ALTER TABLE scheduled_tasks
  MODIFY COLUMN cron_expr VARCHAR(512) NOT NULL
    COMMENT '每行一条 Spring CronExpression（6 段），最多 8 条；触发取最近的一条';
