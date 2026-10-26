# PushBox 更新日志

本文档记录每个版本的核心改动、新增功能与修复的缺陷。详细技术决策见 [软件设计方案](docs/软件设计方案.md)。

首个正式版本是 v1.0.0。发布前的 28 轮内部流水（原编号 v1.1–v1.28）已归档到
[开发轮次记录](docs/开发轮次记录.md)，那里按时间保留每一轮的缺陷、判据与验证结论。

---

---

## 未发布 —— Windows：窄屏单栏真正生效，色板对比度收口

> 起因两条：① 窗口拖到 720 以下时"退化单栏"只是纸面上的——列表列的 `Width` 虽然设成 0，XAML 里的 `MinWidth=200` 会把它夹回来，对话区白让 200px；未选会话那支反过来被 `MaxWidth=400` 截住，700 宽的窗口右边空出一大块。② 浅色下 `Bg.Elevated` 与 `Divider` 同为 `#E0E0E0`，卡片边框和菜单栏右边框落在同色的底上等于没画；`Fg.Secondary #757575` 在 `#E0E0E0` 上只有 3.5:1，`Accent #14A36A` 当正文色 3.1:1、白字压在其上 3.2:1；设置窗还自带 8 处 `#0ABF5E`（白字 2.4:1）与深色模式下的白底输入框。

### Windows

- **摆列收敛到 `MainModel.PaneLayoutFor`**：返回四列宽度、列表列的 Min/Max 与气泡百分比吃的右栏宽，窗口只负责抄到 `ColumnDefinition` 上。窄屏两支把列表列的 `MinWidth` 放开到 0、`MaxWidth` 放开到 ∞，回到宽屏再装回 200/400——`Width=0` 单独不够，WPF 会按 MinWidth 夹住。
- **色板分层与对比度**：`Bg.Elevated` 浅色 `#FFFFFF` / 深色 `#2A2A2A`（抬升方向统一为"越浮起来越亮"），`Divider` 浅色 `#CFCFCF`；`Fg.Secondary` 浅色 `#5F5F5F`（页面/卡片/列表三种底都 ≥5:1）；`Accent` 浅色 `#0F7A4F`（白字 5.4:1、当正文色 5.1:1），深色 `#3FBF7A`（深底 6.6:1）。
- **新增 `OnAccent` 与 `Bg.AccentTint`**：浅色主色偏深用白字、深色主色偏亮用深墨绿，压在主色实底上的字一律走 `OnAccent`（首启主按钮、设置页按钮与勾选框）；选中项的浅绿底从"内联 `#0ABF5E` + Opacity"改成 token，深色下不再是一块发灰的绿。
- **设置窗去 token 孤岛**：8 处硬编码绿改成 `Accent`，两处 14px 标题改用 `Text.H3`，导航项 14px 归到 13px；`TextBox` 补 `Background`/`Foreground`/`CaretBrush`，深色模式不再是"白底浅字"。`SectionTitleStyle`/`DescriptionStyle` 两个本地字号样式由 `Text.H3`/`Text.Label` 取代。

### 测试

- `dotnet test apps/windows/PushBox.sln`：**561/561 通过**（本轮新增 3 条）。
- `MainModelTest` 新增两条：宽屏四列与列表上下限、窄屏两支的"收起/吃满"及 Min/Max 放开；`MainWindowTest` 新增一条把模型算出的列宽真摆进 WPF `Grid` 再量 `ActualWidth` 的用例——只断模型数值的话，忘了把 Min/Max 抄上去照样能过。
- `ThemeTest` 的两份字典与文档值同步（新增 `OnAccent`/`Bg.AccentTint`）；`ci.yml` 的 `$CS_BASELINE` 与六处文档现值 `558` → `561`。
- **没跑的**：浅色/深色两套配色的真机观感（改的是数值，仍建议装机看一眼）；设置页 `ComboBox` 的深色模板还没做（下拉弹层仍是系统白底），留给下一批。

---

## 未发布 —— Windows：开机自启只在托盘起，长消息不再被裁成一行

> 起因两条：① 开机自启那一笔注册表只写了 exe 路径，每次登录都把主窗摆到脸上，"自启"应当等于"帮我收消息"而不是"开机先开一扇窗"；② 时间线里长正文被排成一条一万多像素的长条，超出一屏的那截被直接裁掉，看起来就是"消息内容显示不全"。

### Windows

- **自启那一次不 Show 主窗**：`HKCU\...\Run` 里写的是 `"<exe 全路径>" --tray`（`AutoStart.CommandFor`）。带 `--tray` 启动时托盘、同步循环、补拉与通知照常起来，只有主窗不 Show；用户从托盘点开才是第一屏。
- **接线挪进窗口构造函数**（`MainWindow` 的 `Wire()`）：托盘要读的未读数、连接状态和"引擎每落一条就重画"原来挂在 `OnContentRendered` 上，而那个回调只有 Show 过才会来——后台启动会静默地什么都收不到。`OnContentRendered` 现在只负责 Show 之后的那一次首屏 `Refresh()`。
- **升级自动改写**：老版本写的是不带开关的光路径，`ReconcileAutoStart` 判成 `MovedInstall` 后按新值重写，不需要用户重新开关一次；用户自己在任务管理器的"启动"页里删掉条目的老口径不变（判成 `UserCleared` 后顺着他把设置改成关）。
- **后台启动不查更新**，免得"查到新版"这一步又弹一扇窗。
- **第一次启动就不该有窗的边界**：自启但本机没有身份（退出过空间）时直接退出，不弹首启注册窗。
- `OpenSettings`/`OpenUpdatePrompt` 的 `Owner` 只认真正露着的主窗：后台启动时主窗对象在、但从未 Show，挂一个看不见的 owner 会把子窗一起压住。
- **长正文按列宽折行**：会话行第三列（气泡列）和"竖条 + 气泡"那一行的气泡列都改成星号列（`GridLength(1, Star)`），气泡本体 `HorizontalAlignment=Left` 收在内容宽度上。原先是 `Auto` 列——WPF 会先按"内容无限宽"量一次子元素，`TextWrapping=Wrap` 的正文因此一行都不折。

### 测试

- `dotnet test apps/windows/PushBox.sln`：**558/558 通过**（本轮新增 3 条，`BubbleViewTest` 10 条）。
- `RenderTest` 新增两条度量用例：`LongBodyWrapsInsideTheTimelineInsteadOfRunningOffTheRightEdge`（长正文的实际宽度不超过列表宽度、行数确实折了）与 `ShortBodyKeepsTheBubbleHuggingTheText`（短消息不拉成一整条，时间戳不被挤掉）。长正文那条**反向验证过**：把星号列改回 `Auto`，它当场红（`消息行 7125.8 比列表 510 还宽，右侧会被裁掉`）。
- `SystemIntegrationTest`：`CommandFor`/`DesiredValue` 断言带上 `--tray`；新增"老版本的光路径算 `MovedInstall`（升级要重写）"与 `App.StartInBackground` 的大小写不敏感、不误判 `--notify-smoke`。
- `MainWindowTest` 新增 `AWindowForABackgroundStartIsWiredOnConstructionAndNeverShown`：带上引擎构造、不 Show，未读数算得出来、状态点已亮（订阅生效的证据）。把构造里那行 `Wire()` 注释掉，这条当场红。
- **没跑的**：真实 `--tray` 冷启动（免打扰地验"只见托盘不见窗"）。开发机上 1.1.0 客户端从 14:30 起一直在跑，且它自己的 `settings.json` 里 `auto_start=true`——拿开发构建去启动会顶掉它的实例锁、还会把 `Run` 改指到 `bin\Debug`，所以留给发布检查清单里的那条手工项。

---

## 未发布 —— Android 头像：正缓存 24h 复核，换掉的头像不再永远显示旧图

> 起因：头像 token 恒定为 `sha256(sender_id)`，服务端换头像时按 (频道, 发送者) 覆盖写同一个对象，客户端缓存的文件名与内容对不上号——正缓存又永不失效，所以发送者换了头像后，本机（含通知栏）会一直显示第一张，除非清除应用数据。

### Android

- **正缓存加 24h 有效期**（`AvatarCache.FRESH_TTL_MILLIS`）：缓存文件的 mtime 就是上次取回时刻，`resolve()` 见到超过 24h 的缓存会回源复核一次并覆盖写入。与负标记的 24h 同档：一边负责"事后补上的头像"，一边负责"换掉的头像"。
- **过期不等于不能用**：`Avatar()` 先用 `AvatarCache.cachedFor()` 把旧图顶上去，再做复核，不会为了一次复核闪成首字占位；复核失败（超时/5xx/401）继续用旧图，只有 404 才把缓存一起丢掉——服务端都没有这张头像了，界面上不该还挂着。
- **代价**：每个发送者每天多一次取图请求，换头像最多晚一天可见。

### 测试

- `AvatarCacheTest` 增至 4 条：原有 2 条负标记用例，新增 24h 边界（23h 算新、25h 算旧）与"过期缓存 + 新鲜 404 标记仍要返回旧图"。
- `assembleDebug` 通过；instrumentation **18 条全绿**（`Pixel_5` API 34）。
- **没跑的**：真服务上"换一张头像 → 把缓存文件 mtime 拨到 24h 前 → 重进会话看到新图"，需要本机实例的频道密钥，留给发布检查清单里的同一条手工项。

---

## 未发布 —— Android 头像：事后补的头像不再被 24h 负缓存挡住

> 起因：服务端转存头像是异步的，首帧通常 `sender_avatar = null`，Android 会因此落一个 `{token}.absent` 负标记（24h 内不再重试）。发送者之后才补了头像、后续帧开始自带 `sender_avatar` 时，标记还在，会话列表与对话页就一直画首字占位——要等 24h 过期才可能恢复。

### Android

- **帧里带 `sender_avatar` 就作废负标记**：`SyncEngine.ingestNow` 见到非空 `sender_avatar` 立刻调 `AvatarCache.invalidate(sender_id)` 删掉 `{token}.absent`。实时下发、`resume_gap`/WorkManager 补拉、重装后的历史回放共用这一个落帧入口，三条路径都覆盖。
- **UI 侧要能被叫醒**：`AvatarCache` 新增 `revision: StateFlow<Int>`，只有真删掉标记时才 +1，`Avatar()` 的 `produceState` 多了这个键——磁盘上少了枚标记不会让已经画成占位的头像自己重组。绝大多数消息都带 `sender_avatar`，所以"没删到标记就不推"是刻意的：否则每条消息都会让整屏头像重跑一遍解析。
- **代价**：每个负标记被作废会多一次取图请求。

### 测试

- `AvatarCacheTest` 2 条：删标记并推 revision；没有标记时是空操作、revision 不动。
- `SyncEngineAvatarTest` 2 条：带 `sender_avatar` 的帧擦掉标记、不带的保留（in-memory Room，不联网；`ingestNow` 为此放开成 `internal`）。
- `assembleDebug` 通过；instrumentation **16 条全绿**（`Pixel_5` API 34）。

---

## 未发布 —— Android 通知改为按频道分组（第四十轮）

> 起因是老模型的失控：每个发送者一条系统通知渠道，一个空间里几十个告警机器人就把系统通知设置塞成几十条互不相干的条目，想静音某个软件得逐条找；而渠道重要度按第一条消息的 `level` 定死，之后系统不允许应用上调。这一轮把粒度改成「一个频道一条渠道 + 用户显式指定的发送者例外」，锁屏默认值也翻转为显示发送者与摘要。

### Android

- **渠道模型**（`notify/Notifier.kt`）：频道渠道 `pb_ch_<频道号>`，同一频道的所有发送者默认共用；发送者例外 `pb_sender_<频道号>_<发送者哈希>` 只有用户主动创建时才存在。通知**分组**仍是会话级 `pb_conv_group_*`，所以「全部已读」只撤自己那条会话，不会串到同频道的其他人。`Notifier` 初始化时幂等删除旧模型的 `pb_conv_*`（删除后系统只留 tombstone，`getNotificationChannel` 返回 null）。
- **`level` 不再决定渠道重要度**：`NotificationChannel.importance` 只在创建时生效、应用无法上调，按 `level` 映射等于用第一条消息锁死整条频道。现在 `level` 只用于通知配色与静默时段里 `error` 的例外；新渠道的初始档位来自「新频道默认提醒」（高 / 中 / 低，默认高）。
- **锁屏默认显示发送者 + 摘要**：不再固定「收到 1 条新消息」。设置里的「锁屏显示内容」可关回占位，会话级「锁屏隐藏内容」仍走 `VISIBILITY_SECRET`。`publicVersion` 挂在消息自己的渠道上（挂 `pb_service` 的 `IMPORTANCE_NONE` 上不会显示）。已知边界：系统锁屏选「显示全部通知内容」时 `VISIBILITY_PRIVATE` 被整体覆盖，App 内关闭也不生效。
- **静默时段走独立渠道 `pb_quiet`**（`IMPORTANCE_LOW`，无铃声/振动/悬浮）：在原渠道上压 `NotificationCompat.priority` 无效——渠道重要度盖过通知优先级。只有非 `error` 消息降级。
- **「按频道管理」**（设置 → 通知）：列出频道名、频道号与当前档位，点一行深链到该频道的系统通知设置；还没收到过消息的频道标注「还没收到过消息」。频道页加载成功后把系统渠道名同步成频道名，只改已存在的渠道（不凭空创建）。
- **发送者例外入口**（会话长按）：未设过时是「单独设置这个发送者」（建渠道并跳到系统设置），设过后变成「调整这个发送者的通知」+「恢复跟随频道」（删除例外渠道，该发送者回到频道统一档位）。

### 文档

- `docs/软件设计方案.md`：新增决策 **D34**（渠道粒度、`level` 语义变更、锁屏默认值、静默渠道、按频道管理），§7.3 按新模型重写，D11 与 §7.4 设置树、模块表同步。
- `docs/用户使用手册.md`：新增「2.9 通知怎么分组、怎么单独调」；会话菜单与设置页条目补齐；Q2 排查清单加入频道渠道档位与发送者例外。
- `docs/release/发布检查清单.md`：Android 通知矩阵追加到「功能验证」。
- `site/usage.html`：通知章节改为频道渠道口径，补发送者例外与系统锁屏设置覆盖说明；后台常驻表的入口改成「按频道管理」。

### 测试

- `NotifierChannelTest` 7 条 + `NotifierPrivacyTest` 5 条，共 **12 条 instrumentation 在 `Pixel_5`（API 34）全绿**：同频道发送者共用一条渠道、发送者例外接管与恢复、撤会话通知不误伤他人、旧 `pb_conv_*` 清理、渠道名同步只碰已存在渠道、档位读取、锁屏默认/关闭/私密三档、静默时段换渠道、新频道默认档。
- 修掉一条测试自身的竞态：`cancelConversation` 按 `activeNotifications` 里的 group 过滤，测试刚 `post` 完就撤时系统可能还没登记那条通知；改为先 `await` 到两条都可见再撤（生产路径是用户点进会话，通知早已在栏里）。
- `assembleDebug` 通过；`scripts/release-verify.sh` **89 项通过 / 0 失败 / 2 警告**（警告为工作区未提交与既有 LF/CRLF 提示）。

---

## 未发布 —— 定时任务的发送者身份与管理台粘贴 JSON（第三十九轮）

> 起因是照着外部系统已有的 `POST /message` 请求体建任务：此前定时任务发出去的消息一律记作 `sender_id="scheduled"`，客户端只能把它们堆在一个叫 scheduled 的会话里；发送者名、头像、级别还得手抄一遍，而手里明明已经有一段现成的 JSON。这一轮让任务自带发送者身份，并给管理台加一个只做回显的粘贴入口。

### 服务端

- **`scheduled_tasks` 加两列**（`V12__scheduled_task_sender.sql`）：`sender_id VARCHAR(64) NOT NULL DEFAULT 'scheduled'` 与 `sender_avatar VARCHAR(1024) NULL`。老任务升级后原样可读，行为不变。
- **触发时用任务自己的身份**：`fire()` 把这两列交给 `ingestInternal`，不再写死 `scheduled`；ID 去空白后为空回落 `scheduled`、超 64 字符截断。
- **头像走同一条转存链路**：只收 http(s)、最长 1024 字符，`data:` 内联头像 400 并提示先传到图床；触发时由服务端抓取转存，客户端经 `/api/v1/avatars/{token}` 取。
- **接口字段**：请求体 `senderId`/`senderAvatar`（驼峰），响应 `sender_id`/`sender_avatar`（下划线，列表、详情与三个写操作恒在，头像没设回 `""`）。`PUT` 仍是整体覆盖——请求体里不出现就回落默认值。

### 管理台

- 表单新增「发送者 ID」「发送者头像」，编辑时从详情回显，预览弹窗显示最终生效的 `sender_id`。
- 新增「粘贴 API JSON」面板：认 `channel` / `sender_id` / `sender_avatar` / `sender_name` / `title` / `level` / `content` / `images`，其余键（含 `secret`）不读；cron 与时区永远不动；未知频道、非法头像、未知级别分别给提示并保持原值。这段 JSON 只留在组件内存里回显，不提交、不落 localStorage、关窗即清，最终载荷以表单为准。

### 文档

- `site/admin-api.html`：定时推送一节去掉「发送者固定 scheduled」，补 `senderId`/`senderAvatar` 请求字段、响应字段、`PUT` 覆盖清单与执行口径（客户端按 `sender_id` 归会话）。
- `docs/用户使用手册.md` 4.1：补发送者身份与粘贴 JSON 的用法（认哪些键、忽略哪些键、以表单为准）。
- `docs/软件设计方案.md`：追加 `V12` 与表单回填口径。

### 测试

- `ScheduledTaskLogicTest` 新增 5 条：触发时把 `sender_id` 与头像传给摄入、缺 `sender_id` 回落 `scheduled`、空白 ID 回退与 64 字截断、头像只收 http(s)（`data:` / 相对路径 / 超 1024 都 400）、列表与详情回显两列；`ScheduledTaskStoreTest` 新增 1 条 `V12` 往返（INSERT 列清单、`SELECT *` 扫描、UPDATE 都带得到这两列）。
- web-admin `npm run build` 通过（`vue-tsc` 无错），并在自起实例（8081）上验证粘贴回填、预览、保存、编辑回显与三类异常提示；Maven 全量 **365 条（core 50 + server 315），0 失败 0 错误**，本机 332 执行 + 33 跳过（DB/S3 门控，与基线逐条一致）。

---

## 未发布 —— 定时推送支持多条 cron（第三十八轮）

> 起因是一个很具体的需求：「工作日 09:00 发一次、周末 12:00 发一次」。此前只能建两个只差 cron 的任务——正文、时区、启停各维护一份，改文案要改两处、还容易只改一处。这一轮让一个任务的 `cron_expr` 装多行表达式（每行一条 6 段 cron，最多 8 条），触发取所有行里最近的那一次。

### 服务端

- **`scheduled_tasks.cron_expr` 由 `VARCHAR(64)` 放宽到 `VARCHAR(512)`**（`V11__scheduled_task_multi_cron.sql`）：按 8 条 × 单行上限留的余量；老数据都是单行，升级后原样可读，列注释写明「每行一条、最多 8 条、触发取最近的一条」。
- **`ScheduledTaskService` 改为逐行解析**：`splitCrons` 按行拆（CRLF 与空行都吃掉，规范化后落库），超过 8 条直接 400；`nextFires` 逐行调 `CronExpression.next`，`next_fire_at` 取其中最小的那一个。某条规则已经没有下一次时不拖垮整个任务，只是不参与取最小；全部都没有下一次才 400。
- **错误定位到行**：多行时提示是「第 2 条 cron 不合法（需要 6 段：秒 分 时 日 月 周）：…」，单行仍是原来的「cron 不合法」——八行里挑一个错字段，只说"cron 不合法"等于让人逐行试。
- **幂等仍然按任务算**：`msg_key` 是 `sched:<taskId>:<slot/60000>`，不含 cron 行，所以两条规则落在同一分钟（例如 `0 0 9 * * *` 与 `0 0 9 * * 1-5`）也只发一条。
- **预览多回一份 `next_fires`**：`[{cron, next_fire_at}, …]`，顺序与填写顺序一致；`next_fire_at` 仍是所有行的最小值，正文里的 `${scheduled_at}` 按它渲染。

### 管理台

- cron 从单行输入框改成**可增删的多行**：每行一个输入框，行尾显示该行的下次触发（点一次「预览」才填，改 cron 或时区会清空重算），至少保留一行；「快捷添加」里的预设（每天 9:00 / 工作日 9:00 / 周末 12:00 …）改为"追加一行"，已经在列表里的不再重复添加。
- 列表的 cron 列只显示第一行 + 「等 N 条」，完整表达式挂在 tooltip 上；预览弹窗在多条时逐行列出每条的下次触发，底部口径改成"`${scheduled_at}` 按所有规则里最近的那一次渲染"。

### 文档

- `site/admin-api.html` 的定时推送一节改成多行示例（工作日 + 周末），请求体、响应、执行口径三处都补上「每行一条、最多 8 条、取最近一次、多行错误带第 N 条、同秒只发一条」。

### 测试

- `ScheduledTaskLogicTest` 新增 7 条：③d 取所有行里最近的一次（工作日 9:00 + 周末 12:00，跨过周五/周六边界）、③e CRLF 与空行忽略、③f 多行错误带行号、③g 9 条 400 且不落库、③h 两条同秒规则只发一条、③i 落库前归一、⑩f 预览逐条回 `next_fires` 且顺序与填写一致。
- web-admin `npm run build` 通过（`vue-tsc` 无错）；Maven 全量 **359 条（core 与 server 两档合计），0 失败 0 错误**，本机 326 执行 + 33 跳过（DB/S3 门控，与基线逐条一致）。

---

## 未发布 —— 定时推送的任务时区、正文变量与表单预览（第三十七轮）

> 起因是一个很具体的问题：定时任务的「每天 09:00」到底按哪里的 09:00 算？此前 cron 走 `ZoneId.systemDefault()`，容器里通常是 UTC，于是运维在管理台写的 09:00 实际落在北京时间 17:00；正文里也没有任何办法写"当前时间"。这一轮把时区变成任务自己的属性，并给正文加一套只做替换、不做逻辑的变量。

### 服务端

- **`scheduled_tasks` 加 `timezone` 列**（`V10__scheduled_task_timezone.sql`，`NOT NULL DEFAULT 'Asia/Shanghai' AFTER cron_expr`）：老任务升级后行为不变，新建任务在管理台里可改。`next_fire_at` 的计算从 `systemDefault()` 改成按任务时区换算——`CronExpression` 本身不带时区，这一步漏了就会"存的是上海时间、算的是 UTC 时间"。
- **核心渲染器 `packages/core/.../text/Templates.java`**：只认一层 `${名字}` / `${名字:参数}`，没有条件、循环、嵌套与转义。未知变量、非法格式串、没闭合的 `${` 一律原样保留；`${now}` 支持自定义 `DateTimeFormatter` 格式。**时区权威只有一处**：`render(text, zone, now, vars)` 拿到哪个时区就用哪个，`${timezone:xx}` 的参数不参与计算。
- **定时推送在触发时渲染**：正文存模板原文，`fire()` 里按任务时区 + 计划时隙展开。变量：`now`（可带格式）、`date`、`time`、`weekday`、`timestamp`、`scheduled_at`、`timezone`、`task_name`、`channel_no`、`channel_name`、`sender_name`、`host`、`version`。`scheduled_at` 与 `${now}` 刻意分开：前者是"本该几点发"，后者是"实际渲染的此刻"，补跑时两者能差几分钟。
- **第三方 `POST /message` 也认这套变量**：时区取正文里第一处 `${timezone:xx}`，没写或写错落 `Asia/Shanghai`（不跟部署机器的 TZ 走）。渲染发生在验签之后，`strict_auth` 仍签原文；入库正文、`summary`、`daily_counters.bytes` 都用渲染后的值。内部入口 `ingestInternal` 不做二次展开，避免定时任务展开出的字面量被再解释一遍。
- **字节上限判渲染之后**：模板 `${now}` 只有 6 字节、展开成 19 字节，只量模板会放过一个"创建成功、每分钟必失败"的任务；创建/更新时按渲染结果判 400，触发时 `ingestAccepted` 再判一次。
- **`ServerIdentity`**：为 `${host}` / `${version}` 提供来源（`HOSTNAME` 环境变量 → JVM 主机名 → `unknown`；版本走 `build-info` → `Implementation-Version` → `unknown`），只在渲染这两处使用，`/healthz` 仍然不回版本号。
- **表单预览 `POST /api/v1/admin/scheduled-tasks/preview`**：与建任务同一份请求体，按当前时刻渲染一遍、**不落库也不要求正文非空**，回 `{timezone, now, next_fire_at, content, bytes, max_body_bytes, over_limit}`。渲染留在服务端是刻意的——`${now:yyyy-MM-dd}` 用的是 Java 的 `DateTimeFormatter`，前端复刻一套只会在两边慢慢长歪。顺手修掉一处真缺陷：`${channel_name}` 原来读的是频道行上不存在的 `channel_name` 列（`findChannel` 是 `SELECT *`，列名就是 `name`），定时任务里这个变量一直是字面量，预览把它照出来了。

### 管理台

- 「定时推送」表单新增**时区**下拉（可过滤、可直接输入任意 IANA 名，默认 `Asia/Shanghai`），列表新增一列显示它；正文框下面挂一排**可点击插入**的变量标签，每个带一句说明。请求体新增 `timezone`，响应同步回显。
- 表单底部新增**预览**：拿当前填写的内容调一次服务端，弹窗里显示渲染后的正文、发送者/级别/标题、预计下次触发时刻，以及"渲染后字节 / 频道上限"——超限当场标红（保存仍会被服务端拒绝）。`${now}` 与 `${scheduled_at}` 的口径在弹窗底部写清楚：前者是预览这一刻，后者是 cron 算出的下一次时隙，到点发出时两个值都会变。弹窗里的「预计下次触发」按**任务自己的时区**显示（`Intl.DateTimeFormat` 带 `timeZone`），不跟浏览器本地时区走——否则标签写着 `Asia/Tokyo`、旁边的时间却是本机的，正好和 `${scheduled_at}` 自相矛盾。
- 变量标签按「时间」「任务/频道」**分组**放进一步带边框的面板，面板头一句"点一下插入到正文末尾，悬停看说明"；说明文案也补齐了实际输出形状（`${date}` 是 `2026-10-24`、`${weekday}` 是 `星期六`）。
- **频道被删后预览/保存不再只剩一句 `not_found`**：打开表单时重拉一次频道；点预览/保存前拿已加载的列表对一遍；服务端回 `404 not_found(hint=channel)` 或 `invalid_param(频道已归档)` 时清掉已选频道并再刷一次列表。拦截器也把 404 统一换成"请求的资源不存在，可能已被删除，请刷新后重试"，`invalid_param` 则优先显示服务端那句人话（如"频道已归档，不能作为定时推送目标"）而不是代码。频道列表为空时，目标频道下面直接提示先用 `POST /api/v1/admin/channels` 建一个（管理台没有建频道入口）。

### 文档

- `docs/用户使用手册.md` 新增 4.1「定时推送」与 4.2「第三方正文里的变量」：cron 与触发精度、时区归属规则、两张变量表、渲染后才判长度；并点明 `#{...}` 是 ddns-go 自己的占位符，不归 PushBox。
- `docs/软件设计方案.md` 在 API 表后追加 2026-10-24 的订正段（V10、模板语义、时区优先级、变量清单、落库与上限），D33 原文保持不动。

### 测试

- 新增 `TemplatesTest` 7 条（别名、东京时区偏移、自定义格式、时区优先级、认不出/写错的原样保留）。
- `ScheduledTaskLogicTest` 28 条：新增任务时区影响下次触发、非法时区 400、默认时区兜底、触发时展开 `${scheduled_at}`/`${timezone}`、正文里的 `${timezone:...}` 压不过任务时区、渲染后超限 400、时区随载荷落库与回显；另加 2 条预览用例（预览与触发同源且 `${channel_name}` 取频道行的 `name` 列、超限只标红不落库）。
- 新增 `IngestVariableRenderTest` 3 条：第三方路径展开、缺省时区回落、内部路径不二次展开（不依赖真库）。
- `ScheduledTaskStoreTest` 增加时区往返一条（INSERT/SELECT/UPDATE 三个动作都带这一列），跑真库时生效。
- web-admin `npm run build` 通过；Maven 全量 **352 条**（当轮 core 50 与 server 302 两档合计），0 失败 0 错误，本机 319 执行 + 33 跳过（DB/S3 门控，与基线逐条一致）。真库门控的 `ScheduledTaskStoreTest` 时区往返这条本机没跑起来（MySQL 未启动），由 CI 的 service container 覆盖。

