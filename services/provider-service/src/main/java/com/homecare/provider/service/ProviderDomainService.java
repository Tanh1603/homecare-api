package com.homecare.provider.service;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.homecare.common.error.DomainException;
import com.homecare.common.web.EtagSupport;
import com.homecare.provider.dto.ProviderDtos.CreateScheduleWindowRequest;
import com.homecare.provider.dto.ProviderDtos.ProviderResponse;
import com.homecare.provider.dto.ProviderDtos.ScheduleWindowResponse;
import com.homecare.provider.dto.ProviderDtos.ServiceOfferingResponse;
import com.homecare.provider.dto.ProviderDtos.TechnicianResponse;
import com.homecare.provider.dto.ProviderDtos.UpdateScheduleWindowRequest;
import com.homecare.provider.entity.ProviderEntity;
import com.homecare.provider.entity.ScheduleWindow;
import com.homecare.provider.entity.ServiceOffering;
import com.homecare.provider.entity.Technician;
import com.homecare.provider.entity.TechnicianAllocation;
import com.homecare.provider.repository.ProviderRepository;
import com.homecare.provider.repository.ScheduleWindowRepository;
import com.homecare.provider.repository.ServiceOfferingRepository;
import com.homecare.provider.repository.TechnicianAllocationRepository;
import com.homecare.provider.repository.TechnicianRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProviderDomainService {

    private final ProviderRepository providerRepository;
    private final ServiceOfferingRepository offeringRepository;
    private final TechnicianRepository technicianRepository;
    private final ScheduleWindowRepository scheduleWindowRepository;
    private final TechnicianAllocationRepository allocationRepository;

    @Transactional(readOnly = true)
    public List<ServiceOfferingResponse> listOfferings(String category) {
        return offeringRepository.findByActiveTrueOrderByCreatedAtDesc()
                .stream()
                .filter(o -> category == null || category.isBlank() || o.getCategory().equalsIgnoreCase(category.trim()))
                .map(this::toOfferingResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public ServiceOfferingResponse getOffering(UUID serviceId) {
        ServiceOffering offering = offeringRepository.findById(serviceId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "Service offering not found."));
        return toOfferingResponse(offering);
    }

    @Transactional(readOnly = true)
    public List<ProviderResponse> listProviders() {
        return providerRepository.findAll().stream().map(this::toProviderResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProviderResponse getProvider(UUID providerId) {
        ProviderEntity p = providerRepository.findById(providerId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "PROVIDER_NOT_FOUND", "Provider not found."));
        return toProviderResponse(p);
    }

    @Transactional(readOnly = true)
    public List<TechnicianResponse> listTechnicians(UUID providerId) {
        List<Technician> techs = providerId != null
                ? technicianRepository.findByProviderIdAndStatus(providerId, "ACTIVE")
                : technicianRepository.findByStatus("ACTIVE");
        return techs.stream().map(this::toTechnicianResponse).toList();
    }

    @Transactional(readOnly = true)
    public TechnicianResponse getTechnician(UUID technicianId) {
        Technician t = technicianRepository.findById(technicianId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "TECHNICIAN_NOT_FOUND", "Technician not found."));
        return toTechnicianResponse(t);
    }

    @Transactional
    public Technician resolveTechnicianOrDemo(String technicianIdOrMe, String jwtSubject) {
        if ("me".equalsIgnoreCase(technicianIdOrMe)) {
            if (jwtSubject != null) {
                Optional<Technician> bySub = technicianRepository.findByProfileId(jwtSubject);
                if (bySub.isPresent()) {
                    return bySub.get();
                }
            }
            return technicianRepository.findById(ProviderDataInitializer.DEMO_TECH_ID)
                    .orElseThrow(() -> new DomainException(
                            HttpStatus.NOT_FOUND, "TECHNICIAN_NOT_FOUND", "Demo technician not found."));
        }
        UUID id = UUID.fromString(technicianIdOrMe);
        return technicianRepository.findById(id)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "TECHNICIAN_NOT_FOUND", "Technician not found."));
    }

    @Transactional(readOnly = true)
    public List<ScheduleWindowResponse> listScheduleWindows(String technicianIdOrMe, String jwtSubject) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, jwtSubject);
        return scheduleWindowRepository.findByTechnicianIdOrderByStartsAtAsc(tech.getId())
                .stream()
                .map(this::toScheduleResponse)
                .toList();
    }

    @Transactional
    public ScheduleWindowResponse createScheduleWindow(
            String technicianIdOrMe,
            String jwtSubject,
            CreateScheduleWindowRequest request) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, jwtSubject);
        if (!request.endsAt().isAfter(request.startsAt())) {
            throw new DomainException(
                    HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "endsAt must be after startsAt.");
        }
        List<ScheduleWindow> overlaps = scheduleWindowRepository.findOverlapping(
                tech.getId(), request.startsAt(), request.endsAt());
        if (!overlaps.isEmpty()) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "SCHEDULE_OVERLAP",
                    "Schedule window overlaps an existing schedule window.");
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
            UpdateScheduleWindowRequest request) {
        Technician tech = resolveTechnicianOrDemo(technicianIdOrMe, jwtSubject);
        ScheduleWindow window = scheduleWindowRepository.findById(windowId)
                .orElseThrow(() -> new DomainException(
                        HttpStatus.NOT_FOUND, "SCHEDULE_WINDOW_NOT_FOUND", "Schedule window not found."));
        if (!window.getTechnicianId().equals(tech.getId())) {
            throw new DomainException(
                    HttpStatus.FORBIDDEN, "RESOURCE_FORBIDDEN", "Not the owner of this schedule window.");
        }
        EtagSupport.verifyIfMatch(ifMatchHeader, window.getVersion());
        if (window.getAllocatedBookingId() != null) {
            throw new DomainException(
                    HttpStatus.CONFLICT,
                    "SCHEDULE_ALLOCATION_CONFLICT",
                    "Cannot modify a schedule window with an active booking allocation.");
        }
        if (request.startsAt() != null) {
            window.setStartsAt(request.startsAt());
        }
        if (request.endsAt() != null) {
            window.setEndsAt(request.endsAt());
        }
        if (request.status() != null && !request.status().isBlank()) {
            window.setStatus(request.status().trim().toUpperCase());
        }
        return toScheduleResponse(scheduleWindowRepository.saveAndFlush(window));
    }

    @Transactional
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

    @Transactional
    public AllocationOutcome allocateTechnicianForBooking(
            String intentId,
            UUID bookingId,
            UUID serviceId,
            String deviceType,
            Instant preferredStart,
            Instant preferredEnd,
            UUID preferredProviderId,
            boolean strictProviderMatch) {
        Optional<TechnicianAllocation> existing = allocationRepository.findByIntentId(intentId);
        if (existing.isPresent()) {
            TechnicianAllocation alloc = existing.get();
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
        if (offeringOpt.isEmpty() || !offeringOpt.get().isActive()) {
            return new AllocationOutcome(false, null, "", "", null, null, "SERVICE_NOT_AVAILABLE");
        }
        ServiceOffering offering = offeringOpt.get();
        String requiredSpecialty = (deviceType != null && !deviceType.isBlank())
                ? deviceType.trim().toUpperCase()
                : offering.getCategory().toUpperCase();

        UUID targetProviderId = preferredProviderId != null ? preferredProviderId : offering.getProviderId();

        List<Technician> candidates;
        if (strictProviderMatch) {
            // Flow 1 (WARRANTY_CLAIM): Strictly lock technician search to the store that issued the warranty
            candidates = technicianRepository.findByProviderIdAndStatus(targetProviderId, "ACTIVE");
        } else {
            // Flow 2 & 3 (STANDARD): Search all active technicians, giving +25.0 affinity bonus to targetProviderId
            candidates = technicianRepository.findByStatus("ACTIVE");
        }

        List<Technician> eligible = candidates.stream()
                .filter(t -> matchesSpecialty(t, requiredSpecialty))
                .filter(t -> !scheduleWindowRepository.findAvailableCovering(
                        t.getId(), preferredStart, preferredEnd).isEmpty())
                .filter(t -> allocationRepository.findActiveOverlapping(
                        t.getId(), preferredStart, preferredEnd).isEmpty())
                .sorted(Comparator.comparingDouble((Technician t) -> aiRankScoreWithStoreAffinity(t, targetProviderId)).reversed())
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

        Technician chosen = eligible.getFirst();
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
        return new ScheduleWindowResponse(
                w.getId(),
                w.getTechnicianId(),
                w.getStartsAt(),
                w.getEndsAt(),
                w.getStatus(),
                w.getAllocatedBookingId(),
                w.getVersion());
    }
}
