package com.homecare.provider.service;

import lombok.RequiredArgsConstructor;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.OfferingInput;
import com.homecare.provider.dto.ProviderDtos.ProviderInput;
import com.homecare.provider.dto.ProviderDtos.ProviderResponse;
import com.homecare.provider.dto.ProviderDtos.ScheduleWindowResponse;
import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.dto.ProviderDtos.TechnicianInput;
import com.homecare.provider.dto.ProviderDtos.TechnicianResponse;
import com.homecare.provider.dto.ProviderDtos.UpdateScheduleWindowRequest;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.ServiceOffering;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.entity.TechnicianAllocation;
import com.homecare.provider.exception.ProviderErrorCode;
import com.homecare.provider.organization.entity.OrganizationMemberEntity;
import com.homecare.provider.organization.entity.OrganizationStatus;
import com.homecare.provider.organization.entity.OrgRole;
import com.homecare.provider.organization.repository.OrganizationMemberRepository;
import com.homecare.provider.organization.repository.OrganizationRepository;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.ServiceOfferingRepository;
import com.homecare.provider.repository.TechnicianAllocationRepository;
import com.homecare.provider.repository.TechnicianRepository;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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
        var result = offeringRepository.search(blankToNull(category), providerId, blankToNull(q), pageRequest(page, size));
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
        var result = technicianRepository.search(providerId, blankToNull(q), pageRequest(page, size));
        return PageResponse.of(result.map(this::toTechnicianResponse).getContent(), page, size, result.getTotalElements());
    }

    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private Pageable pageRequest(int page, int size) {
        if (page < 1 || size < 1 || size > 100) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        return PageRequest.of(page - 1, size, Sort.by("createdAt").descending().and(Sort.by("id")));
    }

    public void requireProviderManager(UUID providerId, UUID organizationId, String userId, boolean admin) {
        ProviderEntity provider = providerRepository.findById(providerId)
                .orElseThrow(() -> new DomainException(ProviderErrorCode.PROVIDER_NOT_FOUND));
        if (admin) return;
        if (organizationId == null || !organizationId.equals(provider.getOrganizationId())) throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        var org = organizationRepository.findById(organizationId).filter(o -> o.getStatus() == OrganizationStatus.APPROVED);
        var member = organizationMemberRepository.findByOrganizationIdAndUserId(organizationId, userId)
                .filter(m -> "ACTIVE".equals(m.getStatus()))
                .filter(m -> m.getRole() == OrgRole.ORG_OWNER || m.getRole() == OrgRole.ORG_ADMIN);
        if (org.isEmpty() || member.isEmpty()) throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
    }

    @Transactional
    public ProviderResponse updateProvider(UUID id, ProviderInput input, UUID orgId, String user, boolean admin) {
        requireProviderManager(id, orgId, user, admin);
        var p = providerRepository.findById(id).orElseThrow();
        if (input.name() != null) p.setName(input.name());
        if (input.description() != null) p.setDescription(input.description());
        if (input.city() != null) p.setCity(input.city());
        if (input.phone() != null) p.setPhone(input.phone());
        return toProviderResponse(providerRepository.save(p));
    }

    @Transactional
    public ServiceOfferingResponse saveOffering(UUID id, OfferingInput input, UUID orgId, String user, boolean admin) {
        var item = id == null ? new ServiceOffering() : offeringRepository.findById(id).orElseThrow(() -> new DomainException(ProviderErrorCode.SERVICE_NOT_FOUND));
        UUID provider = id == null ? input.providerId() : item.getProviderId();
        if (provider == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        requireProviderManager(provider, orgId, user, admin);
        if (input.providerId() != null && !provider.equals(input.providerId())) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        if (id == null) {
            if (input.name() == null || input.category() == null || input.basePrice() == null || input.estimatedDurationMinutes() == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
            item.setId(UUID.randomUUID()); item.setProviderId(provider); item.setActive(true); item.setCreatedAt(Instant.now());
        }
        if (input.name() != null) item.setName(input.name());
        if (input.category() != null) item.setCategory(input.category());
        if (input.description() != null) item.setDescription(input.description());
        if (input.basePrice() != null) item.setBasePrice(input.basePrice());
        if (input.estimatedDurationMinutes() != null) item.setEstimatedDurationMinutes(input.estimatedDurationMinutes());
        if (input.active() != null) item.setActive(input.active());
        return toOfferingResponse(offeringRepository.save(item));
    }

    @Transactional
    public void deleteOffering(UUID id, UUID orgId, String user, boolean admin) {
        var item = offeringRepository.findById(id).orElseThrow(() -> new DomainException(ProviderErrorCode.SERVICE_NOT_FOUND));
        requireProviderManager(item.getProviderId(), orgId, user, admin);
        item.setActive(false); offeringRepository.save(item);
    }

    @Transactional
    public TechnicianResponse saveTechnician(UUID id, TechnicianInput input, UUID orgId, String user, boolean admin) {
        var item = id == null ? new Technician() : technicianRepository.findById(id).orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        UUID provider = id == null ? input.providerId() : item.getProviderId();
        if (provider == null) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        requireProviderManager(provider, orgId, user, admin);
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
    public void deleteTechnician(UUID id, UUID orgId, String user, boolean admin) {
        var item = technicianRepository.findById(id).orElseThrow(() -> new DomainException(ProviderErrorCode.TECHNICIAN_NOT_FOUND));
        requireProviderManager(item.getProviderId(), orgId, user, admin);
        if (item.getActiveAllocations() > 0) throw new DomainException(ProviderErrorCode.SCHEDULE_ALLOCATION_CONFLICT);
        item.setStatus("INACTIVE"); technicianRepository.save(item);
    }

    @Transactional(readOnly = true)
    public TechnicianResponse me(String user) { return toTechnicianResponse(resolveTechnicianOrDemo("me", user)); }

    @Transactional
    public void deleteSchedule(String technicianId, UUID windowId, String user, UUID orgId, boolean admin, String ifMatch) {
        var tech = resolveTechnicianOrDemo(technicianId, user);
        if (!user.equals(tech.getProfileId())) requireProviderManager(tech.getProviderId(), orgId, user, admin);
        var window = scheduleWindowRepository.findById(windowId).orElseThrow(() -> new DomainException(ProviderErrorCode.SCHEDULE_WINDOW_NOT_FOUND));
        if (!window.getTechnicianId().equals(tech.getId())) throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        EtagSupport.verifyIfMatch(ifMatch, window.getVersion());
        if (window.getAllocatedBookingId() != null) throw new DomainException(ProviderErrorCode.SCHEDULE_ALLOCATION_CONFLICT);
        scheduleWindowRepository.delete(window);
    }

    private static final Set<String> VALID_SCHEDULE_STATUSES = Set.of("AVAILABLE", "UNAVAILABLE", "BLOCKED", "BOOKED");

    private final ProviderRepository providerRepository;
    private final ServiceOfferingRepository offeringRepository;
    private final TechnicianRepository technicianRepository;
    private final ScheduleWindowRepository scheduleWindowRepository;
    private final TechnicianAllocationRepository allocationRepository;
    private final OrganizationRepository organizationRepository;
    private final OrganizationMemberRepository organizationMemberRepository;
    private final PlatformTransactionManager transactionManager;

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
            return Optional.of("PROVIDER_OWNER");
        }
        boolean isActiveTech = technicianRepository.findByProfileId(userId)
                .filter(t -> EntityStatus.ACTIVE.name().equalsIgnoreCase(t.getStatus()))
                .map(t -> orgId.equals(t.getProviderId()))
                .orElse(false)
                && isProviderActive(orgId);
        return isActiveTech ? Optional.of("TECHNICIAN") : Optional.empty();
    }

    private boolean hasOrgRole(UUID providerId, String jwtSubject, Set<String> allowedRoles) {
        return jwtSubject != null && !jwtSubject.isBlank()
                && resolveMembershipRole(providerId, jwtSubject).filter(allowedRoles::contains).isPresent();
    }

    private void verifyCanManageTechnicianSchedule(Technician tech, String jwtSubject, boolean isGlobalAdmin) {
        if (jwtSubject == null || jwtSubject.isBlank()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        if (isGlobalAdmin || jwtSubject.equals(tech.getProfileId())) {
            return;
        }
        boolean isOrgManager = hasOrgRole(
                tech.getProviderId(),
                jwtSubject,
                Set.of(OrgRole.ORG_OWNER.name(), OrgRole.ORG_ADMIN.name(), "PROVIDER_OWNER"));
        if (!isOrgManager) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
    }

    @Transactional(readOnly = true)
    public List<ScheduleWindowResponse> listScheduleWindows(
            String technicianIdOrMe, String jwtSubject, boolean isGlobalAdmin) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, jwtSubject);
        boolean includeBookingId = isGlobalAdmin
                || (jwtSubject != null && !jwtSubject.isBlank() && (jwtSubject.equals(tech.getProfileId())
                || hasOrgRole(tech.getProviderId(), jwtSubject, Set.of(
                        OrgRole.ORG_OWNER.name(), OrgRole.ORG_ADMIN.name(), OrgRole.ORG_STAFF.name(), "PROVIDER_OWNER"))));
        return scheduleWindowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId())
                .stream()
                .map(w -> toScheduleResponse(w, includeBookingId))
                .toList();
    }

    @Transactional
    public ScheduleWindowResponse createScheduleWindow(
            String technicianIdOrMe,
            String jwtSubject,
            CreateScheduleWindowRequest request,
            boolean isGlobalAdmin) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, jwtSubject);
        verifyCanManageTechnicianSchedule(tech, jwtSubject, isGlobalAdmin);
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "endsAt must be after startsAt.");
        }
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
                .status("AVAILABLE")
                .createdAt(Instant.now())
                .build());
        return toScheduleResponse(saved);
    }

    @Transactional
    public ScheduleWindowResponse updateScheduleWindow(
            String technicianIdOrMe,
            String jwtSubject,
            UUID windowId,
            String ifMatchHeader,
            UpdateScheduleWindowRequest request,
            boolean isGlobalAdmin) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, jwtSubject);
        verifyCanManageTechnicianSchedule(tech, jwtSubject, isGlobalAdmin);
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
            if (!VALID_SCHEDULE_STATUSES.contains(normalizedStatus)) {
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
        if (!preferredEnd.isAfter(preferredStart)) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        Optional<TechnicianAllocation> existing = allocationRepository.findByIntentId(intentId);
        if (existing.isPresent()) {
            TechnicianAllocation alloc = existing.get();
            if (!"ALLOCATED".equals(alloc.getStatus()) || !bookingId.equals(alloc.getBookingId())
                    || (technicianId != null && !technicianId.equals(alloc.getTechnicianId()))) {
                return new AllocationOutcome(false, null, "", "", null, null, "ALLOCATION_CONFLICT");
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
            return new AllocationOutcome(false, null, "", "", null, null, "SERVICE_NOT_AVAILABLE");
        }
        ServiceOffering offering = offeringOpt.get();
        String requiredSpecialty = (deviceType != null && !deviceType.isBlank())
                ? deviceType.trim().toUpperCase()
                : offering.getCategory().toUpperCase();

        UUID targetProviderId = preferredProviderId != null ? preferredProviderId : offering.getProviderId();

        List<Technician> candidates;
        if (strictProviderMatch) {
            // Flow 1 (WARRANTY_CLAIM): Strictly lock technician search to the store that
            // issued the warranty
            candidates = technicianRepository.findByProviderIdAndStatus(targetProviderId, EntityStatus.ACTIVE.name());
        } else {
            // Flow 2 & 3 (STANDARD): Search all active technicians, giving +25.0 affinity
            // bonus to targetProviderId
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
            return new AllocationOutcome(
                    false,
                    null,
                    "",
                    "",
                    targetProviderId,
                    null,
                    strictProviderMatch
                            ? "NO_AVAILABLE_TECHNICIAN_IN_WARRANTY_ORGANIZATION"
                            : "NO_AVAILABLE_TECHNICIAN");
        }

        // Try each candidate in its own REQUIRES_NEW NOWAIT transaction so at most one row lock is held at a time.
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        for (Technician candidate : eligible) {
            try {
                AllocationOutcome claimed = tx.execute(status ->
                        claimCandidateSlot(intentId, bookingId, candidate.getId(), preferredStart, preferredEnd));
                if (claimed != null) {
                    return claimed;
                }
            } catch (PessimisticLockingFailureException ignored) {
                // Candidate is locked by another request; skip without waiting.
            }
        }
        return new AllocationOutcome(false, null, "", "", targetProviderId, null, "NO_AVAILABLE_TECHNICIAN");
    }

    private AllocationOutcome claimCandidateSlot(
            String intentId,
            UUID bookingId,
            UUID candidateId,
            Instant preferredStart,
            Instant preferredEnd) {
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
                .bookingId(bookingId)
                .technicianId(chosen.getId())
                .providerId(chosen.getProviderId())
                .startsAt(preferredStart)
                .endsAt(preferredEnd)
                .status("ALLOCATED")
                .createdAt(Instant.now())
                .build());

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

    @Transactional
    public void releaseBooking(UUID bookingId) {
        var allocation = allocationRepository.lockByBookingId(bookingId).orElse(null);
        if (allocation == null || !"ALLOCATED".equals(allocation.getStatus())) return;
        var tech = technicianRepository.lockById(allocation.getTechnicianId()).orElseThrow();
        tech.setActiveAllocations(Math.max(0, tech.getActiveAllocations() - 1));
        technicianRepository.save(tech);
        for (var window : scheduleWindowRepository.findByAllocatedBookingId(bookingId)) {
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
        allocation.setStatus("RELEASED"); allocationRepository.save(allocation);
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
        return new ServiceOfferingResponse(
                o.getId(),
                o.getProviderId(),
                providerName,
                o.getName(),
                o.getCategory(),
                o.getDescription(),
                o.getBasePrice(),
                o.getEstimatedDurationMinutes(),
                o.isActive());
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
