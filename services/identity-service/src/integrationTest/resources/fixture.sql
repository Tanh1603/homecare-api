--liquibase formatted sql
--changeset phase2-test:1
CREATE TABLE p2_schema_probe (id bigint PRIMARY KEY, label varchar(40) NOT NULL);
--rollback DROP TABLE p2_schema_probe;
