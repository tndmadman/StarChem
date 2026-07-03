@echo off
setlocal
cd /d "%~dp0"

echo Opening StarChem lobby...
echo.
call "%~dp0run-starchem.bat"

echo.
pause
