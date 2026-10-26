# Windows 客户端开发环境安装教程（.NET 8 + WPF）

> 适用范围：PushBox P3 Windows 客户端（设计方案 §8、§10.1 选型）。
> 目录约定沿用本机现状：运行时/SDK 装 `D:\Apps\DE`，IDE/GUI 工具装 `D:\Apps\DT`。
> 本教程基于 2026-10-14 勘察 + 2026-10-15 实测安装：`.NET SDK 8.0.426` 已装进 `D:\Apps\DE\Net`、winget v1.29.380 可用；IDE 用已装的 Rider，可执行文件在 `D:\Apps\DT\JetBrains\JetBrains Rider 2026.2.3.1\bin\rider64.exe`（**目录名里 "JetBrains" 出现两次不是笔误**）。Visual Studio 未装（Rider 路线不需要）；Inno Setup 6.7.3 已装（实测走 `winget install JRSoftware.InnoSetup`，`--location` 对它有效，落在 **`D:\Apps\DE\Inno Setup 6`**——目录名带空格，装法与踩坑见 §5.1）。
> **进度：本教程已全部走完（§1–§5）**，`apps/windows/` 下 `dotnet test` 567/567 绿，设计方案 §8.4 的 **W1–W15**（四原语 + WS 信封 + REST canonical/密封盒 + 帧 DTO 绑定 + DPAPI 身份 + 首启两屏 + §5.3 全流程长连接 + SQLite/FTS5 本地库 + `SyncEngine` 退避重连与补拉 + `ReadReporter` 已读上报 + `MediaLoader` 服务端代理取图 + `SearchService`/`SearchText` 两段搜索合并 + WPF 双栏 UI 骨架：§9.1 token 落 `ResourceDictionary`、§9.2 Markdown 支持集与气泡/图位度量、§9.3 的"N 条新消息"胶囊 + 系统集成：`H.NotifyIcon.Wpf` 托盘、裸 WinRT Toast（头像/摘要/两颗按钮、`pushbox:` 协议点回会话）、`Settings/ClientSettings` 落盘、`HKCU\...\Run` 自启与静默时段 + 删除与清空：`Sync/DeleteReporter` 的乐观移除 + 回滚票 + 确认后驱逐媒体缓存，两发 §5.2 签名 DELETE + 发布与隐私收尾：`Logging/` 的 Serilog 滚动日志全文脱敏与崩溃上报关闭、`apps/windows/installer/` 的 Inno Setup 无人值守安装包、§5.4 的自签 CA 根证书接入口径）已落地，P3 的 vectors 闸口已开，**且 §12 P3 的十二条判据已在真服务端上跑过（`PushBox.LiveCheck` 12/12）**；§8.4 之后又补了 §5.2 的频道接入面（设置 → 频道：用频道号 + 密钥接入、取消接入、逐设备「只接收已接入的频道」开关，判据 ⑪⑫）。**P3 至此没有待办模块**；剩下的只有 §8.4 W12 那两处**刻意未完成**（会话行头像仍是首字占位、§9.3 的 200ms 骨架屏），不是缺陷清单。

---

## 0. 需要装什么

| 组件 | 作用 | 安装位置 | 必备 |
|------|------|----------|------|
| .NET SDK 8.0 | 编译 WPF、跑单元测试（vectors 对齐） | `D:\Apps\DE\Net\sdk\8.0.xxx` | 是 |
| Rider（已装） | IDE：C# + XAML + 调试器 | `D:\Apps\DT\JetBrains\Rider …`（现状） | 是 |
| Visual Studio 2022 Community | Rider 的替代品，二选一 | `D:\Apps\DT\VisualStudio\2022\Community` | 否 |
| Inno Setup 6 | 发布阶段打安装包（§10.1） | `D:\Apps\DE\Inno Setup 6`（实测路径，**带空格**） | 已装（6.7.3，2026-10-15） |

SDK 版本策略：`8.0` 是 LTS，与服务端要求的 Java 21 同级定位。**先只装一个 SDK 版本**，装在 `DE\Net\sdk` 而不是 `DE\Net\8.0`，原因是 dotnet 官方布局就是 `dotnet.exe + sdk\<版本> + shared\<版本>`，多版本（将来 9.0/10.0）可共存于同一 `root`，与 `DE\Java` 手放多个 jdk 目录的习惯等价。

---

## 1. 安装 .NET SDK 到 D:\Apps\DE

### 路线 A：官方图形安装器（推荐）