---

## 未发布 —— 发布线重置：三端版本号回到 1.1.0，历史标签与 Release 撤销（第三十六轮）

> 起点是上一轮收尾时的一条事实：Docker Hub 上的标签已被手动清光（复查 `count: 0`、单查全 404），而 GitHub 上还挂着 `server-v1.1.2`…`server-v1.1.6` 五枚服务端标签与两条客户端 Release。留下一条"号在后面、镜像不在"的半截发布线，比重新开始更难解释——维护者拍板**整条线回到 1.1.0 重发**，历史标签与历史 Release 全部撤销，Hub 上从这一枚起算。

### 版本号写入（本轮已完成）

- `bash scripts/set-version.sh 1.1.0`（scope=all）一次写满：Maven 反应堆三份 pom、`apps/web-admin/package.json`、两份 compose 的镜像标签、`infra/WSL测试速查.md` 里那条 `buildx --load` 的 tag、Android `versionName=1.1.0` / `versionCode=10100`、Windows `PushBox.Windows.csproj` 的 `<Version>` 与 `PushBox.iss` 的 `MyAppVersion`、`site/*.html` 的版本徽章。
- 站点徽章不需要单独手改：`release-verify.sh` 里那条 `BADGE_*` 闸判的就是"站点徽章 == `pom.xml` 的 version"，`site/` 与 `--only=site` 一起被脚本覆盖。

### 这次重置的真实代价：Android 是降级

- `versionCode` 由 `major*10000 + minor*100 + patch` 推导（`scripts/set-version.sh:74`），1.1.0 得 **10100**，比已经发出去的 1.1.2 的 10102 小。已装 1.1.2 的设备覆盖安装 1.1.0 会被系统判 `INSTALL_FAILED_VERSION_DOWNGRADED`，**必须先卸载**——这是"号回到 1.1.0"这个决定自带的后果，不是脚本缺陷。
- 同一条单调性在发布脚本里是硬拦：`scripts/release-android.sh:52` 只要读得到本地 `android-v*` 标签且新号不比它大，就在"定版本号"那一步直接退出。**所以删本地历史 tag 不是收尾的清洁工作，而是发 1.1.0 的前置条件**，且它读的是本地 tag（`git tag --list`），远端删干净也没用。
- Windows 侧没有 versionCode 这种系统级单调门，低版本能否覆盖高版本本轮没在实机走过，不写结论。

### 撤销清单与执行顺序（维护者手动，本轮只准备不执行）

1. 网页删两条 Release：`android-v1.1.2`（资产 `pushbox-release-1.1.2.apk`，12 797 054 B）、`windows-v1.1.2`（资产 `PushBox-Setup-1.1.2-win-x64.exe`，58 722 683 B，仍未签名）。这台机器既没有 `gh` 也没有可用的仓库 token，这一步只能在网页做。
2. 删 `github` 上 7 枚 tag：`android-v1.1.2`、`windows-v1.1.2`、`server-v1.1.2` / `1.1.3` / `1.1.4` / `1.1.5` / `1.1.6`。
3. 删本地同名 7 枚 `git tag -d`——上一条说的硬拦就在这里等着。
4. `bash scripts/release.sh --all 1.1.0 --push --yes`：三端各自写号、各自跑本地测试、各打一枚标签，一次推出 `server-v1.1.0` / `android-v1.1.0` / `windows-v1.1.0`，`docker-publish` / `build-apk` / `build-msi` 三条链同时被触发。
- **没有历史可删的两处**（本轮现查，别多此一举）：Docker Hub 已经 `count: 0`，发完应该是且仅是 `1.1.0` / `1.1` / `latest`；Gitea 镜像 `git ls-remote --tags` 返回空；Gitee 本机连 ssh 别名都解析不了（`Could not resolve hostname gitee`），而它从不在写授权内。

### 历史记录怎么处理

- 本文件 `1.1.1`–`1.1.6` 每一轮的记录**一条不删**：它们记的是代码变更与验证结论，跟"这几枚 tag 还在不在"是两回事。从这一节往下读到的版本号，凡是"已发布""Hub 上有"这类现状陈述，一律按当轮快照理解。
- `docs/release/发布检查清单.md` 顶部注记与尾部「最后更新」各追加一段同口径说明，README 发布链表格里那些带 run 号的绿勾同理保留为历史。**订正追加、不改写原文**是这仓库文档的既有规矩。

### 验证（本轮实测）

- Maven 全量 **332** 条（core 43 条与 server 289 条），0 失败、33 跳过（DB/S3 门控，与基线逐条一致）；`Reactor Summary for pushbox-parent 1.1.0` 顺带证号写对了。
- `dotnet test` **554 通过 / 0 失败 / 0 跳过**。
- `bash scripts/install-page-smoke.sh` **5 通过 / 0 失败**，跑的是新产物 `apps/server/target/pushbox-server-1.1.0.jar`（自带端口 7499，用户手起的 7404 实例没动）。
- `bash scripts/release-verify.sh` **88 通过 / 0 失败**，2 条警告是"本机没有 docker CLI 判不了镜像体积"与"工作区 18 个文件未提交"——提交后第二条应消失，回到 88 / 0 / 1。
- **本轮验不了的**：Hub 实际拉到不到 1.1.0、真机卸载重装、Release 页资产挂没挂上——三条都要远端动作，属上面第 1–4 步之后才能补的证据。

---

## 未发布 —— 推代码与出发版解耦：三条制品链只认标签、Hub 只出版本标签、工作分支只剩 `main`（第三十五轮）

> 起点是 v1.1.6 发完之后的一次对账：Docker Hub 上躺着 15 个标签，其中一多半是每次推 `main` 顺手产出来的 `main` 与 `sha-*`；维护者的要求是三句话——**保留官网页面分支、只保留 `main` 一个工作分支、但不能每次推送代码都打包发版，也不能每次打包都让 APK/MSI/Docker 三件套都跑一遍**。这三句其实是一件事：过去"推代码"和"出一份要发给用户的制品"被同一根触发线拴在一起，所以为了只推代码，仓库里得养一条 `publish` 分支躲 CI。

### 三条链的触发面（步骤 1）

- `build-apk.yml` / `build-msi.yml` / `docker-publish.yml` 的 `push:` 只剩 `tags:`（自己的 `<component>-v*` 加旧的 `v*`），**分支触发与 `paths:` 过滤一起删掉**。留着 `paths` 会误导：它让人以为 push 还能出包，只是范围小一点。
- 编译退化不会因此没人管：`ci.yml` 每次推 `main` 全量跑，Windows job 里补了一步 `dotnet build PushBox.Windows/PushBox.Windows.csproj -c Release`——原来这一步只在 `build-msi.yml` 的 main 触发里，摘掉分支触发就一起摘掉了它，只 build 不 publish、不装 Inno。
- 手动 `workflow_dispatch` 保留，用途写进文件头：想试打一次包随时点，它不占版本号。

### Hub 只出版本标签（步骤 2）

- metadata 去掉 `type=ref,event=branch` 与 `type=sha`，只剩 tag 事件的 `X.Y.Z` / `X.Y` / `latest`。**回滚点改为"留在 Hub 上的历史版本标签"**，不再靠一个每次构建都移动、没人知道指向哪次提交的 `main`。
- "这次是不是要发某个版本"由 `github.ref` 的 `refs/tags/` 前缀判，不再用 `github.ref_type`——后者在"于 tag 上点 dispatch"这种场景语义没实测过，而版本号闸门、push 闸门、metadata 的 `enable` 三处都要拿它当唯一依据，猜错就是"该推的没推"。三处现在共用同一个 `is_tag` 输出。
- 非 tag 触发一律 `push=false`；buildx 在 `push=false` 下仍要一个导出目标，所以补一条只存在于 runner 本地的占位标签 `dispatch-<run>`。

### 分支收口（步骤 3）

- 本地 `publish`、`master` 删除；`master` 与 `archive/legacy-history` 指向同一提交（删前核过 0/0）。GitHub 上先把默认分支从 `publish` 改成 `main`（这一步只能在网页 Settings 里点：这台机器没有 `gh` CLI、也没有可用的仓库 token），再删远端 `publish`，`git remote set-head github -a` 跟着指回 `main`。
- 仓库血脉原本是三条互不相干的线：`publish` 与 `github/main` 同根（`28988a0`），本地 `main`（`5cd43d9`）是另一条没有共同祖先的旧线，`archive/legacy-history`（`d51ea49`）又是第三条。旧线存档成 `archive/orphan-old-line`，**存档前用 `filter-branch --index-filter` 整条重写，把 `deploy/wsl-test.env` 从每一个提交的树里摘掉**——那份 WSL 联调凭据只有这条本地线在跟踪（HEAD、`github/main`、`github/publish`、`archive/legacy-history`、`master` 都没有它），复核过重写后整条线 `.env` 匹配 0 处，并删掉 filter-branch 留下的 `refs/original` 备份引用。旧线独有的 32 个源码文件全是重构改名前的旧路径（`deploy/`、`server/`、`android/`、`RELEASE-CHECKLIST.md`），内容都在 HEAD 的新路径下；其余两千多个是当年误提交的 `bin/`、`obj/`。
- `gitee`/`gitea` 两个镜像仍各挂着一条没同步的 `publish`（这两个远端没授权推），所以"三端同名分支一致"那条判据改成只对 `github/main` 比 sha。

### 闸门（步骤 1/2 各一道，都在 `scripts/release-verify.sh`）

- 三条制品链里任何一条出现 `branches:` 就判红——回归这条在本机看不出来（要等 tag 推上去才发现"只改服务端又把客户端带上"或"打 tag 根本没流水线跑"）。
- `docker-publish.yml` 出现 `type=ref,event=branch` / `type=sha` 判红；缺 `latest` 也判红（装的人写的就是 `:latest`）。

### 验证

- 触发面静态判据：`grep` 三种组合各验一次——把 `branches: [main]` 塞回去立刻 1 FAIL，撤掉恢复 0 失败；把 `type=sha,...` 塞回去同样立刻 1 FAIL。
- 行为判据不是看一眼 YAML：把 `docker-publish.yml` 里「版本号一致性闸门」与「本次是否真推」两步的 `run` 脚本原样抽出来，配桩 `pom.xml` / `infra/docker-compose.yml` 在临时目录跑五种触发组合——tag push → `push=true`；tag + dry_run → `false`；分支 dispatch → `is_tag=false`、`push=false`；tag 与 pom 不一致 → RC=1；compose 与 pom 不一致 → RC=1。
- **端到端兑现**：`d8d6476..d71fe81` 快进 `github/main` 之后，按该 SHA 查 `actions/runs` 得 `total_count: 1`，唯一一条是 CI（event=push），docker-publish / build-apk / build-msi 一条都没起；那条 CI run `39964668069` 最终 `conclusion=success`，含新增的 `dotnet build 主工程`。
- `bash scripts/release-verify.sh`：86 通过 / 0 失败 / 2 警告（未提交文件、本机无 docker CLI）。

### 边界

- **Hub 现在一个标签都没有**（本次订正时用 WSL 复查 tags 接口 `count: 0`、`main` 单查 404：旧标签被手动清走，新链路又不再产分支标签）。`infra/docker-compose.yml` 钉的 `:1.1.6` 因此暂时拉不到——这一版是靠"下一次标签事件"或"在 `server-v1.1.6` 上点一次不勾 dry-run 的 dispatch"补回来，属发布动作，由维护者手动执行。
- 镜像本来就不可复现：同一个 `d8d6476` 在 tag 事件 / 分支事件 / 手动 dispatch 下量到三个 digest（`ca14f281…` / `d9b35cea…` / `90bed7ef…`），根因是 gha 层缓存复用未变动的阶段。认版本读 OCI label `org.opencontainers.image.revision`。
- `v*` 与 `<component>-v*` 两种标签的语义、GitHub Release 那两条链的资产命名都没动，本轮只改触发面与标签集合。

---

## 未发布 —— 配置模型重构：安装页取代环境变量（第三十四轮）

> 起点：一条来自真实安装的抱怨。v1.1.4 为了让飞牛的「环境变量」表单有行可填，往镜像里烘了 5 枚 `__SET_ME__` 占位值；v1.1.5 紧接着修的就是"只配主钥的人被那个占位口令判成二选一冲突"。**为了让表单有行可填而塞假值、再为了让假值不生效而在 entrypoint 里 `unset` 它**——这个形状说明该换的不是键的数量，而是配置的形态。维护者的判断是"不要设计太复杂，要符合用户逻辑，而不是死板追求安全性"，并明确推翻了"凭据只走环境变量"这条硬规则（它本来就只是开发环境的口径）。

### 新形态

- **服务端不再读任何环境变量**。配置只有一份 JSON：`data/config/pushbox.json`（容器里 `/app/data/config/pushbox.json`），模型类 `setup/RuntimeConfig`，到 Spring 属性的翻译收在一处（`RuntimeConfig#toSpringProperties`，经 `META-INF/spring.factories` 注册的 `EnvironmentPostProcessor` 在上下文刷新前注入）。
- **首次启动弹安装页**。没有配置文件时 `main()` 先用 JDK 自带的 `HttpServer` 在服务端将要用的**同一个端口**（默认 7404）上起一个迷你服务：根路径 `/` 是表单，提交走 `POST /api/v1/setup`，另有 `?action=test`（只测库）与 `?action=restore`（从库里找回上一次的配置）。写盘之后**同一个 JVM 接着 `SpringApplication.run`，不用重启容器**。选"同端口 + 同 JVM"是为了让 NAS 上那条 `docker run` 保持一条；代价是 healthcheck 的 TCP 探活在安装期间也通，`healthy` 从此不代表"配好了"。
- **提交即校验，不过则一个字节都不写**：`validate()`（格式层）→ `checkDatabase()`（真连一次，顺手 `CREATE DATABASE IF NOT EXISTS`，建不动不判死）→ `checkS3()`（勾了对象存储才走，headBucket + putObject + deleteObject 各一次）。于是"配了一半"这种状态不存在，"库地址对、口令错"这类最高频的错在装机当场就是一句人话，不再是 MySQL 的 1045 加几百行 Hikari WARN。
- **存储从三档开关变成两个布尔**：`storage.s3`（改用对象存储）与 `storage.encrypted`（加密存储）。**加密与"用不用对象存储"解耦**——本地目录模式同样按这个开关决定落盘的是明文还是 `KeyWrap.seal` 封装后的密文，读侧按 `media_objects`/`senders` 各自记的 `enc_mode` 逐对象解读。勾了加密后 KEK 来源二选一：口令（≥12 个码点，Argon2id 派生）或 base64 的 32 字节主钥；两个都填拒绝提交。
- **管理台从"两把钥"变成一个口令**：`admin.password`（≥8 字符，**留空 = 管理台整个关闭**，`AdminAuthController` 的 403「管理台未启用」不变），签名钥 `admin.jwtKey` 由 `RuntimeConfig#normalize()` 首次启动自动生成并回写。部署者不再需要生成两个强随机值并记住它们必须不同。
- **配置副本落库**：启动后整份配置抄进 `app_state.runtime_config_v1`，重装或换机器时只带库凭据就能把上次的配置拉回来改（**KEK 不在副本里**，它从来不落库，那一栏得手工重填）。

### 同一个端口上的交接：安装页 → 管理台

安装页和服务本来就共用一个端口，这一轮把"访问者手上的那个地址"也接上了：

- **未配置时非根路径 302 回安装页**。`/dashboard`、`/login`、`/admin` 这些地址在"还没配置"期间原来都是 404（只有 `/` 是表单），收藏夹里的管理台地址在首次部署时等于死链。现在一律 `302 Location: /` + `Cache-Control: no-store`，配置完同一个地址自然就是管理台。
- **安装页注入真实监听端口**。表单里有一个「端口」，提交后服务端会改在**新端口**上起 Spring；页面要判断"端口到底改没改"，不能拿 `location.port` 比——容器把宿主 7405 映射到容器 7404 时它是 7405，反代在 443 时它是空串。所以在页面里放 `<meta name="pb-install-port" content="__INSTALL_PORT__">`，由 `InstallServer` 在吐出 HTML 时替换成实际监听端口（直接双击打开模板文件时回退到 `location.port`，页面仍可用）。
- **保存成功后同源轮询、就绪再跳转**。`POST /api/v1/setup` 成功只代表配置落盘，Spring 还在起；页面每 600ms（首轮之后 1s）取一次同源 `/`，最多 60 次，解析出管理台的挂载点就 `location.replace('/')`，超时则留一句"稍后刷新本页就能进入管理台"。**端口改过时不跳**：服务端会改在新端口上启动，旧端口这一页跳过去只会连不上，所以直接给出 `http://<host>:<新端口>/`。

这一条在真实浏览器里第一次跑就抓到一个假阳性，`mvn test` 抓不到：判据第一版写的是 `html.indexOf('id="app"')`，而**这段判据代码本身就在安装页里**——取回来的安装页 HTML 因此天然含有 `id="app"` 这几个字节；再加上安装页 `stop(1)` 留出的一秒宽限，保存后第一轮轮询必然把自己认成管理台，`location.replace('/')` 立刻刷新、表单被清空，看起来像"点保存就丢掉配置"。修法是把判据从字符串换成 DOM：`new DOMParser().parseFromString(html, 'text/html').getElementById('app')`。单测同时补了一条"安装页里不许出现 `<div id="app"`"，把"安装页不能带 SPA 挂载点"变成显式契约。

### 删掉的

- 全部服务端 `PB_*` 键，**连兼容别名一起删、不留过渡期**（留别名等于留两套真值）。
- `application-dev.yml` 与 `application-prod.yml`（合成一份 `application.yml`）。
- `DevCredentialsGuard`（它判的"该配的键没配"由 `validate()` 在提交时判，且判得更早）与 `ProdPlaintextMediaGuard` + `PB_ALLOW_PLAINTEXT_MEDIA` 逃生阀（明文不再是"忘了配"的默认结果，而是表单上一个开关的显式表态）。
- 镜像里全部 17 项 `ENV`；entrypoint 收成**只做一件事**：`/app/data` 的写入探针（探不过就打出 `chown -R 10001:10001` 与"删掉那行让 Docker 建匿名卷"两种修法，而不是让人去读一段 Logback 的 `Permission denied` 堆栈）。v1.1.5 那 25 条 entrypoint 场景矩阵随之作废。
- 两个 `VOLUME`（`/app/data/media` + `/app/logs`）收敛成**一个 `VOLUME /app/data`**，装下 `config/` + `media/` + `logs/`。理由是"人只需要管一处"：「存储位置」只有一行要选、备份脚本只指一个目录、`chown` 只 chown 一次。
- `deploy/env/set-user-env.ps1` 与 `local.env.example`；`deploy/run-dev.sh` 只剩 `exec java -jar`。

### 明文凭据是刻意的

`pushbox.json` 里明文存着库口令、桶 AK/SK、管理台口令与加密口令。**这不是漏配，是把不变式换了位置**：旧规则"凭据只走环境变量"在真实部署里从没真的成立过——部署者总要把真值写在某个地方（compose 的 `environment`、NAS 的表单、`.env`、systemd 的 `EnvironmentFile`），那条规则换来的只是"那个地方由部署工具管、没人管权限"。新的不变式是"**明文只在一个地方，且它进不了提交**"：文件原子写（同目录临时文件 + move）、权限 `rw-------`、`.gitignore` 排掉 `/data/` 与 `/server/data/`，`release-verify.sh` 断言这几条忽略规则还在、并直接问 git。代价写明白：拿到那个卷就等于拿到全部凭据，包括派生媒体 KEK 的口令。

### 缺陷与修法（真跑一次容器才照出来的三条）

- **安装页在 fat jar 里连不上库**：`DriverManager` 初始化时用**系统类加载器**跑 `ServiceLoader`，而 Spring Boot 把 `mysql-connector-j`（`server/pom.xml` 里是 `runtime` scope）放在 `BOOT-INF/lib/` 下，只有 `LaunchedClassLoader` 看得见——安装页跑在 Spring 之前，于是 `POST /api/v1/setup` 一律 `No suitable driver found for jdbc:mysql://…`。Spring 那条路不受影响（Hikari 自己按线程上下文类加载器 `Class.forName`），`mvn test` 也不受影响（exploded classpath 上系统类加载器本来就看得见驱动），所以**这条只有真跑一次 `java -jar` 才验得到**。修法：`InstallServer.loadMysqlDriver()` 显式 `Class.forName`；判据落在新脚本 `scripts/install-page-smoke.sh`（真出包、真起 fat jar、真 POST 一次），由 `release-server.sh` 第 3 步调用，`release-verify.sh` 断言这个脚本在位、过 `bash -n` 且真的被调用。
- **安装明明成功了，部署脚本却报"连不上安装页"**：`install()` 在 `return` 之前就 `configured.countDown()`，`awaitConfiguration()` 一醒就在 `finally` 里 `server.stop(0)`——`0` 是"不等在途请求写完"，于是配置真的落盘了、Spring 也起来了，而 curl 只拿到被截断的空响应。修法：`countDown()` 挪到 `handleSetup` 里 `respondJson(...)` 之后，`stop(1)` 再给一秒余量。`InstallServerTest` ⑬ 真起一次 HttpServer、真打一次 POST 钉住它；做过负测——把两处改回原样，这条当场红成 `HTTP/1.1 header parser received no bytes`，正是容器里 curl 看到的那个症状。
- **内网图床没有任何一处能放行**：`SsrfGuard` 默认拒绝回环/私网/CGNAT（这一条是对的，自建对象存储常落在 `100.64.0.0/10`），豁免只有 `pb.media.allow-hosts` 一个入口，而它既不在 `application.yml`、也不在安装页上。NAS 与内网部署于是每条图片都 `media_objects.error=ssrf_blocked`，而表单里找不到任何能改的地方——这是"所有配置都要能在表单填写"漏掉的一项。修法：`RuntimeConfig.Media.allowHosts` 进表单（第 2 节「图片抓取放行主机」），翻成 `pb.media.allow-hosts`；落盘前 `normalize()` 归一成小写去空白（`SsrfGuard` 是精确等值比较，写成 `IMG.lan` 或带个空格等于没填）。与之相对的 `pb.media.allow-private`（整段放开私网）**刻意不进表单**：豁免按主机点名，不给"一键关掉 SSRF 校验"的开关。

### 缺陷与修法（CI 全量档第一次真跑 S3 用例）

- **两条 media 用例其实一直测的是本地盘**：配置模型重构后 `PbProperties.Storage.type` 的默认值是 `local`，而 `MediaObjectsTest.build()` 与 `MediaSsePipelineTest.plainObjects()` 都是手工 `new MediaObjects(...)`、只设了 S3 段——本机这 33 条被 `objectStorageReady` 门跳过，看不出来；CI 把门打开后第一次真跑就红了两条：`keylessReadOfEncryptedObjectFails` 拿不到 400（本地盘对无钥读返回 null，不是 `S3Exception`），`legacyPlainObjectsStillReadable` 拿到 404（明文对象写进本地盘、读的却是桶）。修法是在这两处显式 `storage.type=s3`；生产代码一行未改，用例计数不变（332）。

### 新增

- `deploy/pushbox.json.example`：一份带注释的配置样张，TLS 口径（`useSSL=false` 与 `http://` 意味着什么、跨主机该怎么改）从 `prod.env.example` 搬到这里。
- `deploy/backup/backup.sh` **第 0 步：备份配置文件**，产出 `config-<UTC>.json.age`。库副本能让安装页找回配置，但**KEK 的盐与校验值在 `app_state.media_kek_v1`、KEK 本身不在库里**，缺这一份的结果是"库恢复成功、图片全部解不开"，而它长得就像一次成功的恢复。两个 `CONFIG_*` 都没给时脚本只打一条 `⚠️` 并**继续跑完**（退出码仍是 0），所以判据是产物清单里有没有 `config-*`，不是退出码。
- 测试门控从一档拆成两档：只需连得上库的 13 个类挂 `TestConfig#databaseReady`（开发机照跑），真要桶的 5 个 media 类挂 `objectStorageReady`（它们验 SSE-C 客户钥、61B 封装格式、无钥读 400 这类 S3 语义，本地目录照不出来）。本机跳过数因此从 114 降到 33。
- **发布改走脚本，不再手敲命令**：`scripts/release.sh`（总入口：汇总三端最新标签与仓库现值、算出下一个 patch/minor/major、按需分发）+ `scripts/release-server.sh` / `release-android.sh` / `release-windows.sh`（各端自己的六步）+ `scripts/release-common.sh`（公共部分）。顺序是刻意的：**先跑本地构建与测试，再写版本号，再提交打标签**，测试红了就退出、工作区一个字节都没改；推远端要显式 `--push`，且 `PB_REMOTE` 只可能是 `github`（gitee/gitea 不在授权范围内）。版本号只从**标签**算，仓库现值只用来对账——跑过 `set-version.sh` 却忘了打标签那种半成品状态会把基线抬错，汇总表里那一栏直接写成「⚠ 现值领先标签」。每端另有一道只对自己成立的断言：服务端数 surefire 报告并与 `ci.yml` 的 `if tests < N` 比（跑之前先 `rm -rf` 两个 `surefire-reports`：`mvn test` 不清 `target`，被删掉的测试类留下的孤儿报告会一直把总数抬上去，那一轮实测 344 vs 当时真值 327）；Android 比 `apksigner` 指纹与 `android/release-cert.sha256`、核 `versionCode == major*10000+minor*100+patch`；Windows 从 `crypto.trx` 数用例并与 `ci.yml` 的 `$CS_BASELINE` 比、核 csproj 与 `PushBox.iss` 同值。`skipped == 0` 这一档本机判不了（runner 上才喂了 DB/S3 凭据），脚本打 WARN 说明"全绿不代表测过"而不是当通过。

### 验证

