#!/usr/bin/env bash
set -euo pipefail
umask 077

fail() { echo "BACKUP/RESTORE CERTIFICATION FAILED: $*" >&2; exit 1; }

: "${PGHOST:?PGHOST must point to the PostgreSQL host}"
: "${PGDATABASE:?PGDATABASE must name the source database}"
: "${PGUSER:?PGUSER must name a role allowed to dump and create databases}"
: "${BACKUP_FILE:?BACKUP_FILE must be an absolute or relative destination for the verified dump}"

for tool in pg_dump pg_restore psql createdb dropdb sha256sum; do
  command -v "$tool" >/dev/null 2>&1 || fail "Required PostgreSQL client tool '$tool' is not installed"
done

[[ "$PGDATABASE" =~ ^[a-zA-Z0-9_][a-zA-Z0-9_-]*$ ]] || fail "PGDATABASE contains unsupported characters"
backup_dir="$(dirname "$BACKUP_FILE")"
mkdir -p "$backup_dir"
[[ ! -e "$BACKUP_FILE" ]] || fail "Refusing to overwrite existing backup: $BACKUP_FILE"

tmp_dir="$(mktemp -d)"
restore_db="cricpulse_restore_verify_$$"
restore_created=false
cleanup() {
  if [[ "$restore_created" == "true" ]]; then
    dropdb --if-exists --force --maintenance-db=postgres "$restore_db" >/dev/null 2>&1 || true
  fi
  rm -rf "$tmp_dir"
}
trap cleanup EXIT

echo "[1/6] Check source database connectivity"
psql -X -v ON_ERROR_STOP=1 -d "$PGDATABASE" -Atc 'SELECT 1' >/dev/null || fail "Cannot connect to source database"

echo "[2/6] Capture source table inventory"
psql -X -v ON_ERROR_STOP=1 -d "$PGDATABASE" -Atc \
  "SELECT format('%I.%I', schemaname, tablename) FROM pg_tables WHERE schemaname = 'public' ORDER BY 1" \
  > "$tmp_dir/source-tables.txt"
[[ -s "$tmp_dir/source-tables.txt" ]] || fail "Source database has no public tables; refusing to certify an empty schema"

echo "[3/6] Create a compressed custom-format PostgreSQL dump"
pg_dump --format=custom --no-owner --no-acl --file="$BACKUP_FILE" --dbname="$PGDATABASE"
[[ -s "$BACKUP_FILE" ]] || fail "pg_dump produced an empty backup"
pg_restore --list "$BACKUP_FILE" > "$tmp_dir/archive-list.txt"
grep -q 'TABLE DATA' "$tmp_dir/archive-list.txt" || fail "Backup archive contains no table-data entries"
sha256sum "$BACKUP_FILE"

echo "[4/6] Create an isolated temporary restore database"
createdb --maintenance-db=postgres --template=template0 "$restore_db"
restore_created=true

echo "[5/6] Restore the archive and verify restored schema/data are readable"
pg_restore --exit-on-error --no-owner --no-acl --dbname="$restore_db" "$BACKUP_FILE"
psql -X -v ON_ERROR_STOP=1 -d "$restore_db" -Atc \
  "SELECT format('%I.%I', schemaname, tablename) FROM pg_tables WHERE schemaname = 'public' ORDER BY 1" \
  > "$tmp_dir/restored-tables.txt"
diff -u "$tmp_dir/source-tables.txt" "$tmp_dir/restored-tables.txt" || fail "Restored public table inventory differs from the source"

: > "$tmp_dir/restored-row-counts.txt"
while IFS= read -r qualified_table; do
  row_count="$(psql -X -v ON_ERROR_STOP=1 -d "$restore_db" -Atc "SELECT count(*) FROM $qualified_table")"
  [[ "$row_count" =~ ^[0-9]+$ ]] || fail "Could not verify restored row count for $qualified_table"
  printf '%s|%s\n' "$qualified_table" "$row_count" >> "$tmp_dir/restored-row-counts.txt"
done < "$tmp_dir/restored-tables.txt"

echo "[6/6] Report certification result"
echo "Verified $(wc -l < "$tmp_dir/restored-tables.txt" | tr -d ' ') public tables in the restored database."
echo "Verified row counts:"
cat "$tmp_dir/restored-row-counts.txt"
echo "PASS: backup archive is readable and restores successfully into an isolated database."
echo "Backup file: $BACKUP_FILE"
