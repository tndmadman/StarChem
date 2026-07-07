@echo off
set /p HOST=Server IP: 
if "%HOST%"=="" set HOST=127.0.0.1
java -jar "StarChem.jar" --join %HOST% 50000 --name Client
pause
