@echo off
title Aria2-PanFlow Server
echo Starting Aria2-PanFlow...

:: Check if JRE is available in runtime folder, otherwise use system java
if exist "runtime\bin\java.exe" (
    set JAVA_EXE=runtime\bin\java.exe
) else (
    set JAVA_EXE=java
)

%JAVA_EXE% -jar app/Aria2-PanFlow.jar
pause