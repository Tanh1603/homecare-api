package com.homecare.live;

import com.google.firebase.auth.FirebaseAuth;
import java.security.SecureRandom;
import java.util.*;

/** Explicit operator insert; no public grant endpoint, no production packaging. */
public final class MapFirebaseUser {
    public static void main(String[] args) throws Exception {
        try { insert(); }
        catch (Exception failure) { throw new IllegalStateException("Mapping failed; no role upsert (sensitive details suppressed)"); }
    }
    static void insert() throws Exception {
        String role = LiveSupport.env("MAP_ROLE");
        if (!Set.of("HOUSEHOLD_USER", "SERVICE_PROVIDER", "TECHNICIAN", "MERCHANT", "SYSTEM_ADMIN").contains(role))
            throw new IllegalArgumentException("Invalid role enum");
        var app = LiveSupport.admin(true);
        try {
            var user = FirebaseAuth.getInstance(app).getUser(LiveSupport.env("MAP_FIREBASE_UID"));
            if (user.isDisabled()) throw new IllegalStateException("Firebase user disabled");
            // UUIDv7, same representation as the approved profile contract.
            var random = new SecureRandom();
            var id = new UUID((System.currentTimeMillis() << 16) | 0x7000L | random.nextInt(4096),
                    (random.nextLong() & 0x3fffffffffffffffL) | 0x8000000000000000L);
            try (var db = LiveSupport.database(true)) {
                db.setAutoCommit(false);
                try {
                    try (var statement = db.prepareStatement("INSERT INTO user_profile(id,status,created_at,updated_at,version) VALUES (?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)")) {
                        statement.setObject(1,id); statement.executeUpdate();
                    }
                    try (var statement = db.prepareStatement("INSERT INTO external_identity(profile_id,firebase_project_id,firebase_uid) VALUES (?,?,?)")) {
                        statement.setObject(1,id); statement.setString(2,LiveSupport.env("FIREBASE_PROJECT_ID")); statement.setString(3,user.getUid()); statement.executeUpdate();
                    }
                    try (var statement = db.prepareStatement("INSERT INTO profile_role(profile_id,role) VALUES (?,?)")) {
                        statement.setObject(1,id); statement.setString(2,role); statement.executeUpdate();
                    }
                    db.commit(); System.out.println("Mapping inserted; identity values suppressed.");
                } catch (Exception failure) { db.rollback(); throw failure; }
            }
        } finally { app.delete(); }
    }
}
