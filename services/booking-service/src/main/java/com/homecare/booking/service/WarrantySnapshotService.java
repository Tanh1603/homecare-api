package com.homecare.booking.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.booking.entity.BookingEntity;
import com.homecare.booking.entity.BookingPartsUsageEntity;
import com.homecare.booking.entity.QuotationEntity;
import com.homecare.booking.entity.QuotationItemEntity;
import com.homecare.booking.entity.WarrantyActivationSnapshotEntity;
import com.homecare.booking.entity.WarrantyCommitmentSnapshotEntity;
import com.homecare.booking.repository.BookingPartsUsageRepository;
import com.homecare.booking.repository.QuotationItemRepository;
import com.homecare.booking.repository.QuotationRepository;
import com.homecare.booking.repository.WarrantyActivationSnapshotRepository;
import com.homecare.booking.repository.WarrantyCommitmentSnapshotRepository;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;

import lombok.RequiredArgsConstructor;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Dual Immutable Warranty Snapshots & Asia/Ho_Chi_Minh calendar-day millisecond math (D-12, D-24, D-30).
 */
@Service
@RequiredArgsConstructor
public class WarrantySnapshotService {

    public static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    public static final int REPAIR_MIN_DAYS = 7;
    public static final int REPAIR_DEFAULT_DAYS = 30;
    public static final int REPAIR_MAX_DAYS = 90;
    public static final int MAINTENANCE_MIN_DAYS = 0;
    public static final int MAINTENANCE_DEFAULT_DAYS = 14;
    public static final int MAINTENANCE_MAX_DAYS = 30;
    public static final long POST_ACCEPT_DISPUTE_BASE_DAYS = 30L;

    private final WarrantyCommitmentSnapshotRepository commitmentRepository;
    private final WarrantyActivationSnapshotRepository activationRepository;
    private final BookingPartsUsageRepository partsUsageRepository;
    private final QuotationRepository quotationRepository;
    private final QuotationItemRepository quotationItemRepository;
    private final ObjectMapper objectMapper;

    public static boolean isMaintenanceOrCleaningCategory(String serviceCategory) {
        if (serviceCategory == null || serviceCategory.isBlank()) {
            return false;
        }
        String upper = serviceCategory.trim().toUpperCase();
        return upper.contains("CLEAN") || upper.contains("MAINT");
    }

    public static int defaultWorkmanshipDays(String serviceCategory) {
        return isMaintenanceOrCleaningCategory(serviceCategory) ? MAINTENANCE_DEFAULT_DAYS : REPAIR_DEFAULT_DAYS;
    }

