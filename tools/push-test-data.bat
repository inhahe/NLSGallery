@echo off
rem Copies the test images (the files, and their pixels as PIL decoded them) and thumbsup2's
rem reference output to the phone, for the on-device tests (app\src\androidTest):
rem
rem   tools\push-test-data.bat              to the phone connected by USB
rem   tools\push-test-data.bat emulator     to the running emulator
rem
rem then run a test with adb shell am instrument (see CLAUDE.md, not connectedAndroidTest, which
rem may uninstall the app and the models in its folder).
rem
rem Needs tools\testdata (python tools\fetch_test_images.py), tools\testdata\raw (python
rem tools\make_image_reference.py raw, in thumbsup2's environment), and the debug build
rem installed and opened once (the app makes its folder: one adb makes, the app cannot read).
setlocal

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "TARGET=-d"
if /i "%~1"=="emulator" set "TARGET=-e"
set "DEST=/sdcard/Android/data/com.lithic.gallery.debug/files/test"

cd /d "%~dp0"
if not exist "testdata\raw" (
    echo testdata\raw is missing: run tools\make_image_reference.py raw first.
    exit /b 1
)
"%ADB%" %TARGET% shell mkdir -p "%DEST%/raw" "%DEST%/images"
"%ADB%" %TARGET% push testdata\raw\. "%DEST%/raw/"
if errorlevel 1 exit /b 1
for %%F in (testdata\*.jpg testdata\*.png) do (
    "%ADB%" %TARGET% push "%%F" "%DEST%/images/%%~nxF" >nul
    if errorlevel 1 exit /b 1
)
"%ADB%" %TARGET% push reference\thumbsup2_cuda.json reference\ocr.json "%DEST%/"
if errorlevel 1 exit /b 1
echo Done.
