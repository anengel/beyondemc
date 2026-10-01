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

Add-Type -AssemblyName System.IO.Compression.FileSystem | Out-Null

# 读出一个 jar 真实提供的 modId（只认 [[mods]] 段）。
#
# 为什么不能直接全文正则 modId：neoforge.mods.toml 里 [[dependencies.xxx]] 段
# **也**写 modId，那是依赖声明。全文捞会得出"这个 jar 提供了 projecte"这种错误
# 结论 —— 例如 projectexpansion 依赖 projecte，就会被误判成重复。
function Get-JarModIds($jarPath) {
    $ids = @()
    # ⚠️ 必须用 -LiteralPath：整合包的 jar 名常带方括号（[等价交换重制版] ProjectE-...jar），
    # 而 Test-Path 默认把 [ ] 当通配符字符类，会判定"文件不存在"，于是整个去重逻辑
    # 对这些 jar 静默失效 —— 既不去重，结果核对时也漏看，最后谎报"无重复"。
    if (-not (Test-Path -LiteralPath $jarPath)) { return @() }
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
        try {
            foreach ($e in $zip.Entries) {
                if ($e.FullName -notmatch '^META-INF/.*mods\.toml$') { continue }
                $sr = New-Object System.IO.StreamReader($e.Open())
                $txt = $sr.ReadToEnd()
                $sr.Dispose()
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

# 本模组 + 两个前置的 modId —— 判断"这个 mods 目录是不是我们关心的那个"只看它们，
# 与文件名无关。
$OUR_IDS = @('beyondemc', 'beyonddimensions', 'projecte')

$repo = Split-Path -Parent $PSScriptRoot
Info "仓库根目录: $repo"
Info ""

# ---------------------------------------------------------------- 1. 找构建产物
$libsOut = Join-Path $repo 'build\libs'
$artifact = $null
if (Test-Path -LiteralPath $libsOut) {
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
# 返回该 mods 目录里"属于我们关心范围"的 jar：@( @{ Mod='projecte'; File='xxx.jar' } )
# 按 modId 判，不按文件名 —— 整合包会给 jar 加中文前缀，名字完全对不上。
function Get-OurJars($modsPath) {
    $out = @()
    if (-not (Test-Path -LiteralPath $modsPath)) { return @() }
    Get-ChildItem -LiteralPath $modsPath -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object {
        $name = $_.Name
        foreach ($id in (Get-JarModIds $_.FullName)) {
            if ($OUR_IDS -contains $id) {
                $out += [pscustomobject]@{ Mod = $id; File = $name }
            }
        }
    }
    return $out
}

function Format-OurJars($jars) {
    if (-not $jars -or $jars.Count -eq 0) { return '' }
    return (($jars | ForEach-Object { '{0} ← {1}' -f $_.Mod, $_.File }) -join ' ; ')
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
        if (-not (Test-Path -LiteralPath $root)) { continue }
        # PCL2 / 标准布局：<游戏目录>\versions\<版本名>\mods
        $versions = Join-Path $root 'versions'
        if (Test-Path -LiteralPath $versions) {
            Get-ChildItem -LiteralPath $versions -Directory -ErrorAction SilentlyContinue | ForEach-Object {
                $verName = $_.Name
                $mods = Join-Path $_.FullName 'mods'
                $byName = ($verName -match '1\.21\.1' -and $verName -match 'NeoForge')
                $jars = Get-OurJars $mods
                $byJar = ($jars.Count -gt 0)
                if ($byName -or $byJar) {
                    if ($byJar) {
                        # 直接把命中的 modId 和对应文件名列出来：多个实例往往都装着
                        # 同一个旧版本（比如 0.2.0），只写"已装本模组"根本分不清该选哪个。
                        $why = '已装: ' + (Format-OurJars $jars)
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
        if (Test-Path -LiteralPath $direct) {
            $dj = Get-OurJars $direct
            $dwhy = '游戏目录本身就是实例目录'
            if ($dj.Count -gt 0) { $dwhy += '；已装: ' + (Format-OurJars $dj) }
            $found += [pscustomobject]@{
                Mods    = $direct
                Version = '(游戏目录直属 mods)'
                Root    = $root
                Why     = $dwhy
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
    if ($j.Count -gt 0) { Info ('      已装: ' + (Format-OurJars $j)) }
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
$srcs = @($artifact) + $prereqs

# 每个待装 jar 真实提供的 modId（身份，与文件名无关）
$srcIds = @{}
foreach ($f in $srcs) { $srcIds[$f.FullName] = (Get-JarModIds $f.FullName) }

# 扫目标目录所有 jar 的 modId
$present = @()
if (Test-Path -LiteralPath $target) {
    Get-ChildItem -LiteralPath $target -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object {
        $present += [pscustomobject]@{ Name = $_.Name; Path = $_.FullName; Ids = (Get-JarModIds $_.FullName) }
    }
}

# ⚠️ 按 modId 去重，而不是按文件名。
#
# 整合包实例常给 jar 加中文名前缀，例如：
#     文件名 [等价交换重制版] ProjectE-1.21.1-PE1.1.0.jar
#     modId  projecte
# 名字和上游的 projecte-1.21.1-1.1.0.jar 完全不同，但两者内容一模一样
# （实测 SHA-256 相同），提供了**同一个 modId**。若只按文件名判重，就会把两份
# 都留在 mods 里，NeoForge 直接以 "Duplicate mod" 拒绝启动；而报错信息里
# 完全不会提"文件名不同"这件事，非常难查。
$toRemove = @()          # @( @{ Name=...; Why=... } )
foreach ($f in $srcs) {
    $ids = $srcIds[$f.FullName]
    if ($ids.Count -eq 0) { continue }
    foreach ($e in $present) {
        if ($e.Ids.Count -eq 0) { continue }
        if ($e.Name -eq $f.Name) { continue }             # 同名：复制时直接覆盖
        $shared = @($e.Ids | Where-Object { $ids -contains $_ })
        if ($shared.Count -gt 0) {
            $toRemove += [pscustomobject]@{ Name = $e.Name; Path = $e.Path
                                            Why = ('与本模组/前置同 modId: {0}' -f ($shared -join ',')) }
        }
    }
}
# 兜底：modId 读不出来（jar 损坏等）时，仍按文件名规则清掉旧的本模组 jar。
# 这里要排掉"与待写入文件同名"的那些 —— 它们会被 Copy -Force 直接覆盖，
# 报成"移除旧版本"会让用户以为动了一个本来不该动的文件。
$destNames = @($srcs | ForEach-Object { $_.Name })
foreach ($s in (Get-ChildItem -LiteralPath $target -Filter 'beyondemc-*.jar' -File -ErrorAction SilentlyContinue)) {
    if ($destNames -contains $s.Name) { continue }
    if ($toRemove | Where-Object { $_.Name -eq $s.Name }) { continue }
    $toRemove += [pscustomobject]@{ Name = $s.Name; Path = $s.FullName; Why = '旧版本（文件名规则兜底）' }
}

Info ""
Info "=============================================="
Info " 将安装到: $target"
Info "----------------------------------------------"
foreach ($f in $srcs) {
    $ids = if ($srcIds[$f.FullName].Count -gt 0) { $srcIds[$f.FullName] -join ',' } else { '（未解析到 modId）' }
    Info ("   [写入] {0}" -f $f.Name)
    Info ("          modId={0}" -f $ids)
}
if ($toRemove.Count -gt 0) {
    Info "----------------------------------------------"
    foreach ($r in $toRemove) {
        Info ("   [移除] {0}" -f $r.Name)
        Info ("          {0}" -f $r.Why)
    }
}
Info "=============================================="
if (-not $Yes) {
    $a = Read-Host "确认执行以上改动？(Y/n)"
    if ($a -match '^[Nn]') { Info "已取消。"; exit 0 }
}

if (-not (Test-Path -LiteralPath $target)) {
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    Ok "已创建目录: $target"
}

# ---------------------------------------------------------------- 5. 执行
Info ""
foreach ($r in $toRemove) {
    if (-not (Test-Path -LiteralPath $r.Path)) { continue }
    Remove-Item -LiteralPath $r.Path -Force
    Warn ("已移除: {0}   ({1})" -f $r.Name, $r.Why)
}
foreach ($f in $srcs) {
    $dest = Join-Path $target $f.Name
    Copy-Item -LiteralPath $f.FullName -Destination $dest -Force
    Ok ("已复制: {0}" -f $f.Name)
}

# ---------------------------------------------------------------- 6. 结果核对
Info ""
Info "目标目录现在的相关文件（按 modId 判定，不看文件名）："
$check = @()
Get-ChildItem -LiteralPath $target -Filter '*.jar' -File -ErrorAction SilentlyContinue | ForEach-Object {
    $hit = @((Get-JarModIds $_.FullName) | Where-Object { $OUR_IDS -contains $_ })
    if ($hit.Count -gt 0) { $check += [pscustomobject]@{ Name = $_.Name; Ids = $hit } }
}
$check = @($check | Sort-Object Name)
foreach ($c in $check) { Info ("   [有] {0}   (modId={1})" -f $c.Name, ($c.Ids -join ',')) }

$missing = @()
$dupes   = @()
foreach ($n in $OUR_IDS) {
    $providers = @($check | Where-Object { $_.Ids -contains $n })
    if ($providers.Count -eq 0) { $missing += $n }
    elseif ($providers.Count -gt 1) { $dupes += ('{0} × {1} 份（{2}）' -f $n, $providers.Count, (($providers | ForEach-Object { $_.Name }) -join ' + ')) }
}
Info ""
if ($dupes.Count -gt 0) {
    Bad "发现重复 modId —— 游戏会以 Duplicate mod 拒绝启动："
    foreach ($d in $dupes) { Bad ("   {0}" -f $d) }
    exit 1
} elseif ($missing.Count -eq 0) {
    Ok "三件套齐全且无重复，可以启动游戏了（用 1.21.1 + NeoForge 那个版本启动）。"
} else {
    Bad ("仍缺少: {0}" -f ($missing -join ', '))
}

Info ""
Info "首次启动后："
Info "  - config\beyondemc-server.toml 会自动生成"
Info "  - 在 Mods 列表里应能看到 Beyond EMC"
Info "  - 游戏内运行 /beyondemc selftest 可确认一切正常"
