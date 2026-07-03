@echo off
setlocal
cd /d "%~dp0"

set PORT=50000
if not "%~1"=="" set PORT=%~1

set HOST_NAME=Host-%COMPUTERNAME%
set JOIN_NAME=Player-%RANDOM%

echo Launching StarChem host and local join client on UDP port %PORT%...
echo Host name: %HOST_NAME%
echo Join name: %JOIN_NAME%
echo.
start "StarChem HOST" cmd /k ""%~dp0run-host.bat" %PORT% "%HOST_NAME%""
timeout /t 2 /nobreak >nul
start "StarChem JOIN" cmd /k ""%~dp0run-join-local.bat" 127.0.0.1 %PORT% "%JOIN_NAME%""

echo Both windows launched.
echo Close the JOIN window to test player removal.
echo Close the HOST window to end the session.
echo.
pause
