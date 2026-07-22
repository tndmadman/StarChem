#!/usr/bin/env sh
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR"

PORT=${STARCHEM_PORT:-50000}
SERVER_NAME=${STARCHEM_SERVER_NAME:-StarChem-Server}

if [ -n "${STARCHEM_DEV_TOKEN_FILE:-}" ]; then
    exec "${JAVA_BIN:-java}" -Djava.awt.headless=true -jar StarChem.jar \
        --server "$PORT" \
        --name "$SERVER_NAME" \
        --dev-token-file "$STARCHEM_DEV_TOKEN_FILE" \
        "$@"
fi

exec "${JAVA_BIN:-java}" -Djava.awt.headless=true -jar StarChem.jar \
    --server "$PORT" \
    --name "$SERVER_NAME" \
    "$@"
