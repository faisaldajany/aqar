#!/bin/bash
# Runtime role: not a superuser and not the table owner, so row-level security applies to it.
set -euo pipefail
psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "create role waslah_app login password '${DB_APP_PASSWORD}' nosuperuser nobypassrls;"