- **Java 全量 332 条（core 43 条与 server 289 条，299 执行 + 33 跳过）、0 失败 0 错误**，BUILD SUCCESS。新增用例集中在 `setup/`：`RuntimeConfigTest` 10 + `InstallServerTest` 15 + `RuntimeConfigEnvironmentPostProcessorTest` 5 = 30 条；同时删掉 `DevCredentialsGuardTest` 10 与 `ProdPlaintextMediaGuardTest` 7，净 `+13`（319 → 332）。`InstallServerTest` ⑮ 是真起一次 `HttpServer` 的交接判据：`GET /` 200 且 meta 里是实际端口、`/dashboard` `/login` `/admin` 三条 302 且 `Location: /`。
- **`scripts/release-verify.sh` 84 通过 / 0 失败 / 2 警告**（两条警告是"本机没有 docker CLI 判不了镜像体积"与"工作区有未提交文件"）。本轮新增第 8 节「检查发布脚本」8 条判据，判的是**形状**（五个脚本都在、都过 `bash -n`、三端各自"本地测试那一步的行号 < 写版本号那一步的行号"、`PB_REMOTE` 唯一取值 `github`、没有任何一行把 `push` 与 `gitee`/`gitea` 写在一起、`PB_PUSH` 初值是 0）——这些脚本自己不会被任何工作流触发，坏了没人替你发现。**六条都做过负测**：把 `set-version` 那行挪到测试之前、在 `release-windows.sh` 末尾塞半截 `if`、`PB_REMOTE` 改 `gitee`、`PB_PUSH` 改 1、往 `release-android.sh` 塞一行 `git push gitee main`、把 `release.sh` 挪走，六种改法逐个把对应判据判红，恢复后与备份逐字节相同、闸门回绿。写它时踩到一次自指：判据本来用 `scripts/release*.sh` 这个 glob 扫，把 `release-verify.sh` 自己也扫进去了，而它的判据文本里同时写着 `push` 与 `gitee`，第一跑就把自己判红——改成只扫那五个文件名（与 compose 那条 `${VAR:?}` 判据同一类失效：给人读的说明文字被自己的闸门当成违规）。
- **发布脚本本身验到哪儿**：`release.sh` 的 `--list`、单端 / 多端 / `--all` 的 `--dry-run`、显式旧版本号被判死、部分成功部分失败时的统计与"剩下的端该跑哪条命令"提示、`--bump=minor` 与 `--push` 的参数转发，都实跑过；服务端那一步（`3/6`）是把脚本里那段**原样抽出来真跑的**——`mvn -B -pl :pushbox-server -am test` 出 332 条 / 33 跳过 / 299 执行、与 `ci.yml` 基线相等，`web-admin` 构建出非空 `dist/index.html`，`release-verify.sh` 零 FAIL。**Android 与 Windows 那两步没有真跑过**（`gradlew :app:assembleRelease` + `apksigner` 比指纹、`dotnet test` + 数 trx + `build-installer.sh`），判据是照 `ci.yml` / `build-apk.yml` 抄的，只过了 `bash -n` 与 dry-run；也**没有创建过任何提交或标签、没有推过任何远端**。
- **WSL Docker 上真跑了一次安装页全流程**：从改过的源码重建镜像 → `docker compose up` → 删掉容器里的 `pushbox.json` 把安装页重新武装 → `deploy/deploy-wsl.sh setup` 把表单值 POST 进去 → 在容器内 `/app/data/config/pushbox.json` 里确认 `media.allowHosts` 等字段真的落盘 → `/healthz` 200 → `PushBox.LiveCheck` 12/12 → 入站 `POST /message` 带一张内网图片。上面那三条缺陷就是这么照出来的，`mvn test` 一条都抓不到。图片链路终态：`media_objects.status=ready`、`enc_mode=sse-c`、`length(sse_key_wrapped)=61`、`size=79`、`object_key` 非空，`senders.avatar_key` 也非空；桶里那份 `xl.meta` 既没有 PNG 魔数也没有 `IHDR`，落盘确实是密文。顺带验到两条设计行为：同一 URL 的第二条消息按 sha256 复用了前一条的 `object_key`；先前 `ssrf_blocked` 的那一行在放行主机进配置之后被重试器自己救回了 `ready`。
- **加密媒体的读侧仍然没验**：`/api/v1/media/{msgId}/{idx}` 与 `/api/v1/avatars/{token}` 都要 `DeviceGuard` 的设备签名，curl 复现不出来，而 `LiveCheck` ⑦ 只覆盖 404/hint 那条路；真要桶的那 5 个 IT 类挂在 `objectStorageReady` 上，本机没跑。
- **端点闸补了一处盲区**：它原来只从 `web/*.java` 的 `@*Mapping` 与 `registry.addHandler` 提路由，而 `POST /api/v1/setup` 是 `InstallServer` 用 JDK HttpServer 的 `createContext` 注册的、跑在 Spring 之前，永远不会有注解——闸门因此把文档里那个**真实存在**的端点判成"代码里找不到"。修法是把 `createContext("/api…")` 也纳入提取（只收 `/api` 开头的，同一个 `createContext` 还注册了表单页 `/`，那不是接口路径）。
- 文档侧全部按新口径重写：README、`deploy/部署快速参考.md`、`deploy/NAS部署指南.md`、`deploy/WSL部署指南.md`、`docs/用户使用手册.md`、`docs/开发指南.md`、`SECURITY.md`、`site/` 五页、`发布检查清单.md` 四节、`发布准备报告.md`。`docs/软件设计方案.md` **不改写 §11 正文**，而是新增 **D77** 记录"为什么推翻 D76"，并在 §11 章首挂一条失效横幅——把旧决策改写成新的，"我们曾经认为明文凭据不可接受"这个事实就没了，而下一个人正是需要它才能判断新取舍是不是又想回去。

### 未做 / 边界

- **安装页的浏览器渲染与自动跳转已实测（2026-10-23）**：本地 fat jar + 隔离 MySQL（宿主 3307）、jar 监听 8081，在浏览器里真实填表、点「测试连接」、提交，页面自动跳到 `http://127.0.0.1:8081/login`，管理台登录页正常渲染。**没走到的两支**：表单里把端口改成另一个值（服务端会换端口启动，代码只提示新地址、不跳转，这条分支没有在浏览器里点过）、飞牛/群晖的「存储位置」表单只剩一行之后长什么样。
- **从旧版本（≤1.1.5，只读环境变量）回退不是改一行 image tag**：旧镜像不认 `pushbox.json`，得把整套 `PB_*` 重新配齐，且加密口令必须与新配置里那一份**逐字相同**，否则旧封装钥解不开（症状是图片集体 404）。反方向没有这个问题。
- ARM 机型仍然没验（发布工作流没传 `platforms`，Hub 上是 amd64）。
- 未实现 KEK 轮换或旧对象重封装。

---

## v1.1.5（2026-10-22）—— 修主钥模式被镜像里的口令占位值误杀（第三十三轮）

> 起点：v1.1.4 发布后复查 `docker run` 的主钥路径。镜像 ENV 里烘焙了 `PB_S3_MASTER_PASSPHRASE=__SET_ME__`，用户只传 `PB_S3_MASTER_KEY` 时这一行仍是非空字符串，entrypoint 的二选一检查把"主钥 + 占位口令"当成两个真实来源，直接拒绝启动。`prod.env.example` 与 README 都仍把主钥列为可用选项，这是 v1.1.4 带出去的兼容性回归。

### 缺陷与修法

- **占位值先归一成"没填"再做二选一**：entrypoint 在互斥检查之前识别 `PB_S3_MASTER_PASSPHRASE=__SET_ME__`，把本地变量清空并 **`unset PB_S3_MASTER_PASSPHRASE`**——只清 shell 变量不够，镜像 ENV 会原样传给 `java`，`MediaKeyService` 仍会把它当成真口令，再次触发"二选一"。
- **占位值单独出现时仍点名**：没给主钥、口令又没替换时，报错仍写"PB_S3_MASTER_PASSPHRASE 还是镜像里的占位值"，并提示"改用主钥就把这一行清空"。`PB_S3_MASTER_KEY` 若被误填成占位值也按未设置处理。
- **没有改 Java 侧判定**：`MediaKeyService` 的"两个真值同时出现就拒绝"保持原样；这次只在容器边界把假值清掉，口令/主钥语义与 v1.1.4 相同。

### 验证

- **entrypoint 占位值场景 7/7**（v1.1.4 时是 6/6，新增第 7 条）：只填主钥、口令仍是占位值时走主钥模式正常启动，且哨兵确认 `PB_S3_MASTER_PASSPHRASE` 已不在 Java 进程的环境里（`passphrase-in-env=no`）；其余六条与 v1.1.4 相同（库口令占位值判死、local 下 S3 占位值不影响、s3 端点占位值判死、口令占位值判死并点名、真口令通过、明文逃生阀通过）。
- **entrypoint 口令场景 5/5**：12 字符通过、11 字符判死且不打印口令、4 个汉字按码点被判过短、主钥+口令同时给判死、两边都不给时点名两种来源。
- **`docker buildx build --check`**：`Check complete, no warnings found.`（WSL Docker 29.9.1）。
- **`scripts/release-verify.sh` 73 通过 / 0 失败 / 2 警告**：新增一条静态闸门，要求 entrypoint 既记住"口令占位值出现过"又真的 `unset PB_S3_MASTER_PASSPHRASE`；少任何一半就红。

### 发布（2026-10-22）

- 版本号 `1.1.4 → 1.1.5`（`--only=server`），提交 `a478f0e`，只打 `server-v1.1.5`；标签链 run `39780463627` 全 success，Android / Windows 标签链没有被触发。
- **Hub 实查（WSL）**：`1.1.5` / `1.1` / `latest` 指向同一 index digest `sha256:058f5c471d40672db6535347d6c4aaa4000894c74fdae1c81357ae934c2330d7`；`main` 与 `sha-a478f0e` 是同一 commit 的另一次构建，digest `sha256:a67bff76…`。`1.1.4` / `sha-2d8f3b0`、`1.1.3` / `sha-d8c1486`、`sha-d4a889d` 都还在——这一轮没有删旧标签。
- **从 Hub 拉回 `1.1.5` 复核**：config blob 的 `Volumes={'/app/data/media': {}, '/app/logs': {}}`、`User=pushbox`，ENV 17 项（原 10 项 + 五枚 `__SET_ME__` + `PB_S3_BUCKET=pushbox` + `PB_S3_OBJECT_ENCRYPT=none`）；镜像内 `/app/entrypoint.sh` 与源码渲染结果 md5 同为 `dd70ee6aca3bc69b39c71a1360ca30d2`。
- **发布镜像实跑主钥场景**：`docker run` 只传 `PB_S3_MASTER_KEY`、保留烘焙的 `PB_S3_MASTER_PASSPHRASE=__SET_ME__`，日志出现"主钥长度验证通过"且没有"只能二选一"；随后照预期停在假数据库握手（测试用的是只 accept 再关闭的 TCP 监听器，不是真 MySQL），证明误杀已经消失。
- **同一推送的连带 run**：`ci.yml`（run `39780448593`）、main 的 `docker-publish.yml`（run `39780448837`）、`site-deploy.yml`（run `39780448815`）、`update-dockerhub-description.yml`（run `39780448665`）与 `build-apk.yml`（run `39780448503`，只构建、不发 Release）全部 success；`build-msi.yml` 没跑。

### 未做

- 未实现 KEK 轮换或旧对象重封装；未开始管理台内配置 S3 的第二阶段。边界与 v1.1.4 相同。

---

## v1.1.4（2026-10-22）—— 媒体 KEK 改由短口令派生；NAS 表单显示凭据占位行（第三十二轮）

> 起点：`PB_S3_MASTER_KEY` 要求用户保管一段 44 字符的 base64 随机串。抄错一位、重装时重新生成一份，症状都是"图片打不开"。这一轮让 `sse-c` 可以只给一句至少 12 个字符的口令；原有主钥继续可用。

### 设计

- **`MediaKeyService` 成为 KEK 的唯一来源**：随机主钥模式原样保留；口令模式按 NFC 规范化并去掉首尾空白后，用 Argon2id（默认 `m=65536KB,t=3,p=1`）派生 32 字节 KEK，再走原来的 `KeyWrap` 封装每对象客户钥。没有改任何对象格式——已落库的 61 字节封装钥与历史 `none` 对象照旧可读。
- **口令不落库**：每套安装首次真正用到 KEK 时生成随机 16 字节盐，把 `version/kdf/salt/kcv` 写成 `app_state.media_kek_v1`（JSON）；口令本身一个字都不存。同一口令 + 同一行 state ⇒ 同一把 KEK，所以重启、重建容器、卸载重装（库还在）都不影响旧图。
- **KCV 启动自检**：用 KEK 封装一段固定串存进 state，启动时解封比对。口令错了在启动阶段就报"与首次启用时不是同一个口令"，不会拿错 KEK 去写新对象。
- **空库不提前钉死**：启动时若 state 不存在且库里没有封装钥，只打印"第一张图片写入时初始化"，不写 state。这样刚部署时口令打错一次，不会在还没有任何媒体时就把错误口令永久钉进库里。
- **二选一与模式切换闸门**：`PB_S3_MASTER_KEY` 与 `PB_S3_MASTER_PASSPHRASE` 同时配 → 拒绝启动；口令模式发现库里有封装钥却没有 state（旧主钥写的）→ 拒绝启动且不写 state；主钥模式发现 state 存在（口令写的）→ 拒绝启动，提示继续用口令或在确认后再手工删 `media_kek_v1`。
- **不提供轮换/重封装**：KEK 一旦有对象落库就不可换。换口令与丢主钥等价，旧对象解不开；这是本期明确边界。

### 接线

- `PbProperties.S3.masterPassphrase` + `application-prod.yml` / `application-dev.yml` 的 `master-passphrase: ${PB_S3_MASTER_PASSPHRASE:}`。
- `MediaObjects` / `LocalFileStorage` 改为向 `MediaKeyService` 取 KEK；单测用的 `byte[]` 构造器保留。`PbStore` 增加 `putStateIfAbsent`（`INSERT IGNORE`）与 `hasAnyWrappedKey`（查 `media_objects` / `senders` 两列）。
- 容器 entrypoint 接受两种 KEK 来源：口令按 Unicode 码点判 ≥12（`LC_ALL=C.UTF-8 wc -m`），主钥仍按 40–48 字符；两者同时给直接拒绝，错误输出不含任何口令值。`docker-compose.yml` / `docker-compose-wsl.yml` 透传 `PB_S3_MASTER_PASSPHRASE`，NAS 指南、部署快速参考、`prod.env.example` / `local.env.example` / `wsl-test.env.example`、README、`site/install.html`、软件设计方案与发布检查清单同步成"口令或主钥二选一"。

### 部署表单

- **凭据行改成占位值显示**：`Dockerfile` 的 ENV 追加 `PB_DB_PASSWORD=__SET_ME__`、`PB_S3_ENDPOINT` / `PB_S3_ACCESS_KEY` / `PB_S3_SECRET_KEY` / `PB_S3_MASTER_PASSPHRASE` 四行 `__SET_ME__`，并显示 `PB_S3_BUCKET=pushbox`、`PB_S3_OBJECT_ENCRYPT=none`（与 yml 默认值相同）。飞牛/群晖的「创建容器」只渲染镜像 ENV，这样库口令、AK/SK、KEK 口令的输入行直接出现，用户是改值而不是逐条点「添加」。这些全是假值，不是凭据；跳过 `SecretsUsedInArgOrEnv` 的理由写在 Dockerfile 头注释里（那条 lint 只看键名、分不出占位值）。
- **占位值按"没填"处理**：entrypoint 对 `PB_DB_PASSWORD`、S3 三项、`PB_S3_MASTER_PASSPHRASE` 都显式判 `__SET_ME__` 并拒绝启动，报错点名"还是镜像里的占位值"；`local` 模式不读 S3，那几行占位值原样留着不影响启动。`__SET_ME__` 只有 10 个字符，即使绕过 entrypoint，Java 侧的口令长度下限也会拒绝，不存在"用公开占位值派生 KEK"的路径。**v1.1.4 镜像漏了"口令占位值在二选一之前归一"这一步**，只配主钥时会被误判成两个来源同时存在；v1.1.5 修复，见上一节。
- **文档**：NAS 指南路径 C、README、部署快速参考与 `site/install.html` 从"表单看不到 S3、手动加七行"改成"占位行直接改"；README 里同时保留 v1.1.3 及更早镜像仍是旧行为的说明。

### 验证

- **`MediaKeyServiceTest` 11 条全绿**：同一口令跨实例同 KEK、每次安装不同盐、错口令被 KCV 拒绝、短口令拒绝、二选一、两种模式切换闸门、旧封装钥阻止口令模式且 state 保持为空、空库启动不写 state、主钥模式不依赖数据库、NFC/空白归一、明文模式无 KEK。
- **`mvn -o test`（JDK 21）退出码 0**：`pushbox-core` 43/43；`pushbox-server` 276 条，0 失败，114 条按 `PB_S3_*` 等环境变量门控跳过（未连真桶/真库的那批）。
- **entrypoint 口令场景 5/5**：12 字符通过、11 字符判死且不打印口令、4 个汉字按码点被判过短、主钥+口令同时给判死、两边都不给时点名两种来源。
- **entrypoint 占位值场景 6/6**：库口令仍是 `__SET_ME__` 判死并点名占位值；`local` 下只留 S3 占位值正常启动；`s3` 下端点占位值判死；三件套改真、口令仍是占位值时点名口令占位值；全改真且 `sse-c` 真口令通过；全改真、明文带逃生阀通过。
- **`scripts/release-verify.sh` 72 通过 / 0 失败 / 2 警告**（警告照旧是工作区未提交与本机无 docker CLI），新增五条静态闸门：prod yml 映射、entrypoint 认识口令、compose 透传、entrypoint 执行二选一、GUI 占位值与拒绝逻辑同进同退；原来"PB_DB_PASSWORD 绝不进镜像 ENV"那条改成"只允许 `__SET_ME__` 占位值"，任何真值仍然 FAIL。

### 发布（2026-10-22）

- 版本号 `1.1.3 → 1.1.4`（`--only=server`），提交 `2d8f3b0`，只打 `server-v1.1.4`；标签事件只触发服务端链（run `39779506621`，`Validate Docker Hub credentials` → 版本闸门 → `Build and push` 全 success），Android / Windows 两条标签链没有被触发。
- **Hub 实查（WSL）**：`1.1.4` / `1.1` / `latest` 三枚指向同一 index digest `sha256:0645e2ea9322d40698bbec6708ed311211dc921191824c902434c0656bcacd22`；`main` 与 `sha-2d8f3b0` 是同一 commit 的另一次构建，digest `sha256:3062a00bb8a84b75fdff1eb1d5c7639d865adf53847ae7db31ea92bf58f0d0d8`；`1.1.3` / `sha-d8c1486` 与更早的 `sha-d4a889d` 仍在，没有删旧标签。
- **已知缺陷（v1.1.5 修）**：镜像 ENV 里的 `PB_S3_MASTER_PASSPHRASE=__SET_ME__` 被 entrypoint 当成真口令，只配 `PB_S3_MASTER_KEY` 的用户会被"二选一"误杀。发布后复查主钥路径时发现，v1.1.5 把占位值从环境里清掉并补了第 7 条场景；`1.1.4` 镜像本身保留这一缺陷。
- **同一推送的连带 run**：`ci.yml`（run `39779496418`）、`site-deploy.yml`（run `39779496463`）、`update-dockerhub-description.yml`（run `39779496454`）与 main 的 `docker-publish.yml`（run `39779496509`）都 success；`build-apk.yml`（run `39779496365`）被紧接着的 v1.1.5 推送按 concurrency 取消，`build-msi.yml` 没跑。被取消的是"只构建、不发 Release"的那条，不影响任何产物。

### 未做

- 未实现 KEK 轮换或旧对象重封装。
- 未开始第二阶段（镜像默认 local + 管理台内配置 S3 地址/AK/SK/口令的设置页）；当前仍只有环境变量这一条配置路径。

---

## v1.1.3（2026-10-22）—— 镜像声明两个持久化目录，NAS 表单不再从空挂载开始（第三十一轮）

> 起点是 v1.1.2 发出去之后的一句追问：飞牛「创建容器」表单里的「存储位置」还是空的，S3 配置也看不到。

### 挂载：把两个目录写进镜像元数据

- **`deploy/Dockerfile` 增加 `VOLUME ["/app/data/media", "/app/logs"]`**。飞牛/群晖的「创建容器」表单是按镜像的 `VOLUME` 渲染「存储位置」行的：v1.1.2 没有这一句，所以那一栏一行都没有，指南里"自己加两行"只能靠人照着做。现在容器内路径会直接带出来；**宿主文件夹那一列仍要人选**——Docker 不会替人猜 NAS 上的路径，选不了就落匿名卷（`docker rm` 不再把图片一起删掉，但卷不会自动挂回下一次容器）。
- 这句必须放在 `mkdir -p /app/logs /app/data/media && chown …` **之后**：`VOLUME` 之后再改同一路径的 `RUN` 不会进镜像层，目录与属主会变成镜像默认值。
- **S3 默认不出现是刻意的**：表单只显示镜像烘焙的 `ENV`，而 `PB_S3_*` 一个都不烘焙——AK/SK 是凭据，按"凭据只走环境变量"的规则不能预置；只列 region/bucket 之类又会回到 v1.1.2 之前"桶看起来配了一半"的假象。要用桶就在表单里一次加七行（type / endpoint / AK / SK / bucket / object-encrypt / master-key），NAS 指南路径 C 与 compose 版都按这个数写。
- **顺手订正一处数错的清单**：NAS 指南与 compose 注释原写"六行"，实际列了七行（漏数 `PB_S3_MASTER_KEY`）；这一版统一改成七行。

### 验证

- WSL 本地构建 `pushbox-server:voltest` 后 `docker image inspect`：`Config.Volumes` 为 `{"/app/data/media":{},"/app/logs":{}}`，`Config.User` 仍是 `pushbox`，`Config.Entrypoint` 仍是 `/app/entrypoint.sh`；ENV 与 v1.1.2 一致，没有多出任何 `PB_S3_*` 或口令键。
- `release-verify.sh` 新增一条静态闸门：Dockerfile 必须声明这两个目录，删掉就红。**这条只证明源码里有这一句，不证明 Hub 上的镜像带上了它**——发布后再用 registry 的 config blob 复核（见下面「发布」）。

### 发布（2026-10-22）

- 版本号 `1.1.2 → 1.1.3`（`--only=server`），只打 `server-v1.1.3`。标签事件只触发服务端链（run `39767681288`，`Validate Docker Hub credentials` → 版本闸门 → `Build and push` 全 success），Android / Windows 两条标签链没有被触发。
- **Hub 上的镜像复核到的是元数据，不是 CI 退出码**：`1.1.3` / `1.1` / `latest` / `sha-d8c1486` 四枚指向同一 digest `sha256:1d021fd0e796caafdc0febaad5943671bcca7548a7f83efb3cd348bc445fb8d8`；从 registry 取 `1.1.3` 的 config blob，`Volumes={'/app/data/media': {}, '/app/logs': {}}`、`User=pushbox`，ENV 仍只有 v1.1.2 那 10 项，没有多出 `PB_S3_*` 或口令键。`main` 是同一 commit 上的另一次构建（digest `sha256:3c954e77…`），不冒充版本标签。
- 旧的 `1.1.2` 与 `sha-9f8e201` 已从 Hub 删除，当前只剩 `1.1.3` / `1.1` / `latest` / `main` / `sha-d8c1486` 五枚。
- **`main` 上的连带 run**：这次 push 改了 `core/pom.xml` 的 parent 版本，所以 `ci.yml`、`site-deploy.yml`、`docker-publish.yml`（main 镜像）与 `build-apk.yml`（只构建、不发 Release）都跑了；Windows 链没跑。六条 run 全部 success。`build-apk.yml` 被 `core/**` 路径过滤带起来是分端过滤的已知边界，不是又回到了"改服务端全套重发"——它没有标签、没有 Release。
- 发布前 `bash scripts/release-verify.sh` 是 **67 通过 / 0 失败 / 2 警告**（警告是工作区未提交与本机无 docker CLI 判镜像体积）；新增的 VOLUME 闸门也在这 67 条里。

---

## v1.1.2（2026-10-22）—— 三条发布链解耦；NAS 表单不再露出半套 s3（第三十轮）

> 起点是两张飞牛（FNOS）「创建容器」表单截图：**「存储位置」一栏是空的**，环境变量里只露出 4 个 s3 项（region / bucket / path-style / object-encrypt），看起来像"桶配了一半"；以及一句要求——只改服务端时不要把 APK、MSI 两条流水线一起拉起来跑。

### NAS 图形界面：两个卷、两个变量、s3 七行

- **镜像 ENV 不再烘焙 s3 的四个默认值**（region / bucket / path-style / object-encrypt）：飞牛的容器表单会把 `ENV` 逐行铺成输入框，这四项露出来就自带"桶已经配了一半"的暗示，而实际起作用的默认值在 `application-prod.yml` 与 entrypoint 里各有一份。删掉之后 local 形态行为不变，表单里只剩真正该由你填的库口令两项。
- **存储位置 = 两条挂载**：`deploy/docker-compose.nas.yml` 的头注释写明两个 named volume 的挂载点（`/app/data/media`、`/app/logs`）、用 `docker volume inspect <项目名>_server_media -f '{{.Mountpoint}}'` 查宿主路径、改绑宿主目录时必须 `sudo chown -R 10001:10001`；**不挂就是删容器即删图**。
- **`deploy/NAS部署指南.md` 新增「路径 C：不建 Compose 项目，直接在表单里填」**：挂载两张表、只填 `PB_DB_HOST` + `PB_DB_PASSWORD`、要 s3 就七行（type / endpoint / AK / SK / bucket / 加密方式 / 主钥；原文误写六行，v1.1.3 订正），判据与 Compose 那条路逐条相同。README、`deploy/部署快速参考.md`、`site/install.html` 各加一段指路。

### 发布链解耦：只改服务端就只打 `server-v*`

- **三个 workflow 各认各的触发**：`docker-publish.yml` 只认 `server-v*` 与旧的 `v*`，`build-apk.yml` 只认 `android-v*` 与 `v*`，`build-msi.yml` 只认 `windows-v*` 与 `v*`；push `main` 时三条链按目录过滤（服务端 `server/**`、`core/**`、`web-admin/**`、`pom.xml`、`deploy/**`；客户端各自目录），不再"改一个像素全套跑一遍"。`ci.yml` 保持每次 push 全量跑——它是门禁，不跟发布链的过滤走。
- **版本号按端独立**：`scripts/set-version.sh --only=server|android|windows|site X.Y.Z` 只写这一端的构建配置；不带 `--only` 仍是三端全写。tag 解析兼容 `<组件>-vX.Y.Z` 与旧的 `vX.Y.Z`。
- **Docker Hub 的版本标签改由 shell 现算**：`docker/metadata-action` 的 `type=semver` 不认 `server-v1.2.3`，改成 `type=raw` 喂闸门从 pom 现读的 `version` / `major_minor` / `latest`，tag 事件才继续推出 `X.Y.Z` / `X.Y` / `latest` 三枚。
- **门禁跟着改**：`release-verify.sh` 的「三端版本一致」换成按端自洽（服务端 pom == 两份 compose == WSL 速查、官网徽章 == pom；Android versionName/versionCode；Windows csproj/Inno），并新增三条判据：三条 workflow 只认自己的标签前缀、两条客户端注入必须带 `--only=`、更新检查不许再用 `/releases/latest`。

### 解耦带出来的一处回归：更新检查不能再读 `/releases/latest`

- 分端发布后每个平台各占一个 Release，`/releases/latest` 只返回最后创建的那个：Android 可能读到 Windows 的 tag（没有 apk → 永远不提示更新），反过来也一样，而且**不会报错**。两端改成读 `releases?per_page=30` 列表，按资产名挑出属于自己平台的 Release，再从 tag 去掉 `android-v` / `windows-v` / `v` 前缀得到版本号；一条匹配的都没有时按"没有可安装版本"处理。
- 验证到哪一步：Android `./gradlew :app:compileDebugKotlin` exit 0（`-q` 无输出），Windows `dotnet build … -c Release` **0 警告 0 错误**。首个组件标签发布后（2026-10-22），Windows 的 `UpdateClient` 真实代码直连线上 `releases?per_page=30` 选出 `windows-v1.1.2` / `PushBox-Setup-1.1.2-win-x64.exe`，`IsNewer` 对 1.1.1 为真、对 1.1.2 为假（第一次跑被系统代理共用的未登录出口限额挡成 403，客户端把它翻成"限流或未授权"那句人话；换成不放代理的 `HttpClient` 后通过）。Android 的同一套选取口径（认 `pushbox-release-` 资产、剥 `android-v` / `v` 前缀）喂同一份线上 JSON 也选出 `android-v1.1.2` / `pushbox-release-1.1.2.apk` / 1.1.2，但**没有在真机上跑过更新弹窗**。

### 验证

- `bash scripts/release-verify.sh` **66 通过 / 0 失败 / 2 警告**（本机无 docker CLI 判不了镜像体积、工作区有未提交文件）。
- 三个 workflow 的 YAML 用 PyYAML `safe_load` 解析通过，tags 分别为 `android-v*`+`v*` / `windows-v*`+`v*` / `server-v*`+`v*`；`bash -n` 通过 `set-version.sh` 与 `release-verify.sh`。
- 更新检查的 `isNewer` / `Compare` 分段比较算法未动，tag 前缀剥离暂无单测（两个项目本来就没有更新检查的测试）。

### 发布与清理（2026-10-22）

- **按组件标签发的第一版**：`server-v1.1.2`、`android-v1.1.2`、`windows-v1.1.2` 各自只触发自己那条链；同一 head（`9f8e201`）上的 9 条 workflow（3 条标签链 + `main` 的 CI、三条构建、站点、Hub 说明）全部 success。
  - Android：run `39763487166` → Release `android-v1.1.2`，资产 `pushbox-release-1.1.2.apk`（12,797,054 B，sha256 `1bf6972c53564f1287296f93363fe20dfabd3ab542da6c692751517442e9960f`）。
  - Windows：run `39763487499` → Release `windows-v1.1.2`，资产 `PushBox-Setup-1.1.2-win-x64.exe`（58,722,683 B，sha256 `52f8c5d58e85489edc9edb78ef7b7cd919dc5fd58b3ffb488ced003419016445`）。
  - 服务端：run `39763487572` → Docker Hub `1.1.2` / `1.1` / `latest`，不建 GitHub Release。
- **旧产物清零**：GitHub Release `v1.1.0` / `v1.1.1`（连同资产）与对应标签、本地 `v1.0.0` 标签一并删除，远端只剩三个 `*-v1.1.2` 标签；Docker Hub 删掉 `1.1.0`、`1.1.1` 与 9 枚旧 `sha-*`，只剩 `1.1.2` / `1.1` / `latest` / `main` / `sha-9f8e201`。
- `bash scripts/release-verify.sh` 复跑 **66 通过 / 0 失败 / 2 警告**。

---

## v1.1.1（2026-10-22）—— 红了能不能知道红在哪一条，装进 NAS 要填几个口令（第二十九轮）

