; ============================================================================
; PushBox PC 客户端安装包（§8.4 W15-c，选型见 §10.1，环境见 docs/Windows开发环境安装教程.md）
;
; 这一页只管"把 publish 目录变成一个能无人值守装到干净机器的 exe"。它**不**构建 .NET：
; 先跑 bash apps/windows/installer/build-installer.sh（publish → ISCC），版本号也从那里传进来。
;
; 三条和这个应用的形态绑在一起的取舍，改脚本前先读：
; ① 全程 per-user、不弹 UAC：通知的 AUMID、pushbox: 协议、开机自启三处都写 HKCU，
;    身份/库/日志都在 %LOCALAPPDATA%\PushBox（§8.1、§8.2 第 40 条）。提升成管理员装会把它们
;    落到另一个用户的配置单元里 —— 表现为"通知不弹、托盘点了没反应"，而不是安装报错。
; ② 装的是 self-contained 发布（194MB、269 个文件），所以目标机器**不需要**先装 .NET 8 桌面运行时；
;    验收口径是"装到干净机器"，framework-dependent 那条路在干净机器上装成功、启动失败，不算过。
; ③ 卸载只删 {app} 里自己装进去的文件，**不碰** %LOCALAPPDATA%\PushBox：那里的 identity.json 是
;    DPAPI 绑当前用户的，日志与历史库删掉就没了；重装还要用它们认回同一个 Space。
; ============================================================================

#define MyAppName "PushBox"
#define MyAppExeName "PushBox.Windows.exe"
#define MyAppPublisher "iamplus"

; 版本号只有一个来源：PushBox.Windows.csproj 的 <Version>。build-installer.sh 读出来用 /DMyAppVersion 传进来，
; 下面这个默认值只是"直接双击 ISCC 编译这一页"的兜底。日志里"启动 {Version}"打的是程序集版本，
; 两处一旦错开就说明装的是手工编译的包，别拿它当发布物。
#ifndef MyAppVersion
  #define MyAppVersion "1.1.4"
#endif

; publish 输出目录（build-installer.sh 的 -o）。相对本 .iss 所在目录解析，所以从哪儿调 ISCC 都一样。
#ifndef PublishDir
  #define PublishDir "publish"
#endif

