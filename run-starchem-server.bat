@echo off
setlocal
cd /d "%~dp0"

if not exist "run-starchem.bat" (
    echo Could not find run-starchem.bat beside this launcher.
    echo Make sure this file is in the StarChem repository root.
    echo.
    pause
    exit /b 1
)

if not defined STARCHEM_PORT set "STARCHEM_PORT=50000"
if not defined STARCHEM_SERVER_NAME set "STARCHEM_SERVER_NAME=StarChem-Server"
if not defined STARCHEM_SERVER_SAVE_DIR (
    if defined LOCALAPPDATA (
        set "STARCHEM_SERVER_SAVE_DIR=%LOCALAPPDATA%\StarChem\server"
    ) else (
        set "STARCHEM_SERVER_SAVE_DIR=%USERPROFILE%\AppData\Local\StarChem\server"
    )
)

echo Starting %STARCHEM_SERVER_NAME% on TCP port %STARCHEM_PORT%...
echo Server data: %STARCHEM_SERVER_SAVE_DIR%
echo Players can connect from the lobby with JOIN.
echo.

if defined STARCHEM_DEV_TOKEN_FILE (
    call "%~dp0run-starchem.bat" --server "%STARCHEM_PORT%" --name "%STARCHEM_SERVER_NAME%" --save-dir "%STARCHEM_SERVER_SAVE_DIR%" --dev-token-file "%STARCHEM_DEV_TOKEN_FILE%" %*
) else (
    call "%~dp0run-starchem.bat" --server "%STARCHEM_PORT%" --name "%STARCHEM_SERVER_NAME%" --save-dir "%STARCHEM_SERVER_SAVE_DIR%" %*
)

set "STARCHEM_EXIT=%ERRORLEVEL%"
if not "%STARCHEM_EXIT%"=="0" (
    echo.
    echo StarChem server exited with code %STARCHEM_EXIT%.
    pause
)
exit /b %STARCHEM_EXIT%
