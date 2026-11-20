package com.homecare.household.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;

import com.homecare.household.entity.LocationLevel;
import com.homecare.household.repository.HouseholdRepository;
import com.homecare.household.repository.LocationRepository;
import com.homecare.household.repository.UserAddressRepository;
import com.homecare.household.service.AdministrativeLocationImporter.ImportReport;

@SpringBootTest
@ActiveProfiles("test")
class AdministrativeLocationImporterTest {

    @Autowired
    private AdministrativeLocationImporter importer;

    @Autowired
    private LocationRepository locationRepository;

    @Autowired
    private UserAddressRepository userAddressRepository;

    @Autowired
    private HouseholdRepository householdRepository;

    @BeforeEach
    void cleanLocations() {
        householdRepository.deleteAll();
        userAddressRepository.deleteAll();
        locationRepository.deleteAllByLevel(LocationLevel.WARD);
        locationRepository.deleteAllByLevel(LocationLevel.PROVINCE);
    }

    @Test
    void doesNotImportWhenFlagIsAbsent() throws Exception {
        importer.run(new DefaultApplicationArguments());
        assertThat(locationRepository.count()).isZero();
    }

    @Test
    void dryRunDoesNotPersistAndSecondRunIsIdempotent() throws Exception {
        ImportReport dryReport = importer.importFromClasspath(true);
        assertThat(dryReport.dryRun()).isTrue();
        assertThat(dryReport.errors()).isEmpty();
        assertThat(dryReport.inserted()).isEqualTo(34 + 3321);
        assertThat(locationRepository.count()).isZero();

        ImportReport firstImport = importer.importFromClasspath(false);
        assertThat(firstImport.dryRun()).isFalse();
        assertThat(firstImport.errors()).isEmpty();
        assertThat(firstImport.inserted()).isEqualTo(34 + 3321);
        assertThat(locationRepository.count()).isEqualTo(34 + 3321);
        assertThat(locationRepository.findByLevelAndOfficialCode(LocationLevel.PROVINCE, "79")).isPresent();

        ImportReport secondImport = importer.importFromClasspath(false);
        assertThat(secondImport.errors()).isEmpty();
        assertThat(secondImport.inserted()).isZero();
        assertThat(secondImport.updated()).isZero();
        assertThat(secondImport.unchanged()).isEqualTo(34 + 3321);
        assertThat(locationRepository.count()).isEqualTo(34 + 3321);
    }

    @Test
    void rejectsChecksumMismatch() {
        ByteArrayResource badManifest = new ByteArrayResource(
                "{\"validationStatus\":\"PASSED\",\"checksum\":\"deadbeef\"}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> importer.importFromResources(
                new ClassPathResource("data/administrative-units/locations.json"),
                badManifest,
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum mismatch");
        assertThat(locationRepository.count()).isZero();
    }
}
