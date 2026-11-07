@echo off
rem Copies the AI models' weight files from this computer to the phone connected by USB, into
rem the app's own folder, so the app needs no 1.2 GB download. The app checks each file's
rem SHA-256 the next time it opens.
rem
rem   push-models.bat            for the installed release build (com.lithic.gallery)
rem   push-models.bat debug      for a debug build (com.lithic.gallery.debug)
rem
rem The files come from tools\weights, which "python tools\fetch_weights.py" fills (copying
rem thumbsup2's own downloads, or downloading them from their publishers).
setlocal

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "PKG=com.lithic.gallery"
if /i "%~1"=="debug" set "PKG=com.lithic.gallery.debug"
set "DEST=/sdcard/Android/data/%PKG%/files/models"

cd /d "%~dp0"
rem The app must create its folder itself: one adb creates belongs to the shell user, and the
rem app could not read what is copied into it. Opening the app makes it.
"%ADB%" -d shell am start -n %PKG%/com.lithic.gallery.MainActivity >nul
set /a TRIES=0
:waitdir
"%ADB%" -d shell "[ -d %DEST% ]"
if not errorlevel 1 goto havedir
set /a TRIES+=1
if %TRIES% geq 30 (
    echo The app did not create %DEST%: is it installed?
    exit /b 1
)
timeout /t 1 /nobreak >nul
goto waitdir
:havedir
if not exist "tools\weights\blip\model.safetensors" (
    echo tools\weights is empty: run "python tools\fetch_weights.py" first.
    exit /b 1
)

for %%M in (blip minilm craft english_g2) do (
    "%ADB%" -d shell mkdir -p "%DEST%/%%M"
    if errorlevel 1 goto failed
    for %%F in ("tools\weights\%%M\*") do (
        echo %%M: %%~nxF
        "%ADB%" -d push "%%F" "%DEST%/%%M/%%~nxF"
        if errorlevel 1 goto failed
    )
)
echo.
echo Done. Open the app: it checks the files, then they are ready.
exit /b 0

:failed
echo.
echo Copying failed. Is the phone connected and unlocked, with USB debugging on, and the app
echo installed and opened once (it creates its folder)?
exit /b 1
