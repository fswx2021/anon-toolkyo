@echo off
setlocal
where javaw.exe >nul 2>nul
if errorlevel 1 (
    where java.exe >nul 2>nul
    if errorlevel 1 (
        echo Java 17 or later is required on PATH.
        pause
        exit /b 1
    )
    java.exe -jar "%~dp0shengjian-reader-workbench.jar"
    exit /b %ERRORLEVEL%
)
start "" javaw.exe -jar "%~dp0shengjian-reader-workbench.jar"
