@echo off
rem ============================================================
rem  Fetch JavaFX 20.0.2 Windows x64 jmods (one-time preparation)
rem  Output: tools/jpackage/jmods/javafx-jmods-20.0.2/
rem ============================================================
setlocal
cd /d "%~dp0"
if not exist jmods mkdir jmods
cd jmods

if exist "javafx-jmods-20.0.2\javafx.controls.jmod" (
    echo jmods already present, skip.
    exit /b 0
)

set "URL=https://download2.gluonhq.com/openjfx/20.0.2/openjfx-20.0.2_windows-x64_bin-jmods.zip"
echo Downloading %URL%
curl -L -o openjfx-jmods-20.0.2.zip "%URL%"
if errorlevel 1 (
    echo [ERROR] Download failed. Fetch manually and extract into this dir:
    echo   %URL%
    exit /b 1
)

echo Extracting...
powershell -NoProfile -Command "Expand-Archive -Force openjfx-jmods-20.0.2.zip ."
if errorlevel 1 (
    echo [ERROR] Extract failed.
    exit /b 1
)

echo jmods ready: %~dp0jmods\javafx-jmods-20.0.2\
endlocal
