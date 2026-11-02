@echo off
rem Double-click to install the OVF Server Explorer plugin for KNIME.
powershell -NoProfile -STA -ExecutionPolicy Bypass -File "%~dp0install-ovf.ps1" %*
set OVF_RC=%ERRORLEVEL%
echo.
pause
exit /b %OVF_RC%
