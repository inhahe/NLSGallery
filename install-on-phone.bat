@echo off
rem Builds the release app and installs it on the phone connected by USB, keeping its data
rem (descriptions, settings, downloaded models) when it is already installed.
rem The phone needs USB debugging on, and this computer allowed (see README.md).
setlocal

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "PKG=com.lithic.gallery"
set "APK=app\build\outputs\apk\release\app-arm64-v8a-release.apk"

cd /d "%~dp0"

rem Before building, so a missing phone costs no build.
"%ADB%" -d get-state >nul 2>&1
if errorlevel 1 (
    echo No phone found. Connect it by USB, unlock it, check that USB debugging is on and that
    echo you accepted "Allow USB debugging". "%ADB%" devices  shows what is connected.
    exit /b 1
)

echo Building the release version...
call "%~dp0build-release.bat"
if errorlevel 1 (
    echo.
    echo Nothing was installed.
    exit /b 1
)

echo.
echo Installing on the phone (USB)...
set "LOG=%TEMP%\gallery_install_%RANDOM%.txt"
rem -d: the device connected by USB, even while an emulator is running.
"%ADB%" -d install -r "%APK%" > "%LOG%" 2>&1
set "RC=%ERRORLEVEL%"
type "%LOG%"
findstr /c:"INSTALL_FAILED_UPDATE_INCOMPATIBLE" "%LOG%" >nul && (
    echo.
    echo The copy on the phone was signed with another key. Uninstalling it first,
    echo "%ADB%" uninstall %PKG%, also deletes its descriptions and its downloaded models.
    set "RC=1"
)
findstr /c:"INSTALL_FAILED_VERSION_DOWNGRADE" "%LOG%" >nul && (
    echo.
    echo The phone has a newer version. Raise appVersion and appVersionCode in gradle.properties.
    set "RC=1"
)
del "%LOG%" >nul 2>&1
if not "%RC%"=="0" (
    echo.
    echo Install failed.
    exit /b 1
)

echo.
echo Installed. Open "Lithic Gallery" on the phone. The AI models (1.2 GB) download from the
echo app's AI models screen, or run  push-models.bat  to copy them from this computer.
"%ADB%" -d shell pm path %PKG%.debug >nul 2>&1
if not errorlevel 1 (
    echo.
    echo A debug build is also installed, with its own copy of the models. To remove it:
    echo     "%ADB%" -d uninstall %PKG%.debug
)
exit /b 0
