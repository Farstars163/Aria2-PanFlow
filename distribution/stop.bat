@echo off
title Aria2-PanFlow Stop
echo Stopping Aria2-PanFlow...

:: Find the process running the jar and kill it
for /f "tokens=2" %%a in ('tasklist ^| findstr "Aria2-PanFlow.jar"') do (
    taskkill /F /PID %%a
    echo Process %%a killed.
)

echo Aria2-PanFlow has been stopped.
pause