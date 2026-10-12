@echo off
setlocal
if not "%~2"=="" (
    echo Uso: iniciar-cliente.bat [host-do-registry]
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
rem Sem argumento, ClientMain usa localhost. Execute uma vez em cada terminal de jogador.
if "%~1"=="" (
    java -cp "%JAR%" client.ClientMain
) else (
    java -cp "%JAR%" client.ClientMain "%~1"
)
set "RESULTADO=%ERRORLEVEL%"
popd
exit /b %RESULTADO%
