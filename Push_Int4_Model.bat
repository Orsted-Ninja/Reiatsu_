@echo off
setlocal enabledelayedexpansion

echo ============================================================
echo   StorageSense - Push Official INT4 Gemma Model to Phone
echo ============================================================
echo.

set MODEL_PATH=%USERPROFILE%\.storagesense_models\gemma-2b-it-gpu-int4.bin

if not exist "%MODEL_PATH%" (
    echo [ERROR] Model file not found at: %MODEL_PATH%
    echo Running downloader first...
    python scripts\download_model.py
)

echo [ADB] Checking for connected Android device...
adb wait-for-device

echo [ADB] Phone detected!
echo [ADB] Ensuring target directory exists on phone...
adb shell mkdir -p /sdcard/StorageSense/models

echo [ADB] Pushing gemma-2b-it-gpu-int4.bin (1.29 GB) to phone...
adb push "%MODEL_PATH%" /sdcard/StorageSense/models/gemma-2b-it-gpu-int4.bin

echo.
echo ============================================================
echo [SUCCESS] Model pushed successfully!
echo Files in /sdcard/StorageSense/models/:
adb shell ls -lh /sdcard/StorageSense/models/
echo ============================================================
echo.
pause