1. 下载 x64 Installer：`https://dotnet.microsoft.com/en-us/download/dotnet/8.0`（页面按 "SDK 8.0.x" 行取 Windows x64 Installer `.exe`）。
2. 运行安装器，**Installation Location** 改成：
   ```
   D:\Apps\DE\Net
   ```
   三项（shared framework / host / SDK）都指向同一个 `DE\Net` 根，安装器会自动落成：
   ```
   D:\Apps\DE\Net\dotnet.exe
   D:\Apps\DE\Net\sdk\8.0.4xx\
   D:\Apps\DE\Net\shared\Microsoft.NETCore.App\8.0.xx\
   D:\Apps\DE\Net\shared\Microsoft.WindowsDesktop.App\8.0.xx\   ← WPF 必需
   ```
   若安装器把三项拆成不同目录，手动统一到 `DE\Net` 后再继续。

### 路线 B：winget（无人值守）

winget 的 .NET SDK manifest 支持自定义根目录，用 `--location`：

```powershell
winget install Microsoft.DotNet.SDK.8 --location "D:\Apps\DE\Net"
```

不认 `--location` 时回退到直接下载 `.exe` 并用 msi 参数：

```powershell
winget download Microsoft.DotNet.SDK.8 -d D:\Downloads --accept-source-agreements
# 拿到 dotnet-sdk-*-win-x64.exe 后：
.\dotnet-sdk-8.0.4xx-win-x64.exe /install /quiet /norestart INSTALLDIR="D:\Apps\DE\Net"
```

### 1.1 自定义目录必须补的环境变量

非默认安装位置**不会自动进 PATH**，也不会设置 `DOTNET_ROOT`，需手动配置（设置 → 系统 → 高级系统设置 → 环境变量，或管理员 PowerShell）：

```powershell
[Environment]::SetEnvironmentVariable("DOTNET_ROOT", "D:\Apps\DE\Net", "User")
$paths = [Environment]::GetEnvironmentVariable("Path", "User")
if ($paths -notlike "*D:\Apps\DE\Net*") {
  [Environment]::SetEnvironmentVariable("Path", "$paths;D:\Apps\DE\Net", "User")
}
```

**新开一个终端**再验证（当前终端读不到刚写入的用户变量）：

```powershell
dotnet --list-sdks        # 期望：8.0.4xx [D:\Apps\DE\Net\sdk\8.0.4xx]
dotnet --list-runtimes    # 必须同时出现下面两行，缺 WindowsDesktop 则 WPF 编不过
#   Microsoft.NETCore.App 8.0.xx [D:\Apps\DE\Net\shared\Microsoft.NETCore.App]
#   Microsoft.WindowsDesktop.App 8.0.xx [D:\Apps\DE\Net\shared\Microsoft.WindowsDesktop.App]
```

### 1.2 NuGet 缓存目录（可选，与 DE/Maven 的仓库习惯对齐）

默认包缓存落在 `C:\Users\<你>\.nuget\packages`。想让 .NET 依赖与 `D:\Apps\DE\Maven\MavenRepo` 一样集中在 `DE`：

```powershell
dotnet nuget add source "D:\Apps\DE\NuGet\v3-feed" -n local-cache   # 仅镜像用途时才需要
# 更常用的做法：只迁移全局 packages 文件夹
[Environment]::SetEnvironmentVariable("NUGET_PACKAGES", "D:\Apps\DE\NuGet\packages", "User")
```

`NUGET_PACKAGES` 生效后重建终端，`dotnet restore` 会直接写 `D:\Apps\DE\NuGet\packages`。注意 Rider 需重启才能继承该变量。

---

## 2. 配置 IDE（二选一）

### 路线 A：Rider（推荐，已装）

零安装动作，只需激活 .NET 支持：

1. 打开 `D:\Apps\DT\JetBrains\JetBrains Rider 2026.2.3.1\bin\rider64.exe`（**注意目录名是 `JetBrains Rider …`，父目录已经叫 `JetBrains`，看着像重复但不是笔误**）。
2. 首次启动若提示安装 ".NET SDK" 工具窗口，点 "Configure"，把 Toolchain 指到 `D:\Apps\DE\Net\dotnet.exe`（Rider 有时探测不到自定义位置，手动指定即可）。本机 `DOTNET_ROOT` 与 `PATH` 已配好，一般能自动探测到 8.0.426。
3. WPF 项目由 Rider 原生支持（XAML 编辑器 + 预览），无需额外插件。
4. **打开 `apps\windows\PushBox.sln`，不要打开仓库根目录** —— 根下同时存在 Maven 与 Gradle 工程，IDE 会误索引（见 §D27 的构建分治）。

代价说明：Rider 是付费软件（有 30 天试用 / 开源许可）。你本机已有，直接用即可。

### 路线 B：Visual Studio 2022 Community（免费）

只有决定不用 Rider 时才装。**必须指定安装路径**，否则默认进 C 盘：

```powershell
winget install Microsoft.VisualStudio.2022.Community --override `
  "--wait --quiet --norestart --installPath `"D:\Apps\DT\VisualStudio\2022\Community`" `
   --add Microsoft.VisualStudio.Workload.ManagedDesktop `
   --add Microsoft.Component.ClickOnce --includeRecommended"
```

