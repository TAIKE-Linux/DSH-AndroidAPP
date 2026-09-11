@echo off
rem DeepSeek Harness remote gateway launcher (Windows).
rem ONE command starts everything: the gateway detects whether DeepSeek
rem Harness (dsh web) is running and starts it automatically if not.
rem Press Ctrl+C here to stop both.
cd /d "%~dp0"
echo [dsh-remote-gateway] starting (will launch dsh web automatically if needed)...
node src\index.js
if errorlevel 1 (
  echo.
  echo Gateway exited with an error. Check the messages above.
  pause
)
