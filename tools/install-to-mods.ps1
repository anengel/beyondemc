<#
 把 Beyond EMC 安装到 Minecraft 的 mods 目录。

 用法（推荐双击 tools\install-to-mods.cmd）：
   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\install-to-mods.ps1
   pwsh ... -File tools\install-to-mods.ps1 -ModsPath "D:\某实例\mods"
   pwsh ... -File tools\install-to-mods.ps1 -Yes        # 跳过确认

 它会做三件事：
   1. 找到 build\libs 下最新构建出来的 beyondemc jar
   2. 找到 libs\ 下的两个前置模组（超越维度 / 等价交换）
   3. 把这三个 jar 一起复制进目标 mods 目录

 ⚠️ 为什么必须连前置一起装：本模组的 neoforge.mods.toml 把
    beyonddimensions 与 projecte 声明为 required 依赖。
    只放本模组的 jar，游戏会直接拒绝加载并提示缺少前置。
#>

param(
    [string]$ModsPath,
    [switch]$Yes
)

$ErrorActionPreference = 'Stop'

function Info($m) { Write-Host $m }
function Ok($m)   { Write-Host $m -ForegroundColor Green }
function Warn($m) { Write-Host $m -ForegroundColor Yellow }
function Bad($m)  { Write-Host $m -ForegroundColor Red }

$repo = Split-Path -Parent $PSScriptRoot
Info "仓库根目录: $repo"
Info ""

# ---------------------------------------------------------------- 1. 找构建产物
$libsOut = Join-Path $repo 'build\libs'
$artifact = $null
if (Test-Path $libsOut) {
    $artifact = Get-ChildItem -LiteralPath $libsOut -Filter '*.jar' -File -ErrorAction SilentlyContinue |
                Where-Object { $_.Name -notmatch 'sources|javadoc' } |
                Sort-Object LastWriteTime -Descending |
                Select-Object -First 1
}
if (-not $artifact) {
    Bad "没有找到构建产物（$libsOut\*.jar）。"
    Info "请先构建：双击 tools\play.cmd 之外的构建方式，或运行："
    Info "    tools\gradlew-here.cmd build"
    exit 1
}
Ok ("构建产物: {0}  ({1:N0} 字节, {2})" -f $artifact.Name, $artifact.Length, $artifact.LastWriteTime)

# ---------------------------------------------------------------- 2. 找前置模组
$libsDir = Join-Path $repo 'libs'
$prereqs = @()
foreach ($pattern in @('*beyonddimensions*.jar', '*projecte*.jar')) {
    $f = Get-ChildItem -LiteralPath $libsDir -Filter $pattern -File -ErrorAction SilentlyContinue |
         Select-Object -First 1
    if ($f) { $prereqs += $f; Ok ("前置模组: {0}" -f $f.Name) }
    else    { Warn ("缺少前置模组 jar: libs\{0}（获取方式见 libs\README.md）" -f $pattern) }
}
if ($prereqs.Count -lt 2) {
    Warn "前置不齐全。安装后游戏会因缺少依赖而拒绝加载本模组。是否继续？"
    if (-not $Yes) {
        $a = Read-Host "继续？(y/N)"
        if ($a -notmatch '^[Yy]') { Info "已取消。"; exit 1 }
    }
}

# ---------------------------------------------------------------- 3. 确定目标目录
function Get-Candidates {
    $roots = @(
        (Join-Path $env:APPDATA '.minecraft'),
        'C:\mc',
        (Join-Path $env:USERPROFILE 'curseforge\minecraft\Instances'),
        (Join-Path $env:APPDATA 'PrismLauncher\instances'),
        (Join-Path $env:USERPROFILE 'MultiMC\instances')
    )
    $found = @()
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        # PCL2 / 标准布局：<游戏目录>\versions\<版本名>\mods
        $versions = Join-Path $root 'versions'
        if (Test-Path $versions) {
            Get-ChildItem -LiteralPath $versions -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $verName = $_.Name
                if ($verName -match '1\.21\.1' -and $verName -match 'NeoForge') {
                    $found += [pscustomobject]@{
                        Mods    = (Join-Path $_.FullName 'mods')
                        Version = $verName
                        Root    = $root
                    }
                }
            }
        }
        # 直接就是 mods 的布局
        $direct = Join-Path $root 'mods'
        if (Test-Path $direct) {
            $found += [pscustomobject]@{ Mods = $direct; Version = '(游戏目录直属 mods)'; Root = $root }
        }
    }
    return $found
}

