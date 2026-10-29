@echo off
rem Removes URL Overlay from Wowza Streaming Engine. Options are passed through; run "uninstall.cmd --help" for them.
rem Stop the Wowza Streaming Engine service first.
setlocal
call "%~dp0find-java.cmd" || exit /b 2
"%UO_JAVA%" -jar "%UO_JAR%" Uninstall %*
set "CODE=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %CODE%
