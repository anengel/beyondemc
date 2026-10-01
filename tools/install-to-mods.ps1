<#
 把 Beyond EMC 安装到 Minecraft 的 mods 目录。

 用法（推荐双击 tools\install-to-mods.cmd）：
   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\install-to-mods.ps1
   pwsh ... -File tools\install-to-mods.ps1 -ModsPath "D:\某实例\mods"
   pwsh ... -File tools\install-to-mods.ps1 -Yes        # 跳过确认
   pwsh ... -File tools\install-to-mods.ps1 -ListOnly   # 只列出候选 mods 目录，不改任何文件

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
    [switch]$Yes,
    [switch]$ListOnly
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

# JEI 是**可选**的：只有装了它，「JEI 配方填充自动用 EMC 兑换」才可用。
# 它不参与下面的"前置不齐全"判断（缺了也不影响模组加载）。
$jei = Get-ChildItem -LiteralPath $libsDir -File -ErrorAction SilentlyContinue |
       Where-Object { $_.Name -match 'jei-1\.21\.1' } | Select-Object -First 1
if ($jei) {
    $prereqs += $jei
    Ok ("可选集成: {0}（启用 JEI 配方填充）" -f $jei.Name)
} else {
    Info "未找到 JEI（可选）。没有它时本模组照常工作，只是少了 JEI 配方填充功能。"
}
if ($prereqs.Count -lt 2) {
    Warn "前置不齐全。安装后游戏会因缺少依赖而拒绝加载本模组。是否继续？"
    if (-not $Yes) {
        $a = Read-Host "继续？(y/N)"
        if ($a -notmatch '^[Yy]') { Info "已取消。"; exit 1 }
    }
}

# ---------------------------------------------------------------- 3. 确定目标目录
#
# 为什么要看 jar 而不只看版本目录名：
#   整合包实例常把版本目录改名（例如 "你好，新蒸程！V1.7.5正式版"），名字里既没有
#   "1.21.1" 也没有 "NeoForge"。只按名字匹配会**漏掉真正的目标**，然后因为"只找到 1 个
#   候选"而静默装进另一个空实例 —— 装完游戏里看不到模组，白白浪费时间。
#   所以两个判据取并集：名字匹配 **或** 该 mods 目录里已经有本模组/任一前置模组。
function Get-OurJars($modsPath) {
    if (-not (Test-Path $modsPath)) { return @() }
    return @(Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue |
             Where-Object { $_.Name -match 'beyondemc|beyonddimensions|projecte' } |
             ForEach-Object { $_.Name })
}

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
                $mods = Join-Path $_.FullName 'mods'
                $byName = ($verName -match '1\.21\.1' -and $verName -match 'NeoForge')
                $jars = Get-OurJars $mods
                $byJar = ($jars.Count -gt 0)
                if ($byName -or $byJar) {
                    if ($byJar) {
                        # 直接把命中的 jar 列出来：多个实例往往都装着同一个旧版本
                        # （比如 0.2.0），只写"已装本模组"根本分不清该选哪个。
                        $why = '已装: ' + ($jars -join ' | ')
                    } else {
                        $why = '版本名含 1.21.1 + NeoForge，但 mods 里没有本模组/前置'
                    }
                    $found += [pscustomobject]@{
                        Mods    = $mods
                        Version = $verName
                        Root    = $root
                        Why     = $why
                    }
                }
            }
        }
        # 直接就是 mods 的布局
        $direct = Join-Path $root 'mods'
        if (Test-Path $direct) {
            $found += [pscustomobject]@{
                Mods    = $direct
                Version = '(游戏目录直属 mods)'
                Root    = $root
                Why     = '游戏目录本身就是实例目录'
            }
        }
    }
    # 去重（同一个 mods 路径可能被两条规则同时命中）
    $seen = @{}
    $uniq = @()
    foreach ($c in $found) {
        $k = $c.Mods.ToLowerInvariant()
        if (-not $seen.ContainsKey($k)) { $seen[$k] = $true; $uniq += $c }
    }
    return $uniq
}

$target = $ModsPath
if ($ListOnly -and $target) {
    # 显式给了路径又要 ListOnly —— 按 ListOnly 的字面承诺，只报不改。
    Info ""
    Info ("指定目标: {0}" -f $target)
    $j = Get-OurJars $target
    if ($j.Count -gt 0) { Info ('      已装: ' + ($j -join ' | ')) }
    else                { Info '      该目录为空，或没有本模组/前置。' }
    Info ""
    Info "-ListOnly：仅列出候选，未改动任何文件。"
    exit 0
}
if (-not $target) {
    $cands = @(Get-Candidates)

    # -ListOnly：无论找到几个候选，都只打印、绝不改任何文件就退出。
    # （早先的写法在"恰好 1 个候选"时会直接往下走到复制那一步，属于隐性越权。）
    if ($ListOnly) {
        if ($cands.Count -eq 0) { Warn "没有找到任何候选 mods 目录。"; exit 0 }
        Info ""
        Info ("共 {0} 个候选：" -f $cands.Count)
        for ($i = 0; $i -lt $cands.Count; $i++) {
            Info ("  [{0}] {1}" -f ($i + 1), $cands[$i].Mods)
            Info ("      {0}" -f $cands[$i].Why)
        }
        Info ""
        Info "-ListOnly：仅列出候选，未改动任何文件。"
        exit 0
    }

    if ($cands.Count -eq 0) {
        Warn "没能自动找到 1.21.1 + NeoForge 的 mods 目录。"
        $target = Read-Host "请手动输入 mods 目录的完整路径"
    }
    elseif ($cands.Count -eq 1) {
        Info ""
        Info ("找到候选: {0}" -f $cands[0].Mods)
        Info ("          （{0}）" -f $cands[0].Why)
        $target = $cands[0].Mods
    }
    else {
        Info ""
        Info "找到多个候选，请选择："
        for ($i = 0; $i -lt $cands.Count; $i++) {
            Info ("  [{0}] {1}" -f ($i + 1), $cands[$i].Mods)
            Info ("      {0}" -f $cands[$i].Why)
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
