@echo off
rem ============================================================
rem  WOL MSI installer builder: Maven build + jpackage --type msi
rem  Requires: JDK 17+ (jpackage.exe), WiX Toolset 3.x (in PATH)
rem  Output : target\dist\WOL-1.3.0.msi
rem  Docs   : tools/jpackage/README.md
rem  NOTE   : echo text must NOT contain parentheses or > in if blocks
rem ============================================================
setlocal
cd /d "%~dp0..\.."
set "ROOT=%CD%"
set "JDK_JPACKAGE="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JDK_JPACKAGE=%JAVA_HOME%\bin\jpackage.exe"

rem ---------- 0. locate jpackage ----------
if not defined JDK_JPACKAGE where jpackage >nul 2>nul
if not defined JDK_JPACKAGE if errorlevel 1 (
    echo [ERROR] jpackage.exe not found. Install JDK 17+ and set JAVA_HOME.
    exit /b 1
)
if not defined JDK_JPACKAGE set "JDK_JPACKAGE=jpackage"

rem ---------- 1. check JavaFX jmods ----------
if not exist "%ROOT%\tools\jpackage\jmods\javafx-jmods-20.0.2\javafx.controls.jmod" (
    echo [INFO] JavaFX jmods missing, fetching...
    call "%ROOT%\tools\jpackage\fetch-jmods.bat"
    if errorlevel 1 exit /b 1
)

rem ---------- 2. Maven build (mvn.cmd, then java classworlds fallback) ----------
set "BUILD_OK="
if not defined BUILD_OK if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo [1/3] Maven build: %MAVEN_HOME%\bin\mvn.cmd package
    call "%MAVEN_HOME%\bin\mvn.cmd" -q package
    if not errorlevel 1 set "BUILD_OK=1"
)
if not defined BUILD_OK if defined M2_HOME if exist "%M2_HOME%\bin\mvn.cmd" (
    echo [1/3] Maven build: %M2_HOME%\bin\mvn.cmd package
    call "%M2_HOME%\bin\mvn.cmd" -q package
    if not errorlevel 1 set "BUILD_OK=1"
)
if not defined BUILD_OK if defined MAVEN_HOME if exist "%MAVEN_HOME%\bin\m2.conf" (
    echo [1/3] Maven build via java classworlds
    java -classpath "%MAVEN_HOME%\boot\*" "-Dclassworlds.conf=%MAVEN_HOME%\bin\m2.conf" "-Dmaven.home=%MAVEN_HOME%" "-Dmaven.multiModuleProjectDirectory=%ROOT%" org.codehaus.plexus.classworlds.launcher.Launcher -q package
    if not errorlevel 1 set "BUILD_OK=1"
)
if not defined BUILD_OK (
    echo [ERROR] Maven build failed. Build manually, then rerun.
    exit /b 1
)

rem ---------- 3. staging dir (main jar + non-JavaFX deps) ----------
echo [2/3] Prepare staging dir target\app-staging
if exist "%ROOT%\target\app-staging" rmdir /s /q "%ROOT%\target\app-staging"
mkdir "%ROOT%\target\app-staging"
copy /y "%ROOT%\target\wol-*.jar" "%ROOT%\target\app-staging\" >nul
copy /y "%ROOT%\target\lib\slf4j-api-*.jar" "%ROOT%\target\app-staging\" >nul
copy /y "%ROOT%\target\lib\logback-classic-*.jar" "%ROOT%\target\app-staging\" >nul
copy /y "%ROOT%\target\lib\logback-core-*.jar" "%ROOT%\target\app-staging\" >nul

rem ---------- 4. jpackage msi ----------
echo [3/3] jpackage build msi installer
if exist "%ROOT%\target\dist\WOL-*.msi" del /q "%ROOT%\target\dist\WOL-*.msi"
"%JDK_JPACKAGE%" --type msi ^
    --name WOL ^
    --app-version 1.3.0 ^
    --vendor ovo80 ^
    --input "%ROOT%\target\app-staging" ^
    --main-jar wol-1.3.0.jar ^
    --main-class ad.ovo.wol.Launcher ^
    --module-path "%ROOT%\tools\jpackage\jmods\javafx-jmods-20.0.2" ^
    --add-modules javafx.controls,javafx.fxml,java.naming,jdk.naming.dns ^
    --icon "%ROOT%\src\main\resources\wol.ico" ^
    --win-dir-chooser ^
    --win-shortcut ^
    --win-menu-group "WOL Wake-on-LAN" ^
    --dest "%ROOT%\target\dist"
if errorlevel 1 (
    echo [ERROR] jpackage msi failed. WiX Toolset 3.x must be installed and in PATH.
    exit /b 1
)

echo.
echo ============================================================
echo  DONE: %ROOT%\target\dist\WOL-1.3.0.msi
echo  Double-click to install; uninstall via Control Panel.
echo ============================================================
endlocal
