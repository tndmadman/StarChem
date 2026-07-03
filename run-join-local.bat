@echo off
setlocal
cd /d "%~dp0"

set HOST_IP=127.0.0.1
set PORT=50000
set PLAYER_NAME=Player-%RANDOM%

if not "%~1"=="" set HOST_IP=%~1
if not "%~2"=="" set PORT=%~2
if not "%~3"=="" set PLAYER_NAME=%~3

echo Starting StarChem JOIN client as %PLAYER_NAME%...
echo Connecting to %HOST_IP%:%PORT%
echo.
call "%~dp0run-starchem.bat" --join %HOST_IP% %PORT% --name "%PLAYER_NAME%"

echo.
pause