> 前半的起点是 `v1.1.0` 发出去之后紧接着的一笔：`main` 上 `ci.yml` 的 Windows job 红了一次，而这一轮改的既不是 C# 也不是构建脚本——是"红了以后能不能知道红在哪一条"。后半的起点是另一个人：有用户把 `iamplusdev/push-box:1.1.0` 拉进飞牛（FNOS）的图形界面，`compose up` 连解析阶段都没过。

- **事实摆全**：head `7b148ea`（内容只有文档 + 两个 Release NOTE 模板，一个 C# 字节都没动）上，`ci.yml`（run `39725750003`）的「Windows 端测试」第 4 步 `dotnet test PushBox.Crypto.Tests` 跑 99 秒后失败，同一 run 的另外三个 job（管理台、服务端、Android）全 success。**第 5 步「断言 C# 用例数没有被静默裁剪」被 skip**——它没写 `if:`，默认继承 `success()`，于是唯一能回答"哪几条红了"的那一步在最需要它的时候不执行；匿名能读到的 annotation 只有两条：一条 Node 20 弃用告警和一条 `Process completed with exit code 1`。
- **本机复现不出，也不像时区**：同一条命令（连 CI 那几个参数一起：`--results-directory TestResults --logger "trx;…"`）**554/554 全绿**；考虑到 runner 当时是 05:02 UTC 而本机是 UTC+8，再带 `TZ=UTC` 跑一次，仍然 554/554。下一笔 `f9b1c67` 的同一个 job 又绿（run `39727438065`，第 4、5 步都 success）。所以这一句只写到"一次红、两次绿、红因未知"，**没写到"确认是 runner 抖动"**——那需要名字。
- **修的是出口**：第 5 步加 `if: always()`，并把每条非 Passed 的用例名 + 断言首行写成 `::error::` annotation（同时进 `$GITHUB_STEP_SUMMARY`）。annotation 走 check-run 那条端点，**匿名可读**，等于把红因发到公开面上，不用再靠"登录了才能看日志"。
- **顺手补的两条边界**：① trx 半路损坏时 `[xml]` 转换会把变量留成空，下面那句属性访问会数出"1 条用例、没通过"，把解析故障报成用例红——现在先判 `$report.TestRun` 是否存在；② 用例全绿但 `dotnet test` 退出码非 0（还原/编译/工具链）要单独报一条，不能跟着落一句"✅ 全部执行"。
- **判据是四种输入各跑一遍，不是"看着改对了"**（把 `run` 块从 YAML 里原样抽出来，在本地 PowerShell 上喂不同 trx）：干净 trx 退 0；`DOTNET_OUTCOME=failure` + 全绿退 1 且报工具链那一族；截断 5KB 的坏 trx 报"解析失败"而不是"1 条用例红"；**字节级变异过的 trx**（自关闭的 `outcome="Passed"` 挑两条改成 `Failed` 并塞进带 `
` 的多行 `ErrorInfo`，第三条改 `NotExecuted`）逐条出 annotation。变异脚本自带"变异必须真生效"的断言（`Failed`=2、`NotExecuted`=1、`Passed`=551，且输出里必须出现 `Assert.Equal() Failure`）——**这条断言这一轮真抓到东西**：第一版把断言文本取成 `$r.errorInfo`，PowerShell 的 XmlDocument 适配器只按子元素层级出属性，正确路径是 `Output/ErrorInfo/Message`，取错时 annotation 里只剩用例名、那句话整个丢掉，而上一版负测就是这么"绿"过去的。
- **顺带订正一条写过头的话**：上面"复现路径"那条原来写"runner 日志与 surefire artifact 匿名都是 401，读不到"，容易被读成"CI 的一切匿名都读不到"。实测边界是：**日志与 artifact 要登录**（401/403），**`GET /repos/<仓库>/check-runs/<job id>/annotations` 匿名 200 可读**，`…/check-runs/<id>` 本体同样 200。这一句现在也写进了 `ci.yml` 那步的注释里，因为下一步的红因就靠这条通道出。
- **仍然没有答案的两件**：`7b148ea` 那一次到底红在哪几条（那一步当时被 skip，事后补不出来，trx 也不在手上）；`v1.1.0` 的 Release 页首行仍是 `push-box:v1.1.0`（本机无 `gh`、无 token，改不掉），正确文案从下一个 tag 起生效。

### 后半：镜像把拓扑默认值烘焙好，装进 NAS 只填一个口令（D76）

> 起点是用户原话：把 `iamplusdev/push-box:1.1.0` 拉进飞牛（FNOS）的容器表单，「环境变量关于数据库的什么都没有」，改用 compose 又撞在解析阶段——`services.pushbox-server.environment.PB_ADMIN_JWT_SECRET: required variable PB_ADMIN_JWT_SECRET is missing a value`。那条报错既没有文件可以编辑（图形界面没有 `.env` 这一层），也不说"该填什么"。

- **判据从 compose 解析阶段搬进容器 entrypoint**：`deploy/docker-compose.yml`、`docker-compose-wsl.yml` 里 **`pushbox-server` 那段 `environment` 一项 `${VAR:?}` 都不剩**，`PB_DB_*` 全套透传补齐（原来只透传 `PB_DB_URL`，只填 `PB_DB_HOST` 的人会静默拿到镜像默认的 `mysql`）。同两本文件里的 `mysql`/`rustfs`/`grafana` 那几段**刻意仍然用 `:?`**：它们本来就只在有 `.env` 的机器上被显式取消注释或以 `-f` 叠加时才参与解析，那正是 `:?` 用得对的那种现场。搬家的代价写进了注释：解析阶段"早期失败"的好处没了，换来的是同一条判据能指名变量、给出 `openssl rand -base64 32`、并在 `local` 拓扑下跳过用不到的必填项。
- **必填从 4～6 项收成 1 项**：镜像 `ENV` 段烘焙的全是非凭据的拓扑参数（profile、7404、日志目录、`PB_DB_HOST/PORT/NAME/USERNAME`、`PB_STORAGE_TYPE=local`、`PB_S3_OBJECT_ENCRYPT=none`、`PB_DB_WAIT_SECONDS=60`、桶名/region/path-style、本地目录），于是"什么都不填"里只有 `PB_DB_PASSWORD` 是真跑不起来的那一个；`PB_DB_URL` 缺时由 `PB_DB_HOST:PB_DB_PORT/PB_DB_NAME` 拼出（手写带 `?&=` 的 JDBC 串是最高频填错点，少一个 `&` 只表现为"连不上"）。**口令类一律不进 `ENV`，连空占位都不给**：空占位没有运行时作用（`${VAR:-}` 与 unset 同解），但会让每轮 `docker build` 报 `SecretsUsedInArgOrEnv`——那条 lint 管的是"把秘密烙进镜像层"，而烙进层里的东西删不掉，改一个 `ENV` 就得重出 tag；而 `PB_ADMIN_SECRET` 留空本身就是"管理台禁用"这个安全状态。
- **明文媒体守卫按拓扑分档**（`ProdPlaintextMediaGuard` + entrypoint 第 3 段同进同退）：`s3 + none` 没有逃生阀仍然拒启，`local + none` 只打一条 WARN 就启动。分档理由是两个"明文"不是同一件事——桶侧任何拿到同一组 AK/SK 的读者都直接读得走；local 侧的对象在部署者自己那块盘上、`LocalFileStorage` 已按 600 逐文件设权限，剩下的暴露面归磁盘与备份策略。把桶的门槛原样压到 local，代价是起步路径必须凭空多配一个只为过检查而写的 `PB_ALLOW_PLAINTEXT_MEDIA`。**连取值口径也要两边一致**（trim + 转小写、逃生阀认 `1|true|yes|on`、只有模式确实是 `none` 才拦）：不归一就会出现"SSE-C 被 Java 认、被 entrypoint 当成明文拒掉"，同一份配置两个组件各说一套。`ProdPlaintextMediaGuardTest` 为此加 ⑤⑥ 两条（`+2`，总数 306 → **308**）。
- **`/healthz` 从外面看是 404，不是 403**：`InternalAccessFilter` 拒绝时刻意不用 403，免得扫描器从状态码确认"这里确实有个运维端点"。这一句原先在文档里写成 403，而 entrypoint 出门那段直接把 404 和原因打在日志里——在图形界面里第一次跑的人手上唯一的外部判据就是它。
- **顺手照出一条一路传下去的假事实**：多处文档与注释写着"JRE 镜像里没有 curl/wget"，`健康检查 db=down` 那一节还据此教人在容器里 `apt-get install default-mysql-client`。实测：`eclipse-temurin:21-jre` 自带 `/usr/bin/curl` 与 `/usr/bin/wget`（`command -v` 两条都有，容器内 `curl -s http://127.0.0.1:7404/healthz` 直接回 200 + JSON），mysql 客户端确实没有，而 `apt-get update` 在 uid 10001 下 `Permission denied`（要装得 `-u root`，且装进可写层 `docker rm` 就没了）。所以订正了 8 处：compose 两本与 §11.2 那两条"healthcheck 只做 TCP"的理由从"缺工具"改成真正的理由（**判据不同**：`/healthz` 会真连库，拿它做 healthcheck 等于让一次数据库抖动把容器判死），验收命令一律换成 `curl`，`db=down` 那一节改成"`/healthz` 的 `db` 字段 + Java 第一条异常"三条分流（顺带实测到 `PB_DB_NAME` 写错时 MySQL 8 报的是 `Access denied … to database 'nodb'`，不是 `Unknown database`，那句 `using password: ***` 是 §13 的脱敏把 YES/NO 一起遮了）。
- **新增 `deploy/docker-compose.nas.yml` + `deploy/NAS部署指南.md`**：整本 NAS 文件**零 `${}` 插值**（`release-verify.sh` 专门加了一条闸门判这件事，且比对前先把注释剥掉——头注释里那句"没有 `${VAR:?}`"是给人读的，不该被自己的闸门算成违规），改两行字面值就能 up；路径 A 用已有的库，路径 B 顺带起一个 `mysql:8.4`（3306 刻意不发布到宿主）。
- **顺手修掉一条让 `--env-file` 直接报错的模板缺陷**：`deploy/wsl-test.env.example` 第 7 行把 `cp deploy/wsl-test.env.example deploy/wsl-test.env` 当成"复制并填值"的说明写成了裸命令，而 compose 会把它当一个变量名解析——报的是 `unexpected character "/" in variable name`，从报错里完全看不出是注释掉了还是没注释。加了 `#` 并把这条写进注释。同理改了 `AdminAuthController` 与 `application-prod.yml`、`DevCredentialsGuard` 里那三处"compose 已用 `${VAR:?}` 卡住"的过时注释。

### 构建期照出来的一条：BuildKit 会把 heredoc 里"非美元符"的反斜杠整个吃掉

- **症状**：镜像构建成功、`bash -n` 在本地全绿，容器起来 **1 秒退出**，日志只有 `syntax error near unexpected token '('`。根因是 `COPY <<EOF /app/entrypoint.sh`（未加引号的 heredoc）里，BuildKit 会做变量展开并**删掉所有反斜杠**——`\"$VAR\"` 里那个"反斜杠 + 美元符"变成运行时引用（正是想要的），但 `case` 模式里带转义括号的字符类落进镜像只剩一个裸括号，bash 当场语法错。本地那套跑法把 heredoc 抽出来时保留了反斜杠，于是**同一份源码在本地 25 条场景全绿、在镜像里根本跑不起来**——这是本轮最贵的一条：错不在判据，在"验证用的还原规则和构建规则不是同一套"。
- **修法是三件事一起做**：① 那段主机嗅探改成不用转义字符类（`grep -q '[()]'` 判"认不出来就跳过预检并出声"）；② 本机还原规则改成与 BuildKit 一致（先 `\$`→`$`、再删掉其余每一个反斜杠），并和镜像里的 `/app/entrypoint.sh` **md5 逐字节对齐**——先证明"我验的这份就是你装出来的那份"，再谈场景过没过；③ `release-verify.sh` 加两条静态闸门（heredoc 段里只允许"反斜杠 + 美元符"这一种反斜杠用法；按 BuildKit 规则还原后的脚本过 `bash -n`），两条都做了负测：把括号那版写法塞回去能复现出容器里同一句语法错。

### 这一半的验证

- **真容器、真库、只给一个变量**：本机（WSL2 里的 Docker，x86_64）用 `deploy/Dockerfile` 现场构建，起一个 `mysql:8.4` 并在自定义网络上挂别名 `mysql`，应用容器运行时**只喂 `-e PB_DB_PASSWORD`**——连 `PB_DB_HOST` 都没给，走镜像烘焙的默认值。结果：NAS 指南第 4 节那段日志逐字出现，Flyway 在空库上 `Successfully applied 9 migrations … now at version v9`，`Started PushboxServerApplication in 7.163 seconds`；容器内 `/healthz` 200 `{"ok":true,"db":"up","storage":"up",…}`，根路径 `/` 吐出管理台页面，`/admin` 404，`POST /message` 不带凭据 401 `invalid_credential`，管理台登录 403「管理台未启用」——四条都和"没设 `PB_ADMIN_SECRET`"这个状态自洽。
- **两条判据在同一份镜像里单独复现过**：`PB_DB_HOST=127.0.0.1` 打出环回警告 + 等满后那条四步排查（主机 / 端口没发布 / **库容器在崩溃循环** / 防火墙，第三条是本轮自己踩出来的——我给 mysqld 塞了一个它不认识的启动参数，它把初始化做完就退出，端口从来没人监听过，而 TCP 侧只表现为"连不上"）；把一个 root 属主的空目录绑进 `/app/data/media` 则当场 `❌ 本地存储目录 … 不可写（容器内进程是 uid 10001）` 并退 1，两种修法直接印在日志里。
- **entrypoint 场景矩阵 25 条**（上一版的 20 条 + 这轮为归一化取值补的 ㉑–㉕：`PB_S3_OBJECT_ENCRYPT` 带大小写与空白、逃生阀写 `TRUE`、未知加密模式不该被判死、`PB_STORAGE_TYPE` 大小写）在还原脚本上逐条对上预期输出与退出码；`docker compose config` 把 NAS 那本与主/wsl 两本都渲染过。
- **文档不能领先镜像**：Hub 上当时的 `1.1.0` 那份镜像默认 `s3`、且缺 `PB_DB_URL` 直接拒启，"填两个值就能起"是**下一版**的行为。`README`、`deploy/部署快速参考.md` 主路径那一节、`site/install.html` 三处各加了一句明说这件事的 caveat（site 那条还多写一句"新 compose 配旧镜像时，那条报错会指着一个你已经删掉的变量，先 `docker image inspect` 看镜像日期再照本页填"）——不加这句，这一轮写的文档本身就是一次把用户往解析阶段报错里推的复现。**这几句的寿命就是一次发布**：本次 `v1.1.1` 是第一个带这些烘焙默认值的镜像，所以五处全部删掉（`README` 两处、`部署快速参考` 两处含主命令那行注释、NAS 指南第 0 节那条——随之"先确认四件事"改三件、`site/install.html` 整个 warn 框、`docker-compose.nas.yml` 头注释里"别用旧镜像"那两行）。判据是用户那句「只要本地已经实现，就不要写这种东西」：**文档描述的是发出去的那一版**，窗口期靠发布节奏解决，不靠读者自行判断手上那份新不新。
- 计数：`bash scripts/release-verify.sh` **61 通过 / 0 失败 / 2 警告**（本机没有 docker CLI 判不了镜像体积、工作区有未提交文件），Java 基线 306 → **308**（core 43 条、server 265 条）与 `ci.yml` 的 `$CS_BASELINE = 554` 一起对上；带日期的历史数字继续改成不带求和形状的写法，让计数闸只管现值。

### 部署文档收成一条主路径，官网改用 Pages 地址

> 起点是用户连着两句问：「为什么现在使用 docker 部署还是这么复杂，又要填写变量，又要校验」「为什么不能拉取镜像，docker run 的时候配置环境变量，还有拉取代码，获取 .env」。两句都成立——**`docker run` + 两个环境变量本来就是_supported 的最短路径_，是文档没有把它摆在第一位**。

- **主路径只有一条**：`README`「🐳 Docker 部署」与 `deploy/部署快速参考.md` 现在都从"拉 Hub 镜像 + `docker run` 两个变量"开场，`git clone` + `cp env/prod.env.example .env` + compose 整体降为**形态二**，并在开头一句话讲清它凭什么要仓库：`docker-compose.yml`、`Caddyfile`、`docker-compose.monitoring.yml` 和那几段 sidecar 定义都是**文件**，装不进 `pushbox` 镜像，`.env` 只是给这些文件记账的一层；`docker build` 要克隆是另一件（它读仓库里的 `Dockerfile`）。`site/install.html` 的 Docker 一节同批改写：前四步是"建库 → `docker run` → 容器内 `curl` 验收 → 要管理台再补两把钥"，compose 挪到后面单开一节，NAS 图形界面收成一句指路而不是第三种必填口径。
- **删掉一段重复**：`部署快速参考.md` 原来「方式零」与「📦 单镜像部署」两节讲的是同一个 `docker run`，取值还各写一套（一处 `<镜像标签>` 含糊、一处钉 `latest`）。合并成「🚀 主路径」一节，把散在两边的三条不能省的、追加变量表、镜像外的建库 SQL 归到同一条路上；`方式一/方式二` 改名为 `拓扑 A/C` 与已有的 A/B/C 口径对齐（B 那段补一句"compose 起来的是空实例，建库建账号照样在你自己身上"）。
- **主路径补上两件真正会挡人的事**：建库与 `'pushbox'@'%'` 那段 SQL（服务端不建库，Flyway 只建表）、以及**镜像只有 linux/amd64**（发布工作流没传 `platforms`）+ **库只能 MySQL 8.0/8.4**（`V1__init.sql` 用 `utf8mb4_0900_ai_ci` 与 `FULLTEXT … WITH PARSER ngram`）。这三条原来只在 NAS 指南第 0/1 节有，而主路径那两本是用户先到的一处。
- **照出站点一处假形状**：`site/install.html` 本机起步那步写的期望响应是 `{"status":"UP","db":"up","storage":"up"}`。现读 `HealthzController`：`/healthz` 的键是 `ok` / `db` / `storage` / `uptime_ms`，`{"status":"UP"}` 是 `/actuator/health` 那本（组件明细被 `show-details: never` 挡住）——两本被合成了一本，改成正确形状并把两条一起写清。
- **官网地址统一到 Pages**：`README` 的 `[官网](site/index.html)` 与三本 API 参考的 `site/*.html` 链接、`docs/用户使用手册.md` 的 `../site/admin-api.html` 全部换成 <https://iamplusdev.github.io/push-box/>（`index.html` 与 `install.html` 本轮各自 `curl` 复验 200）。仓库内相对路径在 GitHub 网页上点只会落到文件页，使用者看到的是 HTML 源码而不是站点。另外登记：`AGENTS.md` 的项目上下文补官网地址、`.github/ISSUE_TEMPLATE/config.yml` 多一条「文档与安装（官网）」、`发布检查清单` 那条站点判据改成"仓库内文档一律指 Pages 地址"、`site/index.html` 指回 `install.html#prod` 的锚点保持相对（Pages 在子路径下，根绝对路径会被 `site-deploy.yml` 的事前检查拦掉）。
- **主路径补上"已经有桶"那条完整命令**：追问是「但容器起步，如果已经有个 s3 是否可以直接输入地址、秘钥，而不是新建环境变量？」——问里那个"而不是"没有别的通道（环境变量就是唯一入口，凭据不进任何会入库的文件），但**文档确实只演示了 local**，把这条命令拼完整是它的活。四本各加一节：`README` 形态一给整条 s3 版 `docker run`，`部署快速参考.md` 同一条 + 「已经有桶：同一条命令让它落桶」，`site/install.html` 的 Docker 一节多一个步骤卡（5 行变量、少一个卷），`NAS部署指南.md` 给那一本 compose 的改法（`PB_STORAGE_TYPE` 在那本里是字面值 `local`，**只加 `PB_S3_*` 不改它等于没配**）。四条一起写在命令旁边：`s3` 开关省不得、media 卷落桶后只是空着所以不删、`s3 + none` 拒启（逃生阀 `PB_ALLOW_PLAINTEXT_MEDIA=1`，正路 `sse-c` + 不可轮换的主钥）、`PB_S3_PATH_STYLE` 默认 `true` 对 MinIO/RustFS 而 OSS/AWS 可能要 `false`。顺带照出 NAS 指南默认值表里那句"要连本地盘也加密见下一节"指的其实是「路径 B：顺带起一个 MySQL」，改成点名小节与 `docker-compose.nas.yml` 里那两行注释。
- **发布 v1.1.1：把版本号那八个手写位一起过一遍**：`bash scripts/set-version.sh 1.1.1` 一次写全三端构建配置（Android `versionCode=10101`/`versionName`、Windows `<Version>` 与 Inno 的 `MyAppVersion`、root/core/server 三个 pom、`web-admin/package.json`）、两份 compose 的 `image:` 标签与 `site/*.html` 的顶栏徽章。这一轮另外补掉两处**会自己腐烂但没人管**的写法：① `deploy/WSL测试速查.md` 里那条 `buildx --load -t pushbox/server:<版本号>` 以前不归脚本覆盖，compose 改了它不改，照文档构建完 compose 就找不到镜像——现在进了同一个循环；② `site/index.html` 页脚那条 `PushBox · 自托管消息推送 · 1.1.0` 既不被 `set-version.sh` 的那两种形状匹配、也不被 `release-verify.sh` 的徽章闸看见（等于第八个自称拥有版本号、又不在任何判据里的地方），直接把版本号从页脚去掉，版本只在顶栏徽章那一处。`site/install.html` 安装包文件名同理改成 `PushBox-Setup-<版本号>-win-x64.exe` 这种占位写法——每个 Release 页上本来就只挂着本次那一个。
- **运维与排查两段也跟着形态走**：`README` 的常用运维命令原来只有 `docker compose …` 一套，现在主路径那一个容器另给三条（`docker logs/restart/exec curl`、升级是 pull→rm→重新 run、`docker rm` 不带走 named volume）；「容器启动后立即退出」那三条"检查 .env 文件"式的建议换成 entrypoint 自己打出来的四条排查，管理台 403 那一条改成先分清"没开"与"口令错"。
- **升到 v1.1.1 时照出两处"手写版本号但没人管"**：① `site/index.html` 页脚那条 `PushBox · 自托管消息推送 · 1.1.0` **既不在 `set-version.sh` 的两种覆盖形状里**（它认 `X.Y.Z · 自托管推送` 与 `class="ver">`），**也不在 `release-verify.sh` 徽章闸的 pattern 里**——正是那条闸的注释所说"第七个自称拥有版本号的地方"之外的第八个，而且它坏了不会红。页脚去掉版本号，版本只留顶栏徽章那一处。② `site/install.html` 的安装包文件名写死 `PushBox-Setup-1.1.0-win-x64.exe`，改成 `PushBox-Setup-<版本号>-win-x64.exe`（每个 Release 页本来就只挂着本次那一个，写死只会让下一版的使用者去找一个不存在的文件）。③ `deploy/WSL测试速查.md` 那条 `buildx --load -t pushbox/server:1.1.0` 以前也不归脚本管：compose 的 `image:` 跟着 `set-version` 改了、它没改，照文档构建完 compose 就找不到镜像——现在进了同一个循环（脚本第 7 步）。

## v1.1.0（2026-10-22）—— 本机起步那条路第一次真跑通（第二十八轮）

> 技术决策见 [软件设计方案](docs/软件设计方案.md) §11.1.2 / §11.2.1；这一轮的起点是"发布前还有哪些可优化项"那张清单里的第 1、2、3、5 项加计数闸。
>
> **v1.1.0 这一版装的是第二十六、二十七、二十八这三轮**（`v1.0.0` 之后没打过标签，所以这三轮一起进本次发布，标题里那三个日期各是那一轮收口的日子）。再往下那条 `## v1.1.0（2026-10-19）—— 管理台定时推送` **不是本版本**，它是 `v1.0.0` 之前 28 轮内部流水留下的旧编号（文件顶上那句"原编号 v1.1–v1.28 已归档"说的就是这套），两边撞上纯属同号——按标题里的日期认，别看编号。

### 修掉一条让"本机起步"整条路径跑不起来的缺陷

- **`PB_STORAGE_TYPE=local` 时进程根本起不来**，报 `NoSuchBeanDefinitionException: No qualifying bean of type 'S3Client'`（装配链 `adminCli → MediaPurgeService → MediaObjects`）。`S3Config` 原来在 local 下 `return null`，而 Spring 对 `@Bean` 返回 null 注册的是 `NullBean`——它对必须注入的构造参数等于"没有这个 bean"。**README、`部署快速参考`、compose、entrypoint 四条线都写着 local 是本机默认形态，而它只在单测里通过过**：那些用例全是 `new MediaObjects(null, props)` 手工构造，绕开了容器。
- **修法是让这个 bean 在 local 下不存在**，不是给参数加 `@Nullable` 把现场抹平：`S3Config#s3Client` 上 `@ConditionalOnProperty(prefix="pb.storage", name="type", havingValue="s3", matchIfMissing=true)`（默认值与 `PbProperties.Storage#type` 对齐），`MediaObjects` 的参数留 `@Nullable`、`storageType` 先 `trim`，并把"`s3 `"这类两边都不沾的取值）报成"只认 s3|local"而不是一个堆栈。
- **新增 `StorageWiringTest` 3 条**：跑真 `AnnotationConfigApplicationContext`（`register(S3Config.class, MediaObjects.class)`，`pb.storage.type` 同时塞进 `MapPropertySource` 与绑定后的 `PbProperties`——只给一边测的就不是生产那条路径）。local 那一支断言 `getBeanNamesForType(S3Client.class)` **为空**而不是"有个 bean 但值是 null"，`s3` 与 `S3` 各断建出一个。这类"只有容器才照得出来"的缺陷，从此有一条不用人肉起服务端的证明。

### 服务端：把"配了但读不到"也变成一件要出声的事

- **`DevCredentialsGuard` 加反向 WARN**（①）。必填项按存储类型分档之后，"忘了把 `PB_STORAGE_TYPE` 改成 `s3`"不再让启动失败，只会让整套部署静默落在本地目录上——所以 local 模式却配着 `PB_S3_ENDPOINT/ACCESS_KEY/SECRET_KEY` 时点名这几项"配了但一个字都不读"，并给出落盘目录与"要验桶就带 `PB_STORAGE_TYPE=s3` 重启"的下一步。**只报键名与目录，值不进日志**（同 §13 的脱敏口径）。`DevCredentialsGuardTest` 从 8 条扩到 10 条：⑨ 钉住这条 WARN（含"不许出现哨兵值本身"），⑩ 钉住真跑 s3 时这句不许响。
- 踩到的坑记一笔：`log.warn("…{}…{}", …)` 与 `String.formatted()` 是两套占位符，写成 `"""…{}…""".formatted(a, b)` 会把 `{}` 原样打出来——是 ⑨ 那条断言把它抓出来的。

### 部署：容器里的报错要说清"下一步敲什么"

- **entrypoint 的每一条失败都自带落点**（②）：缺 `PB_DB_URL`/`PB_DB_PASSWORD` 时点名写进哪个文件（`deploy/.env`）、JDBC 该长成什么样、拓扑 B 那台的服务名变体，以及指回 `deploy/部署快速参考.md` 的哪一节；S3 三项缺失时补一句"不想外接存储就设 `PB_STORAGE_TYPE=local`"。这些文案原先只说"未设置"，读者得自己去翻变量清单。
- **`PB_STORAGE_TYPE=local` 时启动阶段做一次写入探针**（③）：往 `${PB_STORAGE_LOCAL_PATH:-/app/data/media}` 写 `.entrypoint-write-probe` 再删掉，写不进就当场退出。判据是"目录存在但属 root"这一类**不会让 `/healthz` 报错**的配法——`HealthzController.storageConfigValid` 只校验配置项非空，靠它兜等于让每条带图消息静默停在 `failed`。探针那句 `if ! : > "$F" 2>/dev/null` 也修了：重定向按从左到右生效，得写成 `( : > "$F" ) 2>/dev/null` 才真把 `Permission denied` 咽下去。
- **local 模式下目录归镜像里预建并 chown**：`useradd` 那步从只建 `/app/logs` 改成 `mkdir -p /app/logs /app/data/media`，`PB_STORAGE_LOCAL_PATH` 的默认值也从 `./data/media` 纠正为容器里真实的那个 `/app/data/media`（entrypoint 原样打印前者，等于给出一条挂不到的路径）。
- **`deploy/docker-compose.yml` 的本地对象目录换成 named volume `server_media`**（③）：绑定挂载 `./data/media` 会把宿主目录属主带进容器，而 Docker 替缺目录建出来的那层属 root，容器里的 `pushbox(10001)` 写不动——同一条判据现在由上面那个探针在启动时判死。备份口径跟着改成 `docker volume inspect pushbox_server_media -f '{{.Mountpoint}}'`；`docker-compose-wsl.yml` **刻意保留绑定挂载**（`deploy-wsl.sh`/`.ps1` 按目录整体重置测试栈），注释里写明了这条差别。

