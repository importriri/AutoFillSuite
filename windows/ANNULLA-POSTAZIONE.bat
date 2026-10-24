@echo off
title Annulla la preparazione - AutoFillSuite
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0prepara-postazione.ps1" -Undo
echo.
pause
