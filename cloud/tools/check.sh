#!/usr/bin/env bash
# Applies the migrations to a throwaway Postgres and runs the scenario test, with a stub of the
# parts of Supabase the schema touches (auth.users, auth.uid(), the anon/authenticated roles).
# Needs a reachable Postgres superuser: PGHOST/PGPORT/PGUSER or DATABASE_URL. In CI, a
# postgres service container. Locally: cloud/tools/check.sh after `pg_ctl start`.
set -euo pipefail
cd "$(dirname "$0")/.."
DB=tav_check_$$
PSQL=(psql -v ON_ERROR_STOP=1 -q ${DATABASE_URL:+"$DATABASE_URL"})
"${PSQL[@]}" -c "create database $DB" postgres
trap '"${PSQL[@]}" -c "drop database if exists $DB" postgres' EXIT
run() { "${PSQL[@]}" -d "$DB" "$@"; }
run -f tools/supabase-stub.sql
for m in supabase/migrations/*.sql; do run -f "$m"; done
run -f tools/scenario.sql
echo "[cloud] schema applies and the scenario passes"
