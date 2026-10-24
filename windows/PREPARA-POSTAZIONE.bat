@echo off
title Prepara la postazione - AutoFillSuite
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepara-postazione.ps1"
echo.
pause
