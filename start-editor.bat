@echo off
setlocal

rem Double-click this file to open the local Piano Show Web editor.
set "PROJECT_ROOT=%~dp0"
set "PYTHON_EXE="

rem Prefer the project environment documented in environment-report.txt.
if exist "D:\Dev\PythonEnvs\piano-show\Scripts\python.exe" set "PYTHON_EXE=D:\Dev\PythonEnvs\piano-show\Scripts\python.exe"
if not defined PYTHON_EXE if exist "%PROJECT_ROOT%editor\.venv\Scripts\python.exe" set "PYTHON_EXE=%PROJECT_ROOT%editor\.venv\Scripts\python.exe"
if not defined PYTHON_EXE (
    where py >nul 2>nul
    if not errorlevel 1 set "PYTHON_EXE=py -3"
)
if not defined PYTHON_EXE (
    where python >nul 2>nul
    if not errorlevel 1 set "PYTHON_EXE=python"
)

if not defined PYTHON_EXE (
    echo Python 3.11 or newer was not found.
    echo Install the editor environment or run: python -m pip install -e "%PROJECT_ROOT%editor"
    pause
    exit /b 1
)

pushd "%PROJECT_ROOT%editor"
%PYTHON_EXE% -m piano_show editor %*
set "EXIT_CODE=%ERRORLEVEL%"
popd

if not "%EXIT_CODE%"=="0" (
    echo.
    echo The Web editor stopped with exit code %EXIT_CODE%.
    pause
)
exit /b %EXIT_CODE%