- `ManagedDesktop` 工作负载 = ".NET 桌面开发"，含 C#、XAML 设计器、WPF 项目模板。
- 磁盘约 8–10GB，安装过程需 UAC 且装完要求重启一次。
- 首次启动选 "Visual C# 开发" 环境预设。

---

## 3. 建工程与冒烟验证（✅ 已于 2026-10-15 完成）

Windows 客户端源码按设计方案的多语言分治结构放仓库内的 `apps/windows/`（与 `apps/server/`、`apps/android/` 并列；Java 侧 Maven + Android 侧 Gradle，C# 侧自带 sln/csproj 不参与前两者）。

```powershell
cd D:\Projects\Android\PushBox
mkdir -p apps/windows; cd apps/windows
dotnet new wpf -n PushBox.Windows
dotnet new xunit -n PushBox.Crypto.Tests
dotnet new sln -n PushBox
dotnet sln add PushBox.Windows/PushBox.Windows.csproj
dotnet sln add PushBox.Crypto.Tests/PushBox.Crypto.Tests.csproj
# 测试项目要能直接测到主项目里的类（FrameChannel 等）
cd PushBox.Crypto.Tests; dotnet add reference ../PushBox.Windows/PushBox.Windows.csproj
```

> **实测坑：`-f` 不接受 windows TFM。** `dotnet new wpf -f net8.0-windows10.0.19041.0`（以及 `-f net8.0-windows`）都会报 `无效选项 … 不是"-f"的有效值`，可选值只列到 `net8.0`。**先按默认创建，再手改 csproj**：

两个 csproj 的 `<TargetFramework>` 都改成设计方案 §10.1 指定的值（才能用 WinRT Toast API）：

```xml
<TargetFramework>net8.0-windows10.0.19041.0</TargetFramework>
<UseWPF>true</UseWPF>
```

跑起来：

```powershell
dotnet run --project PushBox.Windows
```

窗口能弹出即环境通。

---

## 4. 开工第一步：vectors 对齐（✅ 已完成，21 条向量用例绿；现全量 `dotnet test` 567/567 绿）

设计方案 §13 把"vectors 全绿才准写 UI"定为 P3 第一道闸。加密库**实测用 BouncyCastle，不是 NSec**（NSec 26.4.0 只支持 `net9.0`，25.4.0 的密钥构造 API 反直觉；细节与另外五个坑记在设计方案 §8.2 的实测记录里）：

```powershell
cd D:\Projects\Android\PushBox\apps\windows\PushBox.Crypto.Tests
dotnet add package BouncyCastle.Cryptography
# AES-256-GCM 用 BCL 内置的 System.Security.Cryptography.AesGcm，不引包
```

已落的用例与对应文件（都在 `PushBox.Crypto.Tests/`，断言数据全部来自仓库根 `contracts/testvectors/vectors.json`）：

| 用例文件 | 覆盖 | 用的库 |
|----------|------|--------|
| `X25519KeyExchangeTest.cs` | 共享密钥 | BouncyCastle `X25519Agreement` |
| `HkdfSessionKeyTest.cs` | 会话密钥派生 | BouncyCastle `HkdfBytesGenerator` |
| `AesGcmFrameTest.cs` | 帧加解密正例 + **篡改负例** | BCL `AesGcm` |
| `Ed25519SignatureTest.cs` | `ws_auth_sig`（设备签名）+ `kx_sig`（**服务端静态身份密钥**签的，别拿错种子） | BouncyCastle `Ed25519Signer` |
| `FrameChannelTest.cs` | 端到端：envelope JSON → 明文帧；有状态计数器（篡改帧不推进、换 key 解不开） | 上面四个的封装 `PushBox.Windows/Crypto/` + `Net/FrameChannel.cs` |
| `RestCanonicalTest.cs` | §5.2 的 restAuth 8 行 canonical：`get_search`（**小写 method + 含空值的原始 query + 空 body**）与 `post_channels`（中文 body + 空 query）两条期望签名，外加**把 query 重排就验不过**的负例 | `Crypto/RestCanonical.cs`（+ `B64Url.cs`） |
| `RestSealTest.cs` | REST 响应那种一次性密封盒 `{v, eph_pub, salt, data, n}` → 用**设备静态 enc 私钥**解出 `token`/恢复码；断言 `salt` = `sha256(eph_pub 原始字节)`、会话密钥与 `n=0` 对上；篡改 `data` 与换个 `n` 各自抛 `CryptographicException` | `Net/RestSeal.cs` |
| `PushConnectionTest.cs` | §5.3 一条连接的完整流程（25 例）：`auth` canonical 与 `ws_auth_sig_b64` 逐项相等、`kx` 的 64 字节被签串与 `kx_sig_b64` 相等、TOFU 指纹（不匹配时 `auth` 帧压根没发出）、向量信封解成绑定后的 `message` 帧、帧序号跳号判死、静默超时判死 | `Sync/PushConnection.cs`（+ `WsCanonical.cs`、脚本化的 `FakePushTransport`） |
| `DeviceStoreTest.cs` | §3.3 本地库（17 例）：临时目录里建真 db 文件、重开进程原样恢复会话/消息/游标、分页按 seq 正序不重不漏、未读数由 `last_read_seq` 现算、tombstone 三形态（单条 / 整会话 / 整频道）、FTS5 trigram 与短查询的 LIKE 兜底、反向钉住"表里没有一列像在存凭据" | `Data/DeviceStore.cs`（`Microsoft.Data.Sqlite` 8.0.32，自带 FTS5 + trigram） |

