#!/usr/bin/env bash
set -euo pipefail

# Release gate for destructive/schema-changing deployments.  Flyway does not
# provide portable down migrations for this repository; the recoverable unit is
# therefore a verified database backup plus the append-only forward chain.
# This script intentionally fails before the application is started when an
# operator has not recorded a restorable backup.

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

if [[ "${MIGRATION_BACKUP_VERIFIED:-}" != "true" ]]; then
  echo "MIGRATION_BACKUP_VERIFIED=true is required after a restore drill" >&2
  echo "Set MIGRATION_BACKUP_ID to the immutable backup/snapshot identifier." >&2
  exit 2
fi
if [[ -z "${MIGRATION_BACKUP_ID:-}" ]]; then
  echo "MIGRATION_BACKUP_ID is required for the deployment record" >&2
  exit 2
fi

./scripts/verify-migrations.sh

# The source configuration must keep Flyway's metadata lock and prevent an
# accidental clean/out-of-order migration in production.  Check source text so
# this gate also works before a jar has been built.
if ! grep -RiqE 'clean[[:space:]-]*disabled[[:space:]]*:[[:space:]]*true|flyway\.clean-disabled[[:space:]]*=[[:space:]]*true' \
    mini-agent-app/src/main/resources mini-agent-account/src/main/resources \
    mini-agent-memory-service/src/main/resources 2>/dev/null; then
  echo "Flyway clean-disabled=true must be declared in production configuration" >&2
  exit 3
fi
if grep -RiqE 'out[-.]of[-.]order[[:space:]]*:[[:space:]]*true|flyway\.out[-.]of[-.]order[[:space:]]*=\s*true' \
    mini-agent-app/src/main/resources mini-agent-account/src/main/resources \
    mini-agent-memory-service/src/main/resources 2>/dev/null; then
  echo "Flyway out-of-order migrations must remain disabled" >&2
  exit 3
fi

echo "migration preflight passed (backup=$MIGRATION_BACKUP_ID)"
echo "Rollback: stop the release, restore backup $MIGRATION_BACKUP_ID, then rerun verify-migrations.sh."
