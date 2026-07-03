@echo off
setlocal
cd /d "%~dp0"

set PORT=50000
if not "%~1"=="" set PORT=%~1

echo Starting StarChem HOST on UDP port %PORT%...
echo.
call "%~dp0run-starchem.bat" --host %PORT% --id HOST

echo.
pause
