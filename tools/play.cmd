@echo off
REM ============================================================
REM  BeyondEMC launcher - DOUBLE-CLICK THIS FILE TO PLAY
REM
REM  Default: starts the Minecraft client (gradlew runClient).
REM  Optional: drag nothing, just double-click.
REM  From a terminal you can pass another Gradle task:
REM      tools\play.cmd runServer
REM      tools\play.cmd build
REM
REM  JEI is put on the run classpath by default (-PwithJei) so the JEI
REM  integration can be tested in the dev client. Pass "nojei" as the
REM  second argument to turn that off:  tools\play.cmd runClient nojei
REM
REM  Why the window stays open at the end: if something fails you
REM  need to be able to read the error. Without "pause" this window
REM  closes instantly on failure and you see nothing.
REM
REM  NOTE: this file must stay ASCII-only. cmd.exe parses .cmd files
REM  with the OEM code page, so UTF-8 Chinese comments would corrupt
REM  line breaks and get executed as commands.
REM ============================================================

setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-21.0.12.1"
set "PATH=%JAVA_HOME%\bin;%PATH%"

REM Reuse the Gradle cache that already lives inside this project.
REM Without this, Gradle would use %USERPROFILE%\.gradle and re-download
REM the Gradle distribution plus NeoForge/Minecraft (1-2 GB).
set "GRADLE_USER_HOME=%~dp0..\.gradle-home"

cd /d "%~dp0.."

if "%~1"=="" (set "TASK=runClient") else (set "TASK=%~1")

REM JEI on the run classpath (needed to test the JEI recipe-fill feature).
set "JEI_FLAG=-PwithJei"
if /I "%~2"=="nojei" set "JEI_FLAG="

if not exist "%JAVA_HOME%\bin\javac.exe" (
    echo [BeyondEMC] ERROR: no JDK found at "%JAVA_HOME%"
    echo             Edit JAVA_HOME in tools\play.cmd
    echo.
    pause
    exit /b 1
)

echo ============================================================
echo   BeyondEMC launcher
echo ============================================================
echo   JAVA_HOME        = %JAVA_HOME%
echo   GRADLE_USER_HOME = %GRADLE_USER_HOME%
echo   Gradle task      = %TASK%
echo   Extra args       = %JEI_FLAG%  (JEI on run classpath)
echo ============================================================
echo.
echo   The game window will open shortly.
echo   KEEP THIS CONSOLE OPEN while playing - closing it stops the game.
echo.

call gradlew.bat %TASK% --console=plain %JEI_FLAG%
set "RC=%ERRORLEVEL%"

echo.
echo ------------------------------------------------------------
echo   Gradle exited with code %RC%
echo ------------------------------------------------------------
if not "%RC%"=="0" (
    echo.
    echo   Something failed. Scroll up to read the error, or send the
    echo   text above to whoever is helping you.
)
echo.
pause

endlocal
