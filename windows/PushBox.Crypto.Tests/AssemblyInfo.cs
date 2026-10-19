using Xunit;

/// <summary>
/// 整个测试集串行跑。<b>原因只有一个</b>：<see cref="PushBox.Windows.Logging.AppLog"/> 是进程级的一本日志，
/// W15 那两页用例要靠"开在临时目录里的真留痕"断言接入点，而并行时别的用例（跑真删除、真引擎的那些）
/// 会把自己的行一起写进正在被读的那本里——同一句断言就会时多时少。
///
/// 代价是全测试集从两秒变长，换来的是"留痕"这件事不再靠运气：一行日志的归属只能由先后决定，不能由线程决定。
/// </summary>
[assembly: CollectionBehavior(DisableTestParallelization = true)]
