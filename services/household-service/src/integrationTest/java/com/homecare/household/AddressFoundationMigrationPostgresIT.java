package com.homecare.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.homecare.household.entity.LocationLevel;
import com.homecare.household.repository.LocationRepository;
import com.homecare.household.service.AdministrativeLocationImporter;
import com.homecare.household.service.AdministrativeLocationImporter.ImportReport;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "homecare.locations.auto-seed-if-empty=false"
})
class AddressFoundationMigrationPostgresIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    private AdministrativeLocationImporter importer;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void flywayTriggerCoordinatesConstraintUniqueDefaultAndImporterWorkOnPostgres() throws Exception {
        // 1. Importer dry-run + real import + idempotent re-run
        ImportReport dryRun = importer.importFromClasspath(true);
        assertThat(dryRun.errors()).isEmpty();
        assertThat(dryRun.inserted()).isEqualTo(34 + 3321);
        assertThat(locationRepository.count()).isZero();

        ImportReport firstRun = importer.importFromClasspath(false);
        assertThat(firstRun.errors()).isEmpty();
        assertThat(firstRun.inserted()).isEqualTo(34 + 3321);
        assertThat(locationRepository.count()).isEqualTo(34 + 3321);

        ImportReport secondRun = importer.importFromClasspath(false);
        assertThat(secondRun.errors()).isEmpty();
        assertThat(secondRun.inserted()).isZero();
        assertThat(secondRun.updated()).isZero();
        assertThat(secondRun.unchanged()).isEqualTo(34 + 3321);

        // 2. DB trigger: reject WARD -> WARD and reject downgrading PROVINCE with child WARDs
        Integer hcmId = locationRepository.findByLevelAndOfficialCode(LocationLevel.PROVINCE, "79")
                .orElseThrow().getId();
        Integer wardId = jdbcTemplate.queryForObject(
                "SELECT id FROM locations WHERE parent_id = ? LIMIT 1", Integer.class, hcmId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO locations (official_code, name, level, codename, division_type, sort_order, parent_id) "
                        + "VALUES ('99999', 'Bad Ward', 'WARD', 'bad_ward', 'phường', 1, ?)",
                wardId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("WARD location must reference a PROVINCE parent");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE locations SET level = 'WARD', parent_id = ? WHERE id = ?",
                hcmId, hcmId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Cannot downgrade PROVINCE");

        // 3. GPS check constraint: reject single-null coordinates
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO user_addresses (id, owner_profile_id, label, recipient_name, phone_number, address_line, location_id, latitude, longitude) "
                        + "VALUES (?, 'user-1', 'Home', 'An', '0900000001', '123 Le Loi', ?, 10.77, NULL)",
                UUID.randomUUID(), wardId))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 4. Unique partial index: at most 1 active default per owner
        jdbcTemplate.update(
                "INSERT INTO user_addresses (id, owner_profile_id, label, recipient_name, phone_number, address_line, location_id, is_default_shipping, is_active) "
                        + "VALUES (?, 'user-1', 'Home', 'An', '0900000001', '123 Le Loi', ?, TRUE, TRUE)",
                UUID.randomUUID(), wardId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO user_addresses (id, owner_profile_id, label, recipient_name, phone_number, address_line, location_id, is_default_shipping, is_active) "
                        + "VALUES (?, 'user-1', 'Office', 'An', '0900000001', '456 Nguyen Hue', ?, TRUE, TRUE)",
                UUID.randomUUID(), wardId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
