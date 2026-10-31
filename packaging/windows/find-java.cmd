@echo off
rem Sets UO_JAR to the installer next to this file and UO_JAVA to a Java 21 or later, which the capture service needs:
rem WMSJAVA_HOME, else the Java of
rem the WSE install named by WMSAPP_HOME, else of an install under Program Files, else java on the PATH. Each one is
rem asked its version, so the Java 11 or 17 of an older WSE left on the computer is passed over. Exit 8 when either
rem is missing.
set "UO_JAR="
for %%J in ("%~dp0wse-plugin-url-overlay-installer-*.jar") do set "UO_JAR=%%~fJ"
if not defined UO_JAR (
	echo The installer jar is missing from %~dp0
	exit /b 8
)
set "UO_JAVA="
if defined WMSJAVA_HOME call :try "%WMSJAVA_HOME%\bin\java.exe"
if not defined UO_JAVA if defined WMSAPP_HOME call :try "%WMSAPP_HOME%\jre\bin\java.exe"
if not defined UO_JAVA for /d %%D in ("%ProgramFiles%\Wowza Media Systems\Wowza Streaming Engine*") do if not defined UO_JAVA call :try "%%~fD\jre\bin\java.exe"
if not defined UO_JAVA for /f "delims=" %%P in ('where java 2^>nul') do if not defined UO_JAVA call :try "%%P"
if not defined UO_JAVA (
	echo No Java 21 or later found. Set WMSJAVA_HOME to a Java 21 or later and run this again.
	exit /b 8
)
exit /b 0

:try
rem java -version prints, for example: openjdk version "21.0.3" 2024-04-16
if not exist "%~1" exit /b 0
"%~1" -version 2>&1 | findstr /r /c:"version .2[1-9]" /c:"version .[3-9][0-9]" >nul && set "UO_JAVA=%~1"
exit /b 0
