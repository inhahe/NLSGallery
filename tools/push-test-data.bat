@echo off
rem Copies the test images (as PIL decoded them) and thumbsup2's reference output to the phone,
rem for the on-device test (app\src\androidTest\...\OnDeviceModelTest.kt):
rem
rem   tools\push-test-data.bat
rem   gradlew :app:connectedDebugAndroidTest
rem
rem Needs tools\testdata\raw (python tools\make_image_reference.py raw, in thumbsup2's
rem environment) and the debug build installed once.
setlocal

set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if not exist "%ADB%" set "ADB=adb"
set "DEST=/sdcard/Android/data/com.lithic.gallery.debug/files/test"

cd /d "%~dp0"
if not exist "testdata\raw" (
    echo testdata\raw is missing: run tools\make_image_reference.py raw first.
    exit /b 1
)
"%ADB%" -d shell mkdir -p "%DEST%/raw"
"%ADB%" -d push testdata\raw\. "%DEST%/raw/"
if errorlevel 1 exit /b 1
"%ADB%" -d push reference\thumbsup2_cuda.json reference\ocr.json "%DEST%/"
if errorlevel 1 exit /b 1
echo Done.
