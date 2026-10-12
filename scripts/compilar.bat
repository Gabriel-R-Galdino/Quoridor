@echo off
setlocal
rem Localiza a raiz mesmo quando este script e chamado de outra pasta.
pushd "%~dp0.." || exit /b 1
where javac >nul 2>&1
if errorlevel 1 (
    echo Erro: instale um JDK 17 ou superior e adicione javac ao PATH.
    popd
    exit /b 1
)
where mvn >nul 2>&1
if errorlevel 1 (
    echo Erro: instale o Maven e adicione mvn ao PATH.
    popd
    exit /b 1
)
rem package compila, executa os testes e gera o JAR. Argumentos extras vao ao Maven.
rem Deixe a porta 1099 livre: o teste de integracao inicia cinco processos Java.
call mvn package %*
set "RESULTADO=%ERRORLEVEL%"
popd
exit /b %RESULTADO%
