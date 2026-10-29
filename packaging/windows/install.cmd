@echo off
rem Installs URL Overlay into Wowza Streaming Engine with the Java that WSE ships. Options are passed through;
rem run "install.cmd --help" for them. Windows asks for administrator rights when the install needs them.
setlocal
call "%~dp0find-java.cmd" || exit /b 2
"%UO_JAVA%" -jar "%UO_JAR%" Install %*
set "CODE=%ERRORLEVEL%"
if "%~1"=="" pause
exit /b %CODE%
