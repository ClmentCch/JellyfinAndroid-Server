@echo off
rem ===========================================================================
rem  JellyfinServer - compilation de l'APK sous Windows
rem
rem  Telecharge tout ce qui manque (JDK 17, Android SDK, Python 3) dans
rem  how-to-build-android\.tools, construit le bundle serveur puis l'APK,
rem  et copie l'APK dans dist\.
rem
rem  Utilisation : double-clic, ou depuis un terminal :
rem      how-to-build-android\build-windows.bat
rem      how-to-build-android\build-windows.bat assembleDebug
rem ===========================================================================
setlocal EnableExtensions EnableDelayedExpansion
title JellyfinServer - compilation Windows

for %%i in ("%~dp0..") do set "ROOT=%%~fi"
set "TOOLS=%~dp0.tools"
set "SDK=%TOOLS%\android-sdk"
set "JDK=%TOOLS%\jdk"
set "PYDIR=%TOOLS%\python"
set "GRADLE_HOME=%TOOLS%\gradle-home"
set "DL=%TOOLS%\downloads"
set "PLATFORM=34"
set "BUILD_TOOLS=34.0.0"
set "APK_NAME=JellyfinServer-1.0.0.apk"

set "GRADLE_TASKS=%*"
if "%GRADLE_TASKS%"=="" set "GRADLE_TASKS=assembleRelease"

echo ============================================================
echo  JellyfinServer - compilation de l'APK sous Windows
echo  Projet : %ROOT%
echo ============================================================

if not exist "%ROOT%\settings.gradle" (
    echo [x] Projet introuvable : %ROOT%
    goto :fail
)
where curl >nul 2>&1
if errorlevel 1 (
    echo [x] curl.exe est introuvable. Windows 10 1803 ou plus recent est requis.
    goto :fail
)
if not exist "%TOOLS%" mkdir "%TOOLS%"
if not exist "%DL%" mkdir "%DL%"

rem --------------------------------------------------------------- JDK -----
set "ORIG_JAVA_HOME=%JAVA_HOME%"
if exist "%JDK%\bin\java.exe" goto :java_ready
if defined ORIG_JAVA_HOME if exist "%ORIG_JAVA_HOME%\bin\java.exe" (
    set "JAVA_HOME=%ORIG_JAVA_HOME%"
    goto :check_java
)
for /f "delims=" %%p in ('where java 2^>nul') do (
    if not defined JAVA_HOME (
        for %%d in ("%%~dpp..") do set "JAVA_HOME=%%~fd"
    )
)

:check_java
if not defined JAVA_HOME goto :install_jdk
if not exist "%JAVA_HOME%\bin\java.exe" goto :install_jdk
"%JAVA_HOME%\bin\java.exe" -version 2>&1 | findstr /r "17\. 18\. 19\. 2[0-9]\." >nul
if errorlevel 1 (
    echo    Java installe trop ancien ^(17 requis^), telechargement du JDK 17...
    goto :install_jdk
)
echo [1/3] JDK 17 : %JAVA_HOME%
goto :java_ready

:install_jdk
echo [1/3] JDK 17 Temurin ^(~190 Mo^)...
call :fetch "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse" "%DL%\jdk17.zip"
if errorlevel 1 goto :fail
call :unzip "%DL%\jdk17.zip" "%DL%\jdk17"
if errorlevel 1 goto :fail
for /d %%d in ("%DL%\jdk17\*") do (
    if exist "%JDK%" rmdir /s /q "%JDK%"
    move "%%~fd" "%JDK%" >nul
)
if not exist "%JDK%\bin\java.exe" (
    echo [x] Extraction du JDK impossible.
    goto :fail
)
set "JAVA_HOME=%JDK%"
echo       JDK installe dans %JDK%

:java_ready
set "PATH=%JAVA_HOME%\bin;%PATH%"

rem ------------------------------------------------------ Android SDK -----
set "SDKMANAGER=%SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
if exist "%SDKMANAGER%" goto :sdk_ready
echo [2/3] Android SDK command line tools ^(~150 Mo^)...
call :fetch "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip" "%DL%\cmdline-tools.zip"
if errorlevel 1 goto :fail
if exist "%DL%\cmdline-tools" rmdir /s /q "%DL%\cmdline-tools"
call :unzip "%DL%\cmdline-tools.zip" "%DL%\cmdline-tools"
if errorlevel 1 goto :fail
if not exist "%SDK%\cmdline-tools" mkdir "%SDK%\cmdline-tools"
if exist "%SDK%\cmdline-tools\latest" rmdir /s /q "%SDK%\cmdline-tools\latest"
move "%DL%\cmdline-tools\cmdline-tools" "%SDK%\cmdline-tools\latest" >nul
if not exist "%SDKMANAGER%" (
    echo [x] sdkmanager introuvable apres extraction.
    goto :fail
)

