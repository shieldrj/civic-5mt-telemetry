@echo off
echo Connecting to phone wirelessly...
powershell -ExecutionPolicy Bypass -File "%~dp0connect-phone.ps1"
pause

