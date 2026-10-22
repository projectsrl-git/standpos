#!/bin/sh
# Creates the StandPOS database roles on first start of an empty PostgreSQL data directory.
# Run by the official postgres image from /docker-entrypoint-initdb.d, as the bootstrap superuser.
#
#   standpos_owner  owns the schema and runs Flyway migrations
#   standpos_app    runtime role: not an owner, no BYPASSRLS, subject to Row-Level Security
#   standpos_auth   NOLOGIN owner of the pre-tenant lookup functions (see docs/adr/0003-pre-tenant-lookups.md)
#
# Grants on tables are part of the Flyway migrations, not of this script.
set -eu

: "${STANDPOS_OWNER_PASSWORD:?STANDPOS_OWNER_PASSWORD must be set}"
: "${STANDPOS_APP_PASSWORD:?STANDPOS_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v owner_password="$STANDPOS_OWNER_PASSWORD" \
  -v app_password="$STANDPOS_APP_PASSWORD" <<'SQL'
CREATE ROLE standpos_owner LOGIN PASSWORD :'owner_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE standpos_app   LOGIN PASSWORD :'app_password'   NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
CREATE ROLE standpos_auth  NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;

-- The owner may create objects as standpos_auth but does not inherit its privileges or policies.
GRANT standpos_auth TO standpos_owner WITH INHERIT FALSE, SET TRUE;

SELECT format('ALTER DATABASE %I OWNER TO standpos_owner', current_database()) \gexec
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT, TEMPORARY ON DATABASE %I TO standpos_app', current_database()) \gexec
SQL
