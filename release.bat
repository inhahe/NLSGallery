@echo off
rem Publishes a release of NLSGallery on GitHub (github.com/inhahe/NLSGallery/releases): the
rem signed release APKs (arm64-v8a for phones, x86_64 for the emulator) and R8's mapping file for
rem reading that build's crash traces, tagged v<appVersion> (gradle.properties), with notes from
rem changelogs\<appVersionCode>.txt, once the tests pass on their full test data. Publishes
rem nothing if that version is already released or not above the last release: raise it first
rem (CLAUDE.md, Version). With an emulator running, the release APK is started on it first.
rem
rem   release.bat            test, build and publish
rem   release.bat --dry-run  test, build and show the release notes; publish nothing. What would
rem                          stop a release (main not pushed yet, say) is reported, not fatal.
setlocal EnableExtensions

set "REPO=inhahe/NLSGallery"
set "PACKAGE=com.lithic.gallery"
set "DRY="
set "BLOCKED="
if /i "%~1"=="--dry-run" (
    set "DRY=1"
) else if not "%~1"=="" (
    goto :usage
)
if not "%~2"=="" goto :usage

cd /d "%~dp0"

rem The build refuses a version that is not major.minor.patch or whose code disagrees with it.
set "VERSION="
for /f "tokens=1,* delims==" %%a in (gradle.properties) do if "%%a"=="appVersion" set "VERSION=%%b"
call :version_code CODE "type gradle.properties"
if not defined VERSION goto :no_version
if not defined CODE goto :no_version
set "TAG=v%VERSION%"

where gh >nul 2>&1
if errorlevel 1 (
    echo The GitHub CLI ^(gh^) is needed: https://cli.github.com
    exit /b 1
)
gh auth status --hostname github.com >nul 2>&1
if errorlevel 1 (
    echo gh is not logged in to github.com: run  gh auth login
    exit /b 1
)

rem What is released is main as GitHub has it: nothing uncommitted, nothing left out, nothing unpushed.
set "BRANCH="
for /f %%b in ('git symbolic-ref --short HEAD 2^>nul') do set "BRANCH=%%b"
if not "%BRANCH%"=="main" (
    call :refuse "Releases are made from main, and this is %BRANCH%." || exit /b 1
)
set "CLEAN_BEFORE=1"
call :clean_tree
if errorlevel 1 (
    set "CLEAN_BEFORE="
    call :refuse "Commit and push first." || exit /b 1
)
git fetch --quiet --tags origin
if errorlevel 1 (
    call :refuse "Fetching from GitHub failed, as git says above." || exit /b 1
)
for /f %%h in ('git rev-parse HEAD') do set "HEAD=%%h"
set "MAIN="
for /f %%h in ('git rev-parse origin/main 2^>nul') do set "MAIN=%%h"
if not "%HEAD%"=="%MAIN%" (
    call :refuse "This commit is not the main GitHub has: push, or pull, first." || exit /b 1
)

rem Once per version, and each above the one before.
set "RELEASED=unknown"
set "GH_ERR=%TEMP%\nlsgallery-release-gh.txt"
gh api "repos/%REPO%/releases/tags/%TAG%" --silent >nul 2>"%GH_ERR%"
if not errorlevel 1 (
    set "RELEASED=yes"
) else (
    findstr /c:"HTTP 404" "%GH_ERR%" >nul && set "RELEASED=no"
)
if "%RELEASED%"=="unknown" type "%GH_ERR%"
del "%GH_ERR%" 2>nul
if "%RELEASED%"=="yes" (
    call :refuse "Version %VERSION% is already released: https://github.com/%REPO%/releases/tag/%TAG% - raise appVersion in gradle.properties first, see CLAUDE.md." || exit /b 1
)
if "%RELEASED%"=="unknown" (
    call :refuse "GitHub could not say whether %TAG% is released, as gh says above." || exit /b 1
)
rem A tag without a release (an already released version has its tag, said above).
if not "%RELEASED%"=="no" goto :tag_checked
git rev-parse -q --verify "refs/tags/%TAG%" >nul
if not errorlevel 1 (
    call :refuse "The tag %TAG% exists without a release, left by a deleted one?: raise appVersion, or delete the tag on GitHub and here." || exit /b 1
)
:tag_checked
set "PREVIOUS="
set "PREVIOUS_CODE="
for /f %%t in ('git describe --tags --abbrev^=0 --match "v[0-9]*" HEAD 2^>nul') do set "PREVIOUS=%%t"
if not defined PREVIOUS goto :compared
call :version_code PREVIOUS_CODE "git show %PREVIOUS%:gradle.properties"
rem Gone to :compared without a code: an empty one would make the comparison below a syntax error.
if not defined PREVIOUS_CODE (
    call :refuse "The last release, %PREVIOUS%, has no appVersionCode in its gradle.properties to compare %VERSION% with." || exit /b 1
    goto :compared
)
if %CODE% LEQ %PREVIOUS_CODE% (
    call :refuse "Version %VERSION% is not above the last release, %PREVIOUS%: raise appVersion in gradle.properties." || exit /b 1
)
:compared

