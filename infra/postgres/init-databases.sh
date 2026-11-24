#!/bin/bash
# Runs once, on the first start of an empty Postgres volume (docker-entrypoint-initdb.d).
# Database-per-service: each service gets its own database, owned by its own login role,
# so a service cannot read or write another service's tables.
# Passwords come from the environment (.env), never from this file.
set -euo pipefail

create_service_db() {
  local db="$1" role="$2" password="$3"
  echo "Creating database ${db} owned by ${role}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v role="$role" -v pw="$password" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'pw';
CREATE DATABASE :"db" OWNER :"role";
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
SQL
}

create_service_db user_db      user_svc      "$USER_DB_PASSWORD"
create_service_db product_db   product_svc   "$PRODUCT_DB_PASSWORD"
create_service_db inventory_db inventory_svc "$INVENTORY_DB_PASSWORD"
create_service_db order_db     order_svc     "$ORDER_DB_PASSWORD"

# ShedLock (used by inventory-service's ReservationCleanupScheduler) needs this table.
# Hibernate's ddl-auto does not create it because it isn't a JPA entity.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname inventory_db <<'SQL'
CREATE TABLE IF NOT EXISTS shedlock (
    name       VARCHAR(64)  NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP    NOT NULL,
    locked_at  TIMESTAMP    NOT NULL,
    locked_by  VARCHAR(255) NOT NULL
);
ALTER TABLE shedlock OWNER TO inventory_svc;
SQL
