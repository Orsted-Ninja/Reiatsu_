@echo off
title StorageSense Assistant - 1-Click Menu
chcp 65001 >nul
if exist "%~dp0..\.venv\Scripts\python.exe" (
    set "PY_CMD=%~dp0..\.venv\Scripts\python.exe"
) else if exist "%~dp0.venv\Scripts\python.exe" (
    set "PY_CMD=%~dp0.venv\Scripts\python.exe"
) else (
    where py >nul 2>nul
    if %ERRORLEVEL% EQU 0 (
        set "PY_CMD=py"
    ) else (
        set "PY_CMD=python"
    )
)

:menu
cls
echo ============================================================
echo   🧠 StorageSense — 100%% Local AI Assistant (Interactive Menu)
echo ============================================================
echo.
echo   [1] Open Desktop Dashboard (Web UI in Browser)
echo   [2] View Live System Telemetry (RAM, Drive, Ollama status)
echo   [3] Scan & Index a Folder
echo   [4] Search Files with AI Synthesis
echo   [5] Scan for Duplicate Files (Exact, Semantic & Images)
echo   [6] Propose Space Cleanup
echo   [7] Exit
echo.
echo ============================================================
set /p choice="Select an option (1-7): "

if "%choice%"=="1" (
    echo.
    echo Launching Desktop Dashboard...
    cd /d "%~dp0"
    start "" "%PY_CMD%" run_app.py
    pause
    goto menu
)
if "%choice%"=="2" (
    echo.
    cd /d "%~dp0"
    "%PY_CMD%" run_cli.py --telemetry
    echo.
    pause
    goto menu
)
if "%choice%"=="3" (
    echo.
    set /p folder="Enter folder path to scan (or press Enter for current directory): "
    if "%folder%"=="" set "folder=%~dp0.."
    cd /d "%~dp0"
    "%PY_CMD%" run_cli.py --scan "%folder%"
    echo.
    pause
    goto menu
)
if "%choice%"=="4" (
    echo.
    set /p query="Enter your search query: "
    cd /d "%~dp0"
    "%PY_CMD%" run_cli.py --search "%query%"
    echo.
    pause
    goto menu
)
if "%choice%"=="5" (
    echo.
    set /p folder="Enter folder to deduplicate (or press Enter to scan all indexed files): "
    cd /d "%~dp0"
    if not "%folder%"=="" (
        "%PY_CMD%" run_cli.py --dedup "%folder%"
    ) else (
        "%PY_CMD%" run_cli.py --dedup
    )
    echo.
    pause
    goto menu
)
if "%choice%"=="6" (
    echo.
    set /p size_gb="Enter target GB to free (e.g. 0.001 or 1.0): "
    if "%size_gb%"=="" set size_gb=0.001
    cd /d "%~dp0"
    "%PY_CMD%" run_cli.py --clean %size_gb%
    echo.
    pause
    goto menu
)
if "%choice%"=="7" exit
goto menu