rem What is new in this version, for the release notes, in words for the people installing it.
set "CHANGELOG=changelogs\%CODE%.txt"
if not exist "%CHANGELOG%" (
    call :refuse "%CHANGELOG% is missing: say there what is new in %VERSION%, for the release notes." || exit /b 1
)

rem The pipeline's tests against thumbsup2, failing (not skipped) where test data or weights are
rem missing, then the app's; then the build.
echo Testing and building NLSGallery %VERSION%...
call "%~dp0build-release.bat" -PrequireTestFiles :core:test :app:testDebugUnitTest
if errorlevel 1 (
    echo Nothing was published.
    exit /b 1
)

rem The APKs are what HEAD makes only if nothing changed while they were built.
set "BUILT="
for /f %%h in ('git rev-parse HEAD') do set "BUILT=%%h"
if not "%BUILT%"=="%HEAD%" (
    call :refuse "The commit changed during the build, so the APKs may not be what %HEAD% makes." || exit /b 1
)
rem (A tree that was not clean before is reported above already.)
if defined CLEAN_BEFORE (
    call :clean_tree || call :refuse "Files changed during the build, so the APKs may not be what %HEAD% makes." || exit /b 1
)

set "RELEASE_APKS=app\build\outputs\apk\release"
set "MAPPING=app\build\outputs\mapping\release\mapping.txt"
if not exist "%RELEASE_APKS%\app-x86_64-release.apk" (
    call :refuse "The build made no x86_64 APK." || exit /b 1
)
if not exist "%MAPPING%" (
    call :refuse "R8 left no mapping file, %MAPPING%: the build is minified, so it should have." || exit /b 1
)

rem The release build is minified by R8, which tests of the debug build do not see: start it.
set "EMULATOR="
for /f "tokens=1,2" %%d in ('adb devices 2^>nul') do if "%%e"=="device" (
    echo %%d| findstr /b "emulator-" >nul && set "EMULATOR=%%d"
)
if defined EMULATOR (
    call :start_on_emulator %EMULATOR% || call :refuse "The release APK does not run on %EMULATOR%." || exit /b 1
) else (
    echo No emulator is running, so the minified release APK was not started anywhere. After
    echo changes R8 can break ^(Room, serialization, WorkManager, ONNX Runtime^), start one first.
)

set "MINSDK="
for /f "tokens=2 delims== " %%m in ('findstr /r /c:"^ *minSdk *=" app\build.gradle.kts') do set "MINSDK=%%m"
set "ANDROID=Android API level %MINSDK%"
for %%p in (26:8.0 27:8.1 28:9 29:10 30:11 31:12 32:12L 33:13 34:14 35:15 36:16) do (
    for /f "tokens=1,2 delims=:" %%a in ("%%p") do if "%%a"=="%MINSDK%" set "ANDROID=Android %%b"
)

rem From here on, every way out goes through :finish, which removes OUT.
set "RESULT=1"
set "OUT=%TEMP%\nlsgallery-release-%VERSION%"
if exist "%OUT%" rd /s /q "%OUT%"
mkdir "%OUT%" || goto :finish
set "PHONE_APK=NLSGallery-%VERSION%-arm64-v8a.apk"
set "EMULATOR_APK=NLSGallery-%VERSION%-x86_64.apk"
set "MAPPING_FILE=NLSGallery-%VERSION%-mapping.txt"
copy /y "%RELEASE_APKS%\app-arm64-v8a-release.apk" "%OUT%\%PHONE_APK%" >nul || goto :finish
set ASSETS="%OUT%\%PHONE_APK%"
rem Either missing stopped a real release above; a dry run shows what it has.
if exist "%RELEASE_APKS%\app-x86_64-release.apk" (
    copy /y "%RELEASE_APKS%\app-x86_64-release.apk" "%OUT%\%EMULATOR_APK%" >nul || goto :finish
    set ASSETS=%ASSETS% "%OUT%\%EMULATOR_APK%"
)
if exist "%MAPPING%" (
    copy /y "%MAPPING%" "%OUT%\%MAPPING_FILE%" >nul || goto :finish
)
if exist "%MAPPING%" set ASSETS=%ASSETS% "%OUT%\%MAPPING_FILE%"

