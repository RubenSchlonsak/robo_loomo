@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
cd /d C:\Users\rs280\LoomoAgent
call gradlew.bat assembleDebug > C:\Users\rs280\LoomoAgent\build_output.txt 2>&1
echo Exit code: %ERRORLEVEL% >> C:\Users\rs280\LoomoAgent\build_output.txt