$target = $ModsPath
if (-not $target) {
    $cands = @(Get-Candidates)
    if ($cands.Count -eq 0) {
        Warn "没能自动找到 1.21.1 + NeoForge 的 mods 目录。"
        $target = Read-Host "请手动输入 mods 目录的完整路径"
    }
    elseif ($cands.Count -eq 1) {
        Info ""
        Info ("找到候选: {0}" -f $cands[0].Mods)
        $target = $cands[0].Mods
    }
    else {
        Info ""
        Info "找到多个候选，请选择："
        for ($i = 0; $i -lt $cands.Count; $i++) {
            Info ("  [{0}] {1}" -f ($i + 1), $cands[$i].Mods)
        }
        $sel = Read-Host "输入序号（默认 1）"
        if (-not $sel) { $sel = '1' }
        $idx = [int]$sel - 1
        if ($idx -lt 0 -or $idx -ge $cands.Count) { Bad "序号无效。"; exit 1 }
        $target = $cands[$idx].Mods
    }
}
if (-not $target) { Bad "未指定 mods 目录。"; exit 1 }

# ---------------------------------------------------------------- 4. 确认
Info ""
Info "=============================================="
Info " 将安装到: $target"
Info "----------------------------------------------"
foreach ($f in @($artifact) + $prereqs) { Info ("   {0}" -f $f.Name) }
Info "=============================================="
if (-not $Yes) {
    $a = Read-Host "确认复制？(Y/n)"
    if ($a -match '^[Nn]') { Info "已取消。"; exit 0 }
}

if (-not (Test-Path $target)) {
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Ok "已创建目录: $target"
}

# ---------------------------------------------------------------- 5. 复制（先清掉旧版本）
Info ""
$stale = Get-ChildItem -LiteralPath $target -Filter 'beyondemc-*.jar' -File -ErrorAction SilentlyContinue
foreach ($s in $stale) {
    Remove-Item -LiteralPath $s.FullName -Force
    Warn ("已移除旧版本: {0}" -f $s.Name)
}
foreach ($f in @($artifact) + $prereqs) {
    $dest = Join-Path $target $f.Name
    Copy-Item -LiteralPath $f.FullName -Destination $dest -Force
    Ok ("已复制: {0}" -f $f.Name)
}

# ---------------------------------------------------------------- 6. 结果核对
Info ""
Info "目标目录现在的相关文件："
$check = Get-ChildItem -LiteralPath $target -File -ErrorAction SilentlyContinue |
         Where-Object { $_.Name -match 'beyondemc|beyonddimensions|projecte' } |
         Sort-Object Name
foreach ($c in $check) { Info ("   [有] {0}" -f $c.Name) }

$need = @('beyondemc', 'beyonddimensions', 'projecte')
$missing = @()
foreach ($n in $need) {
    if (-not ($check | Where-Object { $_.Name -match $n })) { $missing += $n }
}
Info ""
if ($missing.Count -eq 0) {
    Ok "三件套齐全，可以启动游戏了（用 1.21.1 + NeoForge 那个版本启动）。"
} else {
    Bad ("仍缺少: {0}" -f ($missing -join ', '))
}

Info ""
Info "首次启动后："
Info "  - config\beyondemc-server.toml 会自动生成"
Info "  - 在 Mods 列表里应能看到 Beyond EMC"
Info "  - 游戏内运行 /beyondemc selftest 可确认一切正常"