rem The release notes: what is new, how to install, what the AI needs.
set "NOTES=%OUT%\notes.md"
> "%NOTES%" echo NLSGallery %VERSION%, for %ANDROID% and later on a 64-bit phone.
>> "%NOTES%" echo.
if exist "%CHANGELOG%" (
    type "%CHANGELOG%" >> "%NOTES%"
    >> "%NOTES%" echo.
)
>> "%NOTES%" echo **Install:** on the phone, download `%PHONE_APK%` and open it; allow your browser or file manager to install apps when asked. `%EMULATOR_APK%` is for the Android emulator. A newer version installs over an older one, keeping the descriptions and the downloaded models; uninstalling deletes them.
>> "%NOTES%" echo.
>> "%NOTES%" echo **The AI** that describes pictures and searches them by what they show needs at least 6 GB of memory and about 1.5 GB of storage. Its models, about 1.2 GB, are downloaded in the app: the AI button (sparkles) or the menu's *AI models*. See the [README](https://github.com/%REPO%#readme).
if defined PREVIOUS (
    >> "%NOTES%" echo.
    >> "%NOTES%" echo Every change since %PREVIOUS%: https://github.com/%REPO%/compare/%PREVIOUS%...%TAG%
)

if defined DRY (
    echo.
    if defined BLOCKED echo Dry run: a release now would stop, for the reasons above.
    echo Dry run: would publish %TAG% at %HEAD% with %ASSETS%
    echo and these notes:
    echo.
    type "%NOTES%"
    echo.
    set "RESULT=0"
    goto :finish
)

gh release create "%TAG%" %ASSETS% --repo %REPO% --target %HEAD% --title "NLSGallery %VERSION%" --notes-file "%NOTES%"
if errorlevel 1 (
    echo Publishing failed.
    goto :finish
)
set "RESULT=0"
git fetch --quiet --tags origin
echo.
echo Released: https://github.com/%REPO%/releases/tag/%TAG%

:finish
if exist "%OUT%" rd /s /q "%OUT%"
if not "%RESULT%"=="0" echo Nothing was published.
exit /b %RESULT%

:no_version
echo gradle.properties needs appVersion, major.minor.patch, and appVersionCode beside it.
exit /b 1

:usage
echo Usage: release.bat [--dry-run]
echo Tests, builds and publishes the version in gradle.properties as a GitHub release; with
echo --dry-run, it publishes nothing and reports what would stop a release instead of stopping.
exit /b 1

rem :refuse "<why>" says why there can be no release. Errorlevel 1, except in a dry run, which
rem goes on (and says at its end that a release would stop).
:refuse
if defined DRY (
    echo Would stop here: %~1
    set "BLOCKED=1"
    exit /b 0
)
echo %~1 Nothing was published.
exit /b 1

rem :version_code <variable> "<command>" sets the variable to the appVersionCode in the
rem gradle.properties the command prints, or clears it if there is none that is a number.
:version_code
set "%~1="
set "VC="
for /f "tokens=1,* delims==" %%a in ('%~2 2^>nul') do if "%%a"=="appVersionCode" set "VC=%%b"
echo %VC%| findstr /r /x "[1-9][0-9]*" >nul && set "%~1=%VC%"
exit /b 0

rem :clean_tree: errorlevel 1, saying what, if a file differs from HEAD or a file the release may
rem read is not in git.
:clean_tree
git diff --quiet HEAD --
if errorlevel 1 (
    echo There are uncommitted changes.
    exit /b 1
)
set "LEFT_OUT="
for /f "delims=" %%f in ('git ls-files --others --exclude-standard -- app core gradle build.gradle.kts settings.gradle.kts gradle.properties changelogs') do set "LEFT_OUT=%%f"
if defined LEFT_OUT (
    echo %LEFT_OUT% is not in git, but the release may use it: add it, or ignore it.
    exit /b 1
)
exit /b 0

rem :start_on_emulator <serial> installs the x86_64 release APK on that emulator and starts it:
rem errorlevel 1 if it cannot be installed, or 15 seconds later is not running or has crashed.
:start_on_emulator
adb -s %1 install -r "%RELEASE_APKS%\app-x86_64-release.apk" >nul
if errorlevel 1 (
    echo Could not install the release APK on %1: one signed with another key may be there.
    exit /b 1
)
adb -s %1 logcat -b crash -c >nul 2>&1
adb -s %1 shell am force-stop %PACKAGE%
adb -s %1 shell monkey -p %PACKAGE% -c android.intent.category.LAUNCHER 1 >nul 2>&1
rem ping waits where timeout cannot: timeout fails when input is redirected.
ping -n 16 127.0.0.1 >nul
adb -s %1 shell pidof %PACKAGE% >nul
if errorlevel 1 (
    echo %PACKAGE% is not running on %1 15 seconds after it was started.
    adb -s %1 logcat -b crash -d
    exit /b 1
)
set "CRASH="
rem "Process: <package>, PID: ...": the package exactly, not its .debug build beside it.
for /f "delims=" %%l in ('adb -s %1 logcat -b crash -d 2^>nul ^| findstr /c:"Process: %PACKAGE%,"') do set "CRASH=1"
if defined CRASH (
    echo %PACKAGE% crashed on %1:
    adb -s %1 logcat -b crash -d
    exit /b 1
)
echo The release APK runs on %1.
exit /b 0
