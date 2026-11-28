package com.homecare.household.service;

import tools.jackson.databind.ObjectMapper;

import com.homecare.common.domain.EntityStatus;
import com.homecare.common.error.CommonErrorCode;
import com.homecare.common.error.DomainException;
import com.homecare.common.security.AccessMode;
import com.homecare.common.security.ActorContext;
import com.homecare.household.security.HouseholdAccessPolicy;
import com.homecare.common.event.DomainEventContracts;
import com.homecare.common.web.EtagSupport;
import com.homecare.common.web.PageResponse;
import com.homecare.household.client.BookingUsageGrpcClient;
import com.homecare.household.client.ProviderOrgClient;
import com.homecare.household.dto.HouseholdDtos.CareRecordResponse;
import com.homecare.household.dto.HouseholdDtos.CreateCareRecordRequest;
import com.homecare.household.dto.HouseholdDtos.CreateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.CreateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.CreateMaintenancePolicyRequest;
import com.homecare.household.dto.HouseholdDtos.CreateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.CreateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.DeviceRemovalResponse;
import com.homecare.household.dto.HouseholdDtos.DeviceResponse;
import com.homecare.household.dto.HouseholdDtos.DeviceLifecycleResponse;
import com.homecare.household.dto.HouseholdDtos.HouseholdResponse;
import com.homecare.household.dto.HouseholdDtos.MaintenancePolicyResponse;
import com.homecare.household.dto.HouseholdDtos.MaintenanceScheduleResponse;
import com.homecare.household.dto.HouseholdDtos.UpdateCareRecordRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateDeviceRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateHouseholdRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateMaintenancePolicyRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateMaintenanceScheduleRequest;
import com.homecare.household.dto.HouseholdDtos.UpdateWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.RegisterSerialRequest;
import com.homecare.household.dto.HouseholdDtos.VerifyDeviceReferenceResponse;
import com.homecare.household.dto.HouseholdDtos.AdminVerifyWarrantyRequest;
import com.homecare.household.dto.HouseholdDtos.AttachWarrantyEvidenceRequest;
import com.homecare.household.dto.HouseholdDtos.VerifyWarrantyResponse;
import com.homecare.household.dto.HouseholdDtos.WarrantyResponse;
import com.homecare.household.entity.CareRecord;
import com.homecare.household.entity.Device;
import com.homecare.household.entity.Household;
import com.homecare.household.entity.HouseholdOutboxEvent;
import com.homecare.household.entity.MaintenanceSchedule;
import com.homecare.household.entity.MaintenancePolicyEntity;
import com.homecare.household.entity.UserAddress;
import com.homecare.household.entity.Warranty;
import com.homecare.household.entity.ManufacturerRegistrationStatus;
import com.homecare.household.exception.HouseholdErrorCode;
import com.homecare.household.repository.CareRecordRepository;
import com.homecare.household.repository.DeviceRepository;
import com.homecare.household.repository.HouseholdOutboxEventRepository;
import com.homecare.household.repository.HouseholdRepository;
import com.homecare.household.repository.MaintenanceScheduleRepository;
import com.homecare.household.repository.MaintenancePolicyRepository;
import com.homecare.household.repository.UserAddressRepository;
import com.homecare.household.repository.WarrantyRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HouseholdDomainService {
    public static final ZoneId VIETNAM_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String ORIGIN_MANUAL = "MANUAL";
    private static final String ORIGIN_STORE_ORDER = "STORE_ORDER";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final Set<String> ALLOWED_POLICY_STATUSES = Set.of("DRAFT", "APPROVED", "ARCHIVED");
    private static final Set<String> ALLOWED_SCHEDULE_STATUSES = Set.of("SCHEDULED", "PAUSED", "COMPLETED", "CANCELLED");

    private final HouseholdRepository householdRepository;
    private final DeviceRepository deviceRepository;
    private final WarrantyRepository warrantyRepository;
    private final MaintenanceScheduleRepository scheduleRepository;
    private final MaintenancePolicyRepository maintenancePolicyRepository;
    private final CareRecordRepository careRecordRepository;
    private final HouseholdOutboxEventRepository outboxEventRepository;
    private final UserAddressRepository userAddressRepository;
    private final AddressBookService addressBookService;
    private final ProviderOrgClient providerOrgClient;
    private final BookingUsageGrpcClient bookingUsageGrpcClient;
    private final HouseholdAccessPolicy accessPolicy;
    private final ObjectMapper objectMapper;

    @Transactional
    public HouseholdResponse createHousehold(String ownerProfileId, CreateHouseholdRequest request) {
        if (request.defaultServiceAddressId() == null && (request.address() == null || request.address().isBlank())) {
            throw new DomainException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "Either defaultServiceAddressId or address must be provided.");
        }
        Instant now = Instant.now();
        Household household = Household.builder()
                .id(UUID.randomUUID())
                .ownerProfileId(ownerProfileId)
                .name(request.name().trim())
                .address(request.address() != null ? request.address().trim() : "")
                .timezone(request.timezone() == null || request.timezone().isBlank()
                        ? "Asia/Ho_Chi_Minh"
                        : request.timezone().trim())
                .createdAt(now)
                .updatedAt(now)
                .build();
        if (request.defaultServiceAddressId() != null) {
            assignServiceAddress(household, request.defaultServiceAddressId(), ownerProfileId);
        }
        Household saved = householdRepository.save(household);
        return toHouseholdResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<HouseholdResponse> householdsPage(String ownerProfileId, int page, int size) {
        var pageable = pageRequest(page, size, "createdAt");
        var result = householdRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId, pageable);
        return PageResponse.of(result.map(this::toHouseholdResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional(readOnly = true)
    public HouseholdResponse getHousehold(String ownerProfileId, UUID householdId) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        return toHouseholdResponse(h);
    }

    @Transactional
    public HouseholdResponse updateHousehold(String ownerProfileId, UUID householdId, UpdateHouseholdRequest request) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        if (request.getName() != null && !request.getName().isBlank()) {
            h.setName(request.getName().trim());
        }
        if (request.isDefaultServiceAddressIdProvided()) {
            if (request.getDefaultServiceAddressId() != null) {
                assignServiceAddress(h, request.getDefaultServiceAddressId(), ownerProfileId);
            } else {
                h.setDefaultServiceAddressId(null);
            }
        }
        // Only allow legacy address String update when household is not linked to a UserAddress.
        if (h.getDefaultServiceAddressId() == null && request.getAddress() != null && !request.getAddress().isBlank()) {
            h.setAddress(request.getAddress().trim());
        }
        if (request.getTimezone() != null && !request.getTimezone().isBlank()) {
            h.setTimezone(request.getTimezone().trim());
        }
        h.setUpdatedAt(Instant.now());
        return toHouseholdResponse(householdRepository.save(h));
    }

    @Transactional
    public HouseholdResponse assignHouseholdServiceAddress(String ownerProfileId, UUID householdId, UUID addressId) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        assignServiceAddress(h, addressId, ownerProfileId);
        h.setUpdatedAt(Instant.now());
        return toHouseholdResponse(householdRepository.save(h));
    }

    private void assignServiceAddress(Household household, UUID addressId, String actorUserId) {
        if (!household.getOwnerProfileId().equals(actorUserId)) {
            throw new DomainException(HouseholdErrorCode.HOUSEHOLD_NOT_FOUND);
        }
        UserAddress address = userAddressRepository.lockByIdAndOwner(addressId, actorUserId)
                .filter(UserAddress::isActive)
                .orElseThrow(() -> new DomainException(
                        CommonErrorCode.RESOURCE_NOT_FOUND,
                        "Service address not found."));
        String formatted = addressBookService.toAddressResponse(address).formattedAddress();
        household.setDefaultServiceAddressId(address.getId());
        household.setAddress(formatted);
    }

    @Transactional
    public void deleteHousehold(String ownerProfileId, UUID householdId) {
        Household h = requireOwnedHousehold(ownerProfileId, householdId);
        boolean hasArchivedDevice = false;
        for (var device : deviceRepository.findByHouseholdIdOrderByCreatedAtDesc(householdId)) {
            DeviceRemovalResponse removal = removeDevice(ownerProfileId, device.getId());
            if (STATUS_ARCHIVED.equals(removal.outcome())) {
                hasArchivedDevice = true;
            }
        }
        if (hasArchivedDevice) {
            throw new DomainException(
                    CommonErrorCode.OPERATION_FORBIDDEN,
                    "Cannot delete household with devices that have warranty, care, or booking history.");
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
        String normalizedModel = (request.model() == null || request.model().isBlank()) ? null : request.model().trim();
        Device saved = deviceRepository.saveAndFlush(Device.builder()
                .id(UUID.randomUUID())
                .householdId(householdId)
                .ownerProfileId(ownerProfileId)
                .name(request.name().trim())
                .deviceType(request.deviceType().trim().toUpperCase())
                .brand(request.brand().trim())
                .model(normalizedModel)
                .serialNumber(request.serialNumber() == null || request.serialNumber().isBlank()
                        ? null : request.serialNumber().trim())
                .purchaseDate(request.purchaseDate())
                .status(EntityStatus.ACTIVE.name())
                .originSource(ORIGIN_MANUAL)
                .createdAt(now)
                .updatedAt(now)
                .build());
        createScheduleFromApprovedPolicy(saved.getId());
        return toDeviceResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> devicesPage(String ownerProfileId, UUID householdId, int page, int size) {
        return devicesPage(ownerProfileId, householdId, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<DeviceResponse> devicesPage(String ownerProfileId, UUID householdId, String status, int page, int size) {
        var pageable = pageRequest(page, size, "createdAt");
        String normalizedStatus = (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status.trim()))
                ? null : status.trim().toUpperCase();
        if (normalizedStatus != null
                && !EntityStatus.ACTIVE.name().equals(normalizedStatus)
                && !STATUS_ARCHIVED.equals(normalizedStatus)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Device status filter must be ACTIVE, ARCHIVED, or ALL.");
        }
        var result = householdId != null
                ? (normalizedStatus != null
                        ? deviceRepository.findByHouseholdIdAndStatusOrderByCreatedAtDesc(
                                requireOwnedHousehold(ownerProfileId, householdId).getId(), normalizedStatus, pageable)
                        : deviceRepository.findByHouseholdIdOrderByCreatedAtDesc(
                                requireOwnedHousehold(ownerProfileId, householdId).getId(), pageable))
                : (normalizedStatus != null
                        ? deviceRepository.findByOwnerProfileIdAndStatusOrderByCreatedAtDesc(ownerProfileId, normalizedStatus, pageable)
                        : deviceRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId, pageable));
        return PageResponse.of(result.map(this::toDeviceResponse).getContent(), page, size, result.getTotalElements());
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
        requireActiveDevice(device);
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
        if (request.model() != null) {
            device.setModel(request.model().isBlank() ? null : request.model().trim());
        }
        if (request.serialNumber() != null) {
            device.setSerialNumber(request.serialNumber().isBlank() ? null : request.serialNumber().trim());
        }
        if (request.purchaseDate() != null) {
            device.setPurchaseDate(request.purchaseDate());
        }
        if (request.status() != null && !request.status().isBlank()) {
            String requestedStatus = request.status().trim().toUpperCase();
            if (STATUS_ARCHIVED.equals(requestedStatus)) {
                device.setStatus(STATUS_ARCHIVED);
                device.setArchivedAt(Instant.now());
            } else if (!EntityStatus.ACTIVE.name().equals(requestedStatus)) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Unsupported device status transition.");
            }
        }
        device.setUpdatedAt(Instant.now());
        return toDeviceResponse(deviceRepository.saveAndFlush(device));
    }

    @Transactional
    public DeviceResponse archiveDevice(String ownerProfileId, UUID deviceId) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        if (STATUS_ARCHIVED.equalsIgnoreCase(device.getStatus())) {
            return toDeviceResponse(device);
        }
        Instant now = Instant.now();
        device.setStatus(STATUS_ARCHIVED);
        device.setArchivedAt(now);
        device.setUpdatedAt(now);
        return toDeviceResponse(deviceRepository.saveAndFlush(device));
    }

    @Transactional
    public DeviceRemovalResponse removeDevice(String ownerProfileId, UUID deviceId) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        boolean mustArchive = !ORIGIN_MANUAL.equalsIgnoreCase(device.getOriginSource())
                || careRecordRepository.existsByDeviceId(deviceId)
                || warrantyRepository.existsByDeviceId(deviceId)
                || bookingUsageGrpcClient.hasLinkedBookings(deviceId);
        if (mustArchive) {
            DeviceResponse archived = archiveDevice(ownerProfileId, deviceId);
            return new DeviceRemovalResponse(STATUS_ARCHIVED, archived);
        }
        scheduleRepository.deleteByDeviceId(deviceId);
        deviceRepository.delete(device);
        return new DeviceRemovalResponse("DELETED", null);
    }

    @Transactional
    public void deleteDevice(String ownerProfileId, UUID deviceId) {
        removeDevice(ownerProfileId, deviceId);
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyResponse> warrantiesPage(ActorContext actor, UUID requestedOrgId, int page, int size) {
        var pageable = pageRequest(page, size, "endsOn");
        UUID orgScope = accessPolicy.resolveWarrantyOrganizationScope(actor, requestedOrgId);
        var result = orgScope != null
                ? warrantyRepository.search(null, orgScope, pageable)
                : warrantyRepository.search(actor.isGlobalAdmin() ? null : actor.userId(), null, pageable);
        return PageResponse.of(result.map(this::toWarrantyResponse).getContent(), page, size, result.getTotalElements());
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
        PageResponse.validate(page, size);
        return PageRequest.of(page - 1, size, Sort.by(field, "id"));
    }

    @Transactional
    public WarrantyResponse createWarranty(String ownerProfileId, UUID deviceId, CreateWarrantyRequest request) {
        return createWarranty(ownerProfileId, null, deviceId, request);
    }

    public WarrantyResponse createWarranty(
            String ownerProfileId, String ownerName, UUID deviceId, CreateWarrantyRequest request) {
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
                .ownerName(ownerName)
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
        return autoActivateWarrantyFromOrderLineDelivered(
                buyerProfileId, null, organizationId, merchantName, orderId, orderLineId,
                productId, productName, category, brand, productType, warrantyMonths);
    }

    @Transactional
    public WarrantyResponse autoActivateWarrantyFromOrderLineDelivered(
            String buyerProfileId,
            String buyerName,
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
        List<WarrantyResponse> created = autoActivateFromOrderLineDelivered(
                buyerProfileId,
                buyerName,
                organizationId,
                merchantName,
                orderId,
                orderLineId,
                productId,
                productName,
                category,
                brand,
                productType,
                null,
                null,
                "DELIVERY_DATE",
                warrantyMonths,
                1,
                List.of(),
                null);
        return created.isEmpty() ? null : created.getFirst();
    }

    @Transactional
    public List<WarrantyResponse> autoActivateFromOrderLineDelivered(
            String buyerProfileId,
            String buyerName,
            UUID organizationId,
            String merchantName,
            UUID orderId,
            UUID orderLineId,
            UUID productId,
            String productName,
            String category,
            String brand,
            String productType,
            String model,
            String deviceEligibility,
            String warrantyStartTrigger,
            int warrantyMonths,
            int quantityDelivered,
            List<String> unitKeys,
            String shippingAddressFallback) {
        boolean createsDevice = "REQUIRED".equalsIgnoreCase(deviceEligibility)
                || "OPTIONAL".equalsIgnoreCase(deviceEligibility);
        if (!createsDevice && warrantyMonths <= 0) {
            return List.of();
        }

        String resolvedModel = (model != null && !model.isBlank()) ? model.trim() : null;
        String trigger = (warrantyStartTrigger == null || warrantyStartTrigger.isBlank())
                ? "DELIVERY_DATE"
                : warrantyStartTrigger.trim().toUpperCase();
        boolean activateNow = "DELIVERY_DATE".equals(trigger) || "PURCHASE".equals(trigger);

        List<String> effectiveUnitKeys;
        if (createsDevice) {
            List<String> normalizedKeys = unitKeys == null ? List.of()
                    : unitKeys.stream()
                            .filter(k -> k != null && !k.isBlank())
                            .map(String::trim)
                            .distinct()
                            .toList();
            if (!normalizedKeys.isEmpty()) {
                effectiveUnitKeys = normalizedKeys;
            } else {
                int units = Math.max(1, quantityDelivered);
                effectiveUnitKeys = IntStream.rangeClosed(1, units)
                        .mapToObj(i -> orderLineId + ":" + i)
                        .toList();
            }
        } else {
            effectiveUnitKeys = List.of(orderLineId + ":orderline");
        }

        UUID householdId = createsDevice ? ensureDefaultHouseholdId(buyerProfileId, shippingAddressFallback) : null;
        Instant now = Instant.now();
        LocalDate today = LocalDate.now();
        List<WarrantyResponse> results = new ArrayList<>();

        for (String unitKey : effectiveUnitKeys) {
            Optional<Warranty> existingWarranty = warrantyRepository.findByUnitKey(unitKey);
            if (existingWarranty.isEmpty() && effectiveUnitKeys.size() == 1 && (unitKeys == null || unitKeys.isEmpty())) {
                existingWarranty = warrantyRepository.findFirstByOrderLineIdOrderByCreatedAtAsc(orderLineId);
            }
            if (existingWarranty.isPresent()) {
                results.add(toWarrantyResponse(existingWarranty.get()));
                continue;
            }

            Device unitDevice = null;
            if (createsDevice) {
                unitDevice = deviceRepository.findByUnitKey(unitKey).orElseGet(() -> deviceRepository.saveAndFlush(
                        Device.builder()
                                .id(UUID.randomUUID())
                                .householdId(householdId)
                                .ownerProfileId(buyerProfileId)
                                .name(productName != null && !productName.isBlank() ? productName.trim()
                                        : "Thiết bị mua tại HomeCare")
                                .deviceType(category != null && !category.isBlank() ? category.trim().toUpperCase() : "AC")
                                .brand(brand != null && !brand.isBlank() ? brand.trim() : "HomeCare")
                                .model(resolvedModel)
                                .unitKey(unitKey)
                                .purchaseDate(today)
                                .status(EntityStatus.ACTIVE.name())
                                .originSource(ORIGIN_STORE_ORDER)
                                .createdAt(now)
                                .updatedAt(now)
                                .build()));
            }

            if (warrantyMonths > 0) {
                LocalDate startsOn = activateNow ? today : null;
                LocalDate endsOn = activateNow ? today.plusMonths(warrantyMonths) : null;
                String status = activateNow ? EntityStatus.ACTIVE.name() : "PENDING_TRIGGER";
                Warranty savedWarranty = warrantyRepository.save(Warranty.builder()
                        .id(UUID.randomUUID())
                        .deviceId(unitDevice != null ? unitDevice.getId() : null)
                        .ownerProfileId(buyerProfileId)
                        .ownerName(buyerName)
                        .organizationId(organizationId)
                        .warrantyResponsibleOrgId(organizationId)
                        .orderId(orderId)
                        .orderLineId(orderLineId)
                        .productId(productId)
                        .unitKey(unitKey)
                        .startTrigger(trigger)
                        .coverageMonths(warrantyMonths)
                        .manufacturerRegistrationStatus(ManufacturerRegistrationStatus.NOT_VERIFIED)
                        .source("ORDER_AUTO_ACTIVATED")
                        .providerName(merchantName != null && !merchantName.isBlank()
                                ? merchantName.trim()
                                : "Cửa hàng HomeCare")
                        .startsOn(startsOn)
                        .endsOn(endsOn)
                        .notes("Bảo hành điện tử tự động kích hoạt (" + warrantyMonths + " tháng) từ đơn hàng #" + orderId)
                        .status(status)
                        .createdAt(now)
                        .build());
                results.add(toWarrantyResponse(savedWarranty));
            }
        }
        return results;
    }

    @Transactional
    public void recordInstallationCompleted(
            UUID bookingId,
            UUID deviceId,
            UUID orderLineId,
            UUID executorOrgId,
            Instant completedAt,
            List<String> evidenceRefs) {
        if (bookingId == null) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Installation completion requires bookingId.");
        }
        if (evidenceRefs == null || evidenceRefs.isEmpty()) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "Installation completion requires evidence.");
        }
        List<Warranty> warranties = orderLineId == null
                ? new ArrayList<>()
                : new ArrayList<>(warrantyRepository.findAllByOrderLineIdForUpdate(orderLineId));
        if (deviceId != null) {
            warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId).stream()
                    .filter(w -> !warranties.contains(w))
                    .forEach(warranties::add);
        }
        for (Warranty warranty : warranties) {
            if (deviceId != null && warranty.getDeviceId() != null
                    && !deviceId.equals(warranty.getDeviceId())) {
                continue;
            }
            Device device = warranty.getDeviceId() == null ? null
                    : deviceRepository.findById(warranty.getDeviceId()).orElse(null);
            if (device == null && deviceId != null) {
                device = deviceRepository.findById(deviceId).orElse(null);
                if (device != null) {
                    warranty.setDeviceId(device.getId());
                }
            }
            boolean firstInstallation = device != null && !"COMPLETED".equalsIgnoreCase(device.getInstallationStatus());
            if (device != null && firstInstallation) {
                device.setInstallationStatus("COMPLETED");
                device.setInstallationCompletedAt(completedAt != null ? completedAt : Instant.now());
                device.setInstallationBookingId(bookingId);
                device.setInstallationEvidenceRefs(evidenceRefs == null ? null : String.join(",", evidenceRefs));
                deviceRepository.save(device);
            }
            if (!"INSTALLATION_COMPLETED".equalsIgnoreCase(warranty.getStartTrigger())) {
                continue;
            }
            boolean alreadyActive = "ACTIVE".equalsIgnoreCase(warranty.getStatus());
            if (device == null || device.getSerialNumber() == null || device.getSerialNumber().isBlank()) {
                warranty.setStatus("PENDING_SERIAL");
            } else if (!alreadyActive) {
                activateInstallationWarranty(warranty);
            }
            if (!alreadyActive) {
                warranty.setWarrantyResponsibleOrgId(executorOrgId != null ? executorOrgId : warranty.getWarrantyResponsibleOrgId());
                warranty.setTriggerEvidenceRef(String.join(",", evidenceRefs));
                warrantyRepository.save(warranty);
            }
            if (device != null && careRecordRepository.findFirstByDeviceIdAndKindOrderByCreatedAtAsc(
                    device.getId(), "INSTALLATION").isEmpty()) {
                careRecordRepository.save(CareRecord.builder()
                        .id(UUID.randomUUID())
                        .deviceId(device.getId())
                        .ownerProfileId(device.getOwnerProfileId())
                        .kind("INSTALLATION")
                        .occurredAt(completedAt != null ? completedAt : Instant.now())
                        .title("Installation completed")
                        .notes(evidenceRefs == null ? null : String.join(",", evidenceRefs))
                        .bookingId(bookingId)
                        .createdAt(Instant.now())
                        .build());
            }
        }
    }

    private void activateInstallationWarranty(Warranty warranty) {
        LocalDate startsOn = LocalDate.now();
        warranty.setStartsOn(startsOn);
        warranty.setEndsOn(startsOn.plusMonths(warranty.getCoverageMonths() == null ? 0 : warranty.getCoverageMonths()));
        warranty.setStatus(EntityStatus.ACTIVE.name());
    }

    @Transactional
    public DeviceResponse registerSerial(String ownerProfileId, UUID deviceId, RegisterSerialRequest request) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        device.setSerialNumber(request.serialNumber().trim());
        device.setUpdatedAt(Instant.now());
        Device saved = deviceRepository.saveAndFlush(device);
        warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId).stream()
                .filter(w -> "PENDING_SERIAL".equalsIgnoreCase(w.getStatus()))
                .forEach(w -> {
                    activateInstallationWarranty(w);
                    warrantyRepository.save(w);
                });
        return toDeviceResponse(saved);
    }

    @Transactional(readOnly = true)
    public DeviceLifecycleResponse getDeviceLifecycle(String ownerProfileId, UUID deviceId) {
        Device device = requireOwnedDevice(ownerProfileId, deviceId);
        Warranty warranty = warrantyRepository.findByDeviceIdOrderByEndsOnDesc(deviceId).stream().findFirst().orElse(null);
        return new DeviceLifecycleResponse(
                device.getId(), device.getHouseholdId(), device.getOwnerProfileId(), device.getName(),
                device.getDeviceType(), device.getBrand(), device.getModel(), device.getSerialNumber(),
                device.getInstallationStatus(), device.getInstallationCompletedAt(), device.getInstallationBookingId(),
                warranty == null ? null : warranty.getOrderId(), warranty == null ? null : warranty.getOrderLineId(),
                warranty == null ? null : warranty.getStatus(), warranty == null ? null : warranty.getSource(),
                warranty == null || warranty.getManufacturerRegistrationStatus() == null
                        ? null : warranty.getManufacturerRegistrationStatus().name(),
                warranty == null ? null : warranty.getTriggerEvidenceRef(),
                scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId).stream().map(this::toScheduleResponse).toList(),
                careRecordRepository.findByDeviceIdOrderByOccurredAtDesc(deviceId).stream().map(this::toCareRecordResponse).toList(),
                List.of());
    }

    @Transactional
    public CareRecordResponse recordCareHistoryFromBookingCompleted(
            UUID bookingId,
            UUID deviceId,
            String requesterProfileId,
            String bookingType,
            String serviceName,
            String notes) {
        return recordCareHistoryFromBookingAccepted(
                bookingId, deviceId, requesterProfileId, bookingType, serviceName, Instant.now(), notes);
    }

    @Transactional
    public CareRecordResponse recordCareHistoryFromBookingAccepted(
            UUID bookingId,
            UUID deviceId,
            String requesterProfileId,
            String bookingType,
            String serviceName,
            Instant acceptedAt,
            String notes) {
        if (bookingId == null || deviceId == null) {
            return null;
        }
        Optional<Device> deviceOpt = deviceRepository.findLockedById(deviceId);
        if (deviceOpt.isEmpty()) {
            return null;
        }
        try {
            Optional<CareRecord> existing = careRecordRepository.findByBookingId(bookingId);
            if (existing.isPresent()) {
                return toCareRecordResponse(existing.get());
            }
            Device device = deviceOpt.get();
            String kind = "WARRANTY_CLAIM".equalsIgnoreCase(bookingType) ? "WARRANTY_REPAIR"
                    : ("INSTALLATION".equalsIgnoreCase(bookingType) ? "INSTALLATION"
                    : ("MAINTENANCE".equalsIgnoreCase(bookingType)
                            || "MAINTENANCE_SERVICE".equalsIgnoreCase(bookingType)
                                    ? "MAINTENANCE" : "PAID_REPAIR"));
            Instant occurredAt = acceptedAt != null ? acceptedAt : Instant.now();
            CareRecord saved = careRecordRepository.saveAndFlush(CareRecord.builder()
                    .id(UUID.randomUUID())
                    .deviceId(device.getId())
                    .ownerProfileId(requesterProfileId != null && !requesterProfileId.isBlank()
                            ? requesterProfileId : device.getOwnerProfileId())
                    .kind(kind)
                    .occurredAt(occurredAt)
                    .title(serviceName != null && !serviceName.isBlank() ? serviceName : kind)
                    .notes(notes)
                    .bookingId(bookingId)
                    .createdAt(Instant.now())
                    .build());
            if ("MAINTENANCE".equals(kind)) {
                advanceMaintenanceSchedule(deviceId, occurredAt);
            }
            return toCareRecordResponse(saved);
        } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
            return careRecordRepository.findByBookingId(bookingId)
                    .map(this::toCareRecordResponse)
                    .orElseThrow(() -> duplicate);
        }
    }

    private void advanceMaintenanceSchedule(UUID deviceId, Instant acceptedAt) {
        LocalDate acceptedDateVn = acceptedAt.atZone(VIETNAM_ZONE).toLocalDate();
        scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId).stream()
                .filter(s -> "SCHEDULED".equalsIgnoreCase(s.getStatus()))
                .filter(s -> s.getLastServicedAt() == null || acceptedAt.isAfter(s.getLastServicedAt()))
                .findFirst()
                .ifPresent(schedule -> {
                    int cadence = schedule.getCadenceMonths() == null ? 6 : schedule.getCadenceMonths();
                    schedule.setDueOn(acceptedDateVn.plusMonths(cadence));
                    schedule.setStatus("SCHEDULED");
                    schedule.setNotifiedAt(null);
                    schedule.setCompletedAt(acceptedAt);
                    schedule.setLastServicedAt(acceptedAt);
                    scheduleRepository.save(schedule);
                });
    }

    @Transactional
    public MaintenanceScheduleResponse createScheduleFromApprovedPolicy(UUID deviceId) {
        Device device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || STATUS_ARCHIVED.equalsIgnoreCase(device.getStatus())) {
            return null;
        }
        Optional<MaintenancePolicyEntity> policy = Optional.empty();
        if (device.getModel() != null && !device.getModel().isBlank()) {
            policy = maintenancePolicyRepository
                    .findFirstByCategoryAndModelAndStatusOrderByPolicyVersionDesc(
                            device.getDeviceType(), device.getModel(), "APPROVED");
        }
        if (policy.isEmpty()) {
            policy = maintenancePolicyRepository
                    .findFirstByCategoryAndModelIsNullAndStatusOrderByPolicyVersionDesc(
                            device.getDeviceType(), "APPROVED");
        }
        if (policy.isEmpty()) {
            return null;
        }
        MaintenancePolicyEntity p = policy.get();
        if (p.getDeviceId() != null && !p.getDeviceId().equals(deviceId)) {
            return null;
        }
        if (scheduleRepository.existsByDeviceIdAndPolicyVersion(deviceId, p.getPolicyVersion())) {
            return scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId).stream()
                    .filter(s -> java.util.Objects.equals(s.getPolicyVersion(), p.getPolicyVersion()))
                    .findFirst().map(this::toScheduleResponse).orElse(null);
        }
        MaintenanceSchedule saved = scheduleRepository.save(MaintenanceSchedule.builder()
                .id(UUID.randomUUID())
                .deviceId(deviceId)
                .ownerProfileId(device.getOwnerProfileId())
                .title(p.getTitle())
                .cadenceMonths(p.getCadenceMonths())
                .policyVersion(p.getPolicyVersion())
                .dueOn(p.getFirstDueOn() == null
                        ? LocalDate.now(VIETNAM_ZONE).plusMonths(p.getCadenceMonths())
                        : p.getFirstDueOn())
                .status("SCHEDULED")
                .reminderEnabled(true)
                .createdAt(Instant.now())
                .build());
        return toScheduleResponse(saved);
    }

    @Transactional
    public MaintenancePolicyEntity createMaintenancePolicy(MaintenancePolicyEntity policy) {
        validateCadenceMonths(policy.getCadenceMonths());
        return maintenancePolicyRepository.saveAndFlush(policy);
    }

    @Transactional(readOnly = true)
    public PageResponse<MaintenancePolicyResponse> listMaintenancePoliciesForAdmin(
            ActorContext actor, String category, String status, int page, int size) {
        requireGlobalAdmin(actor);
        PageResponse.validate(page, size);
        String normCat = (category == null || category.isBlank()) ? null : category.trim().toUpperCase();
        String normStatus = (status == null || status.isBlank()) ? null : status.trim().toUpperCase();
        var pageable = PageRequest.of(page - 1, size);
        var result = maintenancePolicyRepository.searchForAdmin(normCat, normStatus, pageable);
        return PageResponse.of(result.map(this::toPolicyResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional
    public MaintenancePolicyResponse createMaintenancePolicyForAdmin(
            ActorContext actor, CreateMaintenancePolicyRequest request) {
        requireGlobalAdmin(actor);
        validateCadenceMonths(request.cadenceMonths());
        String category = request.category().trim().toUpperCase();
        String model = (request.model() == null || request.model().isBlank()) ? null : request.model().trim();
        String status = (request.status() == null || request.status().isBlank())
                ? "DRAFT" : request.status().trim().toUpperCase();
        if (!ALLOWED_POLICY_STATUSES.contains(status)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Policy status must be DRAFT, APPROVED, or ARCHIVED.");
        }
        int nextVersion = maintenancePolicyRepository.findMaxPolicyVersion(category, model) + 1;
        Instant now = Instant.now();
        MaintenancePolicyEntity saved = maintenancePolicyRepository.saveAndFlush(MaintenancePolicyEntity.builder()
                .id(UUID.randomUUID())
                .category(category)
                .model(model)
                .title(request.title().trim())
                .cadenceMonths(request.cadenceMonths())
                .policyVersion(nextVersion)
                .status(status)
                .consentGranted(true)
                .firstDueOn(request.firstDueOn())
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toPolicyResponse(saved);
    }

    @Transactional
    public MaintenancePolicyResponse updateMaintenancePolicyForAdmin(
            ActorContext actor, UUID policyId, UpdateMaintenancePolicyRequest request) {
        requireGlobalAdmin(actor);
        MaintenancePolicyEntity policy = maintenancePolicyRepository.findById(policyId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.MAINTENANCE_POLICY_NOT_FOUND));
        if (request.title() != null && !request.title().isBlank()) {
            policy.setTitle(request.title().trim());
        }
        if (request.cadenceMonths() != null) {
            validateCadenceMonths(request.cadenceMonths());
            policy.setCadenceMonths(request.cadenceMonths());
        }
        if (request.status() != null && !request.status().isBlank()) {
            String nextStatus = request.status().trim().toUpperCase();
            if (!ALLOWED_POLICY_STATUSES.contains(nextStatus)) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Policy status must be DRAFT, APPROVED, or ARCHIVED.");
            }
            policy.setStatus(nextStatus);
        }
        if (request.firstDueOn() != null) {
            policy.setFirstDueOn(request.firstDueOn());
        }
        policy.setUpdatedAt(Instant.now());
        return toPolicyResponse(maintenancePolicyRepository.saveAndFlush(policy));
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyResponse> listWarrantiesPage(String ownerProfileId, UUID deviceId, int page, int size) {
        return slicePage(listWarranties(ownerProfileId, deviceId), page, size);
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
        requireActiveDevice(device);
        int cadence = request.cadenceMonths() == null ? 6 : request.cadenceMonths();
        validateCadenceMonths(cadence);
        MaintenanceSchedule saved = scheduleRepository.save(MaintenanceSchedule.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(ownerProfileId)
                .title(request.title().trim())
                .cadenceMonths(cadence)
                .dueOn(request.dueOn())
                .status("SCHEDULED")
                .reminderEnabled(request.reminderEnabled() == null || request.reminderEnabled())
                .createdAt(Instant.now())
                .build());
        return toScheduleResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<MaintenanceScheduleResponse> listSchedulesPage(String ownerProfileId, UUID deviceId, int page, int size) {
        requireOwnedDevice(ownerProfileId, deviceId);
        PageResponse.validate(page, size);
        var result = scheduleRepository.findByDeviceIdOrderByDueOnAsc(deviceId, PageRequest.of(page - 1, size));
        return PageResponse.of(result.map(this::toScheduleResponse).getContent(), page, size, result.getTotalElements());
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
        Device parent = requireOwnedDevice(ownerProfileId, s.getDeviceId());
        requireActiveDevice(parent);
        if (request.dueOn() != null
                && s.getNotifiedAt() != null
                && !"COMPLETED".equals(request.status())) {
            throw new DomainException(
                    HouseholdErrorCode.SCHEDULE_ALREADY_NOTIFIED,
                    "Schedule has already triggered a notification; complete, skip, or create a new schedule.");
        }
        if (request.title() != null && !request.title().isBlank()) {
            s.setTitle(request.title().trim());
        }
        if (request.cadenceMonths() != null) {
            validateCadenceMonths(request.cadenceMonths());
            s.setCadenceMonths(request.cadenceMonths());
        }
        if (request.dueOn() != null) {
            s.setDueOn(request.dueOn());
        }
        if (request.reminderEnabled() != null) {
            s.setReminderEnabled(request.reminderEnabled());
        }
        if (request.status() != null && !request.status().isBlank()) {
            String nextStatus = request.status().trim().toUpperCase();
            if (!ALLOWED_SCHEDULE_STATUSES.contains(nextStatus)) {
                throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "Unsupported maintenance schedule status.");
            }
            s.setStatus(nextStatus);
            if ("COMPLETED".equals(nextStatus)) {
                s.setCompletedAt(Instant.now());
            }
        }
        return toScheduleResponse(scheduleRepository.save(s));
    }

    @Transactional
    public MaintenanceScheduleResponse skipSchedule(String ownerProfileId, UUID scheduleId) {
        MaintenanceSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.SCHEDULE_NOT_FOUND));
        if (!s.getOwnerProfileId().equals(ownerProfileId)) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Not the owner of this schedule.");
        }
        Device parent = requireOwnedDevice(ownerProfileId, s.getDeviceId());
        requireActiveDevice(parent);
        if ("CANCELLED".equalsIgnoreCase(s.getStatus())) {
            throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN, "Cannot skip a cancelled maintenance schedule.");
        }
        int cadence = s.getCadenceMonths() == null ? 6 : s.getCadenceMonths();
        LocalDate todayVn = LocalDate.now(VIETNAM_ZONE);
        s.setDueOn(todayVn.plusMonths(cadence));
        s.setNotifiedAt(null);
        s.setLastSkippedAt(Instant.now());
        s.setStatus("SCHEDULED");
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
        requireActiveDevice(device);
        if (request.bookingId() != null) {
            throw new DomainException(
                    CommonErrorCode.VALIDATION_FAILED,
                    "Manual care records cannot reference a bookingId; link the accepted booking instead.");
        }
        Instant occurredAt = request.occurredAt() != null ? request.occurredAt() : Instant.now();
        CareRecord saved = careRecordRepository.save(CareRecord.builder()
                .id(UUID.randomUUID())
                .deviceId(device.getId())
                .ownerProfileId(ownerProfileId)
                .kind(request.kind().trim().toUpperCase())
                .occurredAt(occurredAt)
                .title(request.title() != null ? request.title().trim() : request.kind().trim())
                .notes(request.notes())
                .bookingId(null)
                .createdAt(Instant.now())
                .build());
        return toCareRecordResponse(saved);
    }

    @Transactional(readOnly = true)
    public PageResponse<CareRecordResponse> listCareRecordsPage(String ownerProfileId, UUID deviceId, int page, int size) {
        requireOwnedDevice(ownerProfileId, deviceId);
        PageResponse.validate(page, size);
        var result = careRecordRepository.findByDeviceIdOrderByOccurredAtDesc(deviceId, PageRequest.of(page - 1, size));
        return PageResponse.of(result.map(this::toCareRecordResponse).getContent(), page, size, result.getTotalElements());
    }

    private static <T> PageResponse<T> slicePage(List<T> all, int page, int size) {
        PageResponse.validate(page, size);
        int from = Math.min((page - 1) * size, all.size());
        int to = Math.min(from + size, all.size());
        return PageResponse.of(all.subList(from, to), page, size, all.size());
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
        if (r.getBookingId() != null || "INSTALLATION".equalsIgnoreCase(r.getKind())) {
            throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                    "Booking-backed and installation care history is append-only and immutable.");
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
        if (r.getBookingId() != null || "INSTALLATION".equalsIgnoreCase(r.getKind())) {
            throw new DomainException(CommonErrorCode.OPERATION_FORBIDDEN,
                    "Booking-backed and installation care history is append-only and immutable.");
        }
        careRecordRepository.delete(r);
    }

    @Transactional(readOnly = true)
    public VerifyDeviceReferenceResponse verifyDeviceReference(UUID deviceId, String requesterProfileId) {
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId)
                .filter(d -> d.getOwnerProfileId().equals(requesterProfileId));
        if (deviceOpt.isEmpty()) {
            return new VerifyDeviceReferenceResponse(
                    false, deviceId, null, null, null, "NOT_FOUND_OR_FORBIDDEN", null, null);
        }
        Device d = deviceOpt.get();
        boolean active = EntityStatus.ACTIVE.name().equalsIgnoreCase(d.getStatus());
        return new VerifyDeviceReferenceResponse(
                active,
                d.getId(),
                d.getHouseholdId(),
                d.getDeviceType(),
                d.getName(),
                d.getStatus(),
                d.getBrand(),
                d.getModel());
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
        Device device = w.getDeviceId() != null ? deviceRepository.findById(w.getDeviceId()).orElse(null) : null;
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
        if ("PENDING_TRIGGER".equalsIgnoreCase(w.getStatus())) {
            return new VerifyWarrantyResponse(
                    false,
                    false,
                    w.getId(),
                    w.getDeviceId(),
                    deviceType,
                    w.getOrganizationId(),
                    w.getProviderName(),
                    w.getEndsOn(),
                    "PENDING_TRIGGER");
        }
        boolean isExpired = (w.getEndsOn() == null || w.getEndsOn().isBefore(LocalDate.now()))
                || !EntityStatus.ACTIVE.name().equalsIgnoreCase(w.getStatus());
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
        LocalDate effectiveDate = referenceDate != null ? referenceDate : LocalDate.now(VIETNAM_ZONE);
        List<MaintenanceSchedule> due = scheduleRepository.findDueActiveSchedulesForUpdateSkipLocked(effectiveDate);
        Instant now = Instant.now();
        for (MaintenanceSchedule schedule : due) {
            schedule.setNotifiedAt(now);
            scheduleRepository.save(schedule);
            UUID eventId = UUID.nameUUIDFromBytes(
                    ("maint-due-" + schedule.getId() + "-" + schedule.getDueOn()).getBytes(StandardCharsets.UTF_8));
            if (outboxEventRepository.existsById(eventId)) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scheduleId", schedule.getId());
            data.put("deviceId", schedule.getDeviceId());
            data.put("ownerProfileId", schedule.getOwnerProfileId());
            data.put("title", schedule.getTitle());
            data.put("dueOn", schedule.getDueOn().toString());

            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put(DomainEventContracts.FIELD_EVENT_ID, eventId);
            envelope.put(DomainEventContracts.FIELD_EVENT_TYPE, DomainEventContracts.MAINTENANCE_REMINDER_DUE);
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
                    .eventType(DomainEventContracts.MAINTENANCE_REMINDER_DUE)
                    .routingKey(DomainEventContracts.MAINTENANCE_REMINDER_DUE)
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
        return ensureDefaultHouseholdId(ownerProfileId, null);
    }

    private UUID ensureDefaultHouseholdId(String ownerProfileId, String fallbackAddress) {
        List<Household> households = householdRepository.findByOwnerProfileIdOrderByCreatedAtDesc(ownerProfileId);
        if (!households.isEmpty()) {
            return households.getFirst().getId();
        }
        var activeAddresses = userAddressRepository.findActiveWithLocationByOwner(ownerProfileId);
        if (!activeAddresses.isEmpty()) {
            HouseholdResponse created = createHousehold(
                    ownerProfileId,
                    new CreateHouseholdRequest(
                            "Gia đình của tôi",
                            null,
                            "Asia/Ho_Chi_Minh",
                            activeAddresses.getFirst().getId()));
            return created.id();
        }
        String resolvedAddress = (fallbackAddress != null && !fallbackAddress.isBlank())
                ? fallbackAddress.trim()
                : "Chưa cập nhật địa chỉ";
        HouseholdResponse created = createHousehold(
                ownerProfileId,
                new CreateHouseholdRequest("Gia đình của tôi", resolvedAddress, "Asia/Ho_Chi_Minh"));
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
                h.getDefaultServiceAddressId(),
                h.getCreatedAt(),
                h.getUpdatedAt());
    }

    private void requireActiveDevice(Device device) {
        if (STATUS_ARCHIVED.equalsIgnoreCase(device.getStatus())) {
            throw new DomainException(HouseholdErrorCode.DEVICE_ARCHIVED);
        }
    }

    private void validateCadenceMonths(Integer cadenceMonths) {
        if (cadenceMonths == null || cadenceMonths < 1 || cadenceMonths > 60) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED, "cadenceMonths must be between 1 and 60.");
        }
    }

    private void requireGlobalAdmin(ActorContext actor) {
        if (actor == null || !actor.isGlobalAdmin()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN, "Admin privileges are required.");
        }
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
                d.getOriginSource(),
                d.getArchivedAt(),
                d.getVersion(),
                d.getCreatedAt(),
                d.getUpdatedAt());
    }

    @Transactional
    public WarrantyResponse attachWarrantyEvidence(
            ActorContext actor,
            UUID warrantyId,
            AttachWarrantyEvidenceRequest request) {
        Warranty w = warrantyRepository.lockById(warrantyId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.WARRANTY_NOT_FOUND));
        boolean isOwner = actor.userId().equals(w.getOwnerProfileId());
        boolean isResponsibleOrg = actor.mode() == AccessMode.ORGANIZATION
                && actor.organizationId() != null
                && (actor.organizationId().equals(w.getOrganizationId())
                        || actor.organizationId().equals(w.getWarrantyResponsibleOrgId()));
        if (!actor.isGlobalAdmin() && !isOwner && !isResponsibleOrg) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Not authorized to attach evidence to this warranty.");
        }
        // D-14: Submitting evidence NEVER transitions verificationStatus to VERIFIED
        w.setAuthorizationEvidenceRef(request.evidenceRef().trim());
        return toWarrantyResponse(warrantyRepository.saveAndFlush(w));
    }

    @Transactional(readOnly = true)
    public PageResponse<WarrantyResponse> adminListWarranties(
            ActorContext actor,
            String verificationStatus,
            int page,
            int size) {
        if (actor == null || !actor.isGlobalAdmin()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only Global Admin can access the warranty verification queue (D-14).");
        }
        PageResponse.validate(page, size);
        String filter = (verificationStatus == null || verificationStatus.isBlank()
                || "ALL".equalsIgnoreCase(verificationStatus.trim()))
                ? null
                : verificationStatus.trim().toUpperCase();
        var result = warrantyRepository.findByVerificationStatusFilter(
                filter, PageRequest.of(page - 1, size));
        return PageResponse.of(result.map(this::toWarrantyResponse).getContent(), page, size, result.getTotalElements());
    }

    @Transactional
    public WarrantyResponse adminVerifyWarranty(
            ActorContext actor,
            UUID warrantyId,
            String ifMatch,
            AdminVerifyWarrantyRequest request) {
        if (actor == null || !actor.isGlobalAdmin()) {
            throw new DomainException(CommonErrorCode.RESOURCE_FORBIDDEN,
                    "Only Global Admin can verify manufacturer warranty status (D-14).");
        }
        Warranty w = warrantyRepository.lockById(warrantyId)
                .orElseThrow(() -> new DomainException(HouseholdErrorCode.WARRANTY_NOT_FOUND));
        EtagSupport.verifyIfMatch(ifMatch, w.getVersion());
        String target = request.verificationStatus() == null ? "" : request.verificationStatus().trim().toUpperCase();
        if (!"VERIFIED".equals(target) && !"REJECTED".equals(target)) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "verificationStatus must be VERIFIED or REJECTED.");
        }
        if ("REJECTED".equals(target) && (request.note() == null || request.note().isBlank())) {
            throw new DomainException(CommonErrorCode.VALIDATION_FAILED,
                    "note is mandatory when rejecting warranty verification.");
        }
        w.setVerificationStatus(target);
        if ("VERIFIED".equals(target)) {
            w.setManufacturerRegistrationStatus(ManufacturerRegistrationStatus.VERIFIED);
        }
        w.setVerifiedBy(actor.userId());
        w.setVerifiedAt(Instant.now());
        w.setVerificationNote(request.note() != null && !request.note().isBlank() ? request.note().trim() : null);
        return toWarrantyResponse(warrantyRepository.saveAndFlush(w));
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
                w.getOwnerName(),
                w.getStartsOn(),
                w.getStartsOn(),
                w.getEndsOn(),
                w.getEndsOn(),
                w.getNotes(),
                w.getNotes(),
                w.getDocumentUrl(),
                w.getStatus(),
                w.getManufacturerRegistrationStatus() == null ? null : w.getManufacturerRegistrationStatus().name(),
                w.getTriggerEvidenceRef(),
                w.getCreatedAt(),
                w.getWarrantyCategory(),
                w.getWarrantyIssuer(),
                w.getVerificationStatus(),
                w.getValidityStatus(),
                w.getAuthorizationEvidenceRef(),
                w.getVerifiedBy(),
                w.getVerifiedAt(),
                w.getVerificationNote(),
                w.getVersion());
    }

    private MaintenanceScheduleResponse toScheduleResponse(MaintenanceSchedule s) {
        return new MaintenanceScheduleResponse(
                s.getId(),
                s.getDeviceId(),
                s.getTitle(),
                s.getCadenceMonths(),
                s.getDueOn(),
                s.getStatus(),
                s.isReminderEnabled(),
                s.getNotifiedAt(),
                s.getCompletedAt(),
                s.getLastServicedAt(),
                s.getLastSkippedAt(),
                s.getCreatedAt());
    }

    private MaintenancePolicyResponse toPolicyResponse(MaintenancePolicyEntity p) {
        return new MaintenancePolicyResponse(
                p.getId(),
                p.getCategory(),
                p.getModel(),
                p.getTitle(),
                p.getCadenceMonths(),
                p.getPolicyVersion(),
                p.getStatus(),
                p.getFirstDueOn(),
                p.getCreatedAt(),
                p.getUpdatedAt());
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
