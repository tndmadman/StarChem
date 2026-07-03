@echo off
setlocal
cd /d "%~dp0"

set PORT=50000
set PLAYER_NAME=Host-%COMPUTERNAME%

if not "%~1"=="" set PORT=%~1
if not "%~2"=="" set PLAYER_NAME=%~2

echo Starting StarChem HOST on UDP port %PORT% as %PLAYER_NAME%...
echo.
call "%~dp0run-starchem.bat" --host %PORT% --name "%PLAYER_NAME%"

echo.
pause
