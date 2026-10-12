@echo off
setlocal
if not "%~1"=="" (
    echo Uso: iniciar-servidor.bat
    exit /b 1
)
pushd "%~dp0.." || exit /b 1
where java >nul 2>&1
if errorlevel 1 (
    echo Erro: Java 17 ou superior precisa estar no PATH.
    popd
    exit /b 1
)
set "JAR=target\quoridor-distribuido-0.1.0-SNAPSHOT.jar"
if not exist "%JAR%" (
    echo Erro: JAR nao encontrado. Execute scripts\compilar.bat primeiro.
    popd
    exit /b 1
)
rem Mantem o servidor neste terminal; o Registry e criado pela propria aplicacao.
java -cp "%JAR%" server.ServerMain
set "RESULTADO=%ERRORLEVEL%"
popd
exit /b %RESULTADO%
