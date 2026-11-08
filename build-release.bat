@echo off
rem Builds the signed release APKs, one per processor type, in app\build\outputs\apk\release:
rem app-arm64-v8a-release.apk for phones, app-x86_64-release.apk for the emulator. Gradle tasks
rem given as arguments run first. Exits non-zero, saying why, if there is no signed APK.
setlocal

rem The JDK Android Studio downloaded, unless JAVA_HOME names one.
if not defined JAVA_HOME if exist "%USERPROFILE%\.jdks\jdk-21.0.12.1+1\bin\java.exe" set "JAVA_HOME=%USERPROFILE%\.jdks\jdk-21.0.12.1+1"

cd /d "%~dp0"

if not exist keystore.properties (
    echo keystore.properties is missing, so the APK would be unsigned ^(see README.md, Building^).
    exit /b 1
)

rem By its full path: cmd may be set not to look in the current folder.
call "%~dp0gradlew.bat" %* assembleRelease
if errorlevel 1 (
    echo The build failed.
    exit /b 1
)
if not exist "app\build\outputs\apk\release\app-arm64-v8a-release.apk" (
    echo The build made no signed APK.
    exit /b 1
)
exit /b 0
