@echo off
rem Installs a newer Chrome for Testing for the URL Overlay capture service, if there is one, and keeps the previous one.
rem The install runs this daily from the scheduled task WowzaURLOverlayChromeUpdate; run it as administrator to check now.
setlocal
call "%~dp0find-java.cmd" || exit /b 2
"%UO_JAVA%" -jar "%UO_JAR%" UpdateChrome %*
set "CODE=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %CODE%
