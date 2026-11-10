#!/bin/sh
# Prepares a local PostgreSQL 16 for the integration tests, the same way CI does:
# the extensions and the user booking_app (as property-infra-postgres creates them),
# then the schema of property-booking-db applied by its Liquibase runner image.
#
#   scripts/test-db.sh        start it (prints the variables for mvn verify)
#   scripts/test-db.sh down   remove the container
#
# BOOKING_DB_DIR is the clone of property-booking-db (default: the sibling folder).
# TEST_DB_PORT is the port on 127.0.0.1 (default 55433). Development only.
set -eu
export MSYS_NO_PATHCONV=1   # Git Bash: keep container paths as written

NAME=booking-api-test-db
PORT=${TEST_DB_PORT:-55433}
DB_DIR=${BOOKING_DB_DIR:-../property-booking-db}

if [ "${1:-up}" = "down" ]; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  exit 0
fi
[ -f "$DB_DIR/changelog/changelog-master.yaml" ] || {
  echo "property-booking-db not found at $DB_DIR (set BOOKING_DB_DIR)" >&2
  exit 1
}
DB_PATH=$(cd "$DB_DIR" && (pwd -W 2>/dev/null || pwd))

docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" -p "127.0.0.1:$PORT:5432" -e POSTGRES_DB=property \
  -e POSTGRES_USER=property_admin -e POSTGRES_PASSWORD=test-only postgres:16 >/dev/null
# Over TCP: the server answers on it only after its first-start initialization.
until docker exec "$NAME" pg_isready -h 127.0.0.1 -U property_admin -d property >/dev/null 2>&1; do
  sleep 1
done

docker exec -i "$NAME" psql -q -U property_admin -d property -v ON_ERROR_STOP=1 <<'SQL'
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;
CREATE ROLE booking_app LOGIN PASSWORD 'test-only';
ALTER ROLE booking_app SET search_path = booking;
SQL

docker run --rm --network "container:$NAME" -v "$DB_PATH:/workspace:ro" liquibase/liquibase:4.29 \
  --show-banner=false --search-path=/workspace --changelog-file=changelog/changelog-master.yaml \
  --url=jdbc:postgresql://localhost:5432/property --username=property_admin --password=test-only \
  --database-changelog-table-name=databasechangelog_booking \
  --database-changelog-lock-table-name=databasechangeloglock_booking update >/dev/null

echo "Ready. Run the tests with:"
echo "  TEST_DATABASE_URL=jdbc:postgresql://localhost:$PORT/property TEST_DATABASE_USER=booking_app TEST_DATABASE_PASSWORD=test-only mvn -B verify"
