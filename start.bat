@echo off
cd /d "%~dp0"
where pyw >nul 2>nul
if %errorlevel% equ 0 (
    start "" pyw -3 "%~dp0main.py"
    exit /b
)
where pythonw >nul 2>nul
if %errorlevel% equ 0 (
    start "" pythonw "%~dp0main.py"
    exit /b
)
echo Python 3.10 or newer with Tkinter is required.
echo Install Python from python.org, then run this launcher again.
pause