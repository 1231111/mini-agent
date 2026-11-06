#!/usr/bin/env bash
# Generate shared secrets for Account and memory-service.
# Prints to stdout only; do not commit the output.
set -euo pipefail

printf '# Put these values in the server .env file; keep them private.\n'
printf 'ACCOUNT_INTERNAL_KEY=%s\n' "$(openssl rand -hex 32)"
printf 'ACCOUNT_MEMORY_TOKEN_KEY=%s\n' "$(openssl rand -base64 32 | tr -d '\n')"
