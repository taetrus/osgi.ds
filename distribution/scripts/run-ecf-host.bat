@echo off
setlocal enabledelayedexpansion

rem ECF Remote Services demo - HOST process.
rem Exports IRemoteGreet via ECF Generic provider on ecftcp://localhost:3288/server.
rem Start this FIRST, then run-ecf-consumer.bat in another terminal.

set "PRODUCT_UID=com.kk.pde.ds.ecf.host.product"

set "SCRIPT_DIR=%~dp0"
if "%SCRIPT_DIR:~-1%"=="\" set "SCRIPT_DIR=%SCRIPT_DIR:~0,-1%"

if exist "%SCRIPT_DIR%\plugins\" (
    set "PRODUCT_DIR=%SCRIPT_DIR%"
) else (
    for %%A in ("%SCRIPT_DIR%\..") do set "BASE_DIR=%%~fA"
    set "PRODUCT_DIR=!BASE_DIR!\target\products\%PRODUCT_UID%\win32\win32\x86_64"
)

for %%f in ("!PRODUCT_DIR!\plugins\org.eclipse.osgi_*.jar") do set OSGI_JAR=%%f

if not defined OSGI_JAR (
    echo ERROR: Could not find org.eclipse.osgi jar in: !PRODUCT_DIR!\plugins\
    echo Hint: run "mvn clean verify" from the project root first.
    exit /b 1
)

echo Starting ECF HOST: %OSGI_JAR%
cd /d "!PRODUCT_DIR!"
java %* -jar "%OSGI_JAR%" -configuration configuration -console -consoleLog
