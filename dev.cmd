@REM @author kongweiguang
@REM SPDX-License-Identifier: GPL-3.0-or-later
@echo off
if "%~1"=="--" (
    pwsh.exe -NoProfile -File "%~dp0scripts\dev\tauri-dev.ps1" %2 %3 %4 %5 %6 %7 %8 %9
) else (
    pwsh.exe -NoProfile -File "%~dp0scripts\dev\tauri-dev.ps1" %*
)
exit /b %ERRORLEVEL%
