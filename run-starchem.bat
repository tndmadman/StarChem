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

if not exist "src\main\java\com\tndmadman\rts" (
    echo Could not find StarChem source folder.
    echo Make sure you are running this from the StarChem repo folder.
    echo.
    pause
    exit /b 1
)

echo Finding Java sources...
(
    for /r "src\main\java" %%F in (*.java) do echo "%%F"
) > "build\sources.txt"

if not exist "build\sources.txt" (
    echo Could not create source list.
    pause
    exit /b 1
)

echo Compiling StarChem modular source files...
javac --release 17 -encoding UTF-8 -d "build\classes" @"build\sources.txt"
if errorlevel 1 (
    echo.
    echo Compile failed.
    pause
    exit /b 1
)

echo Launching StarChem...
echo.
java -cp "build\classes" com.tndmadman.rts.App %*
