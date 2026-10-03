@echo off
setlocal
cd /d "%~dp0"
echo ============================================================
echo  StorageSense - Compiling Native Windows Executable (.exe)
echo ============================================================
python build_exe.py
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Build failed!
    pause
    exit /b %ERRORLEVEL%
)
echo.
echo Build complete. You can now launch StorageSense.exe directly!
echo ============================================================
pause
