@echo off
setlocal
cd /d "%~dp0"
java -jar "StarChem.jar" %*
set "STARCHEM_EXIT=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %STARCHEM_EXIT%