:sdk_ready
echo [2/3] Android SDK : %SDK%
echo       acceptation des licences...
( for /l %%i in (1,1,60) do @echo y ) | call "%SDKMANAGER%" --sdk_root="%SDK%" --licenses >nul 2>&1
echo       installation de platform-tools, android-%PLATFORM%, build-tools %BUILD_TOOLS%...
call "%SDKMANAGER%" --sdk_root="%SDK%" --install "platform-tools" "platforms;android-%PLATFORM%" "build-tools;%BUILD_TOOLS%"
if errorlevel 1 (
    echo [x] Installation des paquets Android SDK impossible.
    goto :fail
)

rem ------------------------------------------------------------ Python -----
set "PYTHON=%PYDIR%\python.exe"
if exist "%PYTHON%" goto :python_ready
set "SYSPY="
for /f "delims=" %%p in ('where python 2^>nul') do if not defined SYSPY set "SYSPY=%%p"
if defined SYSPY (
    "%SYSPY%" -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" >nul 2>&1
    if not errorlevel 1 (
        set "PYTHON=%SYSPY%"
        echo [3/3] Python 3 : %SYSPY%
        goto :python_ready
    )
)
echo [3/3] Python 3 ^(~26 Mo^, necessaire au bundle serveur^)...
call :fetch "https://www.python.org/ftp/python/3.12.7/python-3.12.7-amd64.exe" "%DL%\python-setup.exe"
if errorlevel 1 goto :fail
echo       installation silencieuse dans %PYDIR% ^(1 a 2 min^)...
"%DL%\python-setup.exe" /quiet InstallAllUsers=0 PrependPath=0 Include_launcher=0 Include_test=0 Include_doc=0 Include_pip=0 Include_tcltk=0 Include_dev=0 Shortcuts=0 AssociateFiles=0 CompileAll=0 TargetDir="%PYDIR%"
for /l %%i in (1,1,90) do (
    if exist "%PYTHON%" goto :python_ready
    timeout /t 2 /nobreak >nul
)
echo [x] Python n'a pas pu etre installe dans %PYDIR%
goto :fail

:python_ready
set "PYTHON=%PYTHON%"

rem -------------------------------------------------------- Preparation ----
set "ANDROID_HOME=%SDK%"
set "ANDROID_SDK_ROOT=%SDK%"
set "GRADLE_USER_HOME=%GRADLE_HOME%"
set "PATH=%JAVA_HOME%\bin;%SDK%\platform-tools;%PATH%"
set "SDK_PROP=%SDK:\=/%"
> "%ROOT%\local.properties" echo sdk.dir=%SDK_PROP%

if not exist "%ROOT%\app\src\main\assets\rootfs.bin" (
    echo.
    echo [i] Le bundle serveur Jellyfin ^(~194 Mo^) n'existe pas encore.
    echo     Il va etre telecharge et construit : ~400 Mo, 5 a 15 min.
    echo.
)

echo.
echo [build] gradlew %GRADLE_TASKS%
echo.
call "%ROOT%\gradlew.bat" %GRADLE_TASKS% --no-daemon
if errorlevel 1 goto :fail

set "APK=%ROOT%\app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" set "APK=%ROOT%\app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
    echo [x] L'APK n'a pas ete produite.
    goto :fail
)
if not exist "%ROOT%\dist" mkdir "%ROOT%\dist"
copy /y "%APK%" "%ROOT%\dist\%APK_NAME%" >nul

echo.
echo ============================================================
echo  Termine : dist\%APK_NAME%
echo ============================================================
echo  Copie ce fichier sur le telephone et installe-le.
echo  C'est une application "sideload" ^(targetSdk 28^), pas Play Store.
echo.
pause
exit /b 0

rem -------------------------------------------------------- Sous-routines --
:fetch
rem :fetch <url> <fichier>
if exist "%~2" (
    echo       deja present : %~nx2
    exit /b 0
)
echo       telechargement : %~nx2
curl -L --fail --retry 3 --retry-delay 2 -# -o "%~2" "%~1"
if exist "%~2" exit /b 0
echo       curl a echoue, nouvelle tentative avec PowerShell...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; try { Invoke-WebRequest -Uri '%~1' -OutFile '%~2' -UseBasicParsing } catch { exit 1 }"
if exist "%~2" exit /b 0
exit /b 1

:unzip
rem :unzip <archive.zip> <dossier>
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ProgressPreference='SilentlyContinue'; Expand-Archive -LiteralPath '%~1' -DestinationPath '%~2' -Force"
exit /b %errorlevel%

:fail
echo.
echo [x] Compilation interrompue. Corrige l'erreur ci-dessus puis relance ce fichier.
echo     Pour repartir de zero : supprime le dossier how-to-build-android\.tools
echo.
pause
exit /b 1