### 计数闸：两处"看着有闸、其实没人看"的盲区（②）

- **C# 侧原来完全没有基线断言**：`ci.yml` 的 windows job 只跑 `dotnet test`，退出码只回答"有没有红"，用例被静默裁掉（删 `[Fact]`、改分类过滤、整类没被 discover）照样退 0。现在改判 trx（`--logger "trx;…"` + PowerShell 数 `UnitTestResult`，不解析控制台文本——那玩意的语言跟 runner 走），并加文档侧第二条计数闸，唯一真值取 `ci.yml` 里的 `$CS_BASELINE = 554`。**加它当天就抓到一处现值腐烂**：`发布准备报告` 的状态表还挂着 556/556，而其余六处同一天已改成实测的 554。
- **Java 那条只认 `core a + server b` 这种能求和的形状**，`site/index.html` 首屏那行裸的 `303 / 554` 从来不在比对范围内。新闸判"关键词同一行里的三位数对"，Java 与 C# 两个基线之外的数一律 FAIL，带日期的行整行跳过（历史证据不参与现值判定，同 `docs/审计报告.md` 的排除理由）。**负测做过**：把 `site/index.html` 改成 `303 / 555` 当场 `FAIL: 文档里出现了基线之外的 C#/Java 用例数：555`；另加一条防空转断言，没有一处文档真写着基线值时判 FAIL 而不是"全都对"。

### 文档与站点

- `deploy/部署快速参考.md`：named volume 与写入探针两条口径、拓扑 C 那一行改口；`deploy/env/prod.env.example`、`deploy/backup/backup.sh` 跟着改卷名与备份路径；`site/install.html` 拓扑表补同一句。
- `docs/软件设计方案.md` §11.2.1 加一段"用同一套跑法复验抓到的接线缺陷"（NullBean 的成因、为什么单测照不出来、判据落在 `StorageWiringTest`），§11.1.2 补"反方向也一样要出声"那句判据与 `DevCredentialsGuardTest` 的真实条数（8 → 10）。
- 测试计数按实测归一：`core` 43、`server` 263，两档合计 **306**（本轮 `+3` 全是 `StorageWiringTest`），不带凭据时 **192 执行 + 114 跳过**。`README`、两份指南、本清单、`发布准备报告`、三张 site 页面与 `ci.yml` 的基线一起抬到 306；`CHANGELOG` 里第二十七轮那两句历史数字改成不带求和形状的写法（数字留着当证据，但不让它和现值撞成两个取值，否则这条闸永远红）。

### 官网：大段说明砍掉 36%，长段落改成编号步骤卡

- **8 个页面全部按"这是什么 → 怎么敲 → 敲完看到什么算对"重写**，纯文本 72 774 → 46 243 字（−36%）。站点是给使用者看的，所以仓库内部口径整类删掉：零构建说明、"内容取自哪些文档"、CI 与签名 Secrets、单测条数、探针的代价、构建踩过的坑、"这里为什么不写死"——**官网已不再携带测试计数**，因此 Java 与 C# 两条计数闸的扫描路径都摘掉 `site/`，Java 那条的文档数下限从 ≥6 改成 ≥4（摘掉后实测 5 个文档带计数，余量 1：一处改成叙述式仍绿，两处红），`发布检查清单` 的闸门描述跟着改口。
- **排版**：`.steps` 编号步骤卡（CSS `counter` 出序号，每步一句说明 + 一条命令）替掉原来的长段落；`h1/h2/h3` 与正文宽度收到 74ch；`callout` 与 `p.sub` 补齐两级层次。全站实测 **>160 字的正文段落 0 个、>120 字的表格单元格 0 个**（剩下的长文本全是步骤卡里"标题 + 命令"两段拼出来的 `li`，正是这次要的形态）。
- **代码样式**：`site/assets/site.js` 加了一个约 40 行的零依赖高亮器（bash/powershell/python 共用一套、sql、json 各一套），保持"双击 `site/index.html` 就能看、无构建"这个前提。**唯一的不变量是重拼后 `textContent` 必须逐字符等于原文**——复制按钮读的就是它；用 62 段代码跑同一套走位验证，0 段丢字。两个坑：标志位不能整表共用 `gi`（`\b[A-Z][A-Z0-9_]{2,}\b` 加了 `i` 会把 `mvn`、`deploy` 全染成变量色），Spring 那类长参数要连着取值一起吃掉（`--pb.s3.delete-grace=2s` 是一条参数，不是四段）。
- **curl 生成器**（`site/api.html`）改完才发现问题：它用 `textContent` 重刷 `genFile`/`genOut`，等于把首屏那次高亮冲掉——现在 `render()` 之后补一次 `paint()`，实测输入标题后两段分别落 16/9 个 span 且文字完整。顺手把它那句兜底地址从 `127.0.0.1:8080` 改成 7404（表单默认值本来就是 7404，只有清空输入框才走得到兜底）。
- `site/install.html` 不再打印 dev 配置自带的占位管理口令——**公开文档里出现一个能直接登录的口令，等于教人把它带进生产**；改成"口令就是 `PB_ADMIN_SECRET` 的值，本地用自带的那份，对外必须换"。
- **Docker 部署这一节只写从 Docker Hub 拉镜像**：删掉 `docker compose up -d --build` 那行与"改了服务端代码就本地构建"的说法，`git clone` 那一步改口成"仓库里只要 compose 与 env 模板，机器上不用装 JDK 和 Maven"。自己改代码怎么构建仍然只在内部文档（`deploy/部署快速参考.md`）里讲——**站点是给部署的人看的，不是给改代码的人看的**。
- **站点接上真实图标**（用的全是 `logo/web/` 里已有的导出稿，没有新生成任何图）：`favicon.ico` + `favicon-32x32.png` + `apple-touch-icon.png` + `icon-192.png` + `icon-512.png` 五本拷进 `site/assets/img/`，8 个页面的 `<head>` 各挂三条 `link`，侧栏与首页顶栏那个手写的字母 "P" 换成真 logo（`.mark` 从此是 30px 圆角图块），首页标题做成一份 logotype——图标以 `.92em` 跟在 `PushBox` 那行字左边、随 `clamp(52px, 9vw, 96px)` 一起缩放。**先按"hero 上方单独放一张 84px 图"做过一版，被判定"太突兀"后改成行内 logotype**：浮在正文上方的小图块没有归属，嵌进标题行才读得出它是标识（880px 视口实测图标 73×73、与 79.2px 字号同一行且不折行）。**这条改动同时把 memory 里挂着的"favicon 未接"结掉了**。浏览器实测五本全 200、`naturalWidth` 非零。
- **公开渠道只留 GitHub 与 Gitee**：Gitea 那台是私有备份，不该出现在公开站点上。8 个页面的侧栏/页脚与 `README.md` 的仓库行全部删掉那个地址。**`AGENTS.md` 里那一条留着没动**——它是"推三个 remote"这条工作流的依据，删了就等于把备份地址也一起弄丢了；但它跟着仓库公开，要不要挪进 gitignored 的本地文件得先问过再改。
- **首页"源码仓库"改成静态两张卡**（GitHub 主仓 / Gitee 国内镜像，各带一条 `git clone` 与一个 ↗ 链接）。原先这两张卡由 `site/repos.json` 在运行时 fetch 出来，而 `file://` 双击打开正是站点写明的用法——浏览器不允许 fetch 同目录文件，于是首页那一节在最常用的打开方式下只剩一句报错提示。现在地址、命令、角色都写在 HTML 里，`site/repos.json` 与 `site/assets/site.js` 里那段卡片渲染代码一并删除。**卡片文案是按远端实测写的，不是照着旧文案搬**：`git ls-remote` 与 `api.github.com` 都查过——GitHub 有 `main`+`publish` 两条分支、标签与 Release 还是空的，Gitee 目前只有 `publish`。所以旧那句"Release 与安装包下载都在这里"（GitHub）与"按版本手动同步的镜像"（Gitee）都不能原样留着：前者改写成"安装包与镜像标签由 CI 在打 `v*` 标签时产出"这件由 `.github/workflows/build-apk.yml`、`build-msi.yml`、`docker-publish.yml` 三个触发器证实的事，后者改成如实的"同步是手动的，所以它可能落后主仓；提 Issue 请回 GitHub"，都不做带时间口径的断言。

### 官网发布链：`site-deploy.yml` 把 site/ 发到只装站点的 gh-pages 分支

- **形态**：`site/` 没有构建步骤，所以"发布"只是把这一棵目录树搬到一条只装它的分支根上——用 `git commit-tree` 直接以 `site/` 的树做根树生成提交，再推 `gh-pages`。分支根上因此只有 `.nojekyll` + 8 本 HTML + `assets/`，没有源码、没有 README、没有构建产物；第一条提交是孤儿，之后每条挂前一条（不一直造孤儿是因为孤儿会让上一次发布变成不可达对象，GC 一收走就连"哪一次换了哪棵树"的账都没处查）。**只依赖 `actions/checkout`，没引第三方 action**：这条链要往仓库写数据，第三方 action 是白多出来的供应链入口，而且为一份静态 HTML 在 runner 上装 Node 也没必要。
- **"有内容更新时才发布"落成两层**：① `push` 触发带 `paths: ['site/**', 本文件]`，改代码与内部文档不会惊动官网；② 比 `site/` 的树哈希与 `gh-pages` 现有根树，相同就退出。**第 ② 层不能省**：`workflow_dispatch` 完全不吃 `paths:` 过滤，只有第 ① 层时手动每点一次就多一个空 commit 与一次 Pages 构建。并发用 `concurrency` 排队而不是取消（两条部署同时推 `gh-pages` 会互相覆盖），推时用 `--force-with-lease=<分支>:<这次 fetch 到的值>`，有人在窗口里手动动过那条分支就推不动、而不是被悄悄盖掉。
- **刻意不做的事**：不开 GitHub Pages（改仓库设置要人点头；没开时只推分支、站点地址仍打不开，工作流最后一步在 404 分支里把开通那条 `POST` 原样打出来）、不挂 `v*` 标签（官网内容与版本号无关）、不删历史。非 `main` 分支手动触发且真要推上去时直接拒——Pages 只有一个地址、没有预览地址可回退，确认要发得显式勾 `force_branch`；`dry_run` 不吃这条限制，因为它只算树、比对、生成提交，一个字节都不推。
- **`site/.nojekyll` 是这条链的前提之一**：Pages 以分支为源时，根目录没有它就会拿 Jekyll 过一遍这份静态站；站点里全是原样该发出去的 HTML/CSS/JS/PNG，不该有第二套构建参与。发布前还有一步"引用必须全是相对路径"的检查——项目站地址带仓库名（`https://<owner>.github.io/push-box/`），`href="/x"` 这种根绝对路径在 `file://` 与本机静态服务器上都是好的，一上 Pages 就指到域名根，本机看不见这类坏链。
- **验证**：拿一条本地裸仓库当 GitHub 的替身，把四步 plumbing 原样跑过——推上去的根树与 `site/` 树逐字节相同、第一条提交无父、第二次同内容运行判"跳过"、`site/` 多一个文件判"要发"、lease 期望值错则推被挡下、期望值对则历史线性（第二条父=第一条）。`scripts/release-verify.sh` 新增一条 `site-deploy.yml 护栏齐全`（paths 在 / 内容比较在 / 不挂 tag / 只用官方 action / 检查 `.nojekyll`），五种会让它红的改法逐条做过负测；**第一版负测里"补上 tags 触发"那条 sed 根本没匹配上，闸门报绿其实是测了个空气**，加了"变异必须真生效"的自检才露出来。实跑 `bash scripts/release-verify.sh`：**53 通过 / 0 失败**（比上一轮多的那一条就是它）。~~这条链至今没在 runner 上跑过~~ **当天就跑了**：`ab5c9fb7` 推成 `main` 之后 run `39720441735` 四步全绿，`gh-pages` 由工作流建出来（`ff5c8c4`，根树 `f5451108…` 与那次推送的 `HEAD:site` 逐字节相同、16 个文件），Pages 自己的构建 run `39720519087` success，`https://iamplusdev.github.io/push-box/` 实测 **200**，`assets/site.css`、`assets/site.js`、`assets/img/icon-512.png`、`install.html`、`admin-api.html` 五个地址逐个 200，服务出来的 HTML 里 `grep -E '(href|src)="/'` 零命中——那条"引用必须全是相对路径"的事前检查因此被线上反证了一次。**本机验证与 runner 实跑唯一的差别是 Pages 源**：源没配置时这条链照样把分支推上去、站点地址打不开，本轮是维护者在网页侧把源设成 `gh-pages` / `/` 之后才 200 的（工作流本身不碰这个设置）。

### 发布链首跑：六个 workflow 五个绿，红的那条把 `local` 默认与 S3 门控的组合照了出来

- **首跑结论（`ab5c9fb7` 推成 `main`，2026-10-22）**：`docker-publish.yml` 真推 `main`/`sha-*`、`build-apk.yml`、`build-msi.yml`、`update-dockerhub-description.yml`（历史首跑）、`site-deploy.yml`（首推 `gh-pages`，Pages 构建绿，站点实测 200）五个 success；`ci.yml` 的 `server` job 红在 `mvn -B -pl server -am test`，1m14s 就退，另外三个 job 都绿。
- **根因**：`MediaSsePipelineTest` 那 7 条 `@Autowired S3Client`，而这一轮把 dev 的默认存储类型改成了 `local`（"本机起步只要一个口令"那次），`local` 下 `S3Config` 根本不建这个 bean——于是 `UnsatisfiedDependencyException`，7 条一起 ERROR。这条组合只有 CI 会撞到：本机没有 `PB_S3_ACCESS_KEY` 时这 7 条整类跳过，CI 里凭据齐了门放开、跑法却还是 `local`。**同一天新加的 `DevCredentialsGuard` 反向 WARN 其实一直在日志里说这件事**（"配了 `PB_S3_*` 但走 local，一个字都不读"），但它只 WARN、不判红，所以拦不住。
- **修法改测试自己，不改 CI 的环境**：`@SpringBootTest(properties = …)` 里补 `"pb.storage.type=s3"`——这个类要的是真桶，前提就该由它自己声明，而不是指望 runner 恰好把默认值设成 s3。CI 的 env 一个字没动，任何带 `PB_S3_*` 但没有 `PB_STORAGE_TYPE` 的开发机也从此跑出同一份判据。
- **复现路径**（当时手上只有匿名 HTTP：runner 日志与 surefire artifact 都 401/403 取不到，`check-runs/<id>/annotations` 那一路后来实测是 200 可读，但那次它只给得出"exit code 1"，见下面第二十九轮）：WSL 里起 CI 同款 `mysql:8.4 --ngram_token_size=2`（实测 `@@version=8.4.11`、`@@ngram_token_size=2`）+ `rustfs` + aws-cli 建桶 `pushbox`，端口发到宿主，Windows 侧用本机那份热 `~/.m2` 跑 `mvn -B -pl server -am test`。**先在 WSL 里跑失败过一次**：容器内现下依赖实测 2.6 kB/s，等不起，所以换成宿主跑 mvn。复现结果与 CI 同形（`Tests run: 263, Errors: 7`），修完这 7 条变 7/7 绿（单类 20.8s）。
- **修完全量复跑是 `43 条 core 与 263 条 server（那一晚合计 306）、跳过 0`，但仍有 3 条红，全在这台机器的出网**：`error=unreachable host=picsum.photos`（`MediaSsePipelineTest` 两条）与 `http_404 host=httpbin.org`（`MediaFetchServiceTest.generatesThumbnail`），断言都是 `expected: "ready" but was: "failed"`——抓取侧到不了那两台公共图床，不是装配问题。**这类"用例依赖公网图床"的脆弱面本条只登记、没改**：CI runner 那边一直够得着，所以历史上绿过；真要钉死得把那几条换成本机起的测试图片服务，那是另一件事。
- **`v1.1.0` 标签在这条转绿之前不打**：Docker Hub 的版本标签按本仓口径不可变（`1.1.0`/`1.1`/`latest` 一旦推错只能换个号重来），而 `v*` 事件同时开 GitHub Release。**这条规矩当天就兑现了**——先等 `main` 上 `ci.yml` 绿（head `f86eeac` run `39722819597` 四 job 全 success，第 8 步 `skipped == 0` 且用例数 ≥ 306 第一次在 `main` 上跑到），再把只剩文档改动的 `4e2fd71` 推成 tip、在它上面打 tag，见下面「发出去」那一节。

### 验证

- `mvn -B -o -pl core,server test`（2026-10-22）：**306 条，0 失败 0 错误，114 条按 `PB_S3_ACCESS_KEY` 门控跳过**（不带凭据即 192 执行）。本轮新增/改动的 `StorageWiringTest` 3/3、`DevCredentialsGuardTest` 10/10、`MediaObjectsConfigTest` 4/4。
- **端到端探针在 local 存储模式下 23/23 全绿**：`bash deploy/run-dev.sh --server.port=8081` + `PB_BASE=http://127.0.0.1:8081 node deploy/probe/p0-e2e.mjs`，启动行是 `对象存储后端=本地文件系统 path=./data/media encrypt=none`、`/healthz` 回 `db/storage up`。这是这条路径第一次跑通整条 P0 链——同一份 jar 在修复前起不来（就是上面那条缺陷）。用完只杀自己叫起的 PID。
- entrypoint 渲染成脚本后跑了 5 个场景（缺库变量、local 齐全、local 目录不可写、local 却配着 S3 凭据、sse-c 主钥长度不对），文案与退出码都按预期；`docker compose config` 在 WSL 里把主文件与 wsl 两本都渲染过，named volume 解析成 `pushbox_server_media`。
- `dotnet test PushBox.Crypto.Tests -c Release`：**554/554**，trx 解析出的 `UnitTestResult` 计数同一值（新闸的判据就是它）。
- `bash scripts/release-verify.sh`：**52 通过 / 0 失败**，两条 WARN（本机没有 docker CLI 判不了镜像体积、工作区还有未提交文件）。新增两条 PASS：Java 计数与 `ci.yml` 基线一致（306，9 处 / 7 个文档——第二十七轮那两句历史数字改成不带求和形状的写法之后，CHANGELOG 自己就不在这份名单里了，这正是那条约定预期的效果）、C# 计数一致（现值 306 554，8 个文档）。
### 发出去：`v1.1.0` 的三条链第一次全绿，顺手照出 Release 正文里一枚不存在的镜像标签

- **tag 打在 `4e2fd71`**（`main` 的 tip，也就是 CI 刚绿过的那棵树；前面的 `f86eeac` 只少了 CHANGELOG 的编号订正）。这一版之前，`v*` 事件那条路径在 runner 上从没跑过，本机只能验到闸门脚本——所以这三条绿是第一次，不是重复。
- **三条链的结论**：`build-apk.yml` run `39724346953`（`Set version from git tag` → `Verify release APK signature` → `Publish to GitHub Release` 全 success）；`build-msi.yml` run `39724347008`（`Sign installer` **skipped**，其余全 success）；`docker-publish.yml` run `39724347017`（`版本号一致性闸门` → `Docker metadata` → `Build and push` → `本次的标签清单` 全 success）。Release 页 <https://github.com/iamplusdev/push-box/releases/tag/v1.1.0> 上两个资产的名字与字节数（`pushbox-release-v1.1.0.apk` 12 797 054、`PushBox-Setup-1.1.0-win-x64.exe` 58 729 609）是从公开的 releases API 读出来的，不是照工作流文案推的。带 `paths:` 过滤的 `site-deploy.yml` 与 `update-dockerhub-description.yml` 在 `v*` 事件上不触发，这一版本它们没动。
- **`gh` 与 `git push --tags` 之外还有一条真 bug**：Release 正文首行是模板拼出来的 `Docker 镜像 iamplusdev/push-box:v1.1.0`，而 `docker-publish.yml` 用 `type=semver,pattern={{version}}` 推的是**去掉 `v` 的 `1.1.0`**（外加 `1.1` 与 `latest`）。照正文 `docker pull` 的人会指向一枚 Hub 上不存在的标签。两个构建工作流的 `NOTE` 都改成从 `${TAG#v}` 推标签，并且**不是"看着改对了"**：把这两步的 `run` 脚本原样抽出来、把 `gh` 换成打桩函数在本地执行，两条输出实测为 `push-box:1.1.0（同一镜像另有 1.1 与 latest 两个标签）`（打桩还顺带确认 `--verify-tag` 与 `--title` 没被引号拼坏）。
- **改不掉的已经发出去那一页**：这台机器没有 `gh` CLI、也没有可用的仓库 token，`v1.1.0` 那页首行至今仍是 `push-box:v1.1.0`，正确文案从下一个 tag 起生效。这一句写出来是为了别让"工作流已修"被读成"那页已修"。
- **两条没独立复核的**：① Hub 上真实的标签清单——`hub.docker.com` 与 `registry-1.docker.io` 从这台机器都是 `http=000` 连接超时（不是 404，与前面 `update-dockerhub-description` 那次同一个网络现实），所以"三枚版本标签已推上去"只有 CI 那两步的退出码背书；② 两个产物都没在这台机器上装过，APK 只到"CI 里签名与证书指纹对得上基线"，安装包只到"CI 构建出文件并上传"，且**仍未签名**（`CODESIGN_*` 那对 secrets 没配，SmartScreen 那一栏照旧）。

- **环境状态记一笔**：本轮复验时本机 `7404` 上没有监听（那台手动起的服务端不在跑），探针全程打在自起的 8081 上。

### 发出去：`v1.1.1` 六条链全绿，站点与镜像同一批生效

- **顺序按上面那条约定走了一遍**：`bash scripts/set-version.sh 1.1.1` 并提交 → `c78d062` 推成 `github/main`（`2d1c523..c78d062`，顺带快进 `publish`）→ **在同一个提交上**打 `v1.1.1` 再推 tag。删掉那五处 caveat 的那个提交就是被打 tag 的那个提交，所以 Release 页与 Pages 上都不存在"本页要等下一版镜像"这种话——这是这条约定第一次被真的执行，不是写在文档里的规矩。
- **十条 run 的结论**（这台机器没有 `gh`、也没有 token，全部走 `api.github.com` 匿名读）：tag 事件三条——`docker-publish.yml` run `39750471717`（版本号闸门 → metadata → build&push → 标签清单，即 `1.1.1` / `1.1` / `latest` 那三枚）、`build-msi.yml` `39750471713`（`Sign installer` 仍 **skipped**，其余 success）、`build-apk.yml` `39750471704`；`main` 推送五条——`ci.yml` `39750377194`（四个 job 全绿，其中「断言 DB 闸门用例真的执行了」与「断言 C# 用例数没有被静默裁剪」两道计数步第一次是在 `1.1.1` 这棵树上跑的）、`docker-publish.yml` `39750377007`（只出 `main` / `sha-c78d062`，按口径不碰版本标签）、`build-apk.yml` `39750377334`、`build-msi.yml` `39750377041`、`update-dockerhub-description.yml` `39750377324`；发布站点两条——`官网发布（GitHub Pages）` `39750377003` 与其下游 `pages build and deployment` `39750395138`。十条结论全是 `success`。
- **上一版那条"改不掉"现在改得掉了**：`v1.1.1` 这页正文首行实测是 `Docker 镜像 iamplusdev/push-box:1.1.1（同一镜像还有 1.1 与 latest 两个标签）`，不带 `v`。也就是说 `7b148ea` 那次改的 `${TAG#v}` 第一次在真 Release 页上生效——`v1.1.0` 那一页首行仍是错的 `push-box:v1.1.0`，那是历史页、动不了，两页并排看正好是这条修正的前后对照。两个资产的名字与体积从公开 API 读出：`pushbox-release-v1.1.1.apk` 12.2 MB、`PushBox-Setup-1.1.1-win-x64.exe` 56.0 MB。
- **站点这次是"推上去就当场复核"的**：`gh-pages` 顶部提交是 `3c671dc`（`deploy: site tree 49abc386…`，那个哈希由 `site-deploy.yml` 现算，所以只在本轮 `site/**` 真变过时才动）。线上 `install.html` 里 `PB_STORAGE_TYPE=s3` 出现 5 次、步骤卡标题「已经有一个桶？同一条命令让它落桶」在页上，且**那一行的行号与仓库内文件逐行对齐**（121 / 153 / 160 三处在两边一模一样）；`index.html` 与 `install.html` 的徽章都是 `1.1.1`。这条比对是刻意做的：上一版抓到过"仓库改了、站点没跟着改"，行号一致比"看着内容差不多"更能证同一棵树。
- **仍然只有 CI 退出码背书的三件事**：① Hub 上真实的标签清单——`registry.hub.docker.com` 从这台机器是 `curl (28)` 连不上（`http=000`，不是 404），所以"三枚版本标签已就位"没有本机实证；② 镜像体积判不了（本机无 docker CLI，`release-verify.sh` 那唯一一条 WARN 就是它，最终 **62 通过 / 0 失败 / 1 警告**）；③ 这一版**没重跑任何客户端装机**——`v1.1.1` 只动部署资产、文档与版本号，三端代码与用例数没变（Java 仍是 core 43 条、server 265 条），可 tag 一样会把新 APK 与安装包发出去（`versionCode` 10101 > 10100），所以那两份制品的装机路径沿用的是 `v1.1.0` 那批真机证据，安装包也**仍未签名**。

---

## v1.1.0（2026-10-22）—— 频道密钥本机留存 + 服务端启动与单镜像部署（第二十七轮）

> 技术决策见 [软件设计方案](docs/软件设计方案.md) D73 / D74；D17 随之改为"两端都不做筛选"。

### Android 客户端

- **撤掉对话页的"全部 / 未读 / 已读"筛选**（与 PC 第二十五轮同一条理由：消息越少筛选越没意义，一条提示条 + 三个菜单项换来的只是"我怎么只看得到一半"的困惑）。删掉 `ConversationFilter`、筛选提示条与 `showFilter` 弹窗，`messagesFlow` 去掉 `readState` 参数与那条 SQL 判据；撤完之后顶栏只剩搜索一个动作，于是**连 `⋮` 一起删掉**，改成一枚 `Icons.Filled.Search` 的 `IconButton`——"只有一个动作的溢出菜单"就是白多点一下。
- **`⋮` 频道动作从 10 项 `DropdownMenu` 换成分组 `AlertDialog`**：接入与命名 / 密钥 / 治理 / 改号与清理四组，每组一个小标题（`DialogGroup`），危险项靠组名而不是只靠 `error` 色。`MenuOption` 加 `danger` 参数（放在 `onClick` **之后**，避免打断既有位置参数调用）。组内不适用的项**整条不出现**而不是灰态——一行灰掉的按钮暗示的是"权限不够"，而真实情况常常是"这一步在这台机器上根本没有对应数据"。
- **新增「复制密钥」，读的是本机留存**（D73）。服务端只存 `secret_hash`，任何列表接口都给不出明文，所以这一项没有"读一次"的实现路径；能做的是在明文经过屏幕的那四个时刻各写一次：新建（响应）、重置（响应）、手动加入（用户粘进去的那串）、订阅（同前）。存法沿用设备凭据那套：`IdentityStore` 一个 `号码\t密文` 行清单（键 `channel_secrets`），值经 `SecretVault`（AndroidKeyStore 不可导出钥 / AES-GCM / `v1:<iv>:<ct>`）封装，`App.channelSecrets` 冷启动整体解进 `StateFlow`，`ChannelListRow` 按频道号取。**三条一起定的**：解不出来时那一组显示"本机没有这份密钥"而不是灰按钮；**换发新频道号要把留存搬到新号**（先清旧号再写新号，否则一次改号就把唯一一份密钥无声丢掉）；清空写空串而不是删键，让"忘了"与"从没存过"在读取端归一。
- 文案：删 `conv_filter_title` / `filter_all` / `filter_unread` / `filter_read` / `filter_hint_*` / `filter_empty`，新增 `channel_grp_access` / `channel_grp_secret` / `channel_grp_govern` / `channel_grp_danger` / `channel_copy_secret` / `channel_secret_missing` / `confirm_close`。

### 服务端：本机启动不再逼你配一套桶

