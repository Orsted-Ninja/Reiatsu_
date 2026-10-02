@echo off
title StorageSense - Local AI Storage Intelligence
chcp 65001 >nul
cls

echo ============================================================
echo   StorageSense — 100%% Local AI Storage Assistant
echo ============================================================
echo.
echo Initializing on-device engine...
echo Opening Web Dashboard in your browser (http://localhost:8501)...
echo.
echo (To close StorageSense, simply close this window)
echo ============================================================
echo.

cd /d "%~dp0"
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8

where py >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    set "PY_CMD=py"
) else (
    set "PY_CMD=python"
)

%PY_CMD% run_app.py

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] StorageSense stopped with error code: %ERRORLEVEL%
    pause
)
