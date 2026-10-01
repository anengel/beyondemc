<#
 安装器冒烟测试。

 为什么需要它：install-to-mods.ps1 曾经有一版会**谎报成功** —— 它打印
 "三件套齐全且无重复"，实际上却把整合包里那份改了名的同名模组留在原地，
 结果是两份同 modId 的 jar 共存，而它自己没发现。这类"静默谎报"必须由测试来兜，
 靠肉眼看输出看不出来。

 （顺带说明两份同 modId 的实际后果，依据 FML loader 4.0.44 / securejarhandler 3.0.8
   的源码与字节码：内容相同时模块名也相同，FML 只会按版本挑一份、打一条 INFO，
   **不会**拒绝启动；真正的 `fml.modloadingissue.duplicate_mod` 报错要"两个不同模块
   声明同一 modId"才触发。所以这里的断言针对的是"不该留下多余副本"——留下同名不同
   版本的副本时，被挑中的是版本号大的那个，文件名却完全不同，玩家看不出加载了哪份。）

 它复现的关键情形：
   1. 整合包风格的中文前缀文件名（[测试前缀] ProjectE-...jar）—— 靠 modId
      而不是文件名去重才能认出来；
   2. 文件名带方括号 —— Test-Path 默认把 [ ] 当通配符，会静默读不到 modId；
   3. 已存在一个旧版本的本模组 jar。

 全程只在仓库内的临时沙盒里操作，**不碰任何真实游戏实例**。

 用法：pwsh -NoProfile -ExecutionPolicy Bypass -File tools\test-install-to-mods.ps1
      加 -KeepSandbox 可保留沙盒目录以便人工查看。
#>

param([switch]$KeepSandbox)

$ErrorActionPreference = 'Stop'

Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# 与 install-to-mods.ps1 里同名函数保持一致。
# 这里刻意复制一份而不是共用文件：让每个脚本都能单独拷贝出去用。
function Get-JarModIds($jarPath) {
    $ids = @()
    if (-not (Test-Path -LiteralPath $jarPath)) { return @() }   # 必须 -LiteralPath，见文件头第 2 点
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
        try {
            foreach ($e in $zip.Entries) {
                if ($e.FullName -notmatch '^META-INF/.*mods\.toml$') { continue }
                $sr = New-Object System.IO.StreamReader($e.Open())
                $txt = $sr.ReadToEnd(); $sr.Dispose()
                foreach ($seg in [regex]::Split($txt, '(?m)^\[\[')) {
                    if ($seg -notmatch '^mods\]\]') { continue }
                    $m = [regex]::Match($seg, 'modId\s*=\s*["'']([^"'']+)["'']')
                    if ($m.Success) { $ids += $m.Groups[1].Value }
                }
            }
        } finally { $zip.Dispose() }
    } catch { }
    return @($ids | Select-Object -Unique)
}

function Ok($m)   { Write-Host $m -ForegroundColor Green }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }
function Bad($m)  { Write-Host $m -ForegroundColor Red }
function Info($m) { Write-Host $m }

$repo = Split-Path -Parent $PSScriptRoot
$sandbox = Join-Path $repo '.tmp-installer-test\mods'
$libs    = Join-Path $repo 'libs'
$outLibs = Join-Path $repo 'build\libs'

$OUR_IDS = @('beyondemc', 'beyonddimensions', 'projecte')
$fail = 0

# ---------------------------------------------------------------- 准备沙盒
Info "沙盒: $sandbox"
if (Test-Path -LiteralPath (Join-Path $repo '.tmp-installer-test')) {
    Remove-Item -LiteralPath (Join-Path $repo '.tmp-installer-test') -Recurse -Force
}
New-Item -ItemType Directory -Path $sandbox -Force | Out-Null

$artifact = Get-ChildItem -LiteralPath $outLibs -Filter '*.jar' -File -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $artifact) { Bad "找不到构建产物，请先 build。"; exit 1 }

# 1) 中文前缀 + 方括号的异名前置（内容与 libs/ 里那份一致）
$fixtures = @()
foreach ($pat in @('*projecte*.jar', '*beyonddimensions*.jar')) {
    $src = Get-ChildItem -LiteralPath $libs -Filter $pat -File -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($src) { $fixtures += $src }
}
if ($fixtures.Count -lt 2) { Bad "libs\ 里缺少前置模组 jar，无法构造测试样本。"; exit 1 }

Copy-Item -LiteralPath $fixtures[0].FullName -Destination (Join-Path $sandbox ('[测试前缀] ' + $fixtures[0].Name)) -Force
Copy-Item -LiteralPath $fixtures[1].FullName -Destination (Join-Path $sandbox ('【测试前缀】' + $fixtures[1].Name)) -Force
# 2) 旧版本的本模组
Copy-Item -LiteralPath $artifact.FullName  -Destination (Join-Path $sandbox 'beyondemc-1.21.1-neoforge-0.0.0.jar') -Force

Info "初始沙盒内容："
(Get-ChildItem -LiteralPath $sandbox -Filter '*.jar').Name | Sort-Object | ForEach-Object { Info "   $_" }

# ---------------------------------------------------------------- 跑安装器
Info ""
Info "--- 运行 install-to-mods.ps1 ---"
& pwsh -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'install-to-mods.ps1') -ModsPath $sandbox -Yes
$installerExit = $LASTEXITCODE
Info "安装器退出码: $installerExit"
if ($installerExit -ne 0) { Bad "[FAIL] 安装器返回非 0 退出码"; $fail++ }

# ---------------------------------------------------------------- 断言
Info ""
Info "--- 断言 ---"
$jars = @(Get-ChildItem -LiteralPath $sandbox -Filter '*.jar')
$byId = @{}
foreach ($j in $jars) {
    foreach ($id in (Get-JarModIds $j.FullName)) {
        if ($OUR_IDS -contains $id) {
            if (-not $byId.ContainsKey($id)) { $byId[$id] = @() }
            $byId[$id] += $j.Name
        }
    }
}

foreach ($id in $OUR_IDS) {
    $n = if ($byId.ContainsKey($id)) { $byId[$id].Count } else { 0 }
    if ($n -eq 1) {
        Ok "[OK] $id 恰好 1 份  （$($byId[$id][0])）"
    } elseif ($n -eq 0) {
        Bad "[FAIL] $id 一份都没有"; $fail++
    } else {
        Bad "[FAIL] $id 有 $n 份（不该留下多余副本）：$($byId[$id] -join ' + ')"; $fail++
    }
}

foreach ($j in $jars) {
    if ($j.Name -match '^\[|^【') {
        Bad "[FAIL] 异名旧文件没被清掉: $($j.Name)"; $fail++
    }
}
if (-not ($jars | Where-Object { $_.Name -match 'beyondemc-.*0\.0\.0\.jar' })) {
    Ok "[OK] 旧版本 beyondemc-...-0.0.0.jar 已清除"
} else {
    Bad "[FAIL] 旧版本 beyondemc-...-0.0.0.jar 仍在"; $fail++
}

# ---------------------------------------------------------------- 收尾
Info ""
Info "最终沙盒内容："
$jars.Name | Sort-Object | ForEach-Object { Info "   $_" }

if (-not $KeepSandbox) {
    Remove-Item -LiteralPath (Join-Path $repo '.tmp-installer-test') -Recurse -Force
}

Info ""
if ($fail -eq 0) { Ok "全部通过。"; exit 0 }
Bad "$fail 项失败。"; exit 1
