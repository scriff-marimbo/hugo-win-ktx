@echo off
rem Hugo's Horrific Adventure - Kotlin/Swing port launcher (Windows).
rem Run from the folder that holds the original game files, or drag that folder
rem onto this file.
setlocal
set "DIR=%~dp0"
set "JAR="
for %%f in ("%DIR%hugo-port-*-all.jar") do set "JAR=%%~ff"
if not defined JAR (
    echo hugo-port-*-all.jar was not found next to this script.
    pause
    exit /b 1
)
where javaw >nul 2>nul
if errorlevel 1 (
    echo Java 17 or newer was not found. Install a JRE/JDK, e.g. from https://adoptium.net/
    pause
    exit /b 1
)
rem --version / --help should print to this console, not a hidden javaw process.
set "CONSOLE="
if /i "%~1"=="--version" set "CONSOLE=1"
if /i "%~1"=="-v" set "CONSOLE=1"
if /i "%~1"=="--help" set "CONSOLE=1"
if /i "%~1"=="-h" set "CONSOLE=1"
if defined CONSOLE (
    java -jar "%JAR%" %*
    exit /b
)
if "%~1"=="" if exist "%DIR%HUGOWIN1.EXE" (
    start "" javaw -jar "%JAR%" "%DIR%"
    exit /b 0
)
start "" javaw -jar "%JAR%" %*
