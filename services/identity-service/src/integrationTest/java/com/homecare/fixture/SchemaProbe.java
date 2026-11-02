package com.homecare.fixture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Explicitly registered by the schema test; outside application entity scanning. */
@Entity
@Table(name = "p2_schema_probe")
public class SchemaProbe {
    @Id Long id;
    @Column(nullable = false, length = 40) String label;
}
