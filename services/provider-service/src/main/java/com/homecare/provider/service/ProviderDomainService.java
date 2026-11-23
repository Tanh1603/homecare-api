package com.homecare.provider.service;

import lombok.RequiredArgsConstructor;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.common.security.RbacConstants;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.provider.security.ProviderAccessPolicy;
import com.homecare.provider.dto.ProviderDtos.CoverageAreaItem;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.OfferingInput;
import com.homecare.provider.dto.ProviderDtos.ProviderInput;
import com.homecare.provider.dto.ProviderDtos.ProviderResponse;
import com.homecare.provider.dto.ProviderDtos.ScheduleWindowResponse;
import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.dto.ProviderDtos.TechnicianInput;
import com.homecare.provider.dto.ProviderDtos.TechnicianResponse;
import com.homecare.provider.dto.ProviderDtos.UpdateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.UpsertServiceCoverageRequest;
import com.homecare.provider.entity.CategoryQualificationEntity;
import com.homecare.provider.entity.CategoryQualificationStatus;
import com.homecare.provider.entity.InspectionFeePolicy;
import com.homecare.provider.entity.ProviderAllocationOperation;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.ScheduleWindowStatus;
import com.homecare.provider.entity.ServiceCategory;
import com.homecare.provider.entity.ServiceCoverageAreaEntity;
import com.homecare.provider.entity.ServiceOffering;
import com.homecare.provider.entity.ServicePriceType;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.entity.TechnicianAllocation;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.organization.entity.OrganizationMemberEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.entity.ProviderOutboxEventEntity;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.OrganizationRepository;
import com.homecare.provider.organization.repository.ProviderOutboxEventRepository;
import com.homecare.provider.repository.CategoryQualificationRepository;
import com.homecare.provider.repository.ProviderAllocationOperationRepository;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.ServiceCoverageAreaRepository;
import com.homecare.provider.repository.ServiceOfferingRepository;
import com.homecare.provider.repository.TechnicianAllocationRepository;
import com.homecare.provider.repository.TechnicianRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
public class ProviderDomainService {

    @Transactional(readOnly = true)
    public PageResponse<ServiceOfferingResponse> offeringsPage(String category, UUID providerId, String q, int page, int size) {
        return offeringsPage(category, providerId, q, false, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ServiceOfferingResponse> offeringsPage(
            String category, UUID providerId, String q, boolean includeInactive, int page, int size) {
        var result = includeInactive
                ? offeringRepository.searchIncludingInactive(blankToNull(category), providerId, blankToNull(q), pageRequest(page, size))
                : offeringRepository.search(blankToNull(category), providerId, blankToNull(q), pageRequest(page, size));
        Set<UUID> providerIds = result.getContent().stream().map(ServiceOffering::getProviderId).collect(Collectors.toSet());
        Map<UUID, String> providerNames = providerIds.isEmpty() ? Map.of() : providerRepository.findAllById(providerIds).stream()
                .collect(Collectors.toMap(ProviderEntity::getId, ProviderEntity::getName));
        var items = result.getContent().stream()
                .map(o -> toOfferingResponse(o, providerNames.getOrDefault(o.getProviderId(), "HomeCare Partner")))
                .toList();
        return PageResponse.of(items, page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<ProviderResponse> providersPage(String city, String q, int page, int size) {
        var result = providerRepository.search(blankToNull(city), blankToNull(q), pageRequest(page, size));
        return PageResponse.of(result.map(this::toProviderResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<TechnicianResponse> techniciansPage(UUID providerId, String q, int page, int size) {
        return techniciansPage(providerId, q, false, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<TechnicianResponse> techniciansPage(
            UUID providerId, String q, boolean includeInactive, int page, int size) {
        var result = includeInactive
                ? technicianRepository.searchIncludingInactive(providerId, blankToNull(q), pageRequest(page, size))
                : technicianRepository.search(providerId, blankToNull(q), pageRequest(page, size));
        return PageResponse.of(result.map(this::toTechnicianResponse).getContent(), page, size, result.getTotalElements());
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Pageable pageRequest(int page, int size) {
        PageResponse.validate(page, size);
        return PageRequest.of(page - 1, size, Sort.by("createdAt").descending().and(Sort.by("id")));
    }

    public void requireProviderManager(UUID providerId, ActorContext actor) {
        ProviderEntity provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.PROVIDER_NOT_FOUND));
        accessPolicy.assertCanManageProvider(actor, provider);
    }

    @Transactional
    public ProviderResponse updateProvider(UUID id, ProviderInput input, ActorContext actor) {
        requireProviderManager(id, actor);
        var p = providerRepository.findById(id).orElseThrow();
        if (input.name() != null) p.setName(input.name());
        if (input.description() != null) p.setDescription(input.description());
        if (input.city() != null) p.setCity(input.city());
        if (input.phone() != null) p.setPhone(input.phone());
        return toProviderResponse(providerRepository.save(p));
    }

    @Transactional
    public ServiceOfferingResponse saveOffering(UUID id, OfferingInput input, ActorContext actor) {
        var item = id == null ? new ServiceOffering() : offeringRepository.findById(id).orElseThrow(() -> new DomainException(ProviderErrorCode.SERVICE_NOT_FOUND));
        UUID provider = id == null ? input.providerId() : item.getProviderId();
        if (provider == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        requireProviderManager(provider, actor);
        if (input.providerId() != null && !provider.equals(input.providerId())) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        if (input.inspectionFee() != null && input.inspectionFee() < 0) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "inspectionFee must be >= 0.");
        }
        boolean wasActive = id != null && item.isActive();
        boolean priceChanged = false;
        if (id == null) {
            if (input.name() == null || input.category() == null || input.basePrice() == null || input.estimatedDurationMinutes() == null) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
            }
            item.setId(UUID.randomUUID());
            item.setProviderId(provider);
            item.setActive(input.active() != null ? input.active() : false);
            item.setCreatedAt(Instant.now());
            item.setPriceType(ServicePriceType.FIXED);
            item.setInspectionFeePolicy(InspectionFeePolicy.NO_FEE);
            item.setPriceVersion(1);
        }
        if (input.name() != null) item.setName(input.name());
        String effectiveCategoryCode = input.category() != null ? input.category() : item.getCategory();
        ServiceCategory category = ServiceCategory.fromCode(effectiveCategoryCode)
                .orElseThrow(() -> new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "Invalid v0.1 ServiceCategory: " + effectiveCategoryCode));
        item.setCategory(category.name());

        if (input.description() != null) item.setDescription(input.description());
        if (input.basePrice() != null) {
            if (id != null && input.basePrice() != item.getBasePrice()) priceChanged = true;
            item.setBasePrice(input.basePrice());
        }
        if (input.priceType() != null) {
            if (id != null && input.priceType() != item.getPriceType()) priceChanged = true;
            item.setPriceType(input.priceType());
        }
        if (input.inspectionFee() != null) {
            if (id != null && !input.inspectionFee().equals(item.getInspectionFee())) priceChanged = true;
            item.setInspectionFee(input.inspectionFee());
        }
        if (input.inspectionFeePolicy() != null) {
            if (id != null && input.inspectionFeePolicy() != item.getInspectionFeePolicy()) priceChanged = true;
            item.setInspectionFeePolicy(input.inspectionFeePolicy());
        }
        long softThreshold = governanceService.getInspectionFeeSoftWarningVnd();
        item.setInspectionFeeAuditFlag(item.getInspectionFee() != null && item.getInspectionFee() > softThreshold);

        int workmanshipDays = input.workmanshipDurationDays() != null
                ? input.workmanshipDurationDays()
                : (id == null ? Math.max(7, category.minWorkmanshipDays()) : item.getWorkmanshipDurationDays());
        if (!category.isValidWorkmanshipDays(workmanshipDays)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "workmanshipDurationDays for " + category.name() + " must be within ["
                            + category.minWorkmanshipDays() + ", " + category.maxWorkmanshipDays() + "].");
        }
        item.setWorkmanshipDurationDays(workmanshipDays);

        if (priceChanged) {
            item.setPriceVersion(Math.max(1, item.getPriceVersion()) + 1);
        }
        if (input.estimatedDurationMinutes() != null) item.setEstimatedDurationMinutes(input.estimatedDurationMinutes());
        if (input.active() != null) item.setActive(input.active());

        if (item.isActive()) {
            if (category.requiresQualification()) {
                boolean qualified = categoryQualificationRepository.findByProviderIdAndCategory(provider, category)
                        .map(q -> q.getStatus() == CategoryQualificationStatus.VERIFIED)
                        .orElse(false);
                if (!qualified) {
                    throw new DomainException(ProviderErrorCode.ORGANIZATION_NOT_APPROVED,
                            "Admin Qualification Verification is required before activating " + category.name() + " offerings.");
                }
            }
            if (coverageAreaRepository.countByServiceOfferingIdAndActiveTrue(item.getId()) == 0) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                        "At least 1 active ServiceCoverageArea is required before activating a ServiceOffering.");
            }
        }

        ServiceOffering saved = offeringRepository.save(item);
        if (wasActive && !saved.isActive()) {
            enqueueOfferingDeactivatedEvent(saved, "PROVIDER_DEACTIVATED");
        }
        return toOfferingResponse(saved);
    }

    @Transactional
    public List<CoverageAreaItem> replaceOfferingCoverage(UUID offeringId, UpsertServiceCoverageRequest request, ActorContext actor) {
        ServiceOffering offering = offeringRepository.findById(offeringId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.SERVICE_NOT_FOUND));
        requireProviderManager(offering.getProviderId(), actor);
        List<CoverageAreaItem> areas = request != null && request.areas() != null ? request.areas() : List.of();
        long activeCount = 0;
        for (CoverageAreaItem a : areas) {
            if (a == null || a.locationCode() == null || a.locationCode().isBlank()
                    || a.level() == null || (!"PROVINCE".equalsIgnoreCase(a.level()) && !"WARD".equalsIgnoreCase(a.level()))) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Coverage area must have valid PROVINCE or WARD level and locationCode.");
            }
            if (a.active() == null || a.active()) {
                activeCount++;
            }
        }
        if (offering.isActive() && activeCount == 0) {
            throw new DomainException(CommonErrorCode.CONFLICT,
                    "Cannot remove the last active ServiceCoverageArea while the ServiceOffering is ACTIVE.");
        }
        coverageAreaRepository.deleteByServiceOfferingId(offeringId);
        coverageAreaRepository.flush();
        Instant now = Instant.now();
        for (CoverageAreaItem a : areas) {
            boolean isActive = a.active() == null || a.active();
            if (!isActive) continue;
            coverageAreaRepository.save(ServiceCoverageAreaEntity.builder()
                    .id(UUID.randomUUID())
                    .serviceOfferingId(offeringId)
                    .providerId(offering.getProviderId())
                    .locationCode(a.locationCode().trim())
                    .level(a.level().trim().toUpperCase())
                    .active(true)
                    .createdAt(now)
                    .build());
        }
        return getOfferingCoverage(offeringId);
    }

