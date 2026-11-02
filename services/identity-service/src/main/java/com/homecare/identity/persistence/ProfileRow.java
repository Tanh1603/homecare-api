package com.homecare.identity.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Owner-local persistence metadata; Hibernate validates the SQL-managed schema. */
@Entity @Table(name="user_profile")
public class ProfileRow {
    @Id UUID id;
    @Column(name="display_name", length=120) String displayName;
    @Column(length=32) String phone;
    @Column(length=500) String address;
    @Column(nullable=false, length=16) String status;
    @Column(name="created_at", nullable=false) Instant createdAt;
    @Column(name="updated_at", nullable=false) Instant updatedAt;
    @Version @Column(nullable=false) long version;
}
