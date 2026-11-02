package com.homecare.identity.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** Owner-local persistence metadata; Hibernate validates the SQL-managed schema. */
@Entity @Table(name="external_identity")
public class ExternalIdentityRow {
    @Id @Column(name="profile_id") UUID profileId;
    @Column(name="firebase_project_id", nullable=false, length=128) String project;
    @Column(name="firebase_uid", nullable=false, length=128) String uid;
}
