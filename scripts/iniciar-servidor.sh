#!/bin/sh
set -eu
if [ "$#" -ne 0 ]; then
    echo "Uso: sh iniciar-servidor.sh" >&2
    exit 1
fi
PROJECT_DIR=$(CDPATH= cd "$(dirname "$0")/.." && pwd)
if ! command -v java >/dev/null 2>&1; then
    echo "Erro: Java 17 ou superior precisa estar no PATH." >&2
    exit 1
fi
JAR="$PROJECT_DIR/target/quoridor-distribuido-0.1.0-SNAPSHOT.jar"
if [ ! -f "$JAR" ]; then
    echo "Erro: JAR não encontrado. Execute sh scripts/compilar.sh primeiro." >&2
    exit 1
fi
# Mantém o servidor neste terminal; o Registry é criado pela própria aplicação.
exec java -cp "$JAR" server.ServerMain
