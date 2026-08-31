@REM @author kongweiguang
@REM SPDX-License-Identifier: GPL-3.0-or-later
@ECHO OFF
node.exe "%~dp0webview2-edgedriver-runner.mjs" %*
EXIT /B %ERRORLEVEL%
