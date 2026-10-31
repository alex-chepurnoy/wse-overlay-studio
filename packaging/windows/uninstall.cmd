@echo off
rem This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
rem This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
rem Removes URL Overlay from Wowza Streaming Engine. Options are passed through; run "uninstall.cmd --help" for them.
rem Stop the Wowza Streaming Engine service first.
setlocal
call "%~dp0find-java.cmd" || exit /b 8
"%UO_JAVA%" -jar "%UO_JAR%" Uninstall %*
set "CODE=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %CODE%
