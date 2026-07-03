@echo off
setlocal
cd /d "%~dp0"

set PORT=50000
if not "%~1"=="" set PORT=%~1

echo Launching StarChem host and local join client on UDP port %PORT%...
echo.
start "StarChem HOST" cmd /k ""%~dp0run-host.bat" %PORT%"
timeout /t 2 /nobreak >nul
start "StarChem JOIN" cmd /k ""%~dp0run-join-local.bat" 127.0.0.1 %PORT%"

echo Both windows launched.
echo Close the HOST and JOIN windows when done.
echo.
pause
