-- psql variables are SQL-quoted, not string-concatenated. New databases only.
-- Execute as cluster provisioner; never give these credentials to applications.
-- This script targets a dedicated HomeCare dev/test instance.
REVOKE ALL ON DATABASE postgres FROM PUBLIC;
REVOKE ALL ON DATABASE template1 FROM PUBLIC;
CREATE ROLE :"migrator" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'migrator_password';
CREATE ROLE :"runtime" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'runtime_password';
CREATE DATABASE :"database" OWNER :"migrator";
REVOKE ALL ON DATABASE :"database" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"database" TO :"runtime";
\connect :database
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO :"migrator";
GRANT USAGE ON SCHEMA public TO :"runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator" IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO :"runtime";
-- Liquibase metadata is private to migrator and not covered by business DML grants.
CREATE SCHEMA migration AUTHORIZATION :"migrator";
REVOKE ALL ON SCHEMA migration FROM PUBLIC;
