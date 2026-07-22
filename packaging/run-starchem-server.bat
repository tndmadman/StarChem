@echo off
setlocal
cd /d "%~dp0"
if not defined STARCHEM_PORT set "STARCHEM_PORT=50000"
if not defined STARCHEM_SERVER_NAME set "STARCHEM_SERVER_NAME=StarChem-Server"
if defined STARCHEM_DEV_TOKEN_FILE (
    java -Djava.awt.headless=true -jar "StarChem.jar" --server "%STARCHEM_PORT%" --name "%STARCHEM_SERVER_NAME%" --dev-token-file "%STARCHEM_DEV_TOKEN_FILE%" %*
) else (
    java -Djava.awt.headless=true -jar "StarChem.jar" --server "%STARCHEM_PORT%" --name "%STARCHEM_SERVER_NAME%" %*
)
exit /b %ERRORLEVEL%