    @Transactional(readOnly = true)
    public List<CategoryQualificationEntity> listCategoryQualifications(UUID providerOrOrgId) {
        return providerRepository.findById(providerOrOrgId)
                .or(() -> providerRepository.findByOrganizationId(providerOrOrgId))
                .map(p -> categoryQualificationRepository.findByProviderId(p.getId()))
                .orElse(List.of());
    }

    @Transactional
    public CategoryQualificationEntity submitCategoryQualification(
            ServiceCategory category, String evidenceRefs, ActorContext actor) {
        if (actor == null || actor.mode() != AccessMode.ORGANIZATION || actor.organizationId() == null) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Organization context is required.");
        }
        if (!actor.hasOrgPermission(RbacConstants.PERM_SERVICE_MANAGE)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Missing service manage permission.");
        }
        if (!category.requiresQualification()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Category " + category.name() + " does not require qualification verification.");
        }
        if (evidenceRefs == null || evidenceRefs.isBlank()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Qualification evidenceRefs is required.");
        }
        ProviderEntity provider = providerRepository.findByOrganizationId(actor.organizationId())
                .orElseThrow(() -> new DomainException(ProviderErrorCode.PROVIDER_NOT_FOUND));
        Instant now = Instant.now();
        CategoryQualificationEntity qual = categoryQualificationRepository
                .findByProviderIdAndCategory(provider.getId(), category)
                .orElse(CategoryQualificationEntity.builder()
                        .id(UUID.randomUUID())
                        .providerId(provider.getId())
                        .category(category)
                        .createdAt(now)
                        .build());
        qual.setEvidenceRefs(evidenceRefs.trim());
        qual.setStatus(CategoryQualificationStatus.UNVERIFIED);
        qual.setReason(null);
        qual.setVerifiedBy(null);
        qual.setVerifiedAt(null);
        qual.setUpdatedAt(now);
        return categoryQualificationRepository.save(qual);
    }

    @Transactional
    public CategoryQualificationEntity verifyCategoryQualification(
            UUID providerOrOrgId, ServiceCategory category, CategoryQualificationStatus decision, String reason, String adminUserId) {
        ProviderEntity provider = providerRepository.findById(providerOrOrgId)
                .or(() -> providerRepository.findByOrganizationId(providerOrOrgId))
                .orElseThrow(() -> new DomainException(ProviderErrorCode.PROVIDER_NOT_FOUND));
        UUID providerId = provider.getId();
        if (decision == null || decision == CategoryQualificationStatus.UNVERIFIED) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Decision must be VERIFIED, REJECTED, or REVOKED.");
        }
        Instant now = Instant.now();
        CategoryQualificationEntity qual = categoryQualificationRepository.findByProviderIdAndCategory(providerId, category)
                .orElse(CategoryQualificationEntity.builder()
                        .id(UUID.randomUUID())
                        .providerId(providerId)
                        .category(category)
                        .createdAt(now)
                        .build());
        qual.setStatus(decision);
        qual.setReason(reason != null ? reason.trim() : null);
        qual.setVerifiedBy(adminUserId);
        qual.setVerifiedAt(now);
        qual.setUpdatedAt(now);
        CategoryQualificationEntity saved = categoryQualificationRepository.save(qual);

        if (decision == CategoryQualificationStatus.REVOKED || decision == CategoryQualificationStatus.REJECTED) {
            List<ServiceOffering> activeInCat = offeringRepository.findByProviderIdAndActiveTrue(providerId).stream()
                    .filter(o -> category.name().equalsIgnoreCase(o.getCategory()))
                    .toList();
            for (ServiceOffering offering : activeInCat) {
                offering.setActive(false);
                offeringRepository.save(offering);
                enqueueOfferingDeactivatedEvent(offering, "QUALIFICATION_" + decision.name());
            }
        }
        return saved;
    }

    private void enqueueOfferingDeactivatedEvent(ServiceOffering offering, String reason) {
        Instant now = Instant.now();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerId", offering.getProviderId().toString());
        data.put("serviceOfferingId", offering.getId().toString());
        data.put("category", offering.getCategory());
        data.put("reason", reason);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put(DomainEventContracts.FIELD_EVENT_ID, UUID.randomUUID().toString());
        envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.OFFERING_DEACTIVATED);
        envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
        envelope.put(DomainEventContracts.FIELD_PRODUCER, "provider-service");
        envelope.put(DomainEventContracts.FIELD_DATA, data);
        try {
            outboxRepository.save(ProviderOutboxEventEntity.builder()
                    .id(UUID.randomUUID())
                    .aggregateType("ServiceOffering")
                    .aggregateId(offering.getId())
                    .eventType(DomainEventContracts.OFFERING_DEACTIVATED)
                    .payloadJson(objectMapper.writeValueAsString(envelope))
                    .status(EntityStatus.PENDING.name())
                    .retryCount(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize offering deactivated event.", ex);
        }
    }

    @Transactional(readOnly = true)
    public List<CoverageAreaItem> getOfferingCoverage(UUID offeringId) {
        return coverageAreaRepository.findByServiceOfferingIdAndActiveTrueOrderByLevelAscLocationCodeAsc(offeringId)
                .stream()
                .map(c -> new CoverageAreaItem(c.getLocationCode(), c.getLevel(), c.isActive()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceOfferingResponse> listEligibleOfferings(String category, String provinceCode, String wardCode) {
        String normCategory = blankToNull(category);
        String normProvince = blankToNull(provinceCode);
        String normWard = blankToNull(wardCode);
        List<ServiceOffering> activeOfferings = offeringRepository.findAll().stream()
                .filter(ServiceOffering::isActive)
                .filter(o -> normCategory == null || normCategory.equalsIgnoreCase(o.getCategory()))
                .toList();
        if (activeOfferings.isEmpty()) {
            return List.of();
        }
        Set<UUID> providerIds = activeOfferings.stream().map(ServiceOffering::getProviderId).collect(Collectors.toSet());
        Map<UUID, ProviderEntity> activeProviders = providerRepository.findAllById(providerIds).stream()
                .filter(p -> "ACTIVE".equalsIgnoreCase(p.getStatus()))
                .filter(p -> organizationRepository.findById(p.getOrganizationId())
                        .map(o -> o.getStatus() == OrganizationStatus.APPROVED)
                        .orElse(true))
                .collect(Collectors.toMap(ProviderEntity::getId, p -> p));

        Map<UUID, List<ServiceCoverageAreaEntity>> coverageByOffering = coverageAreaRepository
                .findByServiceOfferingIdInAndActiveTrue(
                        activeOfferings.stream().map(ServiceOffering::getId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.groupingBy(ServiceCoverageAreaEntity::getServiceOfferingId));

        return activeOfferings.stream()
                .filter(o -> activeProviders.containsKey(o.getProviderId()))
                .filter(o -> isOfferingQualificationSatisfied(o))
                .filter(o -> matchesCoverage(coverageByOffering.getOrDefault(o.getId(), List.of()), normProvince, normWard))
                .sorted(Comparator
                        .comparingInt((ServiceOffering o) -> coverageRank(coverageByOffering.getOrDefault(o.getId(), List.of()), normProvince, normWard))
                        .thenComparing(Comparator.comparingDouble((ServiceOffering o) -> activeProviders.get(o.getProviderId()).getRatingAvg()).reversed())
                        .thenComparing(ServiceOffering::getId))
                .map(o -> toOfferingResponse(o, activeProviders.get(o.getProviderId()).getName()))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isOfferingQualificationSatisfied(ServiceOffering o) {
        return ServiceCategory.fromCode(o.getCategory())
                .map(cat -> !cat.requiresQualification() || isCategoryQualificationVerified(o.getProviderId(), cat))
                .orElse(true);
    }

    @Transactional(readOnly = true)
    public boolean isCategoryQualificationVerified(UUID providerId, ServiceCategory category) {
        return categoryQualificationRepository.findByProviderIdAndCategory(providerId, category)
                .map(q -> q.getStatus() == CategoryQualificationStatus.VERIFIED)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean isProviderIdentityVerified(UUID providerId) {
        return providerRepository.findById(providerId)
                .flatMap(p -> organizationRepository.findById(p.getOrganizationId()))
                .map(o -> o.isIdentityVerified() && o.getStatus() == OrganizationStatus.APPROVED)
                .orElse(false);
    }

    private boolean matchesCoverage(List<ServiceCoverageAreaEntity> areas, String provinceCode, String wardCode) {
        if (provinceCode == null && wardCode == null) {
            return true;
        }
        if (areas == null || areas.isEmpty()) {
            return false;
        }
        for (ServiceCoverageAreaEntity a : areas) {
            if (!a.isActive()) continue;
            if (wardCode != null && "WARD".equalsIgnoreCase(a.getLevel()) && wardCode.equalsIgnoreCase(a.getLocationCode())) {
                return true;
            }
            if (provinceCode != null && "PROVINCE".equalsIgnoreCase(a.getLevel()) && provinceCode.equalsIgnoreCase(a.getLocationCode())) {
                return true;
            }
        }
        return false;
    }

    private int coverageRank(List<ServiceCoverageAreaEntity> areas, String provinceCode, String wardCode) {
        if (areas != null && wardCode != null) {
            boolean wardMatch = areas.stream()
                    .anyMatch(a -> a.isActive() && "WARD".equalsIgnoreCase(a.getLevel()) && wardCode.equalsIgnoreCase(a.getLocationCode()));
            if (wardMatch) return 0;
        }
        if (areas != null && provinceCode != null) {
            boolean provinceMatch = areas.stream()
                    .anyMatch(a -> a.isActive() && "PROVINCE".equalsIgnoreCase(a.getLevel()) && provinceCode.equalsIgnoreCase(a.getLocationCode()));
            if (provinceMatch) return 1;
        }
        return 2;
    }

    @Transactional
    public void deleteOffering(UUID id, ActorContext actor) {
        var item = offeringRepository.findById(id).orElseThrow(() -> new DomainException(ProviderErrorCode.SERVICE_NOT_FOUND));
        requireProviderManager(item.getProviderId(), actor);
        item.setActive(false); offeringRepository.save(item);
    }

    @Transactional
    public TechnicianResponse saveTechnician(UUID id, TechnicianInput input, ActorContext actor) {
        var item = id == null ? new Technician() : technicianRepository.findById(id).orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        UUID provider = id == null ? input.providerId() : item.getProviderId();
        if (provider == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        requireProviderManager(provider, actor);
        if (input.providerId() != null && !provider.equals(input.providerId())) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        if (id == null) {
            if (input.profileId() == null || input.fullName() == null || input.specialties() == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
            item.setId(UUID.randomUUID()); item.setProviderId(provider); item.setStatus("ACTIVE"); item.setCreatedAt(Instant.now());
        }
        if (input.profileId() != null && id != null && !input.profileId().equals(item.getProfileId())) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        if (input.profileId() != null) item.setProfileId(input.profileId());
        if (input.fullName() != null) item.setFullName(input.fullName());
        if (input.specialties() != null) item.setSpecialties(String.join(",", input.specialties()));
        if (input.experienceYears() != null) item.setExperienceYears(input.experienceYears());
        if (input.status() != null) {
            if ("INACTIVE".equals(input.status()) && item.getActiveAllocations() > 0) throw new DomainException(ProviderErrorCode.SCHEDULE_ALLOCATION_CONFLICT);
            item.setStatus(input.status());
        }
        return toTechnicianResponse(technicianRepository.save(item));
    }

    @Transactional
    public void deleteTechnician(UUID id, ActorContext actor) {
        var item = technicianRepository.findById(id).orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        requireProviderManager(item.getProviderId(), actor);
        if (item.getActiveAllocations() > 0) throw new DomainException(ProviderErrorCode.SCHEDULE_ALLOCATION_CONFLICT);
        item.setStatus("INACTIVE"); technicianRepository.save(item);
    }

    @Transactional(readOnly = true)
    public TechnicianResponse me(String user) { return toTechnicianResponse(resolveTechnicianOrDemo("me", user)); }

    @Transactional
    public void deleteSchedule(String technicianId, UUID windowId, ActorContext actor, String ifMatch) {
        var tech = resolveTechnicianOrDemo(technicianId, actor.userId());
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);
        var window = scheduleWindowRepository.findById(windowId).orElseThrow(() -> new DomainException(ProviderErrorCode.SCHEDULE_WINDOW_NOT_FOUND));
        if (!window.getTechnicianId().equals(tech.getId())) throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        EtagSupport.verifyIfMatch(ifMatch, window.getVersion());
        if (window.getAllocatedBookingId() != null) throw new DomainException(ProviderErrorCode.SCHEDULE_ALLOCATION_CONFLICT);
        scheduleWindowRepository.delete(window);
    }

    private final ProviderRepository providerRepository;
    private final ServiceOfferingRepository offeringRepository;
    private final ServiceCoverageAreaRepository coverageAreaRepository;
    private final TechnicianRepository technicianRepository;
    private final ScheduleWindowRepository scheduleWindowRepository;
    private final TechnicianAllocationRepository allocationRepository;
    private final ProviderAllocationOperationRepository operationRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final ProviderAccessPolicy accessPolicy;
    private final PlatformTransactionManager transactionManager;
    private final CategoryQualificationRepository categoryQualificationRepository;
    private final PlatformGovernanceService governanceService;
    private final ProviderOutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public ServiceOfferingResponse getOffering(UUID serviceId) {
        ServiceOffering offering = offeringRepository.findById(serviceId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.SERVICE_NOT_FOUND));
        return toOfferingResponse(offering);
    }

    @Transactional(readOnly = true)
    public ProviderResponse getProvider(UUID providerId) {
        ProviderEntity p = providerRepository.findById(providerId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.PROVIDER_NOT_FOUND));
        return toProviderResponse(p);
    }

    @Transactional(readOnly = true)
    public TechnicianResponse getTechnician(UUID technicianId) {
        Technician t = technicianRepository.findById(technicianId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        return toTechnicianResponse(t);
    }

    @Transactional(readOnly = true)
    public Technician resolveTechnicianOrDemo(String technicianIdOrMe, String jwtSubject) {
        if ("me".equalsIgnoreCase(technicianIdOrMe)) {
            if (jwtSubject == null || jwtSubject.isBlank()) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
            return technicianRepository.findByProfileId(jwtSubject)
                    .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        }
        UUID id = UUID.fromString(technicianIdOrMe);
        return technicianRepository.findById(id)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public boolean isProviderActive(UUID providerId) {
        return providerId != null && providerRepository.findById(providerId)
                .filter(p -> EntityStatus.ACTIVE.name().equalsIgnoreCase(p.getStatus()))
                .isPresent();
    }

    @Transactional(readOnly = true)
    public boolean verifyOrganizationMembership(UUID orgId, String userId) {
        return resolveMembershipRole(orgId, userId).isPresent();
    }

    @Transactional(readOnly = true)
    public Optional<String> resolveMembershipRole(UUID orgId, String userId) {
        if (orgId == null || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        Optional<OrganizationMemberEntity> orgMember = organizationRepository.findById(orgId)
                .filter(org -> org.getStatus() == OrganizationStatus.APPROVED)
                .flatMap(org -> organizationMemberRepository.findByOrganizationIdAndUserId(orgId, userId))
                .filter(m -> EntityStatus.ACTIVE.name().equalsIgnoreCase(m.getStatus()));
        if (orgMember.isPresent()) {
            return Optional.of(orgMember.get().getRole().name());
        }
        boolean isProviderOwner = providerRepository.findById(orgId)
                .or(() -> providerRepository.findByOrganizationId(orgId))
                .filter(p -> EntityStatus.ACTIVE.name().equalsIgnoreCase(p.getStatus()))
                .map(p -> userId.equals(p.getOwnerProfileId()))
                .orElse(false);
        if (isProviderOwner) {
            return Optional.of(OrgRole.ORG_OWNER.name());
        }
        boolean isActiveTech = technicianRepository.findByProfileId(userId)
                .filter(t -> EntityStatus.ACTIVE.name().equalsIgnoreCase(t.getStatus()))
                .map(t -> orgId.equals(t.getProviderId()))
                .orElse(false)
                && isProviderActive(orgId);
        return isActiveTech ? Optional.of(OrgRole.ORG_TECHNICIAN.name()) : Optional.empty();
    }

    @Transactional(readOnly = true)
    public PageResponse<ScheduleWindowResponse> listScheduleWindowsPage(
            String technicianIdOrMe, ActorContext actor, int page, int size) {
        PageResponse.validate(page, size);
        List<ScheduleWindowResponse> all = listScheduleWindows(technicianIdOrMe, actor);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return PageResponse.of(all.subList(from, to), page, size, all.size());
    }

    @Transactional(readOnly = true)
    public List<ScheduleWindowResponse> listScheduleWindows(String technicianIdOrMe, ActorContext actor) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, actor != null ? actor.userId() : null);
        boolean includeBookingId = accessPolicy.canViewScheduleBookingIds(actor, tech);
        return scheduleWindowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId())
                .stream()
                .map(w -> toScheduleResponse(w, includeBookingId))
                .toList();
    }

    @Transactional
    public ScheduleWindowResponse createScheduleWindow(
            String technicianIdOrMe,
            ActorContext actor,
            CreateScheduleWindowRequest request) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, actor.userId());
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "endsAt must be after startsAt.");
        }
        technicianRepository.lockById(tech.getId());
        List<ScheduleWindow> overlaps = scheduleWindowRepository.findOverlapping(
                tech.getId(), request.startsAt(), request.endsAt());
        if (!overlaps.isEmpty()) {
            throw new DomainException(ProviderErrorCode.SCHEDULE_OVERLAP);
        }
        ScheduleWindow saved = scheduleWindowRepository.saveAndFlush(ScheduleWindow.builder()
                .id(UUID.randomUUID())
                .technicianId(tech.getId())
                .startsAt(request.startsAt())
                .endsAt(request.endsAt())
                .status(ScheduleWindowStatus.AVAILABLE.name())
                .createdAt(Instant.now())
                .build());
        return toScheduleResponse(saved);
    }

    @Transactional
    public ScheduleWindowResponse updateScheduleWindow(
            String technicianIdOrMe,
            ActorContext actor,
            UUID windowId,
            String ifMatchHeader,
            UpdateScheduleWindowRequest request) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, actor.userId());
        accessPolicy.assertCanManageTechnicianSchedule(actor, tech);
        technicianRepository.lockById(tech.getId());
        ScheduleWindow window = scheduleWindowRepository.findById(windowId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.SCHEDULE_WINDOW_NOT_FOUND));
        if (!window.getTechnicianId().equals(tech.getId())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this schedule window.");
        }
        EtagSupport.verifyIfMatch(ifMatchHeader, window.getVersion());
        if (window.getAllocatedBookingId() != null) {
            throw new DomainException(ProviderErrorCode.SCHEDULE_ALLOCATION_CONFLICT);
        }
        if (request.startsAt() != null) {
            window.setStartsAt(request.startsAt());
        }
        if (request.endsAt() != null) {
            window.setEndsAt(request.endsAt());
        }
        if (!window.getEndsAt().isAfter(window.getStartsAt())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "endsAt must be after startsAt.");
        }
        boolean overlaps = scheduleWindowRepository.findOverlapping(tech.getId(), window.getStartsAt(), window.getEndsAt())
                .stream()
                .anyMatch(w -> !w.getId().equals(windowId));
        if (overlaps) {
            throw new DomainException(ProviderErrorCode.SCHEDULE_OVERLAP);
        }
        if (request.status() != null && !request.status().isBlank()) {
            String normalizedStatus = request.status().trim().toUpperCase();
            try {
                ScheduleWindowStatus.valueOf(normalizedStatus);
            } catch (IllegalArgumentException ex) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Invalid schedule status.");
            }
            window.setStatus(normalizedStatus);
        }
        return toScheduleResponse(scheduleWindowRepository.saveAndFlush(window));
    }

    public AllocationOutcome allocateTechnicianForBooking(
            String intentId,
            UUID bookingId,
            UUID serviceId,
            String deviceType,
            Instant preferredStart,
            Instant preferredEnd) {
        return allocateTechnicianForBooking(
                intentId, bookingId, serviceId, deviceType, preferredStart, preferredEnd, null, false);
    }

    public AllocationOutcome allocateTechnicianForBooking(
            String intentId,
            UUID bookingId,
            UUID serviceId,
            String deviceType,
            Instant preferredStart,
            Instant preferredEnd,
            UUID preferredProviderId,
            boolean strictProviderMatch) {
        return allocateTechnicianForBooking(intentId, bookingId, serviceId, deviceType, preferredStart, preferredEnd, preferredProviderId, strictProviderMatch, null);
    }

    public AllocationOutcome allocateTechnicianForBooking(String intentId, UUID bookingId, UUID serviceId,
            String deviceType, Instant preferredStart, Instant preferredEnd, UUID preferredProviderId,
            boolean strictProviderMatch, UUID technicianId) {
        return allocateTechnicianForBooking(null, intentId, bookingId, serviceId, deviceType,
                preferredStart, preferredEnd, preferredProviderId, strictProviderMatch, technicianId);
    }

    public AllocationOutcome allocateTechnicianForBooking(UUID operationId, String intentId, UUID bookingId,
            UUID serviceId, String deviceType, Instant preferredStart, Instant preferredEnd,
            UUID preferredProviderId, boolean strictProviderMatch, UUID technicianId) {
        if (!preferredEnd.isAfter(preferredStart)) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);

        if (operationId != null) {
            AllocationOutcome precheck = prepareAllocationOperation(operationId, bookingId);
            if (precheck != null) {
                return precheck;
            }
        }

        Optional<TechnicianAllocation> existing = allocationRepository.findByIntentId(intentId);
        if (existing.isPresent()) {
            TechnicianAllocation alloc = existing.get();
            if (!"ALLOCATED".equals(alloc.getStatus()) || !bookingId.equals(alloc.getBookingId())
                    || (technicianId != null && !technicianId.equals(alloc.getTechnicianId()))) {
                return finishOperationFailure(operationId, null, "ALLOCATION_CONFLICT");
            }
            Technician tech = technicianRepository.findById(alloc.getTechnicianId()).orElseThrow();
            return new AllocationOutcome(
                    true,
                    tech.getId(),
                    tech.getProfileId(),
                    tech.getFullName(),
                    alloc.getProviderId(),
                    alloc.getId(),
                    "");
        }

        Optional<ServiceOffering> offeringOpt = offeringRepository.findById(serviceId);
        if (offeringOpt.isEmpty() || !offeringOpt.get().isActive()
                || !isProviderActive(offeringOpt.get().getProviderId())) {
            return finishOperationFailure(operationId, null, "SERVICE_NOT_AVAILABLE");
        }
        ServiceOffering offering = offeringOpt.get();
        String requiredSpecialty = (deviceType != null && !deviceType.isBlank())
                ? deviceType.trim().toUpperCase()
                : offering.getCategory().toUpperCase();

        UUID targetProviderId = preferredProviderId != null ? preferredProviderId : offering.getProviderId();

        List<Technician> candidates;
        if (strictProviderMatch) {
            candidates = technicianRepository.findByProviderIdAndStatus(targetProviderId, EntityStatus.ACTIVE.name());
        } else {
            candidates = technicianRepository.findByStatus(EntityStatus.ACTIVE.name());
        }

        List<Technician> eligible = candidates.stream()
                .filter(t -> technicianId == null || technicianId.equals(t.getId()))
                .filter(t -> providerRepository.findById(t.getProviderId())
                        .filter(p -> EntityStatus.ACTIVE.name().equals(p.getStatus())).isPresent())
                .filter(t -> matchesSpecialty(t, requiredSpecialty))
                .filter(t -> !scheduleWindowRepository.findAvailableCovering(
                        t.getId(), preferredStart, preferredEnd).isEmpty())
                .filter(t -> allocationRepository.findActiveOverlapping(
                        t.getId(), preferredStart, preferredEnd).isEmpty())
                .sorted(Comparator.comparingDouble((Technician t) -> aiRankScoreWithStoreAffinity(t, targetProviderId))
                        .reversed())
                .toList();

        if (eligible.isEmpty()) {
            String reason = strictProviderMatch
                    ? "NO_AVAILABLE_TECHNICIAN_IN_WARRANTY_ORGANIZATION"
                    : "NO_AVAILABLE_TECHNICIAN";
            return finishOperationFailure(operationId, targetProviderId, reason);
        }

        // Try each candidate in its own REQUIRES_NEW NOWAIT transaction so at most one row lock is held at a time.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (Technician candidate : eligible) {
            try {
                AllocationOutcome claimed = tx.execute(status ->
                        claimCandidateSlot(operationId, intentId, bookingId, candidate.getId(), preferredStart, preferredEnd));
                if (claimed != null) {
                    return claimed;
                }
            } catch (PessimisticLockingFailureException ignored) {
                // Candidate is locked by another request; skip without waiting.
            } catch (DataIntegrityViolationException conflictEx) {
                return finishOperationFailure(operationId, targetProviderId, "ALLOCATION_CONFLICT");
            }
        }
        return finishOperationFailure(operationId, targetProviderId, "NO_AVAILABLE_TECHNICIAN");
    }

    private AllocationOutcome prepareAllocationOperation(UUID operationId, UUID bookingId) {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return tx.execute(status -> ensureOrReadAllocationOp(operationId, bookingId));
        } catch (DataIntegrityViolationException concurrentInsert) {
            return tx.execute(status -> ensureOrReadAllocationOp(operationId, bookingId));
        }
    }

    private AllocationOutcome ensureOrReadAllocationOp(UUID operationId, UUID bookingId) {
        Optional<ProviderAllocationOperation> existing = operationRepository.lockByOperationId(operationId);
        if (existing.isEmpty()) {
            Instant now = Instant.now();
            operationRepository.saveAndFlush(ProviderAllocationOperation.builder()
                    .operationId(operationId)
                    .bookingId(bookingId)
                    .status("ALLOCATING")
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            return null;
        }
        ProviderAllocationOperation op = existing.get();
        if (!bookingId.equals(op.getBookingId())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "operationId belongs to another booking.");
        }
        return outcomeFromOperation(op);
    }

    private AllocationOutcome outcomeFromOperation(ProviderAllocationOperation op) {
        return switch (op.getStatus()) {
            case "ALLOCATED" -> new AllocationOutcome(
                    true,
                    op.getTechnicianId(),
                    op.getTechnicianProfileId(),
                    op.getTechnicianName(),
                    op.getProviderId(),
                    op.getAllocationId(),
                    "");
            case "FAILED" -> new AllocationOutcome(
                    false, null, "", "", op.getProviderId(), null,
                    op.getFailureReason() != null ? op.getFailureReason() : "ALLOCATION_FAILED");
            case "RELEASED" -> new AllocationOutcome(
                    false, null, "", "", null, null, "OPERATION_ALREADY_RELEASED");
            default -> null;
        };
    }

    private AllocationOutcome finishOperationFailure(UUID operationId, UUID providerId, String reason) {
        if (operationId != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            AllocationOutcome existingOutcome = tx.execute(status -> {
                ProviderAllocationOperation op = operationRepository.lockByOperationId(operationId).orElse(null);
                if (op == null) {
                    return null;
                }
                AllocationOutcome terminal = outcomeFromOperation(op);
                if (terminal != null) {
                    return terminal;
                }
                op.setStatus("FAILED");
                op.setProviderId(providerId);
                op.setFailureReason(reason);
                op.setUpdatedAt(Instant.now());
                operationRepository.save(op);
                return null;
            });
            if (existingOutcome != null) {
                return existingOutcome;
            }
        }
        return new AllocationOutcome(false, null, "", "", providerId, null, reason);
    }

    private AllocationOutcome claimCandidateSlot(
            UUID operationId,
            String intentId,
            UUID bookingId,
            UUID candidateId,
            Instant preferredStart,
            Instant preferredEnd) {
        ProviderAllocationOperation op = null;
        if (operationId != null) {
            op = operationRepository.lockByOperationId(operationId).orElse(null);
            if (op != null) {
                if (!bookingId.equals(op.getBookingId())) {
                    throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "operationId belongs to another booking.");
                }
                AllocationOutcome terminal = outcomeFromOperation(op);
                if (terminal != null) {
                    return terminal;
                }
            }
        }
        if (allocationRepository.lockByBookingId(bookingId).isPresent()) {
            if (op != null) {
                op.setStatus("FAILED");
                op.setFailureReason("ALLOCATION_CONFLICT");
                op.setUpdatedAt(Instant.now());
                operationRepository.save(op);
            }
            return new AllocationOutcome(false, null, "", "", null, null, "ALLOCATION_CONFLICT");
        }
        Technician chosen = technicianRepository.lockByIdNoWait(candidateId).orElse(null);
        if (chosen == null) {
            return null;
        }
        var available = scheduleWindowRepository.findAvailableCovering(chosen.getId(), preferredStart, preferredEnd);
        if (available.isEmpty()
                || !allocationRepository.findActiveOverlapping(chosen.getId(), preferredStart, preferredEnd).isEmpty()) {
            return null;
        }
        ScheduleWindow window = available.getFirst();
        if (window.getStartsAt().isBefore(preferredStart)) {
            scheduleWindowRepository.save(ScheduleWindow.builder().id(UUID.randomUUID()).technicianId(chosen.getId())
                    .startsAt(window.getStartsAt()).endsAt(preferredStart).status("AVAILABLE").createdAt(Instant.now()).build());
        }
        if (window.getEndsAt().isAfter(preferredEnd)) {
            scheduleWindowRepository.save(ScheduleWindow.builder().id(UUID.randomUUID()).technicianId(chosen.getId())
                    .startsAt(preferredEnd).endsAt(window.getEndsAt()).status("AVAILABLE").createdAt(Instant.now()).build());
        }
        window.setStartsAt(preferredStart); window.setEndsAt(preferredEnd);
        window.setAllocatedBookingId(bookingId); window.setStatus("BOOKED");
        scheduleWindowRepository.save(window);
        chosen.setActiveAllocations(chosen.getActiveAllocations() + 1);
        technicianRepository.save(chosen);

        TechnicianAllocation savedAlloc = allocationRepository.save(TechnicianAllocation.builder()
                .id(UUID.randomUUID())
                .intentId(intentId)
                .operationId(operationId)
                .bookingId(bookingId)
                .technicianId(chosen.getId())
                .providerId(chosen.getProviderId())
                .startsAt(preferredStart)
                .endsAt(preferredEnd)
                .status("ALLOCATED")
                .createdAt(Instant.now())
                .build());

        if (op != null) {
            op.setStatus("ALLOCATED");
            op.setAllocationId(savedAlloc.getId());
            op.setTechnicianId(chosen.getId());
            op.setProviderId(chosen.getProviderId());
            op.setTechnicianProfileId(chosen.getProfileId());
            op.setTechnicianName(chosen.getFullName());
            op.setFailureReason(null);
            op.setUpdatedAt(Instant.now());
            operationRepository.save(op);
        }

        return new AllocationOutcome(
                true,
                chosen.getId(),
                chosen.getProfileId(),
                chosen.getFullName(),
                chosen.getProviderId(),
                savedAlloc.getId(),
                "");
    }

    public double aiRankScore(Technician t) {
        return (t.getRatingAvg() * 20.0) + (t.getExperienceYears() * 5.0) - (t.getActiveAllocations() * 10.0);
    }

    @Transactional
    public void completeBooking(UUID bookingId) {
        var allocation = allocationRepository.lockByBookingId(bookingId).orElse(null);
        if (allocation == null || !"ALLOCATED".equals(allocation.getStatus())) return;
        var tech = technicianRepository.lockById(allocation.getTechnicianId()).orElseThrow();
        tech.setActiveAllocations(Math.max(0, tech.getActiveAllocations() - 1));
        technicianRepository.save(tech);
        // The worked time slot stays BOOKED so it cannot be sold again.
        allocation.setStatus("COMPLETED"); allocationRepository.save(allocation);
    }

    public record ReleaseOutcome(boolean released, String status) {}

    public ReleaseOutcome releaseBookingByOperation(UUID bookingId, UUID operationId, String reason) {
        if (operationId == null) {
            releaseBooking(bookingId);
            return new ReleaseOutcome(true, "RELEASED");
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            return tx.execute(status -> releaseOperationInternal(bookingId, operationId, reason));
        } catch (DataIntegrityViolationException concurrentInsert) {
            return tx.execute(status -> releaseOperationInternal(bookingId, operationId, reason));
        }
    }

    private ReleaseOutcome releaseOperationInternal(UUID bookingId, UUID operationId, String reason) {
        Optional<ProviderAllocationOperation> existing = operationRepository.lockByOperationId(operationId);
        Instant now = Instant.now();
        if (existing.isEmpty()) {
            operationRepository.saveAndFlush(ProviderAllocationOperation.builder()
                    .operationId(operationId)
                    .bookingId(bookingId)
                    .status("RELEASED")
                    .failureReason(reason)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            return new ReleaseOutcome(true, "RELEASED");
        }
        ProviderAllocationOperation op = existing.get();
        if (!bookingId.equals(op.getBookingId())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "operationId belongs to another booking.");
        }
        if ("RELEASED".equals(op.getStatus())) {
            return new ReleaseOutcome(true, "RELEASED");
        }
        if ("FAILED".equals(op.getStatus())) {
            return new ReleaseOutcome(true, "FAILED");
        }
        allocationRepository.lockByOperationId(operationId).ifPresent(alloc -> {
            if ("ALLOCATED".equals(alloc.getStatus())) {
                releaseAllocationInternal(alloc);
            }
        });
        op.setStatus("RELEASED");
        op.setFailureReason(reason);
        op.setUpdatedAt(now);
        operationRepository.save(op);
        return new ReleaseOutcome(true, "RELEASED");
    }

    @Transactional
    public void releaseBooking(UUID bookingId) {
        var allocation = allocationRepository.lockByBookingId(bookingId).orElse(null);
        if (allocation == null || !"ALLOCATED".equals(allocation.getStatus())) return;
        releaseAllocationInternal(allocation);
        if (allocation.getOperationId() != null) {
            operationRepository.lockByOperationId(allocation.getOperationId()).ifPresent(op -> {
                op.setStatus("RELEASED");
                op.setUpdatedAt(Instant.now());
                operationRepository.save(op);
            });
        }
    }

    private void releaseAllocationInternal(TechnicianAllocation allocation) {
        var tech = technicianRepository.lockById(allocation.getTechnicianId()).orElseThrow();
        tech.setActiveAllocations(Math.max(0, tech.getActiveAllocations() - 1));
        technicianRepository.save(tech);
        for (var window : scheduleWindowRepository.findByAllocatedBookingId(allocation.getBookingId())) {
            window.setAllocatedBookingId(null); window.setStatus("AVAILABLE");
            scheduleWindowRepository.save(window);
        }
        ScheduleWindow previous = null;
        for (var window : scheduleWindowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId())) {
            if (!"AVAILABLE".equals(window.getStatus()) || window.getAllocatedBookingId() != null) {
                previous = null;
            } else if (previous != null && previous.getEndsAt().equals(window.getStartsAt())) {
                previous.setEndsAt(window.getEndsAt());
                scheduleWindowRepository.save(previous);
                scheduleWindowRepository.delete(window);
            } else {
                previous = window;
            }
        }
        allocation.setStatus("RELEASED");
        allocationRepository.save(allocation);
    }

    public record BookingAllocationSnapshot(
            boolean found,
            UUID bookingId,
            UUID technicianId,
            String technicianProfileId,
            String technicianName,
            UUID providerId,
            UUID allocationId,
            UUID operationId,
            String status) {}

    @Transactional(readOnly = true)
    public BookingAllocationSnapshot getBookingAllocation(UUID bookingId) {
        return allocationRepository.findByBookingIdAndStatus(bookingId, "ALLOCATED")
                .map(alloc -> {
                    Technician tech = technicianRepository.findById(alloc.getTechnicianId()).orElse(null);
                    return new BookingAllocationSnapshot(
                            true,
                            alloc.getBookingId(),
                            alloc.getTechnicianId(),
                            tech != null ? tech.getProfileId() : "",
                            tech != null ? tech.getFullName() : "",
                            alloc.getProviderId(),
                            alloc.getId(),
                            alloc.getOperationId(),
                            alloc.getStatus());
                })
                .orElseGet(() -> new BookingAllocationSnapshot(
                        false, bookingId, null, "", "", null, null, null, "UNALLOCATED"));
    }

    public AllocationOutcome reassignTechnicianForBooking(
            UUID operationId,
            UUID bookingId,
            UUID newTechnicianId,
            UUID providerId,
            String reason) {
        if (operationId != null) {
            AllocationOutcome precheck = prepareAllocationOperation(operationId, bookingId);
            if (precheck != null) {
                return precheck;
            }
        }
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            AllocationOutcome outcome = tx.execute(status ->
                    reassignInternal(operationId, bookingId, newTechnicianId, providerId, reason));
            if (outcome != null && !outcome.allocated()) {
                return finishOperationFailure(operationId, providerId, outcome.failureReason());
            }
            return outcome;
        } catch (DataIntegrityViolationException conflictEx) {
            return finishOperationFailure(operationId, providerId, "REASSIGNMENT_CONFLICT");
        }
    }

    private AllocationOutcome reassignInternal(
            UUID operationId,
            UUID bookingId,
            UUID newTechnicianId,
            UUID providerId,
            String reason) {
        var oldAllocOpt = allocationRepository.lockByBookingId(bookingId);
        if (oldAllocOpt.isEmpty()) {
            return new AllocationOutcome(false, null, "", "", providerId, null, "NO_ACTIVE_ALLOCATION");
        }
        TechnicianAllocation oldAlloc = oldAllocOpt.get();
        UUID oldTechId = oldAlloc.getTechnicianId();
        if (oldTechId.equals(newTechnicianId)) {
            return new AllocationOutcome(false, null, "", "", providerId, null, "SAME_TECHNICIAN");
        }

        // Deterministic lock ordering by UUID prevents deadlocks on concurrent reassignments.
        Technician newTech;
        if (oldTechId.compareTo(newTechnicianId) < 0) {
            technicianRepository.lockById(oldTechId);
            newTech = technicianRepository.lockById(newTechnicianId).orElse(null);
        } else {
            newTech = technicianRepository.lockById(newTechnicianId).orElse(null);
            technicianRepository.lockById(oldTechId);
        }

        if (newTech == null || !EntityStatus.ACTIVE.name().equals(newTech.getStatus())) {
            return new AllocationOutcome(false, null, "", "", providerId, null, "TECHNICIAN_NOT_AVAILABLE");
        }
        if (providerId != null && !providerId.equals(newTech.getProviderId())) {
            return new AllocationOutcome(false, null, "", "", providerId, null, "TECHNICIAN_PROVIDER_MISMATCH");
        }
        if (!isProviderActive(newTech.getProviderId())) {
            return new AllocationOutcome(false, null, "", "", newTech.getProviderId(), null, "PROVIDER_NOT_ACTIVE");
        }
        Instant startsAt = oldAlloc.getStartsAt();
        Instant endsAt = oldAlloc.getEndsAt();
        if (!allocationRepository.findActiveOverlapping(newTechnicianId, startsAt, endsAt).isEmpty()) {
            return new AllocationOutcome(false, null, "", "", newTech.getProviderId(), null, "TECHNICIAN_SCHEDULE_CONFLICT");
        }
        var availableWindows = scheduleWindowRepository.findAvailableCovering(newTechnicianId, startsAt, endsAt);
        if (availableWindows.isEmpty()) {
            return new AllocationOutcome(false, null, "", "", newTech.getProviderId(), null, "NO_AVAILABLE_SCHEDULE");
        }

        releaseAllocationInternal(oldAlloc);
        allocationRepository.flush();
        if (oldAlloc.getOperationId() != null) {
            operationRepository.lockByOperationId(oldAlloc.getOperationId()).ifPresent(oldOp -> {
                oldOp.setStatus("RELEASED");
                oldOp.setFailureReason(reason != null && !reason.isBlank() ? reason : "REASSIGNED");
                oldOp.setUpdatedAt(Instant.now());
                operationRepository.save(oldOp);
            });
        }

        ScheduleWindow window = availableWindows.getFirst();
        if (window.getStartsAt().isBefore(startsAt)) {
            scheduleWindowRepository.save(ScheduleWindow.builder().id(UUID.randomUUID()).technicianId(newTech.getId())
                    .startsAt(window.getStartsAt()).endsAt(startsAt).status("AVAILABLE").createdAt(Instant.now()).build());
        }
        if (window.getEndsAt().isAfter(endsAt)) {
            scheduleWindowRepository.save(ScheduleWindow.builder().id(UUID.randomUUID()).technicianId(newTech.getId())
                    .startsAt(endsAt).endsAt(window.getEndsAt()).status("AVAILABLE").createdAt(Instant.now()).build());
        }
        window.setStartsAt(startsAt);
        window.setEndsAt(endsAt);
        window.setAllocatedBookingId(bookingId);
        window.setStatus("BOOKED");
        scheduleWindowRepository.save(window);

        newTech.setActiveAllocations(newTech.getActiveAllocations() + 1);
        technicianRepository.save(newTech);

        Instant now = Instant.now();
        String intentId = operationId != null ? "reassign:" + operationId : "reassign:" + UUID.randomUUID();
        TechnicianAllocation savedAlloc = allocationRepository.saveAndFlush(TechnicianAllocation.builder()
                .id(UUID.randomUUID())
                .intentId(intentId)
                .operationId(operationId)
                .bookingId(bookingId)
                .technicianId(newTech.getId())
                .providerId(newTech.getProviderId())
                .startsAt(startsAt)
                .endsAt(endsAt)
                .status("ALLOCATED")
                .createdAt(now)
                .build());

        if (operationId != null) {
            operationRepository.lockByOperationId(operationId).ifPresent(op -> {
                op.setStatus("ALLOCATED");
                op.setTechnicianId(newTech.getId());
                op.setTechnicianProfileId(newTech.getProfileId());
                op.setTechnicianName(newTech.getFullName());
                op.setProviderId(newTech.getProviderId());
                op.setAllocationId(savedAlloc.getId());
                op.setFailureReason(null);
                op.setUpdatedAt(now);
                operationRepository.save(op);
            });
        }

        return new AllocationOutcome(
                true,
                newTech.getId(),
                newTech.getProfileId(),
                newTech.getFullName(),
                newTech.getProviderId(),
                savedAlloc.getId(),
                "");
    }

    public double aiRankScoreWithStoreAffinity(Technician t, UUID targetProviderId) {
        double base = aiRankScore(t);
        if (targetProviderId != null && targetProviderId.equals(t.getProviderId())) {
            return base + 25.0;
        }
        return base;
    }

    private boolean matchesSpecialty(Technician t, String requiredSpecialty) {
        return Arrays.stream(t.getSpecialties().split(","))
                .map(String::trim)
                .anyMatch(s -> s.equalsIgnoreCase(requiredSpecialty) || s.equalsIgnoreCase("ALL"));
    }

    public record AllocationOutcome(
            boolean allocated,
            UUID technicianId,
            String technicianProfileId,
            String technicianName,
            UUID providerId,
            UUID allocationId,
            String failureReason) {
    }

    private ServiceOfferingResponse toOfferingResponse(ServiceOffering o) {
        String providerName = providerRepository.findById(o.getProviderId())
                .map(ProviderEntity::getName)
                .orElse("HomeCare Partner");
        return toOfferingResponse(o, providerName);
    }

    private ServiceOfferingResponse toOfferingResponse(ServiceOffering o, String providerName) {
        List<CoverageAreaItem> areas = o.getId() != null ? getOfferingCoverage(o.getId()) : List.of();
        long threshold = governanceService.getInspectionFeeSoftWarningVnd();
        String warning = o.isInspectionFeeAuditFlag()
                ? "Inspection fee exceeds platform soft-warning threshold (" + threshold + " VND) and is flagged for Admin post-audit."
                : null;
        return new ServiceOfferingResponse(
                o.getId(),
                o.getProviderId(),
                providerName,
                o.getName(),
                o.getCategory(),
                o.getDescription(),
                o.getBasePrice(),
                o.getEstimatedDurationMinutes(),
                o.isActive(),
                o.getPriceType() != null ? o.getPriceType() : ServicePriceType.FIXED,
                o.getInspectionFee(),
                o.getInspectionFeePolicy() != null ? o.getInspectionFeePolicy() : InspectionFeePolicy.NO_FEE,
                o.isInspectionFeeAuditFlag(),
                warning,
                o.getWorkmanshipDurationDays(),
                o.getPriceVersion() > 0 ? o.getPriceVersion() : 1,
                areas);
    }

    private ProviderResponse toProviderResponse(ProviderEntity p) {
        return new ProviderResponse(
                p.getId(),
                p.getName(),
                p.getDescription(),
                p.getCity(),
                p.getPhone(),
                p.getRatingAvg(),
                p.getStatus());
    }

    private TechnicianResponse toTechnicianResponse(Technician t) {
        List<String> specs = Arrays.stream(t.getSpecialties().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return new TechnicianResponse(
                t.getId(),
                t.getProfileId(),
                t.getProviderId(),
                t.getFullName(),
                specs,
                t.getExperienceYears(),
                t.getRatingAvg(),
                t.getActiveAllocations(),
                t.getStatus());
    }

    private ScheduleWindowResponse toScheduleResponse(ScheduleWindow w) {
        return toScheduleResponse(w, true);
    }

    private ScheduleWindowResponse toScheduleResponse(ScheduleWindow w, boolean includeBookingId) {
        return new ScheduleWindowResponse(
                w.getId(),
                w.getTechnicianId(),
                w.getStartsAt(),
                w.getEndsAt(),
                w.getStatus(),
                includeBookingId ? w.getAllocatedBookingId() : null,
                w.getVersion());
    }
}