- **`application-dev.yml` 的存储默认改成 `local`**：`pb.storage.type: ${PB_STORAGE_TYPE:local}`、`local-path: ${PB_STORAGE_LOCAL_PATH:./data/media}`，同时给四个 `PB_S3_*` 占位符加空默认值（原来解析不了会原样留下字面量）。于是**起一个能收消息的本机服务端只需要 `PB_DB_PASSWORD` 一个环境变量**，图片落 `./data/media`。
- **`DevCredentialsGuard` 的必填清单跟着这次选择走**：`PB_DB_PASSWORD` 恒要，`PB_S3_ENDPOINT` / `PB_S3_ACCESS_KEY` / `PB_S3_SECRET_KEY` 只在 `pb.storage.type` 不是 `local` 时才要。报错文案补一句"只想要一个能收消息的本机服务端：`PB_STORAGE_TYPE` 保持默认的 local，就只需要 `PB_DB_PASSWORD` 一个键"。
- **`DevCredentialsGuardTest` 从 6 条扩到 8 条**：新增"local 模式只缺口令"与"local 模式下的报错**不许出现** `PB_S3_ENDPOINT`"（多列一项用不到的键，等于让人去凑一套根本用不上的桶配置）。原有 ①–⑥ 不回归，因为它们的上下文里存储类型仍是默认判据 `s3`。

### 部署：一个镜像含服务端与管理台

- **`SpaWebConfig` 的兜底回退加了保留名单**（D74）：`api` / `actuator` / `ws` / `healthz` / `admin` 加**可配的入站路径**（`pb.ingest.base-path`），命中即返回 null → 保持 404。这条修的是 2026-10-20 实测到的那个坑：漏掉 `/api/v1` 前缀的 `GET /admin/channels` 回的是 **HTTP 200 + 一段 HTML**，只看状态码的脚本一路走下去、取字段全 `undefined`，症状长得像"服务端返回空数据"而不是"URL 写错了"。**判据是第一段整段相等，不是 `startsWith`**——管理台的搜索页路由是 `/messages`，按前缀匹配会被入站的 `/message` 吃掉，页面刷新一下就 404（这条错向比原来那条更难发现）。回退目标改成构造期注入，因为本地/CI 的 jar 里通常没有 `static/index.html`，不注入就没法测这条分支。
- **新增 `SpaWebConfigTest` 7 条**：前端深链与缺失资源回退、后端路径保持 404、`/messages` 与 `wso` 这类撞前缀的名字不被误杀、入站路径可配（改名后旧路径回退给前端）、base-path 配成空/带斜杠/撞名单时名单不重复不炸、真存在的文件优先、没有 SPA 的 jar 返回 null 而不是抛异常。
- **`deploy/Dockerfile` 的 entrypoint：S3 三项检查改成 `PB_STORAGE_TYPE=local` 时跳过**——否则"零依赖单机部署"这条文档路径在容器里被硬拦。`EXPOSE 8080` 纠正为 `EXPOSE 7404`（真实端口一直是 `${PB_SERVER_PORT:7404}`，那行只是给人看的，但看的人会被它骗）。补一句本地对象落在 `/app/data/media`、不挂卷 `docker rm` 之后全没。
- **`deploy/docker-compose-wsl.yml` 的三个 `PB_S3_*` 从 `${VAR:?}` 降为 `${VAR:-}`**（同一条理由；空值不会静默，`S3Config` 在 s3 模式下逐个点名并拒绝启动，只是从 compose 解析挪到了进程启动）。
- **`deploy/部署快速参考.md` 补两节**：「📦 单镜像部署（服务端 + 管理台同一个容器）」给出 `docker run` 跑法与三条不能省的（挂卷、端口以 `PB_SERVER_PORT` 为准、`/app/data` 归 uid 10001）；「加密口径」用一张表说清 `PB_S3_OBJECT_ENCRYPT` 管的是**对象字节**、与用不用对象存储服务无关，`local` 模式同样吃这条判据。
- **顺手修掉一处会让文档路径跑不起来的缺陷**：`方式二`（本地存储）那份 `.env` 原来写 `PB_S3_OBJECT_ENCRYPT=none` 却没有 `PB_ALLOW_PLAINTEXT_MEDIA=1`，照抄会启动即被 `ProdPlaintextMediaGuard` 拒绝。

### 文档与官网

- **D17 改成"两端都不做"**；§7.4 对话页那张 ASCII 图与"筛选"那条整段重写（顶栏只剩放大镜），§7.4 ③ 频道页补分组弹窗与"复制密钥读本机留存"三条，§9.3 状态表删"筛选态"一行，§11.1.2 改成"dev 只要一个键"，§11.2 加"一个镜像两种界面"、拓扑表补 C、必填项清单跟着 compose 的真实判据重写、`.dockerignore` 的描述改成它实际的黑名单形状。新增决策 **D73**（客户端密钥留存）与 **D74**（单镜像下的兜底路由判据）。
- **`用户使用手册`**：新增 §2.5「管理我的频道」（分组表 + 复制密钥的两条前置认知），搜索一节改点放大镜，Windows 那节残留的"§3.4 筛选消息"删掉（PC 第二十五轮就撤了，文档一直没跟上），后续小节顺延重编号。**另加 §4「Web 管理台」**（五页能做什么/看不到什么一张表，随后 常见问题→§5、故障排查→§6）——单镜像把这五页送到了每个装服务端的人手上，可三端文档里以前只有 `site/admin-api.html` 那份接口参考，没有一句"打开哪个地址、要什么口令、哪两件事界面上没有"。§4 里三条判据都是现读的：管理台在**根路径 `/`**；没配 `PB_ADMIN_SECRET` 时是**一律 403**（`AdminAuthController` 的默认状态，不是故障）；建频道与 `rotate-secret` **只有接口、没有界面按钮**（`Channels.vue` 现查确实没有这两个动作），所以那一节明确把用户指回客户端的「频道」页。
- **`README`**：管理台地址从 `https://<host>/admin` 纠正为**根路径 `/`**——vite 没配 `base`、路由是根下的 `/dashboard`、`/channels`、`/messages`，`/admin` 从来不是真实地址（现在按保留名单直接 404）。
- **`site/usage.html`**：删筛选那条、搜索改成放大镜、"「我的」→「接入」"改成底栏「频道」（这条在第二十六轮就搬了，页面没跟）、新增分组弹窗与本机留存密钥那段。**`site/admin-api.html`**：那条"前缀漏掉 → 200 + HTML"改成"以前 200，现在 404"，并保留"验 `Content-Type` / 验 `ok` 键"这条纪律（现网旧版本仍回 200）。**`site/install.html`**：dev 凭据改成"只必填 `PB_DB_PASSWORD`"、对象存储一节标注"要用桶时才需要"、生产拓扑表补 C 与单镜像/根路径说明。

### 本机起步的三处"文档说的和跑的不一样"

这轮把 §11.1 那套联调配置逐行对着真实文件与真实进程读了一遍，三处错向都不致命、但都会让人白花半小时：

- **`deploy/env/local.env.example` 的每一项补上 `export`，并把"dev 根本不读哪些键"标出来**。前半条：模板被 `source` 进当前 shell，少了 `export` 只得到一组 bash 变量，而 JVM 与 `@EnabledIfEnvironmentVariable` 读的是进程环境——照模板抄的第一次启动会报"没配 `PB_DB_PASSWORD`"，114 条集成测试整批静默跳过，两个症状都长得像别处的 bug（真实的 `local.env` 一直带 `export`，缺的只是模板）。后半条更阴：**`application-dev.yml` 只有 `PB_DB_PASSWORD` / `PB_STORAGE_TYPE` / `PB_STORAGE_LOCAL_PATH` / `PB_S3_*` / `PB_ADMIN_SECRET` 是占位符**，库地址与库用户写死成 `127.0.0.1:3306/pushbox` + `pushbox`，`object-encrypt` 写死 `none`，`admin.jwt-secret` 写死一份仅本地的值，端口来自 base yml。所以往 `local.env` 里加 `PB_SERVER_PORT` 换端口、或改 `PB_S3_OBJECT_ENCRYPT` 试加密，**改完一点变化都没有**，而人会继续怀疑代码。模板现在把带 ◆ 的（dev 真读）与"dev 不读"分开标，`开发指南` 同一段补了这句判据。
- **默认端口全仓库统一成 7404**。文档与探针里成片写着"默认 8080"（§11.1 的 yaml 片段、curl 示例、`site/install|overview|index|api|usage`、两个探针的 `PB_BASE` 默认值、开发指南的"用户手动起 8080"），而 `application.yml` 与 prod 的 `${PB_SERVER_PORT:…}` 默认都是 7404、dev profile 根本不设端口、真机验收用的是 `adb reverse tcp:7404`。现查本机那个跑着的实例也在 `0.0.0.0:7404`（`/healthz` 回 `db/storage up`）。**改的是文档与默认值，不是运行时**：没有往 `application-dev.yml` 加 `server.port`——那会让下一次手动启动从 7404 搬到 8080，把已经配好地址的手机与 PC 一起甩掉。探针头部补一句"那台端口上是别人的实例就另起 8081"，因为默认值现在真会指到一个活着的库。
- **`run-dev.sh` 的文件头**还写着"只连远端 MySQL/RustFS"（2026-10-13 库就搬到本机了，昨天存储也改成本地文件），改成按现状描述。

- **§11.2 的 `Caddyfile` 一节整段重写**：以前的写法是"Caddy 上自签 TLS + 显式设 `read_timeout 300s`、`idle_timeout 300s`"，而真实的 `deploy/Caddyfile` 只监听 `:80`、没有 `tls` 那一行，那两个子指令在 `caddy:2`（v2.11.7）的 `reverse_proxy` 与 `transport http` 两处都不合法（v2.16 才提升到顶层）——照旧文档抄的结果是配置加载失败、容器重启循环。同一段里 Prometheus/Caddy 的上游端口从 8080 改成实际的 7404，"上 HTTPS 的配套动作"补上那条容易踩空的实情：`network_security_config.xml` 只信任 `src="system"`，**用户级安装的根 CA 它不认**，`res/xml/network_security_config.xml` 的注释同步改掉"生产时整份替换"这句与 §11.1 明文允许项自相矛盾的话。
- **测试条数按现跑实测**：`core` 43、`server` 258 两档合计 **301**（本轮 `+9`：`SpaWebConfigTest` 7 条、`DevCredentialsGuardTest` 2 条），不带凭据时 **187 执行 + 114 跳过**。`开发指南` / `测试指南` / `README` 三处原来写 292/249 的同一行、以及 `测试指南` 报告模板里的勾选项一起改。
- **本机起步那一步，`README` 与两张 site 页面都指错了模板**：写的是 `cp deploy/env/prod.env.example deploy/env/local.env`。那一份的口径是容器（键集、必填项、默认值都按 compose 写），拿它当本机模板填出来的是一个"名字对了、内容不对"的 `local.env`；本机模板一直是 `deploy/env/local.env.example`。同一句的注释也从"填 `PB_DB_PASSWORD` / `PB_S3_*`"改成"起步只必填 `PB_DB_PASSWORD`"，前置条件里"S3 兼容桶（本项目用 RustFS）"整项删掉——dev 默认 `local`，桶是"要验桶形态才装"的那一个。
- **"管理台能不能建频道/重置密钥"四份文档四种说法，现读代码归成一份口径**：`Channels.vue` 全文只有 `刷新` 与 `删除`（归档）两个动作，`ChannelDetail.vue` 只有一个 `返回`、整页只读——也就是**建频道与 `rotate-secret` 确实只有接口、界面上没有按钮**（`site/admin-api.html` 那份端点总表标的"控制台：否"一直是对的）。对不上的三处：`README` 的已知限制把这节标题写成"Web 管理台支持频道创建与密钥重置"（读起来像界面能点），`发布检查清单` 有一条**怎么演**写成"管理台建频道 → … → 管理台发一条测试消息"（两个动作都没有按钮，照着演会卡在第一步），§11.1 的第三方自测引导写成"在 App 的新建频道或 **Web 管理台创建**时一次性回显"。三处统一改成：日常走 Android/PC 的「频道」页，脚本才用那两个端点；即时消息走 `POST /message`，管理台能触发推送的只有定时任务。

### 验证

- `mvn -B -o -pl core,server test`（2026-10-22 复跑两次，改 `application-dev.yml` 注释前后各一次）：**301 条（core 43、server 258 两档），0 失败 0 错误，114 条按 `PB_S3_ACCESS_KEY` 门控跳过**（不带凭据即 187 执行 + 114 跳过，与既往口径一致）。其中本轮新增/改动的 `DevCredentialsGuardTest` 8/8、`SpaWebConfigTest` 7/7。跑法必须让 `core` 与 `server` 在同一个 reactor（`-pl core,server` 或 `-pl server -am`），否则用的是本地仓库里的旧 core jar。
- 现查本机在跑的那个服务端：`0.0.0.0:7404` LISTENING，`/healthz` 回 `{"ok":true,"db":"up","storage":"up"}`，`/` 回 404 JSON（API-only 构建，jar 里没有 `static/index.html`，正是 `SpaWebConfigTest` 第 ⑦ 条钉住的那个分支）。这一条就是"默认端口按 7404 写、不要动 dev 的 `server.port`"的直接依据。
- `./gradlew :app:assembleRelease` BUILD SUCCESSFUL，release APK 已装到真机（`4TF6HQ8TCE6XHURO`）。
- `dotnet test windows/PushBox.sln -c Release`（2026-10-22 复跑）：**554 通过 / 0 失败 / 0 跳过**，退出码 0。比上一版少 2 条是撤掉「全部/未读/已读」筛选时连带删掉的 `MainModelTest` 两条筛选用例，不是测试被裁剪。
- `bash scripts/release-verify.sh`：**第一轮跑出 2 条 FAIL**，两条都是这轮改文档改出来的，且都只有机器能抓：① 用例计数闸发现仓库里同时存在两个"现值"（292 与 301）——`ci.yml` 的基线还钉在 292，`site/install.html` 与 `site/overview.html` 那两行没跟着本轮改，`site/index.html` 首屏更是停在更早的 288。现按实测把基线抬到 301，`CHANGELOG` 与 `发布检查清单` 里那句**历史**数字改成不带"core 加 server"形状的写法（旧值留着当证据，但不让它和现值撞成两个取值，否则这条闸永远红）。② 地址泄漏闸抓到 `docs/用户使用手册.md` 里一个写死内网 IP 的示例，改成 `<服务端所在机器的内网 IP>` 占位符。改完复跑 **51 通过 / 0 失败**，只剩两条 WARN：本机没有 docker CLI（判不了镜像体积）、以及跑的时候工作区还有未提交文件。
- **仍未完成的验证**：本轮两处 Android 改动（对话页放大镜、频道分组弹窗与复制密钥）的**真机截图验收**——设备当前停在数字密码锁屏界面，需要解锁后才能拍。`EnrollScreen` 那一屏同样还没拍过（要退出登录，属破坏性操作）。
- **没有重跑的**：端到端探针（默认 `PB_BASE` 这轮从 8080 改成 7404，会写到本机那个活实例上，要验得先另起 8081）。

---

## v1.1.0（2026-10-21）—— Android 端 UI 对齐 PC（第二十六轮）

> 技术决策见 [软件设计方案](docs/软件设计方案.md) D72；规范落在 §7.4 / §7.5 / §9.1 / §9.2。

### Android 客户端

- **两根条不再用 Material3 组件**：`TopAppBar`（64dp）/ `NavigationBar`（80dp）换成手写的 `PushBoxTopBar`（内容 48dp）/ `PushBoxBottomBar`（内容 52dp）。顶栏底 `#EDEDED`、底栏底 `#F7F7F7`，各带一条 0.5dp `#DEDEDE` 发丝线。栏底色铺在 `statusBarsPadding()` / `navigationBarsPadding()` **之前**，所以系统条看起来是同色延伸，而不是栏上面一条黑缝。
- **状态栏字符色跟随深浅色**：`isAppearanceLightStatusBars` 原本写死 `true`，深色模式下变成"深底深字"完全看不见；现在与 `isAppearanceLightNavigationBars` 一起进 `LaunchedEffect(dark)`。
- **配色 token 落进 `ui/Theme.kt` 的 `PushBoxColors`**：会话列表整张 `#FFFFFF`、行分割线 `#DEDEDE`（左缩进 66dp = 16 + 40 头像 + 10 间距，起于文字而非头像底下）、气泡 `#FFFFFF` + 正文 `#161415`、页面底 `#F5F5F5`。`bubbleIn` 更名 `bubble`（单向接收，"入/出"两档已经没有读者）。同时把 Material3 的 `ColorScheme` **整套**换成中性阶梯（`surfaceContainer*` / `primaryContainer` / `secondaryContainer` / `surfaceTint`），否则 `Card`、填充式 `TextField`、提示条仍走基线的紫。
- **对话页顶栏标题居中**，且标题带 30dp 会话头像 + 单行省略；右侧只剩搜索一个动作，所以直接放一枚放大镜图标（`⋮` 与"全部/未读/已读"筛选在第二十七轮整条撤掉，见下一节）。
- **头像形状与定色同步 PC**：圆角按 `size × 6 / 44`（微信式方圆形，非正圆），首字字号 `size × 16 / 44`，六色调色板与 `AvatarVisual.Palette` 逐值对齐（D33 的 31 多项式保证同一个发送者两端同色）。
- **首启接入页照 PC 重排**：新增 HTTPS 开关，`baseUrl` 从第三个 `state` 改成 `remember(host, port, https)` 派生（三处状态各改各的，出现过"开关关了 URL 还写着 `https://`"）；"最近使用"一排胶囊可点，点完由 `applyUrl()` 把 scheme/host/port 三段拆回来；「加入已有空间」默认收起；报错从贴在表单尾巴上的一行红字改成带 3dp 红色左轨的卡片（`height(IntrinsicSize.Min)`，否则 `fillMaxHeight()` 在无定高约束的父容器里塌成 0，轨直接消失）。
- **底栏拆成 消息 / 频道 / 设置，`MineTab` 删除**：接入是日常操作，收进「我的 → 接入」等于每次多一层（D17 的收纳只对低频设置成立）。设置页不再夹频道 CRUD。
- **频道行从 12 枚平铺按钮改成"两行信息 + 一枚 ⋮"**：动作收进 `⋮`，危险项着 `error` 色。提醒强度上限（`max_level`）并进「频道治理」弹窗，`PushBoxApi.setChannelMaxLevel()` 随之删除——它本来就是 `ChannelPatch` 的一个字段，PC 侧也一直在治理卡里。（这一枚 `⋮` 原本开的是 10 项 `DropdownMenu`，第二十七轮换成分组弹窗并加上「复制密钥」，见下一节。）
- **长内容的口径是"截断 + 可复制"而不是换行堆高**：频道号等宽 + `maxLines=1`，`InfoRow` / `CopyableField` 的值 `maxLines=2 + Ellipsis`（Compose 的 `Box` 不裁剪子内容，不设上限的长 URL 会横向溢出压到隔壁，症状长得像渲染 bug）。
- 文案与资源同步：`tab_me` / `mine_tab_access` / `mine_tab_settings` 删除，新增 `tab_settings`、`channels_refresh`、`channel_actions`、`channel_copy_no`、`channel_flag_muted`、`enroll_have_code_*`；`channel_not_subscribed` 缩短为"未接入"。

### 文档

- §7.4 重写：三 tab、两根条的高度与色、会话列表分割线、对话页居中标题、新增「③ 频道页 / ④ 设置页」两块（原来只有一张「我的页」清单）
- §7.5 补首启接入页排版与三条理由；§9.1 增补 Android 那 7 个 token 与"两端不强行同值"的说明；§9.2 补栏高、头像公式与调色板
- 全文清掉旧的 Android 导航路径（9 处「我的 → …」→「设置 → …」/「频道 → ⋮ → …」）
- [用户使用手册](docs/用户使用手册.md) §1.2 / §2 同步：三 tab、频道页操作路径、HTTPS 开关与"最近使用"、搜索改成实际入口（会话内 `⋮`）

### 验证

- `:app:compileDebugKotlin` 与 `:app:assembleRelease` 均通过；release APK（`dev/pushbox-release.jks` 签名，与机上 1.1.0 同签名）已 `adb install -r` 就地覆盖，数据未清
- **真机截图核对已完成**（2026-10-22 凌晨解锁后补跑）：消息列表 / 对话 / 频道 / 设置四页 × 浅色 + 深色两档，共 8 帧。栏高与 `#EDEDED`/`#F7F7F7` 双色、状态栏字符极性、分割线 66dp 缩进、居中标题、方圆形头像与调色板均按 §9.1/§9.2 落地
- **截图抓出一处漏色缺陷**（编译、代码审查与 Compose 预览都看不到，只有真机看得出来）：`SettingsSection` 的 `Card` 与填充式 `TextField` 在频道/设置两页渲染成**淡紫色**。根因是 `lightColorScheme()` / `darkColorScheme()` 只覆盖传进去的那几项 token，`surfaceContainer*`、`secondaryContainer`、`primaryContainer`、`surfaceTint` 全部沿用 Material3 基线的紫（`#E6E0E9` / `#E8DEF8` / `#B69DF8`），而 `Card` 的默认容器正是 `surfaceContainerHigh`。修法是把这一整套中性阶梯一起写进两套 scheme；重新构建装机后浅色/深色两档复验，卡片与输入框均为中性灰绿。**口径**：只要项目自己声明了"微信式中性"，`ColorScheme` 就必须整套覆盖，不能只挑用得到的几项——详见 D72 ⑦
- **首启接入页（`EnrollScreen`）仍未截图**：它只在无身份时出现，要拍到必须先退出/清空设备身份，那是破坏性操作，需先确认
- **顺带修掉一处已存在的编译断裂**（不是本轮引入）：`PushBoxApi.kt` 里 `formatErrorMessage` 是 companion 方法却引用了实例的 `code`，`ApiError` 构造改判 `formatErrorMessage(code, body)`。这一条卡住了此前所有 Android 命令行构建

---

## v1.1.0（2026-10-19）—— 管理台定时推送

### 服务端
- **定时推送任务**：新增 `scheduled_tasks` 表（Flyway V8）与 `ScheduledTaskService`，运维在 Web 管理台挑一条已有频道、写一段 cron，服务端按点把消息灌进那条频道。入库与下发复用 `POST /message` 的同一条链路（`IngestService.ingestInternal`），差别只在凭据：库里只存 `secret_hash`，定时任务拿不到明文密钥，所以由管理台 JWT 这一侧授权、不重放 secret
- **调度用扫表 + CAS，不建内存定时器**：`next_fire_at` 就是轮询游标，`@Scheduled` 每 15s 扫一轮（`pb.scheduled.sweep-interval`），到期行靠 `UPDATE ... WHERE task_id=? AND next_fire_at=<旧值> AND enabled=1` 抢占。多实例同时扫到同一行只有一个改得动，因此不需要 `SELECT ... FOR UPDATE` 占连接，重启也不丢已排好的任务
- **幂等按整分钟分桶**：`client_msg_id = sched:<task_id>:<分钟槽>`，配合 `messages.uk_msg_idem`，亚分钟级 cron（`* * * * * *`）的实际效果是每分钟一条。2026-10-19 在真栈上跑了 80s 验证：跨一个分钟边界只落 2 条，`last_result` 全程 `ok`、无错误残留
- **cron 必须 6 段**（秒 分 时 日 月 周，Spring `CronExpression`）：5 段 unix 写法当场 400 并提示"需要 6 段"，不会按误写的语义静默跑成另一套时刻
- **任务正文按字节受目标频道 `max_body_bytes` 约束**：与投递时用的是同一把尺子。此前按字符判会让"32K 个汉字"的任务创建成功、然后每分钟失败一次
- **`level` 落库前归一化到 `Level` 的 wire 值**：`warn` 这类近义写法触发时会被 `Level.fromWire` 静默降级成 `info`，任务看着是 warn、消息却是 info，排查起来没有任何报错可循
- **任务分类标签 `category`**（Flyway `V9__scheduled_task_category.sql`，`VARCHAR(32) NOT NULL DEFAULT ''`）：给管理台的列表筛选用的**自由标签**，不是字典表——服务端只做 trim 与 32 字符截断，不校验取值、不建外键、不建分类管理页，空串就是"未分类"。选这个形状的理由是"给列表加个筛选"用不到一张需要一组 CRUD 端点伺候的字典表；反过来它也因此必须有个"空=未分类"的约定，不能让 null 进列（列是 NOT NULL，塞 null 会被 MySQL 严格模式当场拒掉，任务连建都建不出来）
- **入站 JSON 不再放过"尾部残留 token"**（2026-10-21，落点在全项目唯一的 `core/src/main/java/dev/pushbox/core/protocol/Json.java`）：Jackson 默认看完第一个完整对象就返回，后面的字节不检查，于是 `{"name":"x"}y` 这种请求体是"解析成功 + 请求真执行 + 200"，多出来的部分被安静丢掉。当天在旧版服务端上实测过：这样真的建出了一条频道；而 `{"name":"a"}{"name":"b"}` 两个对象拼一块时只有第一个生效，提交者以为两段都进去了。开了 `FAIL_ON_TRAILING_TOKENS` 之后同一份请求体经 `RequestBodyJson` 收成 400 信封（`invalid_param` + "请求体必须是合法 JSON"），尾部只留空白或换行仍然放过——那不是 token。**代价要说清**：这把尺子是 HTTP 的 `byte[]` 端点、WS 入站帧与管理台 JWT payload 解析共用的，所以这是协议级收紧，不是某个端点的小补丁；`FAIL_ON_UNKNOWN_PROPERTIES=false` 那份向前兼容没动。HTTP 那一侧有两组用例（管理台 + 设备侧）和活服务端实测兜着；WS 那一侧当时只有 mock 级单元用例（6 条，不经过真实 socket），只证明了"这种输入会在 `Json.read` 抛"；2026-10-21 补的 `PushboxWsSocketTest` 让握手与密文帧真过了 TCP，但它发的是合法帧，`Json.read` 抛错之后 handler 怎么处置仍没有端到端覆盖
- **`POST /api/v1/admin/channels` 的 `groupId` 改成必填且要真实存在**（2026-10-21，`AdminController.requireKnownGroup`）：此前是 `request.groupId() != null ? … : auth.groupId()`，而管理台分支的 `auth.groupId()` 是 `DeviceGuard` 造出来的字面量 `"admin"`（管理 JWT 没有设备行），`groups` 表里根本没这行；设备侧的频道列表按 `PbStore.listChannels(groupId)` 过滤，所以这样建出来的频道**入库成功、管理台看得见、任何真设备永远查不到**，而且入站照常发消息、没有任何一处报错。现在省略或全空白 → 400 `缺少 groupId：频道要建在设备所在的那个 Space 下，否则设备查不到`；给了一个 `groups` 里查不到的 id（含字面量 `admin`）→ 400 `groupId 不存在：这个 Space 里还没有设备注册过`；顺带把首尾空白 trim 掉再落库。校验用的是 `PbStore.findGroup()`——这个方法一直在仓库里，只是**从没有人调用过**。**为什么不在 `ChannelService.create` 收口**：设备侧那一条传的是真设备行的 `auth.groupId()`，本来就必然存在，在服务层加校验会让每次建频道多查一次库、还会把 `ChannelServiceTest` 那批 mock 出来的 `store` 一起打进 null 分支。**要留个底的两件事**：① 已经建在 `admin` Space 里的老频道不会被这次改动搬走，仍然只有管理台看得见，要给用户收到只能在真实 Space 下重建再归档旧的（本机联调库现查是 6 条，全部已是归档态、活跃 0 条）；② **管理台界面本来就没有"新建频道"入口**（`web-admin/src` 里 `createChannel` 零命中，`api/channels.ts` 只有列表/详情/删除三个函数），所以这条只影响照 `site/admin-api.html` 用 curl 的建站者，不影响任何点击路径

### Web 管理台

