# 把 deploy/env/local.env 写进"当前用户"的环境变量，这样 IDEA 无论从快捷方式还是终端启动，
# 运行配置都能继承到 PB_* 这几个凭据——明文只落在注册表与那份已 gitignore 的文件里，
# 不会进工程内任何会入库的文件（IDEA 运行配置里的 Environment variables 是会存在 .idea 下的）。
#
# 用法（PowerShell）：
#   powershell -NoProfile -ExecutionPolicy Bypass -File deploy/env/set-user-env.ps1
# 然后重启 IDEA：IDEA 只在启动时读一次进程环境，改完不重启看不到。
#
# 全程不打印任何值，只打印写入了哪些键。
$ErrorActionPreference = 'Stop'

$file = Join-Path $PSScriptRoot 'local.env'
if (-not (Test-Path $file)) {
    throw "找不到 $file —— 它只在本机存在（已 gitignore），先按 deploy/env/prod.env.example 的键位建好它"
}

$written = @()
foreach ($line in Get-Content $file -Encoding UTF8) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#')) { continue }
    # local.env 是给 bash 的 source 用的，每行带 export 前缀；这里剥掉再取键值
    if ($t -match '^export\s+') { $t = $t -replace '^export\s+', '' }
    if ($t -notmatch '^[A-Za-z_][A-Za-z0-9_]*=') {
        # 只报键的那一段，避免把整行（含值）打进终端
        $bad = $t.Split('=')[0]
        throw "无法解析的一行，键名：$bad —— 需要 [export ]KEY=value 形态（可带引号）"
    }
    $parts = $t -split '=', 2
    $key = $parts[0].Trim()
    # local.env 是给 bash 的 source 用的，值带引号；properties 形态才会把引号当成值的一部分
    $value = $parts[1].Trim().Trim('"', "'")
    [Environment]::SetEnvironmentVariable($key, $value, 'User')
    $written += $key
}

Write-Host "已写入用户环境变量：$($written -join ', ')"
Write-Host '下一步：完全退出并重新打开 IDEA（Run 配置会继承它）。'
