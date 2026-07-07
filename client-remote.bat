@echo off
set /p HOST=Server IP: 
if "%HOST%"=="" set HOST=127.0.0.1
run-starchem.bat --join %HOST% 50000 --name Client
