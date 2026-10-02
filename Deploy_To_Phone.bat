@echo off
setlocal enabledelayedexpansion
title StorageSense - 1-Click ADB Phone Deployment
chcp 65001 >nul

echo ============================================================
echo   StorageSense - 1-Click Android Phone Deployment
echo   100%% On-Device Execution (POCO F6 / Snapdragon 8s Gen 3)
echo ============================================================
echo.

:: 1. Locate ADB
set "ADB_CMD=adb"
where adb >nul 2>nul
if %ERRORLEVEL% NEQ 0 (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB_CMD=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    ) else (
        echo [ERROR] adb.exe not found in PATH or Android SDK platform-tools!
        echo Please make sure Android SDK platform-tools are installed.
        pause
        exit /b 1
    )
)

echo [1/5] Checking connected Android devices via ADB...
"%ADB_CMD%" devices
echo.

:: Check if at least one device is connected
for /f "skip=1 tokens=1,2" %%i in ('"%ADB_CMD%" devices') do (
    if "%%j"=="device" (
        set "DEVICE_ID=%%i"
        goto :device_found
    )
)

echo [WARNING] No authorized device detected.
echo Ensure USB Debugging is ON and tap 'Allow USB debugging' on your phone.
pause
exit /b 1

:device_found
echo [SUCCESS] Connected device: !DEVICE_ID!
echo.

:: 2. Build Debug APK with Gradle (using local cache outside OneDrive to avoid file locks)
echo [2/5] Compiling Debug APK with Gradle...
call gradlew.bat assembleDebug --project-cache-dir "%USERPROFILE%\.gradle-ascent-cache"
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Gradle build failed!
    pause
    exit /b %ERRORLEVEL%
)
echo.

:: 3. Install APK via ADB
set "APK_PATH=%USERPROFILE%\.gradle-ascent-build\app\outputs\apk\debug\app-debug.apk"
if not exist "%APK_PATH%" (
    set "APK_PATH=%~dp0app\build\outputs\apk\debug\app-debug.apk"
)
if not exist "%APK_PATH%" (
    echo [ERROR] APK not found at: %APK_PATH%
    pause
    exit /b 1
)

echo [3/5] Installing StorageSense APK onto !DEVICE_ID!...
"%ADB_CMD%" -s !DEVICE_ID! install -r -d "%APK_PATH%"
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] ADB installation failed!
    pause
    exit /b %ERRORLEVEL%
)
echo [SUCCESS] App successfully installed!
echo.

:: 4. Grant File Permissions & Create Models Folder
echo [4/5] Configuring storage permissions and models folder...
"%ADB_CMD%" -s !DEVICE_ID! shell appops set com.storagesense.app.debug MANAGE_EXTERNAL_STORAGE allow
"%ADB_CMD%" -s !DEVICE_ID! shell "mkdir -p /sdcard/StorageSense/models /sdcard/Download/models"
echo.

:: 5. Launch StorageSense on the Phone
echo [5/5] Launching StorageSense on phone screen...
"%ADB_CMD%" -s !DEVICE_ID! shell am start -n com.storagesense.app.debug/com.storagesense.app.ui.MainActivity
echo.
echo ============================================================
echo   StorageSense is running live on your phone!
echo.
echo   Model Location on phone: /sdcard/Download/models/
echo   (Optional: Copy gemma-2b-it.bin into that folder for
echo    full on-device generative reasoning)
echo ============================================================
echo.
pause
