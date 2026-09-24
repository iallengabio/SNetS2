#!/bin/bash
set -e

# Detect maven executable
if command -v mvn &> /dev/null; then
    MVN_CMD="mvn"
elif command -v mise &> /dev/null; then
    MVN_CMD="mise exec -- mvn"
elif [ -d "$HOME/.local/share/mise/installs/maven" ]; then
    MVN_BIN=$(find "$HOME/.local/share/mise/installs/maven" -name mvn -type f 2>/dev/null | head -n 1)
    if [ -n "$MVN_BIN" ]; then
        MVN_CMD="$MVN_BIN"
    fi
fi

if [ -z "$MVN_CMD" ]; then
    echo "Erro: Apache Maven (mvn) não foi encontrado no PATH."
    exit 1
fi

echo "=========================================="
echo "  Construindo SNetS2 (Clean & Package)    "
echo "=========================================="

$MVN_CMD clean package "$@"

echo ""
echo "=========================================="
echo "  Build concluído com sucesso!"
echo "  JAR Executável: target/SNetS2-1.0-SNAPSHOT.jar"
echo "=========================================="
