@echo off
cd /d "%~dp0"
set JAVA_HOME=D:\jdk-21.0.11.10-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%
mvn -pl desktop-host exec:exec
