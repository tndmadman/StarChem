@echo off
setlocal
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
    echo Java 17 or newer was not found in PATH.
    echo Install Java, reopen this folder, and run this file again.
    pause
    exit /b 1
)

if not exist "StarChem.jar" (
    echo StarChem.jar was not found beside this launcher.
    echo Extract the complete StarChem release ZIP before starting the server.
    pause
    exit /b 1
)

if not defined STARCHEM_PORT set "STARCHEM_PORT=50000"
if not defined STARCHEM_SERVER_NAME set "STARCHEM_SERVER_NAME=StarChem-Server"

echo Starting %STARCHEM_SERVER_NAME% on TCP port %STARCHEM_PORT%...
echo Players can connect from the lobby with JOIN.
echo.

if defined STARCHEM_DEV_TOKEN_FILE (
    java -Djava.awt.headless=true -jar "StarChem.jar" --server "%STARCHEM_PORT%" --name "%STARCHEM_SERVER_NAME%" --dev-token-file "%STARCHEM_DEV_TOKEN_FILE%" %*
) else (
    java -Djava.awt.headless=true -jar "StarChem.jar" --server "%STARCHEM_PORT%" --name "%STARCHEM_SERVER_NAME%" %*
)

set "STARCHEM_EXIT=%ERRORLEVEL%"
if not "%STARCHEM_EXIT%"=="0" (
    echo.
    echo StarChem server exited with code %STARCHEM_EXIT%.
    pause
)
exit /b %STARCHEM_EXIT%
