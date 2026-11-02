@echo off
rem This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
rem This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
rem Installs a newer Chrome for Testing for the Overlay Studio capture service, if there is one, and keeps the previous one.
rem The install runs this daily from the scheduled task WowzaOverlayStudioChromeUpdate; run it as administrator to check now.
setlocal
call "%~dp0find-java.cmd" || exit /b 8
"%OVS_JAVA%" -jar "%OVS_JAR%" UpdateChrome %*
set "CODE=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %CODE%
