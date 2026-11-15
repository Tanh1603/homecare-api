package com.homecare.household.service;

import tools.jackson.databind.ObjectMapper;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.household.client.ProviderOrgClient;
import com.homecare.household.dto.HouseholdDtos.CareRecordResponse;
import com.homecare.household.dto.HouseholdDtos.CreateCareRecordRequest;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.CreateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.CreateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.DeviceResponse;
import com.homecare.household.dto.HouseholdDtos.HouseholdResponse;
import com.homecare.household.dto.HouseholdDtos.MaintenanceScheduleResponse;
import com.homecare.household.dto.HouseholdDtos.UpdateCareRecordRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceResponse;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyResponse;
import com.homecare.household.dto.HouseholdDtos.WarrantyResponse;
import com.homecare.household.entity.CareRecord;
import com.homecare.household.entity.Device;
import com.homecare.household.entity.Household;
import com.homecare.household.entity.HouseholdOutboxEvent;
import com.homecare.household.entity.MaintenanceSchedule;
import com.homecare.household.entity.Warranty;
import com.homecare.household.exception.HouseholdErrorCode;
import com.homecare.household.repository.CareRecordRepository;
import com.homecare.household.repository.DeviceRepository;
import com.homecare.household.repository.HouseholdOutboxEventRepository;
import com.homecare.household.repository.HouseholdRepository;
import com.homecare.household.repository.MaintenanceScheduleRepository;
import com.homecare.household.repository.WarrantyRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HouseholdDomainService {

    private final HouseholdRepository householdRepository;
    private final DeviceRepository deviceRepository;
    private final WarrantyRepository warrantyRepository;
    private final MaintenanceScheduleRepository scheduleRepository;
    private final CareRecordRepository careRecordRepository;
    private final HouseholdOutboxEventRepository outboxEventRepository;
    private final ProviderOrgClient providerOrgClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public HouseholdResponse createHousehold(String ownerProfileId, CreateHouseholdRequest request) {
        Instant now = Instant.now();
        Household saved = householdRepository.save(Household.builder()
                .id(UUID.randomUUID())
                .ownerProfileId(ownerProfileId)
                .name(request.name().trim())
                .address(request.address().trim())
                .timezone(request.timezone() == null || request.timezone().isBlank()
                        ? "Asia/Ho_Chi_Minh"
                        : request.timezone().trim())
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toHouseholdResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<HouseholdResponse> listHouseholds(String ownerProfileId) {
        return householdRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId)
                .stream()
                .map(this::toHouseholdResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public HouseholdResponse getHousehold(String ownerProfileId, UUID householdId) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        return toHouseholdResponse(h);
    }

    @Transactional
    public HouseholdResponse updateHousehold(String ownerProfileId, UUID householdId, UpdateHouseholdRequest request) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        if (request.name() != null && !request.name().isBlank()) {
            h.setName(request.name().trim());
        }
        if (request.address() != null && !request.address().isBlank()) {
            h.setAddress(request.address().trim());
        }
        if (request.timezone() != null && !request.timezone().isBlank()) {
            h.setTimezone(request.timezone().trim());
        }
        h.setUpdatedAt(Instant.now());
        return toHouseholdResponse(householdRepository.save(h));
    }

    @Transactional
    public void deleteHousehold(String ownerProfileId, UUID householdId) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        for (var device : deviceRepository.findByHouseholdIdOrderByCreatedAtDesc(householdId)) {
            deleteDevice(ownerProfileId, device.getId());
        }
        householdRepository.delete(h);
    }

    @Transactional
    public DeviceResponse createDevice(String ownerProfileId, CreateDeviceRequest request) {
        UUID householdId = request.householdId();
        if (householdId != null) {
            requireOwnedHousehold(ownerProfileId, householdId);
        } else {
            householdId = ensureDefaultHouseholdId(ownerProfileId);
        }
        Instant now = Instant.now();
        Device saved = deviceRepository.saveAndFlush(Device.builder()
                .id(UUID.randomUUID())
                .householdId(householdId)
                .ownerProfileId(ownerProfileId)
                .name(request.name().trim())
                .deviceType(request.deviceType().trim().toUpperCase())
                .brand(request.brand().trim())
                .model(request.model().trim())
                .serialNumber(request.serialNumber())
                .purchaseDate(request.purchaseDate())
                .status(EntityStatus.ACTIVE.name())
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toDeviceResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> listDevices(String ownerProfileId, UUID householdId) {
        List<Device> devices = householdId != null
                ? deviceRepository.findByHouseholdIdOrderByCreatedAtDesc(
                        requireOwnedHousehold(ownerProfileId, householdId).getId())
                : deviceRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId);
        return devices.stream().map(this::toDeviceResponse).toList();
    }

    @Transactional(readOnly = true)
    public DeviceResponse getDevice(String ownerProfileId, UUID deviceId) {
        return toDeviceResponse(requireOwnedDevice(ownerProfileId, deviceId));
    }

    @Transactional
    public DeviceResponse updateDevice(
            String ownerProfileId,
            UUID deviceId,
            String ifMatchHeader,
            UpdateDeviceRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        EtagSupport.verifyIfMatch(ifMatchHeader, device.getVersion());
        if (request.name() != null && !request.name().isBlank()) {
            device.setName(request.name().trim());
        }
        if (request.deviceType() != null && !request.deviceType().isBlank()) {
            device.setDeviceType(request.deviceType().trim().toUpperCase());
        }
        if (request.brand() != null && !request.brand().isBlank()) {
            device.setBrand(request.brand().trim());
        }
        if (request.model() != null && !request.model().isBlank()) {
            device.setModel(request.model().trim());
        }
        if (request.serialNumber() != null) {
            device.setSerialNumber(request.serialNumber().trim());
        }
        if (request.purchaseDate() != null) {
            device.setPurchaseDate(request.purchaseDate());
        }
        if (request.status() != null && !request.status().isBlank()) {
            device.setStatus(request.status().trim().toUpperCase());
        }
        device.setUpdatedAt(Instant.now());
        return toDeviceResponse(deviceRepository.saveAndFlush(device));
    }

    @Transactional
    public void deleteDevice(String ownerProfileId, UUID deviceId) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        careRecordRepository.deleteByDeviceId(deviceId);
        scheduleRepository.deleteByDeviceId(deviceId);
        warrantyRepository.deleteByDeviceId(deviceId);
        deviceRepository.delete(device);
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyResponse> warrantiesPage(String user, UUID org, boolean admin, boolean operator, int page, int size) {
        var pageable = pageRequest(page, size, "endsOn");
        if (org != null && !admin && (!operator || !providerOrgClient.verifyOrganizationMembership(org, user))) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
        }
        var result = org != null
                ? warrantyRepository.search(null, org, pageable)
                : warrantyRepository.search(admin ? null : user, null, pageable);
        return PageResponse.of(result.map(this::toWarrantyResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public PageResponse<MaintenanceScheduleResponse> schedulesPage(String user, int page, int size) {
        var result = scheduleRepository.findByOwnerProfileId(user, pageRequest(page, size, "dueOn"));
        return PageResponse.of(result.map(this::toScheduleResponse).getContent(), page, size, result.getTotalElements());
    }

    private Pageable pageRequest(int page, int size, String field) {
        if (page < 1 || size < 1 || size > 100) throw new DomainException(CommonErrorCode.VALIDATION_FAILED);
        return PageRequest.of(page - 1, size, Sort.by(field, "id"));
    }

    @Transactional
    public WarrantyResponse createWarranty(String ownerProfileId, UUID deviceId, CreateWarrantyRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        if (request.endsOn().isBefore(request.startsOn())) {
            throw new DomainException(
                    HouseholdErrorCode.DATE_ORDER_INVALID,
                    "Warranty endsOn must not be before startsOn.");
        }
        Warranty saved = warrantyRepository.save(Warranty.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(ownerProfileId)
                .organizationId(null)
                .source("MANUAL")
                .providerName(request.providerName().trim())
                .startsOn(request.startsOn())
                .endsOn(request.endsOn())
                .notes(request.notes())
                .documentUrl(request.documentUrl())
                .status(request.endsOn().isBefore(LocalDate.now()) ? "EXPIRED" : EntityStatus.ACTIVE.name())
                .createdAt(Instant.now())
                .build());
        return toWarrantyResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<WarrantyResponse> listWarrantiesForOrganization(
            String actorProfileId,
            UUID organizationId,
            boolean globalAdmin) {
        if (!globalAdmin) {
            if (organizationId == null || actorProfileId == null
                    || !providerOrgClient.verifyOrganizationMembership(organizationId, actorProfileId)) {
                throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN);
            }
        }
        return listWarrantiesForOrganization(organizationId, globalAdmin);
    }

    @Transactional(readOnly = true)
    public List<WarrantyResponse> listWarrantiesForOrganization(UUID organizationId, boolean globalAdmin) {
        List<Warranty> list;
        if (organizationId != null) {
            list = warrantyRepository.findByOrganizationIdOrderByEndsOnDesc(organizationId);
        } else if (globalAdmin) {
            list = warrantyRepository.findAllByOrderByEndsOnDesc();
        } else {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Organization context is required.");
        }
        return list.stream().map(this::toWarrantyResponse).toList();
    }

    @Transactional
    public WarrantyResponse autoActivateWarrantyFromOrderLineDelivered(
            String buyerProfileId,
            UUID organizationId,
            String merchantName,
            UUID orderId,
            UUID orderLineId,
            UUID productId,
            String productName,
            String category,
            String brand,
            String productType,
            int warrantyMonths) {
        if (warrantyMonths <= 0) {
            return null;
        }
        Optional<Warranty> existing = warrantyRepository.findByOrderLineId(orderLineId);
        if (existing.isPresent()) {
            return toWarrantyResponse(existing.get());
        }

        UUID householdId = ensureDefaultHouseholdId(buyerProfileId);
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        Device createdDevice = deviceRepository.saveAndFlush(Device.builder()
                .id(UUID.randomUUID())
                .householdId(householdId)
                .ownerProfileId(buyerProfileId)
                .name(productName != null && !productName.isBlank() ? productName.trim() : "Thiết bị mua tại HomeCare")
                .deviceType(category != null && !category.isBlank() ? category.trim().toUpperCase() : "AC")
                .brand(brand != null && !brand.isBlank() ? brand.trim() : "HomeCare")
                .model(productType != null && !productType.isBlank() ? productType.trim() : "MAJOR_PART")
                .purchaseDate(today)
                .status(EntityStatus.ACTIVE.name())
                .createdAt(now)
                .updatedAt(now)
                .build());

        Warranty savedWarranty = warrantyRepository.save(Warranty.builder()
                .id(UUID.randomUUID())
                .deviceId(createdDevice.getId())
                .ownerProfileId(buyerProfileId)
                .organizationId(organizationId)
                .orderId(orderId)
                .orderLineId(orderLineId)
                .productId(productId)
                .source("ORDER_AUTO_ACTIVATED")
                .providerName(
                        merchantName != null && !merchantName.isBlank() ? merchantName.trim() : "Cửa hàng HomeCare")
                .startsOn(today)
                .endsOn(today.plusMonths(warrantyMonths))
                .notes("Bảo hành điện tử tự động kích hoạt (" + warrantyMonths + " tháng) từ đơn hàng #" + orderId)
                .status(EntityStatus.ACTIVE.name())
                .createdAt(now)
                .build());
        return toWarrantyResponse(savedWarranty);
    }

    @Transactional
    public CareRecordResponse recordCareHistoryFromBookingCompleted(
            UUID bookingId,
            UUID deviceId,
            String requesterProfileId,
            String bookingType,
            String serviceName,
            String notes) {
        if (bookingId == null || deviceId == null) {
            return null;
        }
        Optional<CareRecord> existing = careRecordRepository.findByBookingId(bookingId);
        if (existing.isPresent()) {
            return toCareRecordResponse(existing.get());
        }
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            return null;
        }
        Device device = deviceOpt.get();
        String kind = "WARRANTY_CLAIM".equalsIgnoreCase(bookingType) ? "WARRANTY_REPAIR" : "PAID_REPAIR";
        Instant now = Instant.now();
        CareRecord saved = careRecordRepository.save(CareRecord.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(requesterProfileId != null ? requesterProfileId : device.getOwnerProfileId())
                .kind(kind)
                .occurredAt(now)
                .title(serviceName != null && !serviceName.isBlank() ? serviceName : kind)
                .notes(notes)
                .bookingId(bookingId)
                .createdAt(now)
                .build());
        return toCareRecordResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<WarrantyResponse> listWarranties(String ownerProfileId, UUID deviceId) {
        requireOwnedDevice(ownerProfileId, deviceId);
        return warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId)
                .stream()
                .map(this::toWarrantyResponse)
                .toList();
    }

    @Transactional
    public WarrantyResponse updateWarranty(String ownerProfileId, UUID warrantyId, UpdateWarrantyRequest request) {
        Warranty w = requireMutableOwnedWarranty(ownerProfileId, warrantyId);
        LocalDate newStarts = request.startsOn() != null ? request.startsOn() : w.getStartsOn();
        LocalDate newEnds = request.endsOn() != null ? request.endsOn() : w.getEndsOn();
        if (newEnds.isBefore(newStarts)) {
            throw new DomainException(
                    HouseholdErrorCode.DATE_ORDER_INVALID,
                    "Warranty endsOn must not be before startsOn.");
        }
        w.setStartsOn(newStarts);
        w.setEndsOn(newEnds);
        if (request.providerName() != null && !request.providerName().isBlank()) {
            w.setProviderName(request.providerName().trim());
        }
        if (request.notes() != null) {
            w.setNotes(request.notes());
        }
        if (request.documentUrl() != null) {
            w.setDocumentUrl(request.documentUrl());
        }
        if (request.status() != null && !request.status().isBlank()) {
            w.setStatus(request.status().trim().toUpperCase());
        }
        return toWarrantyResponse(warrantyRepository.save(w));
    }

    @Transactional
    public void deleteWarranty(String ownerProfileId, UUID warrantyId) {
        Warranty w = requireMutableOwnedWarranty(ownerProfileId, warrantyId);
        warrantyRepository.delete(w);
    }

    private Warranty requireMutableOwnedWarranty(String ownerProfileId, UUID warrantyId) {
        Warranty w = warrantyRepository.findById(warrantyId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.WARRANTY_NOT_FOUND));
        if (!w.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this warranty.");
        }
        if ("ORDER_AUTO_ACTIVATED".equalsIgnoreCase(w.getSource())) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Store-issued warranties cannot be modified or deleted.");
        }
        return w;
    }

    @Transactional
    public MaintenanceScheduleResponse createSchedule(
            String ownerProfileId,
            UUID deviceId,
            CreateMaintenanceScheduleRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        MaintenanceSchedule saved = scheduleRepository.save(MaintenanceSchedule.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(ownerProfileId)
                .title(request.title().trim())
                .cadenceMonths(request.cadenceMonths() == null ? 6 : request.cadenceMonths())
                .dueOn(request.dueOn())
                .status("SCHEDULED")
                .createdAt(Instant.now())
                .build());
        return toScheduleResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<MaintenanceScheduleResponse> listSchedules(String ownerProfileId, UUID deviceId) {
        requireOwnedDevice(ownerProfileId, deviceId);
        return scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId)
                .stream()
                .map(this::toScheduleResponse)
                .toList();
    }

    @Transactional
    public MaintenanceScheduleResponse updateSchedule(
            String ownerProfileId,
            UUID scheduleId,
            UpdateMaintenanceScheduleRequest request) {
        MaintenanceSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.SCHEDULE_NOT_FOUND));
        if (!s.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this schedule.");
        }
        if (request.dueOn() != null
                && s.getNotifiedAt() != null
                && !"COMPLETED".equals(request.status())) {
            throw new DomainException(
                    HouseholdErrorCode.SCHEDULE_ALREADY_NOTIFIED,
                    "Schedule has already triggered a notification; complete or create a new schedule.");
        }
        if (request.title() != null && !request.title().isBlank()) {
            s.setTitle(request.title().trim());
        }
        if (request.cadenceMonths() != null) {
            s.setCadenceMonths(request.cadenceMonths());
        }
        if (request.dueOn() != null) {
            s.setDueOn(request.dueOn());
        }
        if (request.status() != null && !request.status().isBlank()) {
            String nextStatus = request.status().trim().toUpperCase();
            s.setStatus(nextStatus);
            if ("COMPLETED".equals(nextStatus)) {
                s.setCompletedAt(Instant.now());
            }
        }
        return toScheduleResponse(scheduleRepository.save(s));
    }

    @Transactional
    public void deleteSchedule(String ownerProfileId, UUID scheduleId) {
        MaintenanceSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.SCHEDULE_NOT_FOUND));
        if (!s.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this schedule.");
        }
        scheduleRepository.delete(s);
    }

    @Transactional
    public CareRecordResponse createCareRecord(
            String ownerProfileId,
            UUID deviceId,
            CreateCareRecordRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : Instant.now();
        CareRecord saved = careRecordRepository.save(CareRecord.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(ownerProfileId)
                .kind(request.kind().trim().toUpperCase())
                .occurredAt(occurredAt)
                .title(request.title() != null ? request.title().trim() : request.kind().trim())
                .notes(request.notes())
                .bookingId(request.bookingId())
                .createdAt(Instant.now())
                .build());
        return toCareRecordResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<CareRecordResponse> listCareRecords(String ownerProfileId, UUID deviceId) {
        requireOwnedDevice(ownerProfileId, deviceId);
        return careRecordRepository.findByDeviceIdOrderByOccurredAtDesc(deviceId)
                .stream()
                .map(this::toCareRecordResponse)
                .toList();
    }

    @Transactional
    public CareRecordResponse updateCareRecord(
            String ownerProfileId,
            UUID recordId,
            UpdateCareRecordRequest request) {
        CareRecord r = careRecordRepository.findById(recordId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.CARE_RECORD_NOT_FOUND));
        if (!r.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this care record.");
        }
        if (request.kind() != null && !request.kind().isBlank()) {
            r.setKind(request.kind().trim().toUpperCase());
        }
        if (request.occurredAt() != null) {
            r.setOccurredAt(request.occurredAt());
        }
        if (request.title() != null) {
            r.setTitle(request.title().trim());
        }
        if (request.notes() != null) {
            r.setNotes(request.notes());
        }
        return toCareRecordResponse(careRecordRepository.save(r));
    }

    @Transactional
    public void deleteCareRecord(String ownerProfileId, UUID recordId) {
        CareRecord r = careRecordRepository.findById(recordId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.CARE_RECORD_NOT_FOUND));
        if (!r.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this care record.");
        }
        careRecordRepository.delete(r);
    }

    @Transactional(readOnly = true)
    public VerifyDeviceReferenceResponse verifyDeviceReference(UUID deviceId, String requesterProfileId) {
        return deviceRepository.findById(deviceId)
                .filter(d -> d.getOwnerProfileId().equals(requesterProfileId) && EntityStatus.ACTIVE.name().equals(d.getStatus()))
                .map(d -> new VerifyDeviceReferenceResponse(
                        true,
                        d.getId(),
                        d.getHouseholdId(),
                        d.getDeviceType(),
                        d.getName(),
                        d.getStatus()))
                .orElseGet(() -> new VerifyDeviceReferenceResponse(
                        false,
                        deviceId,
                        null,
                        null,
                        null,
                        "NOT_FOUND_OR_FORBIDDEN"));
    }

    @Transactional(readOnly = true)
    public VerifyWarrantyResponse verifyWarrantyEligibility(UUID warrantyId, String requesterProfileId) {
        Optional<Warranty> warrantyOpt = warrantyRepository.findById(warrantyId);
        if (warrantyOpt.isEmpty() || !warrantyOpt.get().getOwnerProfileId().equals(requesterProfileId)) {
            return new VerifyWarrantyResponse(
                    false,
                    false,
                    warrantyId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    "NOT_FOUND_OR_FORBIDDEN");
        }
        Warranty w = warrantyOpt.get();
        Device device = deviceRepository.findById(w.getDeviceId()).orElse(null);
        String deviceType = device != null ? device.getDeviceType() : "AC";
        if (!"ORDER_AUTO_ACTIVATED".equalsIgnoreCase(w.getSource()) || w.getOrganizationId() == null) {
            return new VerifyWarrantyResponse(
                    false,
                    false,
                    w.getId(),
                    w.getDeviceId(),
                    deviceType,
                    null,
                    w.getProviderName(),
                    w.getEndsOn(),
                    "NOT_STORE_WARRANTY");
        }
        boolean isExpired = w.getEndsOn().isBefore(LocalDate.now()) || !EntityStatus.ACTIVE.name().equalsIgnoreCase(w.getStatus());
        if (isExpired) {
            return new VerifyWarrantyResponse(
                    false,
                    true,
                    w.getId(),
                    w.getDeviceId(),
                    deviceType,
                    w.getOrganizationId(),
                    w.getProviderName(),
                    w.getEndsOn(),
                    "EXPIRED");
        }
        return new VerifyWarrantyResponse(
                true,
                false,
                w.getId(),
                w.getDeviceId(),
                deviceType,
                w.getOrganizationId(),
                w.getProviderName(),
                w.getEndsOn(),
                w.getStatus());
    }

    @Transactional
    public int enqueueDueMaintenanceReminders(LocalDate referenceDate) {
        List<MaintenanceSchedule> due = scheduleRepository
                .findByStatusAndNotifiedAtIsNullAndDueOnLessThanEqual("SCHEDULED", referenceDate);
        Instant now = Instant.now();
        for (MaintenanceSchedule schedule : due) {
            schedule.setNotifiedAt(now);
            scheduleRepository.save(schedule);
            UUID eventId = UUID.randomUUID();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scheduleId", schedule.getId());
            data.put("deviceId", schedule.getDeviceId());
            data.put("ownerProfileId", schedule.getOwnerProfileId());
            data.put("title", schedule.getTitle());
            data.put("dueOn", schedule.getDueOn().toString());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, "maintenance.reminder.due.v1");
            envelope.put(DomainEventContracts.FIELD_OCCURRED_AT, now.toString());
            envelope.put(DomainEventContracts.FIELD_PRODUCER, "household-service");
            envelope.put("correlationId", eventId);
            envelope.put(DomainEventContracts.FIELD_DATA, data);

            String payloadJson;
            try {
                payloadJson = objectMapper.writeValueAsString(envelope);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to serialize household outbox payload", e);
            }

            outboxEventRepository.save(HouseholdOutboxEvent.builder()
                    .id(eventId)
                    .aggregateType("MaintenanceSchedule")
                    .aggregateId(schedule.getId())
                    .eventType("maintenance.reminder.due.v1")
                    .routingKey("maintenance.reminder.due.v1")
                    .payloadJson(payloadJson)
                    .status(EntityStatus.PENDING.name())
                    .retryCount(0)
                    .nextAttemptAt(now)
                    .createdAt(now)
                    .build());
        }
        return due.size();
    }

    private UUID ensureDefaultHouseholdId(String ownerProfileId) {
        List<Household> households = householdRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId);
        if (!households.isEmpty()) {
            return households.getFirst().getId();
        }
        HouseholdResponse created = createHousehold(
                ownerProfileId,
                new CreateHouseholdRequest("Gia đình của tôi", "TP. Hồ Chí Minh", "Asia/Ho_Chi_Minh"));
        return created.id();
    }

    private Household requireOwnedHousehold(String ownerProfileId, UUID householdId) {
        Household h = householdRepository.findById(householdId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.HOUSEHOLD_NOT_FOUND));
        if (!h.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "You do not own this household.");
        }
        return h;
    }

    private Device requireOwnedDevice(String ownerProfileId, UUID deviceId) {
        Device d = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.DEVICE_NOT_FOUND));
        if (!d.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "You do not own this device.");
        }
        return d;
    }

    private HouseholdResponse toHouseholdResponse(Household h) {
        return new HouseholdResponse(
                h.getId(),
                h.getOwnerProfileId(),
                h.getName(),
                h.getAddress(),
                h.getTimezone(),
                h.getCreatedAt(),
                h.getUpdatedAt());
    }

    private DeviceResponse toDeviceResponse(Device d) {
        return new DeviceResponse(
                d.getId(),
                d.getHouseholdId(),
                d.getOwnerProfileId(),
                d.getName(),
                d.getDeviceType(),
                d.getDeviceType(),
                d.getBrand(),
                d.getModel(),
                d.getSerialNumber(),
                d.getPurchaseDate(),
                d.getStatus(),
                d.getVersion(),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }

    private WarrantyResponse toWarrantyResponse(Warranty w) {
        return new WarrantyResponse(
                w.getId(),
                w.getDeviceId(),
                w.getOrganizationId(),
                w.getOrderId(),
                w.getOrderLineId(),
                w.getProductId(),
                w.getSource(),
                w.getProviderName(),
                w.getStartsOn(),
                w.getStartsOn(),
                w.getEndsOn(),
                w.getEndsOn(),
                w.getNotes(),
                w.getNotes(),
                w.getDocumentUrl(),
                w.getStatus(),
                w.getCreatedAt());
    }

    private MaintenanceScheduleResponse toScheduleResponse(MaintenanceSchedule s) {
        return new MaintenanceScheduleResponse(
                s.getId(),
                s.getDeviceId(),
                s.getTitle(),
                s.getCadenceMonths(),
                s.getDueOn(),
                s.getStatus(),
                s.getNotifiedAt(),
                s.getCompletedAt(),
                s.getCreatedAt());
    }

    private CareRecordResponse toCareRecordResponse(CareRecord r) {
        return new CareRecordResponse(
                r.getId(),
                r.getDeviceId(),
                r.getKind(),
                r.getKind(),
                r.getOccurredAt(),
                r.getOccurredAt(),
                r.getTitle(),
                r.getNotes(),
                r.getBookingId(),
                r.getCreatedAt());
    }
}
