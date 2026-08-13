@echo off
rem ============================================================
rem  WOL MSI installer builder (WiX 3.x, standard wizard UI)
rem  Requires: JDK 17+ (jpackage.exe), WiX Toolset 3.x in PATH
rem  Output : target\dist\WOL-1.4.0.msi
rem  Docs   : tools/jpackage/README.md
rem  UI     : WixUI_Mondo - feature tree (desktop shortcut / start menu
rem           optional) + install dir chooser (defaults to last install
rem           dir remembered in HKCU\Software\ovo80\WOL\InstallDir)
rem  NOTE   : echo text must NOT contain parentheses or > in if blocks
rem ============================================================
setlocal
cd /d "%~dp0..\.."
set "ROOT=%CD%"
set "APP_VERSION=1.4.0"

rem ---------- 0. locate jpackage ----------
set "JDK_JPACKAGE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JDK_JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"
if not defined JDK_JPACKAGE where jpackage >nul 2>nul
if not defined JDK_JPACKAGE if errorlevel 1 (
    echo [ERROR] jpackage.exe not found. Install JDK 17+ and set JAVA_HOME.
    exit /b 1
)
if not defined JDK_JPACKAGE set "JDK_JPACKAGE=jpackage"

rem ---------- 1. locate WiX (candle / heat / light) ----------
set "WIX_BIN="
if defined WIX if exist "%WIX%bin\candle.exe" set "WIX_BIN=%WIX%bin\"
for /f "delims=" %%i in ('where candle 2^>nul') do if not defined WIX_BIN set "WIX_BIN=%%~dpi"
if not defined WIX_BIN (
    echo [ERROR] WiX Toolset 3.x not found. Install wix311.exe from github.com/wixtoolset/wix3/releases
    exit /b 1
)
if not exist "%WIX_BIN%heat.exe" (
    echo [ERROR] heat.exe missing in WiX bin dir.
    exit /b 1
)
echo [INFO] WiX: %WIX_BIN%

rem ---------- 2. check JavaFX jmods ----------
if not exist "%ROOT%\tools\jpackage\jmods\javafx-jmods-20.0.2\javafx.controls.jmod" (
    echo [INFO] JavaFX jmods missing, fetching...
    call "%ROOT%\tools\jpackage\fetch-jmods.bat"
    if errorlevel 1 exit /b 1
)

rem ---------- 3. Maven build (mvn.cmd, then java classworlds fallback) ----------
set "BUILD_OK="
if not defined BUILD_OK if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo [1/5] Maven build: %MAVEN_HOME%\bin\mvn.cmd package
    call "%MAVEN_HOME%\bin\mvn.cmd" -q package
    if not errorlevel 1 set "BUILD_OK=1"
)
if not defined BUILD_OK if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" (
    echo [1/5] Maven build: %M2_HOME%\bin\mvn.cmd package
    call "%M2_HOME%\bin\mvn.cmd" -q package
    if not errorlevel 1 set "BUILD_OK=1"
)
if not defined BUILD_OK if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\m2.conf" (
    echo [1/5] Maven build via java classworlds
    java -classpath "%MAVEN_HOME%\boot\*" "-Dclassworlds.conf=%MAVEN_HOME%\bin\m2.conf" "-Dmaven.home=%MAVEN_HOME%" "-Dmaven.multiModuleProjectDirectory=%ROOT%" org.codehaus.plexus.classworlds.launcher.Launcher -q package
    if not errorlevel 1 set "BUILD_OK=1"
)
if not defined BUILD_OK (
    echo [ERROR] Maven build failed. Build manually, then rerun.
    exit /b 1
)

rem ---------- 4. staging dir (main jar + non-JavaFX deps) ----------
echo [2/5] Prepare staging dir target\app-staging
if exist "%ROOT%\target\app-staging" rmdir /s /q "%ROOT%\target\app-staging"
mkdir "%ROOT%\target\app-staging"
copy /y "%ROOT%\target\wol-core-*.jar" "%ROOT%\target\app-staging\" >nul
copy /y "%ROOT%\target\lib\slf4j-api-*.jar" "%ROOT%\target\app-staging\" >nul
copy /y "%ROOT%\target\lib\logback-classic-*.jar" "%ROOT%\target\app-staging\" >nul
copy /y "%ROOT%\target\lib\logback-core-*.jar" "%ROOT%\target\app-staging\" >nul

rem ---------- 5. jpackage app-image (input for heat) ----------
echo [3/5] jpackage build app-image
if exist "%ROOT%\target\dist\WOL" rmdir /s /q "%ROOT%\target\dist\WOL"
"%JDK_JPACKAGE%" --type app-image ^
    --name WOL ^
    --app-version %APP_VERSION% ^
    --vendor ovo80 ^
    --input "%ROOT%\target\app-staging" ^
    --main-jar wol-core-%APP_VERSION%.jar ^
    --main-class ad.ovo.wol.Launcher ^
    --module-path "%ROOT%\tools\jpackage\jmods\javafx-jmods-20.0.2" ^
    --add-modules javafx.controls,javafx.fxml,java.naming,jdk.naming.dns ^
    --icon "%ROOT%\src\main\resources\wol.ico" ^
    --dest "%ROOT%\target\dist"
if errorlevel 1 (
    echo [ERROR] jpackage app-image failed.
    exit /b 1
)

rem ---------- 6. heat: harvest app-image files ----------
echo [4/5] heat harvest app-image
if exist "%ROOT%\target\wix" rmdir /s /q "%ROOT%\target\wix"
mkdir "%ROOT%\target\wix\obj"
"%WIX_BIN%heat.exe" dir "%ROOT%\target\dist\WOL" ^
    -gg -g1 -srd -sfrag -cg WolFilesGroup -dr INSTALLDIR ^
    -var var.WolDir ^
    -out "%ROOT%\target\wix\appfiles.wxs"
if errorlevel 1 (
    echo [ERROR] heat harvest failed.
    exit /b 1
)

rem ---------- 7. candle: compile wxs ----------
echo [5/5] candle + light build msi
"%WIX_BIN%candle.exe" ^
    -dAppVersion=%APP_VERSION% ^
    -dWolDir="%ROOT%\target\dist\WOL" ^
    -ext WixUIExtension ^
    -out "%ROOT%\target\wix\obj\\" ^
    "%ROOT%\tools\wix\Product.wxs" "%ROOT%\target\wix\appfiles.wxs"
if errorlevel 1 (
    echo [ERROR] candle compile failed.
    exit /b 1
)

rem ---------- 8. light: link msi ----------
if exist "%ROOT%\target\dist\WOL-%APP_VERSION%.msi" del /q "%ROOT%\target\dist\WOL-%APP_VERSION%.msi"
"%WIX_BIN%light.exe" ^
    -ext WixUIExtension ^
    -cultures:zh-CN ^
    -sval ^
    -out "%ROOT%\target\dist\WOL-%APP_VERSION%.msi" ^
    "%ROOT%\target\wix\obj\*.wixobj"
if errorlevel 1 (
    echo [ERROR] light link failed.
    exit /b 1
)

echo.
echo ============================================================
echo  DONE: %ROOT%\target\dist\WOL-%APP_VERSION%.msi
echo  Wizard UI: choose dir + optional shortcuts/start menu.
echo  Upgrades remember the previous install dir.
echo ============================================================
endlocal
