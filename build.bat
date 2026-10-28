@echo off
rem Builds dist\Scale_Bar_Calibration.jar
rem Usage: build.bat "C:\path\to\Fiji.app\jars\ij-1.54p.jar"   (or set IJ_JAR)
rem javac and jar come with a JDK; Fiji ships one in Fiji.app\java\win64\...\bin
setlocal
set "IJ=%~1"
if "%IJ%"=="" set "IJ=%IJ_JAR%"
if not exist "%IJ%" (
  echo Give the path to ij.jar, e.g. build.bat "C:\Fiji.app\jars\ij-1.54p.jar"
  exit /b 1
)
if not exist build mkdir build
if not exist dist mkdir dist
javac -encoding UTF-8 --release 8 -cp "%IJ%" -d build src\Scale_Bar_Calibration.java || exit /b 1
copy /y src\plugins.config build\ >nul
pushd build
jar cf ..\dist\Scale_Bar_Calibration.jar .
popd
echo Built dist\Scale_Bar_Calibration.jar - copy it into Fiji.app\plugins\ and restart Fiji.