后两条依赖 `vectors.json` 里 2026-10-15 新加的 `rest_auth` / `sealed_box` 两段。向量的权威来源始终是 Java 侧，**要改协议或补向量就动 `packages/core/src/test/java/dev/pushbox/core/crypto/CryptoVectorsTest.java` 再重新导出**，不要在 C# 里造期望值：

```powershell
# 默认 JAVA_HOME 是 JDK 1.8，不覆盖会成片报乱码语法错
$env:JAVA_HOME = "D:\Apps\DE\Java\jdk-21.0.8"
cd D:\Projects\Android\PushBox
D:\Apps\DE\Maven\apache-maven-3.9.15\bin\mvn.cmd -B -o -pl :pushbox-core test   # 实测 26/26 绿，顺带重写 contracts\testvectors\vectors.json
```

回归与继续开发：

```powershell
cd D:\Projects\Android\PushBox\windows
dotnet test                                                   # 全量
dotnet test --filter "FullyQualifiedName~AesGcmFrameTest"      # 单文件
```

这一段是**开工当时**的记录，保留原样：那时 W3c/W4/W5/W6/W7 已完成，下一步是 W8 `SyncEngine` 与 W9 `ReadReporter`，而且顺序上要求**先不碰 UI**。此后按设计方案 §8.4 一路做到 W15，P3 已收尾——当前进度与验收口径看本文开头那行，以及设计方案 §8.4 的表格。

---

## 5. 发布：Inno Setup 安装包与自签 CA（✅ 已于 2026-10-15 W15-c 走完）

### 5.1 装 Inno Setup 到 `D:\Apps\DE`

```powershell
winget install --id JRSoftware.InnoSetup --silent --location "D:\Apps\DE\Inno Setup 6" --accept-package-agreements --accept-source-agreements
```

