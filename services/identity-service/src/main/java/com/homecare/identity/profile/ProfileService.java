package com.homecare.identity.profile;

import com.homecare.identity.security.VerifiedSubject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class ProfileService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SELECT = "SELECT p.*, r.role FROM user_profile p JOIN profile_role r ON r.profile_id=p.id "
            + "JOIN external_identity e ON e.profile_id=p.id WHERE e.firebase_project_id=? AND e.firebase_uid=?";
    public ProfileService(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    @Transactional(readOnly = true)
    public Profile me(VerifiedSubject subject) { return require(subject, false); }

    @Transactional(readOnly = true)
    public Profile resolve(VerifiedSubject subject) { return require(subject, true); }

    private Profile require(VerifiedSubject subject, boolean resolve) {
        Profile profile = find(subject);
        if (profile == null) throw new ProfileFailure(resolve ? 403 : 404, resolve ? "PROFILE_REQUIRED" : "PROFILE_NOT_FOUND");
        active(profile);
        return profile;
    }
    private Profile find(VerifiedSubject subject) {
        var rows = jdbc.query(SELECT, (rs, n) -> map(rs), subject.project(), subject.uid());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void active(Profile profile) {
        if (profile.status() != Profile.Status.ACTIVE) throw new ProfileFailure(403, "ACCOUNT_DISABLED");
    }

    public record Bootstrap(Profile profile, int status) {}
    @Transactional
    public Bootstrap bootstrap(VerifiedSubject subject, String key, EmptyRequest body) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{16,128}") || body == null)
            throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED");
        String scope = hash(subject.project() + "\u0000" + subject.uid() + "\u0000POST\u0000/api/v1/users/me/bootstrap\u0000" + key);
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))", Boolean.class, scope);
        if (!Boolean.TRUE.equals(locked)) throw new ProfileFailure(409, "IDEMPOTENCY_IN_PROGRESS");
        // Serialize distinct keys for the same identity; no remote calls occur inside this transaction.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 1))", Object.class,
                hash(subject.project() + "\u0000" + subject.uid()));
        Profile existing = find(subject);
        if (existing != null) active(existing); // reauthorize before replay
        jdbc.update("DELETE FROM identity_idempotency WHERE scope_hash=? AND expires_at<=CURRENT_TIMESTAMP", scope);
        var replay = jdbc.query("SELECT response_body,response_status FROM identity_idempotency WHERE scope_hash=?",
                (rs, n) -> new Bootstrap(json.readValue(rs.getString(1), Profile.class), rs.getInt(2)), scope);
        if (!replay.isEmpty()) return replay.getFirst();
        int status = 200;
        if (existing == null) {
            UUID id = uuidV7();
            jdbc.update("INSERT INTO user_profile(id,status,created_at,updated_at,version) VALUES (?,'ACTIVE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)", id);
            jdbc.update("INSERT INTO external_identity(profile_id,firebase_project_id,firebase_uid) VALUES (?,?,?)", id, subject.project(), subject.uid());
            jdbc.update("INSERT INTO profile_role(profile_id,role) VALUES (?,'HOUSEHOLD_USER')", id);
            existing = require(subject, false); status = 201;
        }
        jdbc.update("INSERT INTO identity_idempotency(scope_hash,profile_id,response_body,response_status,committed_at,expires_at) "
                + "VALUES (?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP + INTERVAL '24 hours')", scope, existing.id(), json.writeValueAsString(existing), status);
        return new Bootstrap(existing, status);
    }

    @Transactional
    public Profile patch(VerifiedSubject subject, String tag, ProfilePatch patch) {
        Profile current = require(subject, false);
        if (tag == null) throw new ProfileFailure(428, "PRECONDITION_REQUIRED");
        if (!tag.matches("\"v(0|[1-9][0-9]*)\"")) throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED");
        long version;
        try { version = Long.parseLong(tag.substring(2, tag.length() - 1)); }
        catch (NumberFormatException e) { throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED"); }
        JsonNode body = patch.fields();
        String name = field(body, "displayName", current.displayName(), 120);
        String phone = field(body, "phone", current.phone(), 32);
        String address = field(body, "address", current.address(), 500);
        if ((name != null && name.isBlank()) || (phone != null && !phone.matches("[+0-9 ()-]+")))
            throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED");
        int updated = jdbc.update("UPDATE user_profile SET display_name=?,phone=?,address=?,updated_at=CURRENT_TIMESTAMP,version=version+1 "
                + "WHERE id=? AND version=? AND status='ACTIVE'", name, phone, address, current.id(), version);
        if (updated == 0) {
            require(subject, false);
            throw new ProfileFailure(412, "RESOURCE_VERSION_MISMATCH");
        }
        return require(subject, false);
    }
    private String field(JsonNode body, String name, String old, int max) {
        if (!body.has(name)) return old;
        var value = body.get(name);
        if (value.isNull()) return null;
        if (!value.isTextual()) throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED");
        String text = value.asText().trim();
        if (text.length() > max) throw new ProfileFailure(400, "REQUEST_VALIDATION_FAILED");
        return text;
    }
    private Profile map(ResultSet rs) throws SQLException {
        return new Profile(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getString("phone"), rs.getString("address"),
                Profile.Role.valueOf(rs.getString("role")), Profile.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private UUID uuidV7() {
        return new UUID((System.currentTimeMillis() << 16) | 0x7000L | (RANDOM.nextLong() & 0xfffL),
                (RANDOM.nextLong() & 0x3fff_ffff_ffff_ffffL) | 0x8000_0000_0000_0000L);
    }
}