- 新增"定时推送"页（`web-admin/src/views/ScheduledTasks.vue`）：选频道、填标题/正文/强度/图片外链与 cron，列表直接显示上次触发结论（`ok`/`failed` + 错误摘要 + 上次消息 ID）与下次触发时间，支持启停与删除。列表只回 `content_head`，编辑时才取全文与图片
- **定时推送页的分类筛选与新建时选分类**：列表多一列「分类」，页头多一个筛选下拉；下拉的选项**从当前列表里现取**（不额外查字典表），里面还多一个"未分类"项——因为 `el-select` 拿到空串会显示成 placeholder，所以用 `__unset__` 当哨兵值，判据仍是"这一行的 `category` 是空串"。表单里的分类框是 `filterable allow-create`：既能在已有标签里选，也能当场敲一个新的，与服务端"自由标签、不校验取值"是同一套语义。筛选走前端 `computed`，与这个页面既有的关键字搜索一致（列表本来就是一次全量拉回来的）。**保存前还硬判一次 32 字**：分类用的是 `allow-create` 的下拉，它的输入框没有 `maxlength` 可挡，不拦就会被服务端静默截到 32 字——截出来的那个词和用户想写的已经不是同一个分类了
- 六个管理台端点：`GET`/`POST /api/v1/admin/scheduled-tasks`、`GET`/`PUT /{id}`、`POST /{id}/enabled`、`DELETE /{id}`。任务请求体上限单独放宽到 64KB——正文本身允许 32K 字符，沿用 8KB 的通用上限会把合法任务挡在门外