[Setup]
; AppId 是"这几个包属于同一个应用"的唯一依据：升级装它会先静默卸掉旧版，卸载列表按它归并。改了它就是造一个新应用。
AppId={{7B4C1D9E-5A2F-4C31-9E60-2D8F7A1B3C45}
AppName={#MyAppName}
AppVersion={#MyAppVersion}
AppVerName={#MyAppName} {#MyAppVersion}
AppPublisher={#MyAppPublisher}

DefaultDirName={localappdata}\Programs\{#MyAppName}
DefaultGroupName={#MyAppName}
DisableProgramGroupPage=yes

; 只认 64 位：self-contained 的运行时是 win-x64 那一套，装到 ARM64 或 32 位系统上是"装上了、起不来"。
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
; §8 开头那句"仅 Win10/11"在这里落地（Win10 = 10.0）。
MinVersion=10.0

; 语言只在"系统 UI 语言"这一层猜一次，不在向导前多一屏"请选择安装语言"：
; 中文 Windows 直接是简体中文，其余落到 [Languages] 的第一条（也是简体中文），英文系统命中 English。
; 静默安装（/VERYSILENT）本来就不走这一屏，取值不影响无人值守那一条。
ShowLanguageDialog=no
LanguageDetectionMethod=uilanguage

; per-user 安装：不请求管理员，也不给"以管理员身份安装"的对话框入口
; （PrivilegesRequiredOverridesAllowed 留空是 Inno 6 的默认，这里写出来是为了别被"顺手加个 dialog"改坏）。
PrivilegesRequired=lowest

; 覆盖安装时正跑着的那个实例：靠 Restart Manager，不靠 AppMutex。单实例那把锁的名字是 exe 全路径的
; sha256 前 12 位（InstanceHandoff.KeyOf，为的是开发实例和装机实例互不挡路），换个目录就是另一个名字，
; 把它写死在脚本里等于骗人。而"要替换的文件正被占用"是事实本身，Inno 据此找进程比按名字猜准。
CloseApplications=yes
; 装完不自动重启被关掉的那个实例：升级后第一次启动要把新的 AUMID/协议路径重写一遍（App.OnStartup 里那几行），
; 让使用者自己点。
RestartApplications=no

UninstallDisplayIcon={app}\{#MyAppExeName}
UninstallDisplayName={#MyAppName}

OutputDir=Output
OutputBaseFilename=PushBox-Setup-{#MyAppVersion}-win-x64
; 安装向导与生成的 Setup.exe 用的图标，和 csproj 的 ApplicationIcon 是同一个 .ico 文件。
; 注意这行不能留空值——ISCC 对空 SetupIconFile 直接报错，所以宁可不写也不能写 `SetupIconFile=`。
SetupIconFile=..\PushBox.Windows\PushBox.ico
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern

VersionInfoVersion={#MyAppVersion}
VersionInfoProductName={#MyAppName}
VersionInfoDescription={#MyAppName} {#MyAppVersion} 安装包
VersionInfoCompany={#MyAppPublisher}

[Languages]
; 中文优先、英文兜底（2026-10-28 步 7/7）。Inno 官方分发里没有简体中文（它属于 unofficial translators，
; 官方只带 Default.isl 与二十几种语言），所以 ChineseSimplified.isl 跟这一页一起收在 installer/Languages ——
; 编译机不必再往 <Inno>\Languages\ 里另塞一份，CI（choco innosetup）与本机（D:\Apps\DE）拿的是同一个文件。
; 取的是 issrc 6.7.3 那一版；Inno 升大版本时这份也要跟着换——[Messages] 里多一条它不认识的键就编不过。
; 第一条是默认与兜底语言：系统 UI 语言既不是中文也不是英文时，向导仍是简体中文。
Name: "zh"; MessagesFile: "Languages\ChineseSimplified.isl"
Name: "en"; MessagesFile: "compiler:Default.isl"

[CustomMessages]
; 这三行是我们自己写的向导文案，.isl 里没有对应的键，所以每种语言各给一份：
; 英文系统走的就是下面这三行，不让它在中英混排里读"附加快捷方式："。
DesktopIcon=在桌面创建快捷方式
ExtraShortcuts=附加快捷方式：
LaunchDescription=立即启动 PushBox（首次启动完成注册或加入，并登记通知与协议）
en.DesktopIcon=Create a desktop shortcut
en.ExtraShortcuts=Additional shortcuts:
en.LaunchDescription=Launch PushBox now (finish registering or joining on first start; notifications and protocol are registered then)

[Tasks]
; 桌面图标默认不勾（unchecked 不是可省的装饰：不写就是默认勾上，而 /VERYSILENT 会把默认勾选一起装上，
; 于是"无人值守装到干净机器"的结果是别人桌面上凭空多一个图标）。这是一个常驻托盘的应用，
; 桌面上多一个图标不代表多一个入口。
Name: "desktopicon"; Description: "{cm:DesktopIcon}"; GroupDescription: "{cm:ExtraShortcuts}"; Flags: unchecked

[Files]
; 整个 publish 目录原样搬进去。[Files] 没有"给这一行写注释"的参数（Comments 属于 [Tasks]），
; 所以"包里到底有什么"全看这一行：一个目录、递归、忽略版本差。
; .pdb 也带上：崩溃留痕只落异常类型名 + 堆栈（§14），而堆栈里的行号靠这些符号文件——
; 没有正文泄漏之虞，换来的是一条能对到代码行的现场记录。
Source: "{#PublishDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
; {group} 在 per-user 安装下就是当前用户的开始菜单程序目录（%APPDATA%\Microsoft\Windows\Start Menu\...）。
Name: "{group}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"
Name: "{group}\{cm:UninstallProgram,{#MyAppName}}"; Filename: "{uninstallexe}"
Name: "{autodesktop}\{#MyAppName}"; Filename: "{app}\{#MyAppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
; 装完顺手起一次：首启两屏（register / join）在那儿等着，HKCU 的 AUMID 与 pushbox: 协议也是在这一步写进去的。
; skipifsilent 让无人值守那一条不额外起进程——干净机器验收量的是"装上、能起、能卸"，弹窗只会挡住脚本。
Filename: "{app}\{#MyAppExeName}"; Description: "{cm:LaunchDescription}"; \
    Flags: postinstall nowait skipifsilent

[UninstallDelete]
; 运行期不往 {app} 写任何东西（库、日志、缓存、设置全在 %LOCALAPPDATA%\PushBox），所以这里没有要清的。

[Code]
const
  ClassesRoot = 'Software\Classes';
  AumidKey = 'AppUserModelId\PushBox.Windows';
  SchemeKey = 'pushbox';
  RunKeyPath = 'Software\Microsoft\Windows\CurrentVersion\Run';
  RunValueName = 'PushBox';

/// <summary>这条注册表值指向的是不是本次要卸载的那个目录。
/// 认前缀而不是认整个字符串：协议那一写是 "…\PushBox.Windows.exe" "%1""（ToastProtocol.HandlerCommand 拼的）。</summary>
function PointsIntoApp(const Value: string): Boolean;
var
  App: string;
begin
  App := Lowercase(ExpandConstant('{app}'));
  Result := (App <> '') and (Pos(App, Lowercase(Value)) > 0);
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
var
  Value: string;
begin
  if CurUninstallStep <> usPostUninstall then
    Exit;

  // 三处都写在 HKCU，卸载不擦就是"这台机器上有个指向不存在文件的 pushbox: 处理器"：
  // 用户下次点通知，系统会把一个死路径叫起来，表现是"点了没反应"，比没注册还难查。
  //
  // 但每一处都先确认"确实指向本安装目录"才动手：同一台机器上还可能有一个从 bin\Debug 跑起来的开发实例，
  // 它写的是同一批键名、另一个路径（§8.2 第 40 条），装机版的卸载程序没有资格把它的路由删掉。
  if RegQueryStringValue(HKCU, ClassesRoot + '\' + SchemeKey + '\shell\open\command', '', Value) and
     PointsIntoApp(Value) then
    RegDeleteKeyIncludingSubkeys(HKCU, ClassesRoot + '\' + SchemeKey);

  if RegQueryStringValue(HKCU, RunKeyPath, RunValueName, Value) and PointsIntoApp(Value) then
    RegDeleteValue(HKCU, RunKeyPath, RunValueName);

  // AUMID 那一项里只有显示名、没有路径，分不出是谁写的；删它安全，因为应用每次启动都会重写
  // （ToastRegistration.Register），而一个已卸载应用留在通知设置里的名字只会让人以为还在。
  RegDeleteKeyIncludingSubkeys(HKCU, ClassesRoot + '\' + AumidKey);

  // 刻意不动 %LOCALAPPDATA%\PushBox（identity.json / pushbox.db / settings.json / logs\）：
  // 前两个是 DPAPI 绑当前用户的凭据与全量历史，删掉等于把"重装还能认回同一个 Space"这条路堵死。
  // 真要清干净是使用者自己的决定 —— 设计方案 §9.3 那条"退出登录 / 清空本机数据"的入口，或者手工删目录。
end;
