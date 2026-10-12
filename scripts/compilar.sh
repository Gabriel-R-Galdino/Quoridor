#!/bin/sh
set -eu
# Localiza a raiz mesmo quando o script é chamado de outra pasta.
PROJECT_DIR=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
cd "$PROJECT_DIR"
if ! command -v javac >/dev/null 2>&1; then
    echo "Erro: instale um JDK 17 ou superior e adicione javac ao PATH." >&2
    exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
    echo "Erro: instale o Maven e adicione mvn ao PATH." >&2
    exit 1
fi
# package compila, executa os testes e gera o JAR; argumentos extras vão ao Maven.
# Deixe a porta 1099 livre: o teste de integração inicia cinco processos Java.
exec mvn package "$@"