    public static void validateWorkmanshipDuration(String serviceCategory, int durationDays) {
        boolean maintenance = isMaintenanceOrCleaningCategory(serviceCategory);
        int min = maintenance ? MAINTENANCE_MIN_DAYS : REPAIR_MIN_DAYS;
        int max = maintenance ? MAINTENANCE_MAX_DAYS : REPAIR_MAX_DAYS;
        if (durationDays < min || durationDays > max) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "workmanshipDurationDays (" + durationDays + ") must be within Platform Hard Cap ["
                            + min + ", " + max + "] for category " + (serviceCategory == null ? "REPAIR" : serviceCategory) + " (BR-WARR-04).");
        }
    }

    public static Instant computeStartsAt(Instant acceptedAt, int workmanshipDurationDays) {
        if (workmanshipDurationDays <= 0 || acceptedAt == null) {
            return null;
        }
        return acceptedAt.truncatedTo(ChronoUnit.MILLIS);
    }

    public static Instant computeEndsAt(Instant acceptedAt, int workmanshipDurationDays) {
        if (workmanshipDurationDays <= 0 || acceptedAt == null) {
            return null;
        }
        Instant startsAt = acceptedAt.truncatedTo(ChronoUnit.MILLIS);
        LocalDate acceptedDateVn = LocalDate.ofInstant(startsAt, VN_ZONE);
        LocalDate expiryDateVn = acceptedDateVn.plusDays(workmanshipDurationDays);
        return expiryDateVn.atTime(23, 59, 59, 999_000_000).atZone(VN_ZONE).toInstant();
    }

    public static boolean isWorkmanshipTemporallyEligible(int workmanshipDurationDays, Instant endsAt, Instant reportedAt) {
        if (workmanshipDurationDays <= 0 || endsAt == null || reportedAt == null) {
            return false;
        }
        return !reportedAt.truncatedTo(ChronoUnit.MILLIS).isAfter(endsAt);
    }

    public record DiscrepancyResult(boolean materialDiscrepancy, String summary) {
    }

    public static DiscrepancyResult detectMaterialDiscrepancy(
            List<Map<String, Object>> committedParts,
            List<BookingPartsUsageEntity> actualParts) {
        List<String> diffs = new ArrayList<>();
        Map<String, Map<String, Object>> committedByKey = new LinkedHashMap<>();
        if (committedParts != null) {
            for (Map<String, Object> item : committedParts) {
                String name = String.valueOf(item.getOrDefault("partName", item.getOrDefault("description", ""))).trim();
                if (!name.isBlank()) {
                    committedByKey.put(name.toLowerCase(), item);
                }
            }
        }
        Map<String, BookingPartsUsageEntity> actualByKey = new LinkedHashMap<>();
        if (actualParts != null) {
            for (BookingPartsUsageEntity u : actualParts) {
                if (u.getPartName() != null && !u.getPartName().isBlank()) {
                    actualByKey.put(u.getPartName().trim().toLowerCase(), u);
                }
            }
        }

        for (var entry : committedByKey.entrySet()) {
            String key = entry.getKey();
            Map<String, Object> committed = entry.getValue();
            BookingPartsUsageEntity actual = actualByKey.get(key);
            String label = String.valueOf(committed.getOrDefault("partName", committed.getOrDefault("description", key)));
            if (actual == null) {
                diffs.add("Committed part missing from actual usage: " + label);
                continue;
            }
            int committedQty = committed.get("quantity") instanceof Number n ? n.intValue() : 1;
            if (actual.getQuantity() != committedQty) {
                diffs.add("Quantity mismatch for " + label + " (committed=" + committedQty + ", actual=" + actual.getQuantity() + ")");
            }
            String committedWarranty = committed.get("partsWarrantyStatus") != null
                    ? String.valueOf(committed.get("partsWarrantyStatus")).trim().toUpperCase()
                    : null;
            if ("AVAILABLE".equals(committedWarranty) && !"AVAILABLE".equalsIgnoreCase(actual.getPartsWarrantyStatus())) {
                diffs.add("Parts warranty downgraded for " + label + " (committed=AVAILABLE, actual=" + actual.getPartsWarrantyStatus() + ")");
            }
        }

        if (!committedByKey.isEmpty() && actualParts != null) {
            for (BookingPartsUsageEntity u : actualParts) {
                String key = u.getPartName().trim().toLowerCase();
                if (!committedByKey.containsKey(key) && u.getUnitPrice() > 0 && !"CUSTOMER_SUPPLIED".equals(u.getPartSource())) {
                    diffs.add("Uncommitted paid part installed: " + u.getPartName());
                }
            }
        }

        if (diffs.isEmpty()) {
            return new DiscrepancyResult(false, null);
        }
        String joined = String.join("; ", diffs);
        if (joined.length() > 1500) {
            joined = joined.substring(0, 1497) + "...";
        }
        return new DiscrepancyResult(true, joined);
    }

    @Transactional
    public WarrantyCommitmentSnapshotEntity recordCommitmentSnapshot(
            UUID bookingId,
            UUID quotationId,
            int quotationVersion,
            String serviceCategory,
            Integer requestedDurationDays,
            List<Map<String, Object>> committedParts,
            String policySummary,
            String committedByProfileId) {
        if (commitmentRepository.existsByBookingIdAndQuotationVersion(bookingId, quotationVersion)) {
            return commitmentRepository.findByBookingIdOrderByQuotationVersionAsc(bookingId).stream()
                    .filter(s -> s.getQuotationVersion() == quotationVersion)
                    .findFirst()
                    .orElseThrow();
        }
        String category = (serviceCategory == null || serviceCategory.isBlank()) ? "REPAIR" : serviceCategory.trim().toUpperCase();
        int duration = requestedDurationDays != null ? requestedDurationDays : defaultWorkmanshipDays(category);
        validateWorkmanshipDuration(category, duration);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return commitmentRepository.saveAndFlush(WarrantyCommitmentSnapshotEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(bookingId)
                .quotationId(quotationId)
                .quotationVersion(quotationVersion)
                .serviceCategory(category)
                .workmanshipDurationDays(duration)
                .committedPartsJson(writeJson(committedParts != null ? committedParts : List.of()))
                .policySummary(policySummary)
                .committedAt(now)
                .committedByProfileId(committedByProfileId)
                .build());
    }

    @Transactional
    public WarrantyCommitmentSnapshotEntity recordQuotationCommitment(
            UUID bookingId,
            QuotationEntity quotation,
            String serviceCategory,
            String committedByProfileId) {
        List<QuotationItemEntity> items = quotationItemRepository.findByQuotationIdOrderByRowIndexAsc(quotation.getId());
        List<Map<String, Object>> materialItems = new ArrayList<>();
        for (QuotationItemEntity item : items) {
            if ("MATERIAL".equalsIgnoreCase(item.getItemType())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("partName", item.getDescription());
                m.put("quantity", item.getQuantity());
                m.put("unitPrice", item.getUnitPrice());
                materialItems.add(m);
            }
        }
        return recordCommitmentSnapshot(
                bookingId,
                quotation.getId(),
                quotation.getVersion(),
                serviceCategory,
                quotation.getWorkmanshipDurationDays(),
                materialItems,
                "Quotation v" + quotation.getVersion() + " approved",
                committedByProfileId);
    }

    @Transactional
    public WarrantyActivationSnapshotEntity createActivationSnapshotOnAcceptance(BookingEntity booking) {
        Optional<WarrantyActivationSnapshotEntity> existing = activationRepository.findByBookingId(booking.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        WarrantyCommitmentSnapshotEntity latestCommitment = commitmentRepository
                .findTopByBookingIdOrderByQuotationVersionDesc(booking.getId())
                .orElse(null);

        int durationDays = latestCommitment != null
                ? latestCommitment.getWorkmanshipDurationDays()
                : defaultWorkmanshipDays(booking.getPurpose());

        Instant acceptedAt = booking.getAcceptedAt() != null
                ? booking.getAcceptedAt().truncatedTo(ChronoUnit.MILLIS)
                : Instant.now().truncatedTo(ChronoUnit.MILLIS);

        Instant startsAt = computeStartsAt(acceptedAt, durationDays);
        Instant endsAt = computeEndsAt(acceptedAt, durationDays);

        List<BookingPartsUsageEntity> actualParts = partsUsageRepository.findByBookingIdOrderByCreatedAtAsc(booking.getId());
        List<Map<String, Object>> committedParts = latestCommitment != null
                ? readJsonList(latestCommitment.getCommittedPartsJson())
                : List.of();
        DiscrepancyResult discrepancy = detectMaterialDiscrepancy(committedParts, actualParts);

        List<Map<String, Object>> actualPartsSummary = actualParts.stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId().toString());
            m.put("partName", p.getPartName());
            m.put("partCode", p.getPartCode());
            m.put("quantity", p.getQuantity());
            m.put("partSource", p.getPartSource());
            m.put("unitPrice", p.getUnitPrice());
            m.put("partsWarrantyStatus", p.getPartsWarrantyStatus());
            m.put("warrantyIssuer", p.getWarrantyIssuer());
            m.put("warrantyDurationDays", p.getWarrantyDurationDays());
            m.put("evidenceRef", p.getEvidenceRef());
            return m;
        }).toList();

        // D-24 05c: Capture rejected Revised Quotation items into excludedScopeJson
        List<Map<String, Object>> excludedScope = new ArrayList<>();
        List<QuotationEntity> revised = quotationRepository
                .findByBookingIdAndQuoteKindOrderByVersionDesc(booking.getId(), QuotationService.QUOTE_KIND_REVISED);
        for (QuotationEntity q : revised) {
            if ("REJECTED".equalsIgnoreCase(q.getStatus())) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("quotationId", q.getId().toString());
                entry.put("version", q.getVersion());
                entry.put("revisionReason", q.getRevisionReason());
                entry.put("rejectionReason", q.getRejectionReason());
                excludedScope.add(entry);
            }
        }

        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        return activationRepository.saveAndFlush(WarrantyActivationSnapshotEntity.builder()
                .id(UUID.randomUUID())
                .bookingId(booking.getId())
                .commitmentSnapshotId(latestCommitment != null ? latestCommitment.getId() : null)
                .workmanshipDurationDays(durationDays)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .actualPartsJson(writeJson(actualPartsSummary))
                .excludedScopeJson(writeJson(excludedScope))
                .materialDiscrepancy(discrepancy.materialDiscrepancy())
                .discrepancySummary(discrepancy.summary())
                .createdAt(now)
                .build());
    }

    @Transactional(readOnly = true)
    public Instant resolvePostAcceptanceDisputeDeadline(UUID bookingId, Instant acceptedAt) {
        LocalDate day0 = acceptedAt.atZone(VN_ZONE).toLocalDate();
        Instant defaultDeadline = day0.plusDays(POST_ACCEPT_DISPUTE_BASE_DAYS)
                .atTime(23, 59, 59, 999_000_000)
                .atZone(VN_ZONE)
                .toInstant();
        return activationRepository.findByBookingId(bookingId)
                .map(WarrantyActivationSnapshotEntity::getEndsAt)
                .filter(endsAt -> endsAt != null && endsAt.isAfter(defaultDeadline))
                .orElse(defaultDeadline);
    }

    @Transactional(readOnly = true)
    public List<WarrantyCommitmentSnapshotEntity> listCommitments(UUID bookingId) {
        return commitmentRepository.findByBookingIdOrderByQuotationVersionAsc(bookingId);
    }

    @Transactional(readOnly = true)
    public Optional<WarrantyActivationSnapshotEntity> findActivation(UUID bookingId) {
        return activationRepository.findByBookingId(bookingId);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize warranty snapshot JSON", ex);
        }
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception ex) {
            return List.of();
        }
    }
}
