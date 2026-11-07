#!/bin/sh
set -eu

create_database_if_missing() {
    db_name="$1"
    echo "Checking/creating database: $db_name"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
        SELECT 'CREATE DATABASE ' || quote_ident('$db_name')
        WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = '$db_name')\gexec
EOSQL
}

DEFAULT_DATABASES="identity_db household_db provider_db booking_db commerce_db review_db notification_db"
DATABASES="${POSTGRES_MULTIPLE_DATABASES:-$DEFAULT_DATABASES}"

for db in $(echo "$DATABASES" | tr ',' ' '); do
    if [ -n "$db" ]; then
        create_database_if_missing "$db"
    fi
done
