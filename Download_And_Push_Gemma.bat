@echo off
title StorageSense - Download & Push Gemma Model to Phone
chcp 65001 >nul

echo ============================================================
echo   StorageSense — On-Device Gemma Model Downloader
echo ============================================================
echo.

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    set "PY_CMD=py"
) else (
    set "PY_CMD=python"
)

%PY_CMD% "%~dp0scripts\download_model.py" %*
echo.
pause
