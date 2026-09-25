@echo off
setlocal
set "APP_DIR=%~dp0"
if not exist "%APP_DIR%runtime\bin\server\jvm.dll" goto incomplete
if not exist "%APP_DIR%app\LerNET.cfg" goto incomplete
start "" "%APP_DIR%LerNET.exe"
exit /b 0

:incomplete
echo LerNET files are incomplete.
echo Extract the entire ZIP archive first, then run Start-LerNET.cmd.
echo Keep LerNET.exe, app, and runtime in the same folder.
pause
exit /b 1