**实测（2026-10-15，6.7.3）**：Inno 自己的安装器就是 Inno 编的，所以它**认** `--location`，`ISCC.exe` 落在 `D:\Apps\DE\Inno Setup 6\`。目录名**带空格**——早期这份教程里计划的 `D:\Apps\DE\InnoSetup6` 不是实际落点，`build-installer.sh` 里的默认值按实测路径写，换机器用 `ISCC=<路径>` 覆盖即可。它属于"被命令行调用的构建工具"，按本机约定归 `DE` 而非 `DT`。

`Languages\` 里**没有 `ChineseSimplified.isl`**（官方分发只带 29 种语言，简体中文属于 unofficial translators，要单独下载再放进去）。所以 `PushBox.iss` 挂的是自带的 `Default.isl`：向导按钮是英文，而**我们自己 authored 的那几句**（任务勾选项、`[Run]` 那一行、卸载时的清理说明）是中文。静默安装两种都不出现，不影响"无人值守"这条验收；要整本中文向导就是补一个 `.isl` + 改 `[Languages]` 一行。

### 5.2 打包：`bash apps/windows/installer/build-installer.sh`

两步在一个脚本里（`--skip-publish` 只重编 .iss）：`dotnet publish -c Release -r win-x64 --self-contained true` → `ISCC.exe /DMyAppVersion=<版本> PushBox.iss` → `apps/windows/installer/Output/PushBox-Setup-<版本>-win-x64.exe`。三个决定和它们的理由：

| 决定 | 理由 |
|------|------|
| **self-contained**，不是 framework-dependent | 验收口径是"装到**干净**机器"。干净机器没有 .NET 8 桌面运行时，framework-dependent 那条路是"装成功、起不来"。实测产物 194MB / 269 个文件，压成 **56MB** 安装包 |
| **per-user**：`PrivilegesRequired=lowest` + 装到 `{localappdata}\Programs\PushBox` | 这个应用的三处系统集成（AUMID、`pushbox:` 协议、`HKCU\...\Run` 自启）全在 **HKCU**，身份/库/设置/日志全在 `%LOCALAPPDATA%\PushBox`（§8.1）。提权安装不报错，但会把它们落到**另一个用户**的配置单元里，表现是"通知不弹、托盘点了没反应"。所以也不给"以管理员身份安装"的入口 |
| 版本号**只从 `PushBox.Windows.csproj` 的 `<Version>` 读** | 日志里"启动 0.1.0 身份=…"打的是程序集版本，安装包显示版本必须和它同一个数；在 .iss 里再抄一份就是等着哪天对不上 |

覆盖安装时正跑着的那个实例靠 Inno 的 Restart Manager（"要替换的文件被占用"这个事实本身），**不**靠 `AppMutex`：单实例那把锁的名字是 exe 全路径的 sha256 前 12 位（`InstanceHandoff.KeyOf`，为的就是开发实例与装机实例互不挡路），换个安装目录就是另一个名字，写死进 .iss 等于骗人。

### 5.3 无人值守验收（2026-10-15 实测）

```powershell
"D:\Projects\Android\PushBox\apps\windows\installer\Output\PushBox-Setup-1.0.0-win-x64.exe" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART
```

| 量什么 | 实测 |
|--------|------|
| 退出码 | 安装 **0**、覆盖安装（同版本号重装）**0**、卸载 **0** |
| 装到哪、装了什么 | `C:\Users\<你>\AppData\Local\Programs\PushBox`，顶层 **269 个文件**（递归 477，多出的是各语言的 satellite 资源目录，`zh-Hans\` 等每本 17 个 dll），与 `publish/` 一一对应；`coreclr.dll`/`hostfxr.dll`/`hostpolicy.dll`/`System.Private.CoreLib.dll`/`PresentationFramework.dll` **全在应用目录里** = self-contained 的直接证据（framework-dependent 发布不会带这五本） |
| 是否惊动 UAC | 没有。卸载信息在 `HKCU\...\Uninstall\{7B4C1D9E-…}_is1`，`Inno Setup: User = XuJiaHao` |
| 干净机器那一条 | 本机装有 .NET 8 SDK，所以"不需要运行时"这一条看的是上一行那五本 DLL 是否随包，而不是"这台机器能不能起" |
| 桌面图标 | **不装**（`[Tasks]` 里必须写 `Flags: unchecked`：不写就是默认勾选，而 `/VERYSILENT` 会把默认勾选一起装上，于是别人桌面上凭空多一个图标） |
| 装机版起得来吗 | `PushBox.Windows.exe pushbox:conv/80000001/live-check`（无身份的机器上点通知那条路）：修复后进程驻留、留痕只有 `启动 0.1.0 身份=待注册` 一行。**修复前这里是一条 `FTL 未处理异常来自 dispatcher`**（见设计方案 §8.2 第 57 条） |
| 卸载留了什么 | `{app}` 与开始菜单那组、三处 HKCU 登记都清掉了；`%LOCALAPPDATA%\PushBox`（`pushbox.db` + `logs\`）**刻意保留** —— 前两个是 DPAPI 绑当前用户的，删掉等于把"重装还能认回同一个 Space"堵死 |

卸载要清那三处 HKCU，但每一处都先确认"**确实指向本安装目录**"才动手：同一台机器上可能还跑着一个 `bin\Debug` 的开发实例，它写的是同一批键名、另一个路径（§8.2 第 40 条）。实测踩到的一侧就是这件事——装机版一启动就把 `pushbox:` 改指向自己（最后一个启动的人接管路由），装机版一卸载又把那一整键删掉，开发实例得再起一次才恢复登记。这不是包的缺陷，是这批键名本来就只有一个位置；运维口径写在设计方案 §8.2 第 58 条。

### 5.4 自签 CA：把根证书装进**本机受信任根存储**（§11.1、D1=B）

PC 客户端**没有任何一处**绕过证书校验：`DeviceClient` 是裸 `new HttpClient()`，长连接是裸 `new ClientWebSocket()`，源码里 `ServerCertificateCustomValidationCallback` / `DangerousAcceptAnyServerCertificate` / `ServicePointManager` **零命中**（grep 过）。所以 `https://` 与 `wss://` 都走系统的链构建，信任从**根证书存储**里来，配置方式只有一种：

```powershell
certutil -addstore -user Root "C:\path\to\pushbox-ca.cer"     # -user = 只进当前用户，不需要管理员
certutil -store  -user Root <sha1 指纹>                        # 核对：Subject 是那条 CN
certutil -delstore -user Root <sha1 指纹>                      # 撤销（运维口径同一条）
```

**实测（2026-10-15，一次性自签 CA + Node HTTPS 靶子 + 一个 .NET 探针，跑完全部拆除）**：

