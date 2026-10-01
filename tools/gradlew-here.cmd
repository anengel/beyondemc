@echo off
REM ============================================================
REM  BeyondEMC - local Gradle wrapper for this machine
REM
REM  Usage (from anywhere):
REM      tools\gradlew-here.cmd build
REM      tools\gradlew-here.cmd runClient
REM
REM  Why this exists: JDK 21 is installed locally but is NOT on PATH
REM  and JAVA_HOME is empty. The already-running DSH host process also
REM  holds a stale environment block, so newly set system variables are
REM  invisible to it. Setting JAVA_HOME explicitly is the only reliable fix.
REM  See docs/plan/ROADMAP.md section 0, "P1 note".
REM
REM  NOTE: this file must stay ASCII-only. cmd.exe parses .cmd files using
REM  the OEM code page, so UTF-8 Chinese comments corrupt line breaks and
REM  get executed as commands.
REM ============================================================

setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Reuse the Gradle cache that already lives inside this project.
REM play.cmd does exactly the same. This is NOT cosmetic:
REM without it Gradle falls back to %USERPROFILE%\.gradle, which is NOT the
REM cache this project uses -- it has to re-download ~1.6 GB of
REM NeoForge/Minecraft artifacts. While that download runs the build prints
REM NOTHING for many minutes and looks hung, because it is actually stuck
REM waiting on a dependency HTTP request rather than compiling.
set "GRADLE_USER_HOME=%~dp0..\.gradle-home"

if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo [BeyondEMC] ERROR: no JDK found at "%JAVA_HOME%"
    echo             Edit JAVA_HOME in tools\gradlew-here.cmd
    exit /b 1
)

cd /d "%~dp0.."

echo [BeyondEMC] JAVA_HOME = %JAVA_HOME%
echo [BeyondEMC] running: gradlew.bat %*
echo.

call gradlew.bat %*
exit /b %ERRORLEVEL%