### 修复
- **锁屏通知隐私泄露**（2026-10-21，Android release/R8）：`Notifier.updateSummaryNotification()` 造的分组摘要通知缺 `setVisibility(PRIVATE)` + `setPublicVersion(lockScreenPlaceholder())`，安全锁屏 + 系统「隐藏敏感内容」下会直接列出消息正文（单条会话通知已正确走 `VISIBILITY_PRIVATE + publicVersion`，但摘要 builder 漏了这两行）。修法落在 [android/app/src/main/java/cn/iamplus/pushbox/notify/Notifier.kt:111-127](file:///D:/Projects/Android/PushBox/android/app/src/main/java/cn/iamplus/pushbox/notify/Notifier.kt#L111)，给摘要 builder 补 `.apply { setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(lockScreenPlaceholder()) }`；私密会话用 `VISIBILITY_SECRET`。真机复验（设备 ID `4TF6HQ8TCE6XHURO`，系统设置里把锁屏通知改为「隐藏敏感内容」）：连发 4 条唯一文本消息（seq 3450–3453），关屏后点亮屏幕确认单条与摘要都只显示"你有一条新消息"占位，无正文泄露；解锁后会话内 4 条消息完整入库、无重复。这是发布前必须修的隐私缺陷，此前只在模拟器上发现、未修即列进清单。
- **App 锁深色模式漏色**（2026-10-21，Android release/R8）：深色模式下 App 锁屏仍是浅底黑字（[MainActivity.kt:281](file:///D:/Projects/Android/PushBox/android/app/src/main/java/cn/iamplus/pushbox/MainActivity.kt#L281) 的 `Column` 背景没跟随主题，标题颜色硬编码默认值）。修法：`background(MaterialTheme.colorScheme.background)` + 标题改用 `onBackground`；重建 release（12,776,366B）安装后复截深/浅两档锁屏，底色与标题都跟随主题。这条是在逐页截图核对时发现的，属于深色三档验收的一部分。
- **WS 补拉阈值改为配置项并补 6 条 handler 用例**（2026-10-21）：`GAP_THRESHOLD` 原来硬编码 2000，注释里写的 `--pb.ws.gap-threshold` 从没绑过；现在挂在 `PbProperties.Ws`（默认 2000、下限 1）。新增 `PushboxWsHandlerTest` 6 条：hello 帧、签名不符拒绝、kx + `resume_ok`、补拉逐批取空、gap-threshold 生效、心跳判死/发 ping。全量 **288 条**（server 245 / core 43）0 失败 0 跳过；不带凭据时 174 执行 + 114 跳过
- **WS 真 socket 集成测试 4 条**（2026-10-21）：`PushboxWsSocketTest` 起一个真的 Tomcat（`WsSci` + `StandardWebSocketHandlerAdapter`），用 JDK 的 `java.net.http.WebSocket` 客户端走 TCP 上的 hello→auth→kx→密文帧，客户端侧用 core 的 `FrameChannel` 解帧。覆盖 ① 重放两帧 `notify=false` 后 `resume_ok`；② 心跳 ping/pong 与 `rotate-after-frames=2` 触发的明文 kx（epoch 递增、新密钥序号从 0 重启）；③ 落后超阈值只回 `resume_gap`；④ 坏签名先回 `auth_fail=bad_signature` 再以 1008 关闭。**踩到的一个坑**：`Tomcat.addContext` 不装 DefaultServlet，没有 Wrapper 映射时请求在过滤器之前就 404，`WsFilter` 根本没机会升级握手——用例里显式装了 DefaultServlet。全量 **292 条**（服务端 249、加密库 43，本轮之后已被 301 取代），0 失败 0 跳过；不带凭据时 178 执行 + 114 跳过
- **所有 `byte[]` 请求体端点的畸形体：500 → 400 信封**（2026-10-20 深夜到夜里，实测暴露的那条健壮性缺口）。收口落在新增的 `RequestBodyJson`（`read` 与 `readTree` 两个方法），它把 `Json.readBytes` / `Json.readNode` 抛的 `IllegalArgumentException` 一律转成 `ApiException.invalidParam("请求体必须是合法 JSON")`——措辞与 `GlobalExceptionHandler.handleBadJson` 同一句话，客户端不必为这一族端点另写一套解析。覆盖面是**八个端点、七处调用**：管理台 `POST /channels`、`POST /scheduled-tasks`、`PUT /scheduled-tasks/{taskId}`、`POST /scheduled-tasks/{taskId}/enabled`，设备侧 `POST /channels`、`/channels/join`、`/channels/leave`、`/channels/subscription-mode`、`PUT /channels/{channelNo}`、`POST /channels/{channelNo}/senders`，以及 `POST /devices/register` + `/devices/join`（这两条共用 `DeviceController.validateAndParse`，所以是一处调用两个端点）。`PUT /channels/{channelNo}` 走 `readTree` 而不是 `read`：它要区分"字段没提交"与"提交 null"，绑记录类会把这些治理位静默清空；树形那条还额外挡掉空体与非对象根，报的是另一句 `请求体必须是一段 JSON 对象`。**两条更省事的改法被明确否掉**：改成 `@RequestBody` 直绑 record 会把先量 8KB/64KB 上限的那一步一起删掉（绑 `byte[]` 就是为了拿到原始字节的长度）；在全局处理里补一支 `IllegalArgumentException → 400` 会把不属于这一族的解析失败（比如 JWT payload）一并改掉，且症状面比收口大。越界提示（`请求体过大` / `请求体过大（上限 65536 字节）`）原样保留；当时"尾部多一段垃圾仍按 Jackson 默认接受"这一条已经在 2026-10-21 由上面服务端那条收紧掉，现在同属 400 那一族。
  - **`MessageQueryController` 不在这份名单里，此前文档把它算进去了**：`PUT /messages/state/read` 那处早就自己写了 `catch (Exception)` → 400，从来不是 500 源。所以"设备侧九处"是错的，实际是六处频道 + 一处设备（两个端点）。
  - 新增两组纯 Mockito 用例：`AdminControllerBodyTest` 4 条（4 个端点 × 4 种坏体共 16 组断言 400 + `invalid_param` + 那句 `hint`，坏体集合里用根数组 `[1,2,3]` 当"类型不符"样本、避免某个端点把它悄悄绑上；启用开关的空体；越界仍是"请求体过大"；正常体照样落库与启停生效）、`DeviceControllerBodyTest` 5 条（六个频道写端点与 register/join 的坏体、空体、越界仍报"请求体过大"、PUT 的两类越界分句报、合法体仍进得 service 且 PATCH 只带 `muted` 时其余治理位仍是 null）。实跑 9/9 绿，那一跑的全量套件是 **273 条**（server 235 / core 38，0 失败 0 跳过），`ci.yml` 基线当晚从 265 抬到 270、2026-10-21 随上面那条分类标签的 3 条用例抬到 273。
  - 活服务端复核两轮都是真打的：把 `mvn package` 出的新 jar `docker cp` 进 `pushbox-test-pushbox-server-1`、`sha256sum` 对上才重启（管理台那轮第一次 cp 其实没落盘，这一步不能省），管理台 4 端点 × 5 种输入**全部 400**；夜里补完设备侧后，`POST /devices/register` 与 `/devices/join` 各打坏 JSON / 非 JSON / 空体三种，500 → 400 逐条对上，9011 字节的请求体仍回 `请求体过大`。打完立刻 `--force-recreate` 还原成镜像自带的 `9e7e4ca0…`（`3c3d764` 的产物，**不含这两项修复**），所以那套 WSL 栈现在仍是 500，别拿它当"没修成功"的证据。
  - **改之前先查了两端客户端**：Android 的 `PushBoxApi.exchange` 在非 2xx 时直接抛 `ApiError(code, 原始文本)`、**根本不解析错误体**，全项目按 HTTP 码分支的只有头像那处 `404`；Windows 的 `ApiError` 用 `JsonDocument` 宽容选读 `error`/`hint`，读不到就当空串，也不绑类型；**两端都没有 4xx/5xx 分档、没有对 401/409/429 的特判、没有按码清设备身份、没有幂等键**（Windows 唯一的待发队列是已读上报，它不分码一律回队）。结论：500 换成 400 只改掉上屏文案（从一串 Spring 默认体变成中文 `hint`），不动任何一端的重试或身份逻辑。频道那六个端点要设备签名，curl 打不到解析那一跳，**它们的 400 只有单测证据，没有活服务端证据**。
- **频道强度标签**：`web-admin/src/utils/format.ts` 把 `warn` 当成了合法 wire 值，而库里存的是 `warning`，warning 级频道一直渲染成默认灰标签
- **入站 `strict_auth` 的重复闸门**：`IngestService.ingestAccepted` 里那段 ts/nonce/sign 窗口校验与调用方 `checkStrictAuth` 完全重复，已删除。闸门只留调用方一处；内部路径（定时推送）没有明文密钥、HMAC 无从计算，整层跳过

### 构建与部署
- **修复 v1.0.2 统一版本号时漏改的父 POM**：根 `pom.xml` 与 `core/pom.xml` 的 `<parent><version>` 没跟着走，整个 Maven 反应堆 `Non-resolvable parent POM`，服务端与 Docker 镜像直接构建不出来。`scripts/set-version.sh` 因此把 root/core/server 三处 pom 与两份 compose 的镜像 tag 一起纳入覆盖面
- **Caddyfile 反代端口透传**：`reverse_proxy pushbox-server:{$PB_SERVER_PORT:7404}`，此前写死 7404，自定义服务端口时反代指向一个不存在的上游
- **WSL 镜像构建卡死的根因**：容器里 `repo.maven.apache.org` 解析到 AAAA 记录，而 Docker bridge 网络没有可用的 IPv6 出口，Maven 下载一直挂着（同一 URL 在宿主机走 IPv4 秒回）。构建改用 `docker buildx build --network=host`；排查口径与另外两个坑（Caddy 按 `PB_CADDY_HOST` 匹配 Host，用 `127.0.0.1` 访问得到 200 空响应体；这台机器上的 Windows `curl` 会把内联中文参数按 GBK 发出）记进 [WSL测试速查.md](deploy/WSL测试速查.md) 的 Q1.7/Q1.8

### 生产部署资产（2026-10-20 生产 compose 全链路实跑发现并修复）

实跑方式是：用一个隔离的 compose project 起生产 `docker-compose.yml`（端口 17404/18080），复用测试栈里已有的 MySQL 与 RustFS 作外部网络，逐项验证密钥闸门、内网白名单、Caddy、管理台登录、WebSocket、定时推送、备份与恢复。下面每一条都是实跑撞出来的，不是纸面推演。

- **监控栈拆成独立文件** `deploy/docker-compose.monitoring.yml`：Prometheus/Grafana 原本靠 `profiles:` 与主栈同居，但 compose 的 `${VAR:?}` 插值是全文件生效、跟 `profiles` 无关——不开监控的部署也会被 `GF_SECURITY_ADMIN_PASSWORD is missing` 挡在 `up` 之前。主文件里现在不留任何"只有监控才需要"的必填项，`release-verify.sh` 加了断言钉住这条
- **`PB_SERVER_PORT` 补上真正的消费点**：此前只有 compose 端口映射、容器 healthcheck 和 Caddy 反代认这个变量，服务端进程依旧听 7404——改端口的人看到"起来了、健康检查也过"，实际所有流量打在一个没人监听的口上。现在 `application-prod.yml` 的 `server.port` 从 `${PB_SERVER_PORT:7404}` 取值
- **`PB_STORAGE_TYPE` / `PB_STORAGE_LOCAL_PATH` 从死变量变成透传变量**：prod profile 不加载 `application-dev.yml`，而 `application-prod.yml` 里没人读这两个键，于是"单机无对象存储"的生产配置填了 `local` 也照样去连 S3。补齐 `pb.storage.type` / `local-path` 映射，并把变量清单写进 `prod.env.example`
- **内网白名单默认值改成 fail-closed**：`PB_SECURITY_INTERNAL_CIDRS` 原来是 `${VAR:-172.16.0.0/12}`——compose 的 `:-` 在变量为空字符串时也会替换，等于每个忘记配置的部署都默认把整段私有网段放行到 `/healthz` 与 `/actuator/*`（消息正文、频道枚举都在这两个端点上）。默认值改为空，只留 loopback；要放行监控必须显式写桥接子网。注意闸门拒绝时返回 **404 而非 403**（不暴露端点存在性），Caddy 的 `@metrics → 403` 只覆盖 `/actuator/*`，不覆盖 `/healthz`
- **S3 三项必填改为可选**：`PB_S3_ENDPOINT/ACCESS_KEY/SECRET_KEY` 从 `${VAR:?}` 降成 `${VAR:-}`，否则 `local` 模式根本起不来；空值的后果改由 `S3Config` 在 `s3` 模式下拒绝启动兜住，本地 storage 路径与明文媒体各自有守卫（`ProdPlaintextMediaGuard`、Dockerfile 入口脚本里的 JWT 长度检查）
- **RustFS 端口与建桶口径纠正**：镜像监听 9000（S3）/9001（Console），文档和 `wsl-test.env` 里写的 7101 是另一台存储机上那个实例的端口，照抄过来得到的是 connection refused。更坑的是 `/healthz` 的 `storage` 只校验配置项非空，既不探端点也不查桶，所以"端点写对、桶没建"这种配置健康检查全绿、每条带图消息停在 `failed`。部署快速参考 新增"桶要建一次"章节（`mc alias set` + `mc mb --ignore-existing`），CI 与 compose 注释都记录了 9000/7101 的区别
- **备份脚本两处硬化**：`backup.sh` 开头加依赖预检（`mysqldump`/`gzip`/`age` 缺任意一个立刻退出，不再"跑了个空备份还报成功"）；mysqldump 加 `--no-tablespaces`——8.4 默认导出表空间需要 PROCESS 权限，普通业务账号会在 stderr 打印 `Access denied` 然后**以 0 退出**，产出一个缺 DDL 的归档

### CI 门禁

- `.github/` 解除 gitignore，新增 `ci.yml`（PR 与 `main` push 触发）：`server` job 起 `mysql:8.4 --ngram_token_size=2` 与 RustFS 服务容器，预建 `pushbox` 桶，跑真实 `mvn -B -pl server -am test` 后按 surefire XML 汇总设闸（`skipped != 0` 或 `tests < 261` 直接判失败；261 是当时实测的用例总数，基线只许涨，当晚补完管理台坏请求体的 4 条用例后抬到 265，夜里补完设备侧那 5 条后抬到 270）——此前"DB 闸门用例按约定跳过"意味着本地一句 `mvn test` 就能把大半测试静默跳掉还报绿。另含 `web-admin` 构建、Windows 加密库 `dotnet test`、Android `assembleDebug` 三个 job
- **`ci.yml` 第一次在 GitHub runner 上跑就红了两个 job（2026-10-20，`publish` 推成 `main` 那一次），两个根因都在外部依赖上**：
  - `server` job 的「等 RustFS 就绪并建桶」死在拉镜像：`minio/mc:latest` 报 `pull access denied ... repository does not exist`（exit 125）。查实的现状是 **`minio/mc` 与 `minio/minio` 都已从 Docker Hub 下架**（`hub.docker.com/v2/repositories/minio/{mc,minio}` 实测 404），连 `dl.min.io/client/mc/release/linux-amd64/mc` 也返回 **410 Gone**，所以"从宿主或另起一个 mc 容器"这句运维口径整体作废。改成 runner 自带的 AWS CLI v2（Ubuntu 24.04 镜像 Readme 实测预装 2.37.13）：`aws --endpoint-url http://127.0.0.1:9000 s3api create-bucket --bucket pushbox` 再 `head-bucket` 复核。**换之前先在本地对真 `rustfs/rustfs:latest` 容器跑过**：`create-bucket` 退 0、同一命令重复执行幂等、`head-bucket` 退 0；再把提取自 YAML 的这段步骤套上 `aws` shim 复跑一遍，确认等待循环与"runner 上没有 aws 就明确报错"的分支都按预期走。顺带把 `deploy/部署快速参考.md` 的建桶章节补上 aws 写法（用户侧也取不到 mc 了），并把 `docker-compose-wsl.yml` 那条 rustfs 的建桶注释改成实测可用的 `--network container:<rustfs>`（那份 compose 不给 rustfs 发布端口，`--network host` 打不到 9000——这个写法同样在本地起了隔离网络实测过）。
  - `android` job 的「Set up Android SDK」死在 `android-actions/setup-android@v3`：它要装已经下架的 legacy `tools` 包，`Failed to find package 'tools'` + sdkmanager 退出 1（`/usr/local/lib/android/sdk/cmdline-tools/16.0/bin/sdkmanager`）。删掉这一步，直接用 runner 预装的 SDK（同一份镜像 Readme：platforms 含 `android-36`、build-tools 含 36.0.0/36.1.0、platform-tools 37.0.1，且 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 已导出，真缺组件时 AGP 会自己走 sdkmanager 补），换成一步"入镜检查"：从 `android/app/build.gradle.kts` 现读 `compileSdk`，断言 `platforms/android-<CS>` 存在且 `build-tools` 非空，红的时候直接说出缺哪一项。`build-apk.yml` 同口径改掉（它后面那步 `apksigner` 就是从 `build-tools` 取的）。四种分支（正例 / 缺 platform / 空 build-tools / 无 `ANDROID_HOME`）是把脚本从 YAML 里抽出来在 bash 里跑的，不是看一眼写的。顺便把这条链上一个悬着的风险查实：Gradle 9.6.1 支持在 Java 25 上运行（官方兼容性表：Java 25 自 9.1.0 起支持），所以 android job 用 JDK 25 不是问题。
  - **这两处修完当时还不等于门禁绿**：`ci.yml` 那条 261/跳过 0 的断言排在失败的建桶步之后，至此**一次都没执行过**；勾清单里"CI 门禁真跑过一遍"要靠下一次 `workflow_dispatch`（对 `publish` 点即可，`ci.yml` 没有发布步，零不可逆），别拿本地等价命令顶账。**这一句到 2026-10-20 深夜已经兑现**，逐条见下面那条"门禁第一次在 runner 上全绿"。
- **SDK 入镜检查在 runner 上绿了，同一条链往后暴露出第三处红，且本地复现不出来（2026-10-20，对 `publish` 手动 dispatch `build-apk.yml`，run `39370419457`，head `685acb4`）**：「核对 runner 预装的 Android SDK」这一步 success（上一版红的 `setup-android` 确实被换掉了），失败挪到后面的 `./gradlew :app:assembleRelease`，配置阶段报 `UnknownPluginException: Plugin [id: 'com.google.devtools.ksp', version: '2.3.12', apply: false] was not found`，六个仓库全列为"已搜索"。**已排掉的方向都实测过**：marker 的 pom 在 `repo1.maven.org` / `repo.maven.apache.org` / `plugins.gradle.org/m2`（跟 303）/ aliyun `public` / aliyun `gradle-plugin` 上全是 200，只有 aliyun `google` 与 `dl.google.com` 是 404——**这正好是 Gradle 期望的搜索顺序，第一个命中就该是 aliyun `public`**；`symbol-processing-gradle-plugin-2.3.12.{pom,module,jar}` 也在；central 的 `maven-metadata.xml` 里 `2.3.12` 的 `lastUpdated` 是 20260909，不是当天刚发、镜像没跟上的新版本；报错只点名 ksp，说明 AGP 9.2.1 与 kotlin-compose 2.2.10 在同一台 runner 上解析成功了，即 runner 到这些仓库是通的。**所以"仓库真没有"和"网络不通"两条都不成立，缺的是 runner 当时的视角**。为此在 `build-apk.yml` 构建步之前加了一步只打印不判失败的探测（同一个 commit 现读 `ksp` 版本，用 runner 自己的网络把 marker 在六个仓库上的 HTTP 状态码打一遍）；脚本是从 YAML 里抽出来在 bash 跑过的，本机输出即上面那六个状态码。**根因就是这一步定下来的，不是猜的**：第二次 dispatch（head `171b076`，run `39373315480`）探测步自己打出 `502 / 502 / 502 / 404 / 200 / 200`——**GitHub runner 打 `maven.aliyun.com` 的三个仓库整片返 502**（境外数据中心 IP 被拒），而 `settings.gradle.kts` 把镜像写在 `pluginManagement` 与 `dependencyResolutionManagement` 的**最前面**，插件解析被这个 502 带进"哪个仓库都没有"的分支；中央仓库和插件门户其实是 200，本机在墙内看不见那个 502，所以本地一直绿。**修法（维护者定的口径）：默认只走官方源，镜像改由开发机自己开**——`android/local.properties`（已 gitignore）加 `pb.maven.cnMirror=true` 才把三个 aliyun 仓库插回最前面，开关逻辑在两个块里各读一次文件（`pluginManagement` 是单独先编译的一段，看不见脚本其余部分的声明，这是试出来的：第一版抽了个顶层 `fun`，`help` 直接报 `Unresolved reference`）。判据用冷 `GRADLE_USER_HOME` 现跑：关掉开关 `--info help` → **`aliyun` 命中 0 次、BUILD SUCCESSFUL（2m53s，KSP marker 从官方源解析通过）**；打开开关换一个全新冷缓存目录 → **`aliyun` 命中 456 次（google 219 / public 232 / gradle-plugin 5）、BUILD SUCCESSFUL（47s）**，两条分支都实测过，不是看一眼写的。探测步留在 `build-apk.yml` 里当这一次修复的保险，**跑绿之后就删掉**——它每次都会打三行已经没有意义的 502。同步把 `docs/开发指南.md` 新增"Gradle 依赖仓库"一节写清这个开关和别再写死镜像的理由。
- `scripts/release-verify.sh` 新增"部署变量必须有人消费"检查块：逐个断言 compose 透传的 `PB_*` 在 `application-prod.yml` 有对应占位符、监控变量不在主文件、工作流入库且 `ci.yml` 不参与版本号注入。**判据是零 FAIL**，WARN 只负责报数不判失败（具体条数会随本机环境变，不写进日志）
- **签名 secrets 第一次真被使用，同时暴露它的格式判据（2026-10-20，第三次 dispatch `build-apk.yml`，head `5f31903`，`run 39375007556`）**：仓库口径改掉之后，runner 的配置阶段一路走过 SDK 入镜检查与插件/依赖解析——**这就是"镜像 502 那条已经解决"的 runner 侧证据**：整个 job 只有第 10 步 `Build release APK` 红，1–9 全 success。红在 `android/app/build.gradle.kts` 的 keystore 解码：`Illegal base64 character 2d`。`2d` 是 `-`，也就是 **`ANDROID_KEYSTORE_BASE64` 里那一串不是标准 base64**——最像 Windows `certutil -encode` 的产物（带 `-----BEGIN/END-----` 包头和换行），也可能是 base64url 字母表。原来那句 `IllegalArgumentException` 说不出区别，而这一条红掉等于发不出包，所以改成先按标准字母表解，解不开就报**形状**（长度、有没有 PEM 包头、有没有 `-`/`_`、有没有换行、有没有 `+`/`/`）再失败；**只报形状不报内容**——那一串本身就是密钥材料，打进日志等于泄露。三条分支都在本机拿真 keystore 跑过（`--no-daemon :app:assembleRelease`，用仓库里 `local.properties` 的三个口令 + `base64 -w0 dev/pushbox-release.jks`）：正例 **BUILD SUCCESSFUL 1m17s，产出 12,740,698 字节的 release APK**，`apksigner verify --print-certs` 出的证书 SHA-256 与 发布检查清单 钉的基线 `99071932…dec4e` 逐字相同——**这是"CI 那条 env 驱动签名路径能用同一把 key"的第一次实测**；PEM 包头那条报"长度 3688，含 PEM 包头/包尾，含 base64url 的 - 或 _，含换行或空格"，base64url 那条报"长度 3640，含 - 或 _，不含 + 或 /"，两条都 rc=1；再把三份日志拿去比对 keystore 的 base64 前 80 字符，**三份都没命中**（判据是命令，不是"我看了一眼没打"）。所以剩下的事只有一件：把 GitHub secrets 里那一项换成 `base64 -w0 android/dev/pushbox-release.jks` 的一整行输出。探测仓库那一步已经完成使命（502 就是它打出来的），随本次一起删掉，留着就是每次三行没有意义的 502。
- **门禁第一次在 GitHub runner 上全绿（2026-10-20 深夜，head `60c86ad`，两次对 `publish` 的 `workflow_dispatch`）**：`ci.yml` `run 39377846888` 四个 job 全 success——服务端 job 里第 6 步「等 RustFS 就绪并建桶」（换成 runner 自带 AWS CLI v2 的那一步）与第 8 步「断言 DB 闸门用例真的执行了」（`skipped == 0` 且用例数 ≥ 261）**都是这条断言第一次在 runner 上真执行并通过**，另三个 job（管理台 `npm ci && npm run build` + 产物非空、Android `assembleDebug`、Windows `dotnet test`）全绿；`build-apk.yml` `run 39376573834` 同码全绿，`Build release APK` 与 `Verify release APK signature` 两步第一次真跑到，即**四个 keystore secrets 全被用过且解得开、签得上、验得过**（`Publish to GitHub Release` 与 `Calculate mapping.txt checksum` 是 skipped，因为它们 `if: startsWith(github.ref,'refs/tags/v')`，非 tag 事件本来就不该跑）。结论是用 `/actions/runs/{id}/jobs` 匿名逐步读出来的（steps 的 `conclusion` 可匿名取，日志体不行，`/logs` 实测 403），不是听汇报。**这条链当天红了四次、每次根因都在上一层**：`setup-android` 装已下架的 legacy `tools` → KSP marker"六个仓库都没有"（runner 打 aliyun 整片 502）→ base64 格式，四个都已定位并改掉；这一步之后 CI 侧没有已知的红灯了。**仍未被证明的两件**：`main` 上从没绿过（`main` 停在 `f6b9a53` 那次红的两条 job，要绿得先把 `publish` 推成 `main`，而那会连带 `docker-publish.yml` 真推 Hub——不可逆，等维护者点头）；`v*` 标签事件那条发布路径（`gh release`、版本闸门在 tag 上的取值、`1.1.0`/`latest` 标签）也没在 runner 上跑过。另外 `Verify release APK signature` 只断言"有签名且不含 `android debug`"，**没有把证书 SHA-256 与基线 `99071932…dec4e` 比对**，所以"CI 那份 APK 与本机那份是同一把 key"目前是靠本机 env 路径实测 + 非 debug 两条间接证据撑着，不是 CI 自己断言出来的。**这一句当天深夜已兑现成机器断言，见下面那条。**
- **签名指纹进了 CI 的判据，基线改成单一来源（2026-10-20 深夜，head `1cd4963` 之后）**：上面那条缺口（runner 那份 APK 只验了"有签名且非 debug"，没比对证书指纹）补上——新增 `android/release-cert.sha256`（内容就是基线那一串 64 位小写十六进制），`build-apk.yml` 的 `Verify release APK signature` 现读它，把 `apksigner verify --print-certs` 打出的**每一个 signer** 的 SHA-256 逐条比对，缺文件 / 不是 64 位小写十六进制 / `apksigner` 没打出任何 digest / 指纹不符一律红，红的时候打印基线与实际两份指纹（这俩都是公开信息，不是密钥材料）。多 signer 要逐条比而不是取第一条，因为 APK Signature Block 允许挂第二个签名而 `--print-certs` 会把它们全列出来——只取第一条会漏。取值锚定 `certificate SHA-256 digest:` 整串而不是宽抓 `SHA-256 digest`：实测 `apksigner verify --verbose --print-certs` 还会多打一行 `V2 Signer: public key SHA-256 digest: a23f8a7f…`，**那是公钥的指纹、和证书指纹是两个数**，宽正则会把 1 个 signer 抓成 2 条而判成不符（这台机器上 `Number of signers: 1`）。另外本机 `apksigner.bat` 的输出实测带 CRLF（`raw bytes tail` 打出来是 `\r\n`），runner 上是 LF，所以取值管道里加了 `tr -d '\r'`——少了这一步，同一份脚本在本机是绿的、在 runner 也绿，但谁拿它做本地复跑就会撞上"指纹不符"这种假红。**四种分支是从 YAML 里抽出这一步在 bash 真跑的**（拿本机那份 12,740,698 字节的 release APK）：正例 rc=0 并回显同一串、换成另一份 64 位十六进制 rc=1 且打出两份指纹、文件删掉 rc=1、文件写成大写且不足 64 位 rc=1。再给 `scripts/release-verify.sh` 加一条一致性闸：基线文件存在且格式合法 + `build-apk.yml` 真在读它 + `发布检查清单.md` 里那一串与文件逐字相同，三条负例（清单里的指纹被改、workflow 不再引用该文件、文件写成大写）各自把脚本打成 1 个 FAIL，复原后 47 PASS / 0 FAIL。**这条新断言已在 runner 上绿过**（2026-10-20 深夜第五次 dispatch，`run 39381072797`，head `5293801`，第 10 步 `Verify release APK signature` success；结论用 `/actions/runs/{id}/jobs` 匿名读 steps 的 `conclusion`，日志体仍 403）——**CI 那份 APK 的证书 SHA-256 与 `android/release-cert.sha256` 的基线逐字相符，从此是 CI 自己验过的事实**，上面那条"只验非 debug、同一把 key 靠间接证据"的缺口就此合上；换 key 从此是有意的破坏性动作（先写 CHANGELOG 再改那一串）。
- **`docker-publish.yml` 不再在构建现场改写版本号，改成构建前核对**：旧写法在 `v*` 事件上跑 `set-version.sh "${GITHUB_REF_NAME#v}"`，于是"tag 说 1.2.3、pom 写 1.1.0"也能构建成功，推上去的是一个自称 1.2.3、里面跑着 1.1.0 的镜像——而 Docker Hub 推出去收不回来，被人拉走的那份 manifest 永远错着。现在新增"版本号一致性闸门"，在 buildx 与 login **之前**现读 `pom.xml` 与 `deploy/docker-compose.yml` 比对，不一致直接红并写出该补的命令。取值把 `<version>` 尖括号一起锚定：本机试过 pom 写成 `1.1.0-SNAPSHOT`，宽正则会把 `-SNAPSHOT` 读成 `1.1.0`，闸门就成了假绿（本仓全仓零 SNAPSHOT，所以判死）。闸门读出的那个值同时盖进镜像的 `org.opencontainers.image.version`——此前谁查这一版都会被骗：WSL 实测本机编出来的那份继承的是基底的标签 `{"org.opencontainers.image.title":"ubuntu","org.opencontainers.image.version":"26.04"}`，查出来的是 Ubuntu 的版本号。手动触发新增 `dry_run` 输入（默认勾上）：只构建并 load 进 runner，用来在不往 Hub 留任何东西的前提下验 `DOCKERHUB_*` 这对 secrets 与 metadata 算出的标签清单；`push=false` 必须配 `load=true`，否则 buildx 因"没有导出目标"直接失败
- **这条链第一次在 GitHub runner 上真跑过（2026-10-20，对 `publish` 分支点 dry-run dispatch）**：9 步全 success，含凭据校验、版本闸门、metadata 与多阶段构建，其中 `Login to Docker Hub` 是真登录——`DOCKERHUB_*` 这对 secrets 因此从"配好了"升级为"验过能用"。而跑完后 `hub.docker.com/v2/repositories/iamplusdev/push-box/tags` 仍是 **404**（对照组 `library/mysql` 返 200，排除网络假象），这才是"确实一个字节都没推上去"的证据。两个证据缺一个都不算验证过
- `scripts/release-verify.sh` 里"三个出制品 workflow 都该有版本注入"这条改成按 workflow 分别断言（apk/msi 注入、docker-publish 只核对），并新增反向判据：`docker-publish.yml` 里出现真正的注入命令就 FAIL。**判"有没有注入"必须锚定行首的命令**，不能整文件 `grep 'set-version.sh'`——第一版就是这么写的，结果闸门自己的报错文案里那句"先跑 set-version.sh 再重打 tag"被读成了"我在现场改写版本号"，一条正确的改动被判成 FAIL

### 文档

- **管理台 API 第一次有参考页：`site/admin-api.html`**（2026-10-20 新增）。此前官网三页只覆盖入站 `/message`、客户端 `/api/v1/**` 与协议，管理台那 16 个端点（登录 1 + 频道 5 + 设备 2 + 消息搜索 1 + 概览 1 + 定时任务 6）在 `docs/软件设计方案.md` 里只有决策描述，给用户一条 curl 都凑不出来。这一页的字段表、错误码与示例全部从代码读出来写：`AdminAuthController`、`AdminController`、`DeviceGuard`、`ChannelService`、`DeviceService`、`ScheduledTaskService`、`PbStore`。其中几条是任何既有文档都没说过的：管理台线是 **camelCase 请求 / snake_case 响应**（Jackson 没配命名策略，请求体字段名拼错会被静默丢掉而不是报错）；`hint` 永远是字符串、不会是 `null`；定时任务的 `channel_name` 与 `content_head`（80 字符）**只在列表里给**，`content`+`images` **只在详情里给**；`last_fire_at` 用 `0` 而不是 `null` 表示从没跑过；`enabled` 只有 `1` 算真；cron 是 6 段 Spring 方言；`msg_key = sched:<id>:<分钟桶>` 意味着一条任务每分钟最多产出一条消息，与 sweep 跑多快无关
- **顺手修掉的 6 处文档错误 + 自查中新发现的 2 处**：管理台消息搜索被写成 `/api/v1/admin/messages` + `/search` 那一段（真实只有 `/messages?q=`，照抄 404），出现在 `deploy/WSL测试速查.md`；`docs/软件设计方案.md` 三处把 REST 补拉的游标写成 `since_seq`（那是 WS 连接 URL 上的参数，REST 叫 `after_seq`）、两处媒体/头像路径写的是不存在的形状（真实是 `/api/v1/media/{msg_id}/{idx}` 与 `/api/v1/avatars/{sha256(sender_id)}`）；`site/usage.html` 把入站写入写成挂在 `/api/v1` 下的那条（真实是 `POST /message`，前缀由 `pb.ingest.base-path` 配置），`CHANGELOG.md` v1.0.0 那条同源错误一起改；`site/api.html` 的 `/healthz` 口径整个是旧的（补上：白名单由 `PB_SECURITY_INTERNAL_CIDRS` 控制且默认只放环回、真做 DB `isValid(2s)` 与本地存储写探针、down 时 503，所以它是**就绪探针不是存活探针**）；`docs/审计报告.md` §2 把 `GET /api/v1/admin/messages` 归到了服务设备侧的 `MessageQueryController` 上，且它"所有管理台端点完全一致"的结论是拿 6 个抽查项外推的（当时定时任务那一组还没进视野），连同会静默腐烂的文件行号引用一起改成"方法 + 完整路径"记账
- **新增一条闸：文档里写到的端点路径必须存在于代码**（`scripts/release-verify.sh`）。代码侧从 `web/*.java` 的 `@RequestMapping`/`@Get…Mapping` 提路由（要处理三种坑：`path = "${pb.ingest.base-path:/message}"` 取默认值、光有注解不带路径的映射到类前缀、以及类前缀必须拼在方法路径前面——第一版没拼，判据把文档里每一条路径都报成不存在）；文档侧扫 `site/*.html` + README + CHANGELOG + 发布检查清单 + `docs/*.md` + `deploy/*.md`。同位段先归一化（`{channelNo}`、纯数字、ULID 都归成 `{}`），否则真端点会被误报。**2026-10-20 实跑：代码侧 34 条、文档侧 36 条、全部命中，全脚本 51 PASS / 0 FAIL / 2 WARN**（文档侧那个数不当基线：傍晚补文档时它自己涨到 37，闸看的始终是"每一条能不能匹配上代码里的路由"）。反向验证往判据里喂一条从未存在的路径，确认可抓出。**这一轮第一次实跑就是红的，红在我自己新写的 发布检查清单 两条勾选项上**：那两句把"这个路径从来不存在"的错误写法整条抄了出来，而闸是纯字符串匹配，分不出"照着用"和"提醒别这么写"——改的是文档措辞（错误写法不再整条照抄），不是给闸加白名单开口子。**闸只看路径**：请求体字段名、鉴权头、响应形状都不在它的能力范围内，那些仍然只能靠人对着代码读。**当天傍晚这条闸第二次红，第二次也红在我自己的句子上**：管理台那一页新写"末段拼错会 404"时，为了举例子把一个从未存在的路径整条照抄进正文——纯字符串匹配的闸分不出"照着用"和"提醒别这么写"，于是判它不存在。改的仍然是措辞（例子里不出现完整路径），**这条闸到今天为止两次红、两次都没需要放宽判据**
- **文档过程中查实并写进页面的三个边界**（此前只有代码知道）：① `POST /api/v1/admin/channels` 省略 `groupId` 会建出一个孤立 Space（管理台分支 `DeviceGuard.require()` 返回 `Authenticated(null, "admin", "admin")`，Space 名就叫 `admin`，真设备永远看不见它，且服务端不校验这个 ID 是否存在）——**这一条当天已在下一页修掉，`groupId` 现在必填且必须存在于 `groups` 表**；② **管理台面默认是公网可达的**——`deploy/Caddyfile` 只挡了 `/actuator/*`，`InternalAccessFilter` 的白名单只覆盖 `/healthz` 与 `/actuator/**`，管住 `/api/v1/admin` 的只有登录口令加一把 24 小时 JWT，且没有刷新与吊销；③ 登录失败锁定是**进程内 Caffeine**，重启即清零、多实例不共享
- **这一轮当时没有验到的两件事**：① 新页面只做过结构核对（DOM 快照确认导航、JS 生成的目录、各节与复制按钮都在，console 零报错，外加 `curl` 返 200），**浏览器截图这一步在本机取不到视口、没做成**，视觉排版未经目视确认——**这一条到当天傍晚仍然没补上**；② 页面上 16 个端点的契约当时是从代码读出来的，**本轮没有一条真打过运行中的服务端**——**这一条当天傍晚已经兑现，见下面"测试与验证"里那一整轮实测**，也正是那一轮把这一页里 5 处"代码级正确"的声称打成了错的

### 测试与验证

- **管理台 API 参考页从"代码级正确"升级成"运行时实测"（2026-10-20 傍晚，WSL 的 `pushbox-test` 栈：prod profile + MySQL 8.4 + RustFS + Caddy，打的时候 `uptime_ms` ≈ 2,713,682）**：16 个端点逐条真打过，把响应的键集合原样 dump 出来跟文档对表。被测镜像构建自 `3c3d764` 那棵树（镜像时间戳 2026-10-19 23:57，傍晚只是用同一份镜像重建容器，二进制没动）。写这条时它与当时的 HEAD 在 `server/src/main/java`、`web-admin/src` 上逐字零差异，差异只在 `application-prod.yml`（HEAD 新增 `server.port` 与 `pb.storage.type` 两处变量消费）——配置管道，不影响响应形状。**当晚 HEAD 又多出上面「修复」那一处（`AdminController` 的坏请求体收口 + 4 条单测），所以那句话现在只对该镜像与那处修复之外的文件成立；这套在跑的栈没有这个修复。****这一轮实测推翻并改掉了 5 处文档声称**：
  - **① "消息列表每条 14 个键"**：这组响应过的是全项目那份 `Include.NON_NULL` 的 mapper，**值为 `null` 的键整个不出现**。实测同一批两条消息一条 14 键一条 13 键，少的是没填的 `title`。顺此查出另外两处同类错写：活频道的 `archived_at`、未吊销设备的 `revoked_at` 在响应里**键根本不存在**（实测未吊销设备每项 6 个键），而文档写的是"未吊销为 `null`"。前端判据从 `=== null` 改成"键在不在"
  - **② 定时任务响应表把 `last_result`/`last_error`/`last_msg_id` 写成"都有，没跑过时是 `null`"**：实测从没触发过的任务详情里**三个键全缺席**；跑过之后也不是三个一起出现——成功轮 = `last_result:"ok"` + `last_msg_id`（`last_error` 缺席），失败轮 = `last_result:"failed"` + `last_error` 且**上一轮成功留下的 `last_msg_id` 被写 `null` 抹掉**，所以它是"最后一次尝试"而不是"最后一条送达"
  - **③ "路径写错会 404 成 JSON"**：实测**漏掉 `/api/v1/admin` 前缀回的是 HTTP 200 + SPA 那份 `<!doctype html>`**（脚本只看状态码就会当成"服务端返回空数据"，这是整轮里最阴的一条）；末段拼错是 404 且为 Spring 默认体不是信封；合法资源名不存在才是应用自己的 404 信封；方法用错是 405 非信封（且方法匹配发生在资源存在性之前，任务号随便写也先 405 不 404）；少一段走到设备签名那套鉴权，回 401 信封 `hint:"缺少签名头 X-PB-Device/Ts/Nonce/Sig"`
  - **④ "口令错到阈值就 429"**：实测阈值卡的是**请求次数**——连错 5 次第 5 次仍然 401（锁是在这一次里登记的），**第 6 次才 429**、`Retry-After: 300` 且 `hint` 里的秒数递减；**锁定期内输对口令照样 429**（锁定判定在比对口令之前）；窗口一过那条计数记录连同锁期一起过期回收（17:53 锁上 → 17:58 到期 → 17:59 再错三次全是 401），换算下来单一来源 IP 约 60 次/小时，这条锁定的作用是限速不是拦住
  - **⑤ 摄入侧与管理侧的阈值节奏不同**：同一套 `pb.ingest.credential-fail-lock=5` 下，`POST /message` 的**第 5 次**就直接 `403 channel_locked`（实测连打 6 次），而管理台要第 6 次才 429——两边的"经验"不能互相照抄，`site/api.html` 那条 403 记法因此是对的，没动
  - **实测坐实（此前只有代码依据）的其余口径**：`q` 上限按**码点**算（101 汉字 400 / 100 汉字 200，60 个 emoji = 120 个 UTF-16 单元仍然 200）、`q` 两侧空白会 trim、`%`/`_`/`\` 已转义（库里有 `a_b_c` 时 `q=a_c` 命中 0 条）、`since`/`limit` 非数字回 400 非信封、驼峰 query 参数被静默忽略、三档请求体上限的 `hint` 原话（`请求体过大` / `请求体过大（上限 65536 字节）` / `正文 40000 字节，超过该频道的 max_body_bytes 32768`）、`PUT` 是整体覆盖（`level` 回落到 `info`、`images` 清空、文本字段变 `""`，`last_*` 保留）、停用期间 `next_fire_at` 冻在已过期的时隙上一条不发、重新启用从当下重算且不补跑、目标频道被归档后下一轮扫表把任务 `enabled` 置 0 并写 `last_error:"频道已归档或不存在，任务已自动停用"`、`rotate-secret` 真能解掉凭据锁定（连错 5 次 → rotate → 新密钥当场 200、旧密钥 401）、归档只影响列表（详情照样 200、历史消息照样搜得到）、建频道与 rotate 的响应都是那 6 个键且**都不回显 `group_id`**、省略 `groupId` 实测 `group_id == "admin"`、缺 `Bearer` 前缀报的是 `hint:"缺少设备 token"` 而不是"token 无效"、`/healthz` 的 `{"ok":true,"db":"up","storage":"up","uptime_ms":…}`、`overview` 的 6 个 `stats` 键与 `trend` 只含有消息的那几天
  - **实测查出一个真实健壮性缺口，当晚按维护者选定的方案改掉了**：`POST /channels`、`POST /scheduled-tasks`、`PUT /scheduled-tasks/{taskId}`、`POST /scheduled-tasks/{taskId}/enabled` 这四个把请求体当 `byte[]` 收、再自己调 `Json.readBytes` 的口，收到语法坏掉 / 类型不匹配 / JSON 数组 / 只有空白时抛 `IllegalArgumentException`，而 `GlobalExceptionHandler` 只覆盖三支（`ApiException`、`HttpMessageNotReadableException`、`MissingServletRequestParameterException`），接不住 → **500 + Spring 默认体（还会回显 `path`）**；同一口收**完全空体**反倒正常（400 信封「请求体必须是合法 JSON」）；对照 `@RequestBody` 直绑 record 的 `/admin/login` 与 `/message` 一律 400。另一侧的松也实测到了：**合法 JSON 后面多带一个字符会被静默接受并真的建出一条任务**（Jackson 默认不查尾部残留）。修法与验证见下面「修复」那一节的同名条目；尾部多字符那条**本轮没动**，它不是异常，是 Jackson 的默认宽松。**
  - **本轮仍然没验的**：多实例下的锁定与扫表抢占、真机端上读到这些消息的完整链路、吊销一台设备之后 `revoked_at` 长成什么样、图片抓取（SSRF 闸与转存）在管理台侧的表现、浏览器视觉截图
  - **两个我自己先踩的坑，写下来免得下次再拿假数据改文档**：`yes x | head -c N | tr -d '\n'` 造出来的"请求体"只有 **N/2** 字节（换行被 `tr` 吃掉），据此一度得出"8192 与 65536 两道闸都没生效"的假结论；Git Bash 里用 `printf` 拼多字节串，`--data-urlencode` 发出去的字节与预期不符，一度把"100 个汉字被 400、60 个 emoji 被 400"读成"上限按字节算"。改成 Node 逐条构造请求体之后，码点口径与代码完全一致——**这两条都是测试脚本的错，不是服务端的错，也不是文档的错**
- 新增 `ScheduledTaskLogicTest` 18 个单测（cron 解析与下次时间计算、CAS 抢占失败不投递、msg_key 分钟桶、内部入口不带密钥、频道上限与字节判定、`level` 归一化），纯 Mockito 不碰容器
- 全量 `mvn -B -pl server -am test` 收集 261 条用例（server 223 条、core 38 条），**source 了 `deploy/env/local.env` 那次是 0 失败 0 跳过**；不带桶凭据时同一份代码只执行 149 条、跳过 112 条（这批用例带 `@EnabledIfEnvironmentVariable(PB_S3_ACCESS_KEY)`），而 `mvn test` 的退出码仍然是 0——这正是 `ci.yml` 要按 surefire XML 断言 `skipped == 0` 的原因。`web-admin` 的 `vue-tsc` 类型检查与 `vite build` 通过
- **补上内部摄入路径的 `max_level` 截断用例**：`IngestServiceTest` ⑫ `internalPathIsTruncatedLikeThePublicOne` 直接调 `ingestInternal()`（定时推送走的那条），断言 `error` 打进 `max_level=warning` 的频道时，响应与库里 `messages.level` 都是 `warning`。此前只有公开 `/message` 路径（该类的 ①）覆盖过截断，内部路径一条都没有；把断言写在 `ScheduledTaskLogicTest` 里是不行的——那个类的 `IngestService` 是 mock，`captor` 只能看到请求发出去了，看不见落库值
- 真实 WSL 栈端到端验收：`pushbox/server` 镜像经 Caddy 反代建任务 → sweep 触发 → `messages` 落 `sched:<id>:<分钟>`、`sender_id=scheduled`、中文正文 UTF-8 完好 → 列表回 `last_result=ok` → 停用 → 删除；Flyway 迁移到 V8，管理台 SPA 产物（含定时推送懒加载 chunk）确认烘进了 jar
- **`testvectors/vectors.json` 从此按 key 排序输出**（`CryptoVectorsTest` 显式开 Jackson 的 `ORDER_MAP_ENTRIES_BY_KEYS`）。此前每跑一次 core 测试，文件都会换一套 key 顺序：内容逐字节相同，git 却报 40+ 行 diff，评审里再没人能判断"协议向量被改过"这件事是真的还是顺序噪声。改完实测两次连跑 sha 一致，Windows 侧 `dotnet test` 仍 **556/556**（C# 只按 key 取值，与顺序无关）

---

## v1.0.2（2026-10-19）—— 管理台功能增强

### 服务端
- **管理台频道创建**：新增 `POST /api/v1/admin/channels` 端点，支持在 Web 管理台直接创建频道并展示明文 secret（可复制），不再局限于 Android 端创建
- **频道密钥重置**：新增 `POST /api/v1/admin/channels/{channelNo}/rotate-secret` 端点，管理员可随时轮换频道密钥，旧密钥立即失效并自动解除频道锁定
- **请求体输入验证**：管理台接口添加 8KB 大小限制，与服务端其他端点保持一致的安全基线

### 设计决策调整
- **D28 修订**：Web 管理台从"只读"升级为"受控写入"，保留频道创建与密钥管理能力，但依然不回显已有频道的 secret（只有创建/重置时返回一次）

### 部署（WSL Docker）
- **WSL 部署套件**：新增 `deploy/docker-compose-wsl.yml`、`wsl-test.env`、`deploy-wsl.sh`/`deploy-wsl.ps1` 一键脚本，配套快速指南 [WSL测试速查.md](deploy/WSL测试速查.md) 与 [WSL部署指南.md](deploy/WSL部署指南.md)
- **MySQL/RustFS 数据目录改用 Docker volume**：Windows NTFS 上的 bind mount 无法满足 MySQL 对 `private_key.pem.temp` 的 POSIX 权限操作，导致容器启动失败；改为 Docker volume 后 MySQL、服务端、RustFS 均健康运行
- **内网白名单 CIDR 修正**：`PB_SECURITY_INTERNAL_CIDRS` 由 `127.0.0.1/8` 改为网段地址格式 `127.0.0.0/8`，此前服务端因主机位非 0 启动即报错
- **WSL 服务端口绑定放开到 0.0.0.0**：`pushbox-server` 端口不再只绑 `127.0.0.1`，Android 模拟器可经 `10.0.2.2:7404` 直连 WSL 内的服务端联调
- **已知注意点**：WSL 中 Docker 若继承了系统代理，构建镜像前需清除 `http_proxy`/`https_proxy`，否则代理拒绝连接导致构建失败（排查口径见快速指南 Q1）

---

## v1.0.1（2026-10-19）—— 安全加固与可观测性增强

### 服务端
- **零依赖本地存储**：新增 `pb.storage.type=local` 模式，替代 S3/RustFS，适用于无对象存储服务的单机部署；数据落盘自动设置 POSIX 600 权限
- **健康检查增强**：`/healthz` 端点现在同时检测数据库连通性与本地存储可写性，区分 DB down 与 storage down
- **磁盘空间监控**：本地存储模式下暴露 `pushbox.storage.disk.usage.percent` 与 `pushbox.storage.disk.free.bytes` 指标，用于 Prometheus/Grafana 预警
- **消息摄入性能监控**：新增 `pushbox.ingest.duration` Timer 指标，追踪从请求受理到入库完成的总耗时
- **输入验证加固**：`/register` 与 `/join` 端点添加 8KB 请求体大小限制，防止 OOM DoS 攻击
- **JWT 解析安全性提升**：管理台登录使用 JSON 解析器替代正则表达式，防止 crafted payload 绕过 exp 校验
- **备份脚本适配**：支持本地存储卷打包，优先级为 local > RustFS volume > S3 mc mirror

### 测试与质量
- 新增 27 个单元测试：LocalFileStorage（15）、KeyWrap seal/open（12）、HealthzController 存储检测（5）
- 修复 MediaObjectsConfigTest 在本地存储模式下的兼容性问题
- 所有 204 个测试通过（0 失败，0 错误）

### 文档
- 更新软件设计方案 §4.5，补充本地文件系统存储拓扑说明
- README 与快速开始文档拆分 Docker Compose 与纯后端两种部署方式

---

## v1.0.0（2026-10-19）—— 首个正式版本

自托管的推送中枢：一台自己的服务端，把消息加密下发到 Android 与 Windows 两端，频道由用户自己建、外部系统用一条 curl 就能发进来。三端与 Web 管理台的功能在这一版全部落地。

### 服务端
- 唯一入站写入接口 `POST /message`（不在 `/api/v1` 下，前缀由 `pb.ingest.base-path` 配置）：频道凭证鉴权、幂等去重、正文截断标记、图片外链抓取转存后与文字同帧下发
- 长连接下发按设备逐条封信封（每条连接自己的会话钥与帧序号），断线重连带 `since_seq` 补拉，补拉批次分页可配
- 设备与频道治理：注册/接管、恢复码加入、恢复码轮换、频道号重置、发送者白名单、频道静音与订阅模式
- 配置变更即时广播（`config` 帧），凭证轮换不需要客户端重连
- 滥用防护：图片抓取限速、每日配额、容量报表；外链抓取失败进重试队列而不是丢消息
- Web 管理台（Vue3 + Element Plus，随镜像烘入）：概览、消息搜索、会话/频道/设备治理、跨空间只读视图

### Android 客户端
- 首启注册 / 恢复码加入、扫码填恢复码、恢复码卡片禁截图
- 常驻长连接（前台服务）+ 极致省电轮询两档；断网重连后消息严格按 seq 顺序显示
- 通知、桌面角标、会话免打扰、静默时段、通知总开关
- 微信风格消息页：图片九宫格、单格失败态、缺失媒体回捞、会话内搜索并点击定位到命中消息
- App 锁（PIN + 生物识别）、外观（浅/深/跟随系统、字号、摘要行数）、频道管理与设备管理
- 启动被动检查 GitHub Releases 并在确认框里下载安装（可关）

### Windows 客户端（Win10/11）
- .NET 8 + WPF 三栏主界面，托盘常驻、系统通知、头像与媒体缓存
- 与 Android 共用同一份加密与协议实现（`core/` 源码直接共享），行为对齐：主动心跳 + 入站超时判死 + 带游标补拉
- 会话/频道/设备/设置各页功能对齐 Android；删除与清空有回滚语义，已读游标只增不减
- 自包含单文件发布 + Inno Setup 安装包（per-user，卸载只清属于本程序的键）

### 安全与端上存储
- 信任边界只有一条：服务端 → 客户端的下行帧与 REST 响应体加密。服务端入库为明文以支持检索，这是拍板的取舍而非缺陷
- 首次连接记下服务端公钥指纹（TOFU），之后每次密钥交换都要对得上
- Android 的私钥 / token / 恢复码 / PIN 验证串在落盘前经 AndroidKeyStore 里一把不可导出的 AES-GCM 密钥密封，那份私有文件里只剩 `v1:<iv>:<密文>`；老数据读到就就地补封，不需要清数据重装
- App 锁口令用 PBKDF2-HMAC-SHA256（12 万轮、随机盐）校验，恒定时间比对，连续失败按 5/15/30/60 秒退避；旧的无盐 SHA-256 在首次解锁成功时自动升级
- 一切密钥与口令只经环境变量注入，仓库里没有任何凭据；`docker-compose` 用 `${VAR:?}`，缺一项就拒绝启动

### 发布与构建
- 三端版本号统一由 `scripts/set-version.sh` 从 tag 注入，`versionCode = major*10000 + minor*100 + patch` 保证单调，非 `X.Y.Z` 的版本一律拒绝写入
- Android release 必须由 CI 用环境变量注入的 keystore 签名，缺签名直接构建失败；客户端只认 `pushbox-release-*` 资产，不再回退 debug 包——装上签名不同的包会让下一次升级只能卸载重装
- GitHub Actions：APK / MSI / Docker 镜像三条流水线，产物只收正式签名的 release 包；Windows 安装包先收集后签名，并以 `Get-AuthenticodeSignature` 校验
- Android R8 混淆与资源压缩开启，关键类由 `proguard-rules.pro` 保护

### 已知边界
- 允许用 `http://内网IP:端口` 直连自建服务端（拍板保留），首启不再预填任何示例地址
- 本地消息库（Android Room / Windows SQLite）是明文：只有拿到已解锁状态的设备才读得到，历史内容与服务端一样不做静态加密
- PC 端没有轮询兜底那一档（15 分钟 WorkManager 周期补拉属于 Android 的极致省电模式）
- 判死阈值两端刻意不同：Android 是 `心跳间隔 × 2`（默认 50s），PC 沿用协议里的 `心跳 + 15s`（默认 40s）

### 验证
- `dotnet build` Debug/Release 0 警告 0 错误；`dotnet test PushBox.Crypto.Tests` 556/556 全绿
- `mvn -B test`（core + server）通过；服务端体检 `PushBox.LiveCheck` 十条判据跑在自起的 8081 上
- `./gradlew assembleDebug` 与 `assembleRelease` 均 BUILD SUCCESSFUL
- Android 真机验收：断网重连补拉幂等、卸载重装靠恢复码找回历史、未注册设备什么都拿不到、消息顺序正确
