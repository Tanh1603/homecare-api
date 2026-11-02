package com.homecare.identity.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Owner-local persistence metadata; Hibernate validates the SQL-managed schema. */
@Entity @Table(name="profile_role")
public class ProfileRoleRow {
    @Id @Column(name="profile_id") UUID profileId;
    @Column(nullable=false, length=32) String role;
}
