@echo off
title Create StorageSense Desktop Shortcut
chcp 65001 >nul
cls

echo ============================================================
echo   Creating StorageSense Desktop Shortcut...
echo ============================================================
echo.

powershell -ExecutionPolicy Bypass -File "%~dp0create_shortcut.ps1"

echo.
echo You can now start StorageSense directly from your Desktop!
echo.
pause
