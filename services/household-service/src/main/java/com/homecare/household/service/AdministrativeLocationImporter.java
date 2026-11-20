package com.homecare.household.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.household.config.LocationProperties;
import com.homecare.household.entity.Location;
import com.homecare.household.entity.LocationLevel;
import com.homecare.household.repository.LocationRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AdministrativeLocationImporter implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdministrativeLocationImporter.class);
    private static final String DEFAULT_LOCATIONS_PATH = "data/administrative-units/locations.json";
    private static final String DEFAULT_MANIFEST_PATH = "data/administrative-units/manifest.json";

    private final LocationRepository locationRepository;
    private final ObjectMapper objectMapper;
    private final LocationProperties locationProperties;

    public record ProvinceInput(
            Integer code,
            String officialCode,
            String name,
            String codename,
            String divisionType,
            Integer phoneCode) {
    }

    public record WardInput(
            Integer code,
            String officialCode,
            String name,
            String codename,
            String divisionType,
            Integer provinceCode) {
    }

    public record NormalizedDataset(
            String apiVersion,
            String sourceUrl,
            String retrievedAt,
            List<ProvinceInput> provinces,
            List<WardInput> wards) {
    }

    public record ImportManifest(
            String sourceUrl,
            String apiVersion,
            String retrievedAt,
            Integer provinceCount,
            Integer wardCount,
            String checksum,
            String validationStatus) {
    }

    public record ImportReport(
            boolean dryRun,
            int inserted,
            int updated,
            int unchanged,
            List<String> errors) {
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean explicitImport = args.containsOption("import-administrative-locations");
        if (!explicitImport) {
            if (!locationProperties.isAutoSeedIfEmpty() || locationRepository.count() > 0) {
                return;
            }
            log.info("Locations table is empty; auto-seeding administrative units from classpath.");
        }
        boolean dryRun = explicitImport && args.containsOption("dry-run");
        ImportReport report = importFromClasspath(dryRun);
        log.info("Administrative location import completed (dryRun={}): inserted={}, updated={}, unchanged={}, errors={}",
                report.dryRun(), report.inserted(), report.updated(), report.unchanged(), report.errors().size());
        if (!report.errors().isEmpty()) {
            throw new IllegalStateException("Administrative location import failed: " + String.join("; ", report.errors()));
        }
    }

    @Transactional
    public ImportReport importFromClasspath(boolean dryRun) throws IOException {
        return importFromResources(
                new ClassPathResource(DEFAULT_LOCATIONS_PATH),
                new ClassPathResource(DEFAULT_MANIFEST_PATH),
                dryRun);
    }

    @Transactional
    public ImportReport importFromResources(Resource locationsResource, Resource manifestResource, boolean dryRun)
            throws IOException {
        byte[] locationsBytes = locationsResource.getInputStream().readAllBytes();
        byte[] manifestBytes = manifestResource.getInputStream().readAllBytes();

        ImportManifest manifest = objectMapper.readValue(manifestBytes, ImportManifest.class);
        if (!"PASSED".equalsIgnoreCase(manifest.validationStatus())) {
            throw new IllegalStateException("Manifest validationStatus is not PASSED: " + manifest.validationStatus());
        }
        String actualSha256 = sha256Hex(locationsBytes);
        if (manifest.checksum() == null || !manifest.checksum().equalsIgnoreCase(actualSha256)) {
            throw new IllegalStateException(
                    "Locations checksum mismatch! expected=" + manifest.checksum() + ", actual=" + actualSha256);
        }

        NormalizedDataset dataset = objectMapper.readValue(
                new String(locationsBytes, StandardCharsets.UTF_8),
                NormalizedDataset.class);

        return importDataset(dataset, dryRun);
    }

    @Transactional
    public ImportReport importDataset(NormalizedDataset dataset, boolean dryRun) {
        List<String> errors = validateDataset(dataset);
        if (!errors.isEmpty()) {
            return new ImportReport(dryRun, 0, 0, 0, errors);
        }

        int inserted = 0;
        int updated = 0;
        int unchanged = 0;
        Instant now = Instant.now();

        Map<String, Integer> provinceIdByCode = new HashMap<>();
        int sortOrder = 0;
        for (ProvinceInput p : dataset.provinces()) {
            sortOrder++;
            String officialCode = resolveOfficialCode(p.officialCode(), p.code());
            Location existing = locationRepository
                    .findByLevelAndOfficialCode(LocationLevel.PROVINCE, officialCode)
                    .orElse(null);

            if (existing == null) {
                inserted++;
                if (!dryRun) {
                    Location saved = locationRepository.save(Location.builder()
                            .officialCode(officialCode)
                            .name(p.name().trim())
                            .level(LocationLevel.PROVINCE)
                            .codename(safeTrim(p.codename()))
                            .divisionType(p.divisionType().trim())
                            .phoneCode(p.phoneCode())
                            .sortOrder(sortOrder)
                            .parentId(null)
                            .active(true)
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                    provinceIdByCode.put(officialCode, saved.getId());
                }
            } else {
                if (existing.getParentId() != null) {
                    errors.add("Existing province " + officialCode + " has non-null parentId");
                    continue;
                }
                boolean changed = updateIfChanged(existing, p.name().trim(), safeTrim(p.codename()),
                        p.divisionType().trim(), p.phoneCode(), sortOrder, now);
                if (changed) {
                    updated++;
                    if (!dryRun) {
                        locationRepository.save(existing);
                    }
                } else {
                    unchanged++;
                }
                provinceIdByCode.put(officialCode, existing.getId());
            }
        }

        int wardSort = 0;
        for (WardInput w : dataset.wards()) {
            wardSort++;
            String officialCode = resolveOfficialCode(w.officialCode(), w.code());
            String parentOfficialCode = String.valueOf(w.provinceCode());
            Integer parentId = provinceIdByCode.get(parentOfficialCode);

            if (parentId == null) {
                if (dryRun) {
                    // In dry-run, a brand-new province wasn't persisted; count ward as inserted.
                    inserted++;
                    continue;
                }
                errors.add("Missing persisted parent province for ward " + officialCode + " (provinceCode=" + parentOfficialCode + ")");
                continue;
            }

            Location existing = locationRepository.findByParentIdAndOfficialCode(parentId, officialCode).orElse(null);
            if (existing == null) {
                inserted++;
                if (!dryRun) {
                    locationRepository.save(Location.builder()
                            .officialCode(officialCode)
                            .name(w.name().trim())
                            .level(LocationLevel.WARD)
                            .codename(safeTrim(w.codename()))
                            .divisionType(w.divisionType().trim())
                            .phoneCode(null)
                            .sortOrder(wardSort)
                            .parentId(parentId)
                            .active(true)
                            .createdAt(now)
                            .updatedAt(now)
                            .build());
                }
            } else {
                if (existing.getLevel() != LocationLevel.WARD) {
                    errors.add("Existing ward " + officialCode + " has non-WARD level=" + existing.getLevel());
                    continue;
                }
                boolean changed = updateIfChanged(existing, w.name().trim(), safeTrim(w.codename()),
                        w.divisionType().trim(), null, wardSort, now);
                if (changed) {
                    updated++;
                    if (!dryRun) {
                        locationRepository.save(existing);
                    }
                } else {
                    unchanged++;
                }
            }
        }

        return new ImportReport(dryRun, inserted, updated, unchanged, errors);
    }

    private List<String> validateDataset(NormalizedDataset dataset) {
        List<String> errors = new ArrayList<>();
        if (dataset == null || dataset.provinces() == null || dataset.wards() == null) {
            errors.add("Dataset, provinces, and wards must not be null");
            return errors;
        }
        Set<String> provinceCodes = new HashSet<>();
        for (ProvinceInput p : dataset.provinces()) {
            String code = resolveOfficialCode(p.officialCode(), p.code());
            if (code.isBlank() || isBlank(p.name()) || isBlank(p.divisionType())) {
                errors.add("Invalid province record: " + p);
                continue;
            }
            if (!provinceCodes.add(code)) {
                errors.add("Duplicate province officialCode: " + code);
            }
        }
        Set<String> wardKeys = new HashSet<>();
        for (WardInput w : dataset.wards()) {
            String code = resolveOfficialCode(w.officialCode(), w.code());
            if (code.isBlank() || isBlank(w.name()) || isBlank(w.divisionType()) || w.provinceCode() == null) {
                errors.add("Invalid ward record: " + w);
                continue;
            }
            String parentCode = String.valueOf(w.provinceCode());
            if (!provinceCodes.contains(parentCode)) {
                errors.add("Orphan ward " + code + " references unknown provinceCode=" + parentCode);
                continue;
            }
            if (!wardKeys.add(parentCode + ":" + code)) {
                errors.add("Duplicate ward code " + code + " in province " + parentCode);
            }
        }
        return errors;
    }

    private boolean updateIfChanged(
            Location existing,
            String name,
            String codename,
            String divisionType,
            Integer phoneCode,
            int sortOrder,
            Instant now) {
        boolean changed = !Objects.equals(existing.getName(), name)
                || !Objects.equals(existing.getCodename(), codename)
                || !Objects.equals(existing.getDivisionType(), divisionType)
                || !Objects.equals(existing.getPhoneCode(), phoneCode)
                || existing.getSortOrder() != sortOrder
                || !existing.isActive();
        if (changed) {
            existing.setName(name);
            existing.setCodename(codename);
            existing.setDivisionType(divisionType);
            existing.setPhoneCode(phoneCode);
            existing.setSortOrder(sortOrder);
            existing.setActive(true);
            existing.setUpdatedAt(now);
        }
        return changed;
    }

    private static String resolveOfficialCode(String officialCode, Integer code) {
        if (officialCode != null && !officialCode.isBlank()) {
            return officialCode.trim();
        }
        return code != null ? String.valueOf(code) : "";
    }

    private static String safeTrim(String val) {
        return val == null ? "" : val.trim();
    }

    private static boolean isBlank(String val) {
        return val == null || val.isBlank();
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
