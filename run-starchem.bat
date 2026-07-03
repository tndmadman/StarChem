@echo off
setlocal
cd /d "%~dp0"

where javac >nul 2>nul
if errorlevel 1 (
    echo Java JDK was not found.
    echo.
    echo Install Java 17 JDK or newer, then reopen this folder and run the .bat again.
    echo You need the JDK, not just the JRE, because this script compiles the game.
    echo.
    pause
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo Java runtime was not found.
    echo Install Java 17 JDK or newer, then try again.
    echo.
    pause
    exit /b 1
)

if not exist "build" mkdir "build"
if not exist "build\classes" mkdir "build\classes"

set MAIN_SOURCE=src\main\java\com\tndmadman\rts\RtsGame.java

if not exist "%MAIN_SOURCE%" (
    echo Could not find %MAIN_SOURCE%.
    echo Make sure you are running this from the StarChem repo folder.
    echo.
    pause
    exit /b 1
)

echo Compiling StarChem...
javac --release 17 -encoding UTF-8 -d "build\classes" "%MAIN_SOURCE%"
if errorlevel 1 (
    echo.
    echo Compile failed.
    pause
    exit /b 1
)

echo Launching StarChem...
echo.
java -cp "build\classes" com.tndmadman.rts.RtsGame %*
