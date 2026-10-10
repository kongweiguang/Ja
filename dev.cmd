@REM @author kongweiguang
@REM SPDX-License-Identifier: GPL-3.0-or-later
@echo off
setlocal
if "%~1"=="--" (
    pwsh.exe -NoProfile -File "%~dp0scripts\dev\tauri-dev.ps1" %2 %3 %4 %5 %6 %7 %8 %9
) else (
    pwsh.exe -NoProfile -File "%~dp0scripts\dev\tauri-dev.ps1" %*
)
set "_jaExitCode=%ERRORLEVEL%"
if not "%_jaExitCode%"=="0" (
    echo.
    echo Ja desktop startup failed, exit code %_jaExitCode%.
    if not defined CI if not defined npm_lifecycle_event pause
)
endlocal & exit /b %_jaExitCode%
