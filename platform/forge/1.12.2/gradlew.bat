@echo off
setlocal
if "%JAVA_HOME%"=="" (
  echo 错误：legacy wrapper 必须显式设置 Java 8 的 JAVA_HOME。 1>&2
  exit /b 1
)
set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not exist "%JAVA_EXE%" (
  echo 错误：JAVA_HOME 无效：%JAVA_HOME% 1>&2
  exit /b 1
)
"%JAVA_EXE%" -Xms64m -Xmx128m -Dorg.gradle.appname=gradlew -classpath "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain -p "%~dp0." %*
exit /b %ERRORLEVEL%
