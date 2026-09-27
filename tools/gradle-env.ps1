# 在本机跑 Gradle 之前，先 dot-source 这个脚本：
#
#   . .\tools\gradle-env.ps1
#   .\gradlew.bat build
#
# ⚠️ 前提：shell 必须是 PowerShell 7（已装于 C:\Program Files\PowerShell\7）。
#    PS7 自带 RemoteSigned 策略、且按 UTF-8 读取无 BOM 脚本，所以本文件
#    （含中文注释）可以直接 dot-source。
#
#    若 shell 是 Windows PowerShell 5.1，本文件会**双重失败**：
#      ① 策略为 Restricted → "running scripts is disabled on this system"；
#      ② 即使绕过策略，5.1 也按 ANSI(gb2312) 读这个无 BOM 的 UTF-8 文件，
#         中文注释乱码会破坏语法（实测 7 处语法错误）。
#    5.1 下的兜底入口是：
#        tools\gradlew-here.cmd build
#
#    注意：用 -File 起子进程没用（环境变量不会回传给调用方），必须 dot-source。
#    若一定要在 5.1 里跑，用：
#        powershell -ExecutionPolicy Bypass -Command ". .\tools\gradle-env.ps1; .\gradlew.bat build"
#
# 为什么需要它：本机 JDK 21 已安装但未进入 PATH，JAVA_HOME 也是空的，
# 而且已经运行的 DSH 宿主进程持有的是旧环境块 —— 所以即使去设系统环境变量，
# 当前会话的 shell 也读不到。显式指定是唯一稳妥的做法。
#
# 详见 docs/plan/ROADMAP.md §0 的「P1 详注」。

$ErrorActionPreference = 'Stop'

$jdk = 'C:\Program Files\Java\jdk-21.0.12.1'

if (-not (Test-Path (Join-Path $jdk 'bin\javac.exe'))) {
    throw "在 $jdk 找不到 JDK。请修改 tools/gradle-env.ps1 里的 `$jdk 路径。"
}

$env:JAVA_HOME = $jdk
$env:Path = "$jdk\bin;$env:Path"

Write-Host "[BeyondEMC] JAVA_HOME = $env:JAVA_HOME" -ForegroundColor Green
& "$jdk\bin\java.exe" -version 2>&1 | ForEach-Object { Write-Host "  $_" }