| 状态 | REST（`HttpClient`，同 §5.2 那条签名接口的传输层） | 长连接（`ClientWebSocket`） |
|------|------|------|
| 根证书**没**装 | `HttpRequestException → AuthenticationException：The remote certificate is invalid because of errors in the certificate chain: **PartialChain**` | `WebSocketException → HttpRequestException → AuthenticationException：The SSL connection could not be established` |
| 根证书装进 `-user Root`，**新起**的进程 | **200 通过** | TLS 过了（那次报错是 `status code '200' when '101' was expected`——靶子本来就不是 WS 端点，等于"握手已经通了"） |
| 根证书装进之后**同一个进程再连一次** | **仍然 `PartialChain`**（实测：进程内两次尝试，中间装证书，两次都被拒） | 同一条路 |

三条结论按这个顺序重要：

1. **不需要改代码、不需要管理员权限、不需要重启机器**——当前用户的根存储就够了，Schannel 每次都从它构建链。
2. **但已经在跑的进程不会回头重读根存储**，所以证书装完要**退出托盘再启一次**。这条不是保守写法而是量出来的：见上表第三行。顺带一句"自动重连算不算新连接"——算连接、不算进程，`SyncEngine` 退避重连起的是同一进程里的新 socket，**依然读的是那份快照**，所以别指望它自己缓过来。
3. 报错形状值得记住：链不完整是 `PartialChain`（"我认不出签发者"），而不是"证书过期"或"名称不匹配"。看到 `PartialChain` 就只该想一件事：**根没装、或者装到了别的用户下面**（提权安装 / 服务账户正是后一种）。名称不匹配是另一条路（`SAN` 里得有客户端实际使用的那个 host 或 IP；裸 IP 访问就要 `IP Address` 条目，只签 `CN=localhost` 不够）。

Android 那一侧的对应口径（根 CA 打进 `network_security_config`、不放行用户 CA）见 §14 与 §11.2；PC 没有等价的"打包进应用"的做法，托盘应用只能靠装到根存储，这一条差异值得在接入文档里对使用者明说。

---

## 6. 常见坑速查

| 现象 | 原因 / 处置 |
|------|-------------|
| `dotnet` 不是内部或外部命令 | 自定义安装不改 PATH；补 §1.1 的环境变量并**新开终端** |
| `NETSDK1100: Windows 桌面平台不支持` | 缺 `Microsoft.WindowsDesktop.App` 运行时，或 TFM 少了 `-windows` |
| `error NETSDK1147: 找不到 framework 'Microsoft.WindowsDesktop.App'` | 只装了 Runtime 没装 SDK，或 `DOTNET_ROOT` 指错目录 |
| Rider 报 "no .NET SDK found" | §2A：手动把 toolchain 指到 `D:\Apps\DE\Net\dotnet.exe` |
| Rider 打开仓库根目录后卡索引/满屏红 | 根目录是 Maven + Gradle 工程，Rider 不认；只打开 `apps\windows\PushBox.sln` |
| NuGet 还原失败但浏览器能开站 | 公司机代理；`dotnet nuget` 走系统代理，必要时 `http_proxy` 环境变量 |
| `NU1202: 包 NSec.Cryptography 26.4.0 与 net8.0-windows10.0.19041 不兼容` | 最新版 NSec 只支持 `net9.0`；本项目改用 `BouncyCastle.Cryptography`（见 §4） |
| `FormatException: The input is not a valid Base-64 string` | `vectors.json` 的 `*_b64` 是 **URL-safe base64 且无 padding**（含 `-`/`_`），要先换字符再补 `=` |
| `error CS0103: 当前上下文中不存在名称“Base64Url”` | `System.Base64Url` 是 **.NET 9** 才加的类，`net8.0` 里没有。本项目统一走 `PushBox.Windows/Crypto/B64Url.cs`（一个 `Encode`/`Decode`），别再复制粘贴那三行 `Replace` |
| `ArgumentException: Plaintext and ciphertext must have the same length` | BCL `AesGcm.Encrypt` 的 `ciphertext` 与 `plaintext` 必须等长，16 字节 tag 是**独立**出参；上线的 `data` 是 `ciphertext ‖ tag` 拼接后再 base64url |
| `warning SYSLIB0053: AesGcm(byte[]) 已过时` | 用 `new AesGcm(key, 16)` 显式给标签长度 |
| XAML 设计器空白/崩溃（VS） | 设计器需 .NET Framework 组件；VS Installer 勾 `.NET Framework 4.7.2/4.8 SDK` |
| 托盘图标在高 DPI 下发虚 | 图标必须给 16/24/32 多尺寸 `.ico`，与 §9.1 视觉 token 一并准备 |
| `ISCC.exe` 报 `Unrecognized file keyword: "Comments"` / `Invalid number of parameters` | `Comments` 只属于 `[Tasks]`，`[Files]` 里写它直接报错（那条注释改用 `;` 行注释）；`RegDeleteValue` 在 Inno 6 是**三个参数**（根键、子键、值名），没有第四个 |
| Git Bash 里 `ISCC /DMyAppVersion=0.1.0` 变成"找不到工程文件 D:/…/DMyAppVersion" | MSYS 把 `/D…` 当 Unix 路径转换了（同一个坑：`openssl req -subj /CN=…` 会变成 `D:/Apps/DE/Git/CN=…`，setup 的 `/VERYSILENT` 也一样）。要么在 PowerShell 里跑，要么 `export MSYS2_ARG_CONV_EXCL='*'`——`build-installer.sh` 用的是后者 |
| `/VERYSILENT` 装完，别人桌面上凭空多了一个图标 | `[Tasks]` 不写 `Flags: unchecked` 就是**默认勾选**，而静默安装会把默认勾选一起执行（§5.3 实测第一版就是这样）|
| 装机版一启动，开发实例的通知就跳到装机版；装机版一卸载，开发实例的 `pushbox:` 也没了 | 那三处 HKCU 键（AUMID、`pushbox:` 协议、`...\Run`）**全机只有一个位置**，装机版与 `bin\Debug` 共用，最后一个启动的接管路由（§8.2 第 40、58 条）。卸载只删"指向 `{app}`"的那些，开发实例再起一次（或 `--notify-smoke`）就恢复登记 |
| 在 Inno 的 `Languages\` 里找不到 `ChineseSimplified.isl` | 官方分发带 29 种**官方**语言，简体中文不在其中（属于 Unofficial translations，要单独下载）。本期 `[Languages]` 用自带的 `Default.isl`，不为一条安装向导引外来文件（§5.1）|
| `certutil -delstore -user Root "PushBox Probe CA"` 返回"操作已被用户取消"，但证书还在 | 按 **CN 名称**删会被系统的确认框拦掉，而且**没删成**——于是后面那轮"根存储里没有时应当被拒"的基线全是废的。改用指纹 `-delstore -user Root <SHA1>`，删完 `-store` 应报 `NTE_NOT_FOUND` |
| 根证书明明装上了，正在跑的那个客户端还是连不上 | **进程不会回头重读根存储**（§5.4 实测第 3 态）。退出应用再起一次才有用；WS 断线重连仍在同一个进程里，不算新进程 |

---

## 7. 装完自检清单

- [x] `dotnet --list-sdks` 显示 8.0.x，路径在 `D:\Apps\DE\Net\sdk`（实测 8.0.426）
- [x] `dotnet --list-runtimes` 含 `Microsoft.WindowsDesktop.App`（实测 8.0.32）
- [x] `DOTNET_ROOT` + `PATH` 已写入用户环境变量
- [x] NuGet 缓存在 `D:\Apps\DE\NuGet\packages`
- [x] `apps/windows/PushBox.sln` 三个项目建好（`PushBox.Windows` / `PushBox.Crypto.Tests` / W9 起的 `PushBox.LiveCheck`），TFM 为 `net8.0-windows10.0.19041.0`
- [x] `dotnet test` 567/567 绿（撤掉「全部/未读/已读」筛选时连带删过 `MainModelTest` 里那两条筛选用例，之后又补了头像缓存、后台自启与长消息折行的用例；P3 开工闸口已开，见设计方案 §13；含 W3b 的 restAuth canonical 与密封盒、W3c 帧绑定、W4/W5 身份与首启、W6 的整条 §5.3 握手 25 例、W7 的本地库 17 例、W8 的同步引擎 20 例、W9 的已读上报 12 例、W10 的媒体链路 36 例、W11 的搜索 34 例、W12 的 UI 骨架 80 例（`MainModelTest` 23 + `MarkdownTest` 18 + `MarkdownViewTest` 11 + `ThemeTest` 8 + `BubbleViewTest` 8 + `MediaStripTest` 7 + `AvatarTest` 4 + `MainWindowTest` 1，其中后四类要在 STA 线程上构造可视对象，跑法见设计方案 §8.3 与 §8.2 第 36–37 条）、W13 的系统集成 132 例（`SystemIntegrationTest` 32 + `AlertPolicyTest` 31 + `ToastProtocolTest` 22 + `ClientSettingsTest` 18 + `ToastPlanTest` 15 + `ToastXmlTest` 10，另给 `DeviceStoreTest`/`SyncEngineTest`/`MainModelTest`/`MainWindowTest` 各补一条；这一批**都不需要真托盘、真注册表与真 toast**，只有 `SystemIntegrationTest` 里那三条用的是真命名管道——而"第二个实例"必须换一条线程去抢那把 mutex，同线程抢必赢，见 §8.2 第 41 条）；W14 的删除与清空 37 例（`DeleteReporterTest` 12（新文件）+ `DeviceClientTest` 9 + `DeviceStoreTest` 7 + `MainModelTest` 6 + `MainWindowTest` 3：两发签名 DELETE 的面相、乐观与回滚的时序、回滚票那三条"不许按平别人新值"的规则，用的还是假 handler 与临时目录，不需要真服务端——`infra/probe` ⑪/⑯/⑰/⑱ 已经拿真 HTTP 把服务端那几支验过）；W15 的日志与隐私 38 例（`AppLogTest` 11：白名单事件落成一行、按天分文件写进和数据库同一个私有目录、**把整个 log 目录 grep 一遍找不到任何正文片段**、崩溃留痕只留抛出那一行而不留它旁边那句话、日志目录写不了就"没有留痕"而不是把应用一起带下去；`PrivacyGateTest` 15：正文尺寸的键名不分大小写、裸值吃到行尾而带引号的值只吃自己、JSON 与 URL query 两种形状、词中词不误伤、hex 与 base64 两把密钥形状、恢复码短于 .NET 的任何一种形状所以把剩下那几道墙逐条钉住、异常只贡献类型与抛出位置；`LogWiringTest` 12：补拉被拒、吊销停机、身份文件读不出、删除被拒、缓存落盘失败、通知与搜索各条路上"日志里只可能出现码和游标"——这一批用 `[Collection("AppLog")]` 关掉并行，因为日志是进程级的，并发跑会互相染上别人的行）
- [x] 真服务体检 12/12：`bash infra/run-dev.sh --server.port=8081`（自己叫起来的实例，跑完只杀自己的 PID；7404 上是使用者手动起的那个，不碰）→ `dotnet run --project apps/windows/PushBox.LiveCheck -- http://127.0.0.1:8081`（第 ⑦ 条是 W10 的签名取图，打真 `MediaController`；第 ⑧ 条是 W11 那种"用户真会打出来的词"——中文 + 空格 + `%` + `&` 一起进 query，打真 `SearchController`；第 ⑨/⑩ 条是 W14 的两发签名 DELETE，故意打在服务端并没有的行上，量的是"404 `hint=message` 不回滚"与"`deleted=0` 读成幂等成功"这两条解读；第 ⑪/⑫ 条是 §5.2 接入面：`subscribed` 必须**逐设备**读得对（自建的真、别人建的假，两台各看各的），以及开了「只接收已接入的频道」之后未接入那条频道的消息要在**长连接与 REST 历史两处同时消失**、接入后回来、关掉立刻全收。这一轮就是 ② 抓到一个真缺陷：`resume_gap` 判据拿的是全局 `MAX(seq)`，别的 Space 推够 2000 条之后新 Space 永远等不到 `resume_ok`，改成本组行高（见设计方案 §8.2 第 65 条））
- [x] 通知那条路的真机体检（2026-10-15 实测通过）：`dotnet run --project apps/windows/PushBox.Windows -- --notify-smoke 输出文件`。它会写两处 HKCU（AUMID 与 `pushbox:` 协议处理器，都指向**当前这个 exe**），跑完桌面右下应弹出两条测试通知；输出文件里实测 `registered=True`、`scheme=True`、`setting=Enabled`、`delivered=2 suppressed=0 failure=-`，把静默窗开成整天后 `quiet_info=Silent quiet_error=HeadsUp`。**开机自启默认关，这条体检不碰 `HKCU\...\Run`**；唯一还需要人点一下的是"在真弹出的那条通知上按打开会话，看窗口有没有落到那一条会话"（`HandleTarget` 之后的每一步都有用例，只有系统把协议处理器叫起来这一段没有）
- [x] `dotnet run --project PushBox.Windows` 起窗成功（实测 exe 起进程后按该 PID 终止；`App.OnStartup` 已改为按是否已注册路由到首启两屏 / 主窗，见设计方案 §7.5）
- [x] Inno Setup 6.7.3 已装进 `D:\Apps\DE\Inno Setup 6`，打包与无人值守验收跑通（2026-10-15 实测）：`bash apps/windows/installer/build-installer.sh` → `apps/windows/installer/Output/PushBox-Setup-0.1.0-win-x64.exe`（56MB）→ `/VERYSILENT /SUPPRESSMSGBOXES /NORESTART` 装 / 覆盖装 / 卸载三段退出码全 0，全程无 UAC，`{app}` 里 269 本顶层文件（递归 477）中五本运行时 DLL 齐全（self-contained 的直接证据），卸载只清指向 `{app}` 的那三处 HKCU 且**保留** `%LOCALAPPDATA%\PushBox`。详见 §5.2、§5.3
- [x] 自签 CA 三态实测（2026-10-15，一次性 CA + Node HTTPS 靶子 + 临时 .NET 探针，跑完全部拆除）：根没装 → REST 与 WS 都被拒（`PartialChain` / `SSL connection could not be established`）；`certutil -addstore -user Root` 之后**新进程**两侧都通；**已在跑的进程不回头重读根存储**，必须退出重启。详见 §5.4
- [ ] IDE 里能识别 csproj 并打断点
